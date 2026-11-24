package dev.insight.review.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.graph.RelationKind
import dev.insight.review.CodeSnippet
import dev.insight.review.TreeConnectionOccurrence
import dev.insight.review.TreeConnectionSite
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Floating preview panel showing short, visually separated source-code snippets from both files
 * for every resolved occurrence (calls, usages, inheritance, imports) of a Tree connection link.
 */
internal class TreeConnectionPreviewPanel(
    private val fromPath: String,
    private val toPath: String,
    site: TreeConnectionSite,
    pinned: Boolean,
    private val openAt: ((String, Int) -> Unit)? = null,
    private val openContext: (() -> Unit)? = null,
    private val onClose: () -> Unit = {},
) : JPanel(BorderLayout()) {

    private val listPanel = JPanel(VerticalLayout(JBUI.scale(8))).apply {
        isOpaque = false
        border = JBUI.Borders.empty(8)
    }

    private val scrollPane = JBScrollPane(listPanel).apply {
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        verticalScrollBar.unitIncrement = JBUI.scale(16)
    }

    init {
        name = "charts-tree-connection-preview"
        isOpaque = true
        background = UIUtil.getListBackground()
        border = JBUI.Borders.customLine(JBColor.border(), 1)

        val entries = site.occurrences.ifEmpty {
            listOf(TreeConnectionOccurrence(RelationKind.REFERENCE, site.fromLine, site.toLine))
        }
        val total = maxOf(site.totalOccurrences, entries.size)
        val fromName = fileName(fromPath)
        val toName = fileName(toPath)

        val header = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = true
            background = JBColor(Color(0xF3F5F8), Color(0x2B2D30))
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineBottom(JBColor.border()),
                JBUI.Borders.empty(6, 10),
            )
            val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(JLabel("$fromName → $toName").apply {
                    name = "charts-tree-connection-title"
                    font = JBFont.label().asBold()
                    toolTipText = "$fromPath → $toPath"
                })
                add(JLabel(summaryText(entries, total, pinned)).apply {
                    name = "charts-tree-connection-summary"
                    font = JBFont.small()
                    foreground = UIUtil.getContextHelpForeground()
                })
            }
            val actionsRow = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                if (openContext != null) {
                    add(ActionLink("Open context…") {
                        onClose()
                        openContext.invoke()
                    }.apply {
                        name = "charts-tree-connection-open-context"
                        font = JBFont.small()
                        toolTipText = "Open context picker for both files"
                    })
                }
                add(InplaceButton(IconButton("Close preview", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) {
                    onClose()
                }.apply {
                    name = "charts-tree-connection-close"
                    cursor = Cursor.getDefaultCursor()
                })
            }
            add(titleRow, BorderLayout.CENTER)
            add(actionsRow, BorderLayout.EAST)
        }

        entries.forEach { occurrence ->
            listPanel.add(occurrenceCard(occurrence, fromName, toName))
        }
        if (total > entries.size) {
            listPanel.add(JLabel("+ ${total - entries.size} more occurrences").apply {
                name = "charts-tree-connection-overflow"
                font = JBFont.small()
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.empty(0, 2)
            })
        }

        add(header, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    override fun getPreferredSize(): Dimension {
        val raw = super.getPreferredSize()
        val width = raw.width.coerceIn(JBUI.scale(MIN_WIDTH), JBUI.scale(MAX_WIDTH))
        val height = raw.height.coerceAtMost(JBUI.scale(MAX_HEIGHT))
        return Dimension(width, height)
    }

    private fun occurrenceCard(
        occurrence: TreeConnectionOccurrence,
        fromName: String,
        toName: String,
    ): JComponent {
        val fromLoc = "$fromName:${occurrence.fromLine ?: "?"}"
        val toLoc = occurrence.toLine?.let { "$toName:$it" } ?: toName
        val (badgeText, badgeFg, badgeBg) = kindStyle(occurrence.kind)

        val card = JPanel(BorderLayout()).apply {
            name = "charts-tree-connection-occurrence"
            isOpaque = true
            background = UIUtil.getListBackground()
            border = JBUI.Borders.customLine(JBColor.border(), 1)
        }

        val cardHeader = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = true
            background = JBColor(Color(0xF7F8FA), Color(0x313337))
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineBottom(JBColor.border()),
                JBUI.Borders.empty(4, 8),
            )
            add(JLabel(badgeText).apply {
                font = JBFont.small().asBold()
                isOpaque = true
                foreground = badgeFg
                background = badgeBg
                border = JBUI.Borders.empty(1, 5)
            })
            add(JLabel("$fromLoc → $toLoc").apply {
                font = JBFont.small()
                foreground = UIUtil.getContextHelpForeground()
                toolTipText = "$fromPath:${occurrence.fromLine ?: "?"} → $toPath${occurrence.toLine?.let { ":$it" } ?: ""}"
            })
        }

        val columns = JPanel(GridLayout(1, 2, JBUI.scale(6), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6)
            add(snippetColumn(fromPath, fromLoc, occurrence.fromLine, occurrence.fromSnippet, missingMessage = "Source snippet unavailable"))
            add(
                snippetColumn(
                    toPath,
                    toLoc,
                    occurrence.toLine,
                    occurrence.toSnippet,
                    missingMessage = if (occurrence.toLine == null) "File / package target" else "Source snippet unavailable",
                )
            )
        }

        card.add(cardHeader, BorderLayout.NORTH)
        card.add(columns, BorderLayout.CENTER)
        return card
    }

    private fun snippetColumn(
        fullPath: String,
        labelText: String,
        line: Int?,
        snippet: CodeSnippet?,
        missingMessage: String,
    ): JComponent = JPanel(BorderLayout(0, JBUI.scale(3))).apply {
        isOpaque = false
        val headerLabel: JComponent = if (openAt != null && line != null) {
            ActionLink(labelText) {
                onClose()
                openAt.invoke(fullPath, line)
            }.apply {
                font = JBFont.small().asBold()
                toolTipText = "Open $fullPath at line $line"
            }
        } else {
            JLabel(labelText).apply {
                font = JBFont.small().asBold()
                foreground = UIUtil.getLabelForeground()
                toolTipText = fullPath
            }
        }
        add(headerLabel, BorderLayout.NORTH)
        val body: JComponent = if (snippet != null) {
            SnippetCodeView(snippet)
        } else {
            JPanel(BorderLayout()).apply {
                isOpaque = true
                background = JBColor(Color(0xFAFAFB), Color(0x26282B))
                border = JBUI.Borders.compound(
                    JBUI.Borders.customLine(JBColor.border(), 1),
                    JBUI.Borders.empty(6, 8),
                )
                add(JLabel(missingMessage).apply {
                    font = JBFont.small()
                    foreground = UIUtil.getContextHelpForeground()
                }, BorderLayout.CENTER)
            }
        }
        add(body, BorderLayout.CENTER)
    }

    private fun summaryText(entries: List<TreeConnectionOccurrence>, total: Int, pinned: Boolean): String {
        val counts = entries.groupingBy { it.kind }.eachCount()
        val parts = buildList {
            counts[RelationKind.CALL]?.let { add(plural(it, "call")) }
            counts[RelationKind.INHERITS]?.let { add(if (it == 1) "1 inherit" else "$it inherits") }
            counts[RelationKind.REFERENCE]?.let { add(plural(it, "usage")) }
            counts[RelationKind.IMPORT]?.let { add(plural(it, "import")) }
        }
        val base = if (total > entries.size) "$total occurrences"
        else if (parts.isNotEmpty()) parts.joinToString(" · ")
        else plural(total, "occurrence")
        return if (pinned) base else "$base · Click link to pin"
    }

    private fun plural(count: Int, noun: String) = if (count == 1) "1 $noun" else "$count ${noun}s"

    private fun kindStyle(kind: RelationKind): Triple<String, Color, Color> = when (kind) {
        RelationKind.CALL -> Triple(
            "CALL",
            JBColor(Color(0x065F5A), Color(0x7CE0D5)),
            JBColor(Color(0xE0F2F1), Color(0x1E3A37)),
        )
        RelationKind.INHERITS -> Triple(
            "INHERITS",
            JBColor(Color(0x5B3D99), Color(0xC9B0FF)),
            JBColor(Color(0xEFE8FA), Color(0x332946)),
        )
        RelationKind.REFERENCE -> Triple(
            "USAGE",
            JBColor(Color(0x1D4ED8), Color(0x8AB4F8)),
            JBColor(Color(0xE8F0FE), Color(0x25334A)),
        )
        RelationKind.IMPORT -> Triple(
            "IMPORT",
            JBColor(Color(0x525866), Color(0xA6ADBA)),
            JBColor(Color(0xECEEF2), Color(0x393C42)),
        )
        RelationKind.CONTAINS -> Triple(
            "LINK",
            UIUtil.getLabelForeground(),
            JBColor(Color(0xECEEF2), Color(0x393C42)),
        )
    }

    private fun fileName(path: String): String = path.substringAfterLast('/')

    private companion object {
        const val MIN_WIDTH = 520
        const val MAX_WIDTH = 720
        const val MAX_HEIGHT = 420
    }
}

/** Compact read-only code snippet renderer with line numbers and highlight on [CodeSnippet.highlightLine]. */
internal class SnippetCodeView(private val snippet: CodeSnippet) : JComponent() {
    private val scheme = runCatching { EditorColorsManager.getInstance().globalScheme }.getOrNull()
    private val codeFont: Font = run {
        val base = scheme?.getFont(EditorFontType.PLAIN) ?: Font(Font.MONOSPACED, Font.PLAIN, JBFont.small().size)
        base.deriveFont(JBFont.small().size2D.coerceAtLeast(11f))
    }
    private val boldFont: Font = codeFont.deriveFont(codeFont.style or Font.BOLD)
    private val fm = getFontMetrics(codeFont)
    private val lineHeight = fm.height.coerceAtLeast(JBUI.scale(14))
    private val pad = JBUI.scale(4)
    private val gutterGap = JBUI.scale(6)
    private val maxDigits = (snippet.firstLine + snippet.lines.size - 1).coerceAtLeast(1).toString().length.coerceAtLeast(2)
    private val gutterWidth = fm.stringWidth("0".repeat(maxDigits)) + pad * 2
    private val bgColor: Color = scheme?.defaultBackground ?: UIUtil.getPanelBackground()
    private val fgColor: Color = scheme?.defaultForeground ?: UIUtil.getLabelForeground()
    private val highlightBg = JBColor(Color(0xE8F1FF), Color(0x2B394F))
    private val gutterBg = JBColor(Color(0xF5F6F8), Color(0x2B2D30))

    init {
        name = "charts-tree-connection-snippet"
        isOpaque = true
        font = codeFont
        val maxLineW = snippet.lines.maxOfOrNull { fm.stringWidth(it) } ?: 0
        val w = (gutterWidth + gutterGap + maxLineW + pad * 2).coerceIn(JBUI.scale(180), JBUI.scale(420))
        val h = snippet.lines.size.coerceAtLeast(1) * lineHeight + pad * 2
        preferredSize = Dimension(w, h)
        minimumSize = Dimension(JBUI.scale(140), h)
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = bgColor
            g.fillRect(0, 0, width, height)

            // Gutter background
            g.color = gutterBg
            g.fillRect(0, 0, gutterWidth, height)

            for (index in snippet.lines.indices) {
                val lineNumber = snippet.firstLine + index
                val y = pad + index * lineHeight
                val isHighlighted = lineNumber == snippet.highlightLine
                if (isHighlighted) {
                    g.color = highlightBg
                    g.fillRect(0, y, width, lineHeight)
                }
                val numText = lineNumber.toString()
                g.font = if (isHighlighted) boldFont else codeFont
                g.color = if (isHighlighted) UIUtil.getLabelForeground() else UIUtil.getContextHelpForeground()
                val numW = g.getFontMetrics(g.font).stringWidth(numText)
                g.drawString(numText, gutterWidth - pad - numW, y + fm.ascent)

                g.font = codeFont
                g.color = fgColor
                g.drawString(snippet.lines[index], gutterWidth + gutterGap, y + fm.ascent)
            }

            // Gutter divider and outer frame
            g.color = JBColor.border()
            g.drawLine(gutterWidth, 0, gutterWidth, height - 1)
            g.drawRect(0, 0, width - 1, height - 1)
        } finally {
            g.dispose()
        }
    }
}

/**
 * Coordinates hover-delayed and click-pinned [TreeConnectionPreviewPanel] popups for [TreeBoardPanel].
 * Tracks the active panel state even in headless tests where [owner] is not showing.
 */
internal class TreeConnectionPreviewController(
    private val owner: JComponent,
    private val openAt: ((String, Int) -> Unit)? = null,
    private val openContext: ((String, String, TreeConnectionSite, RelativePoint) -> Unit)? = null,
) : Disposable {

    var activePanel: TreeConnectionPreviewPanel? = null
        private set
    var activePair: Pair<String, String>? = null
        private set
    var isPinned: Boolean = false
        private set

    private var popup: JBPopup? = null
    private var pendingHover: (() -> Unit)? = null

    private val showTimer = Timer(HOVER_SHOW_DELAY_MS) {
        pendingHover?.invoke()
    }.apply {
        isRepeats = false
    }

    private val hideTimer = Timer(HOVER_HIDE_DELAY_MS) {
        if (!isPinned && !isPointerInsidePopup()) hide()
    }.apply {
        isRepeats = false
    }

    fun scheduleHover(from: String, to: String, site: TreeConnectionSite, anchor: Point) {
        if (isPinned) return
        val pair = from to to
        if (activePair == pair && activePanel != null) {
            hideTimer.stop()
            return
        }
        hideTimer.stop()
        pendingHover = { showPreview(from, to, site, anchor, pinned = false) }
        showTimer.restart()
    }

    fun onHoverCleared() {
        showTimer.stop()
        pendingHover = null
        if (!isPinned && activePanel != null) {
            hideTimer.restart()
        }
    }

    fun showPinned(from: String, to: String, site: TreeConnectionSite, anchor: Point) {
        showTimer.stop()
        hideTimer.stop()
        pendingHover = null
        showPreview(from, to, site, anchor, pinned = true)
    }

    fun hide() {
        showTimer.stop()
        hideTimer.stop()
        pendingHover = null
        val current = popup
        clearState()
        if (current != null && !current.isDisposed) {
            current.cancel()
        }
    }

    private fun showPreview(from: String, to: String, site: TreeConnectionSite, anchor: Point, pinned: Boolean) {
        val current = popup
        clearState()
        if (current != null && !current.isDisposed) {
            current.cancel()
        }

        val relativePoint = RelativePoint(
            owner,
            Point(anchor.x + JBUI.scale(12), anchor.y + JBUI.scale(8)),
        )
        val panel = TreeConnectionPreviewPanel(
            fromPath = from,
            toPath = to,
            site = site,
            pinned = pinned,
            openAt = openAt,
            openContext = openContext?.let { callback -> { callback(from, to, site, relativePoint) } },
            onClose = { hide() },
        )

        if (!pinned) {
            val hoverKeepAlive = object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) = hideTimer.stop()
                override fun mouseExited(e: MouseEvent) {
                    if (!isPinned && !isPointerInsidePanel(panel)) hideTimer.restart()
                }
            }
            panel.addMouseListener(hoverKeepAlive)
        }

        activePanel = panel
        activePair = from to to
        isPinned = pinned

        if (owner.isShowing) {
            val created = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, if (pinned) panel else null)
                .setRequestFocus(pinned)
                .setFocusable(pinned)
                .setMovable(true)
                .setResizable(true)
                .setCancelOnClickOutside(true)
                .setCancelKeyEnabled(true)
                .setCancelCallback {
                    clearState()
                    true
                }
                .createPopup()
            popup = created
            created.show(relativePoint)
        }
    }

    private fun isPointerInsidePopup(): Boolean {
        val panel = activePanel ?: return false
        return isPointerInsidePanel(panel)
    }

    private fun isPointerInsidePanel(panel: JComponent): Boolean {
        if (!panel.isShowing) return false
        val pointer = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull() ?: return false
        val topLeft = runCatching { panel.locationOnScreen }.getOrNull() ?: return false
        val bounds = Rectangle(topLeft.x, topLeft.y, panel.width, panel.height)
        return bounds.contains(pointer)
    }

    private fun clearState() {
        popup = null
        activePanel = null
        activePair = null
        isPinned = false
    }

    override fun dispose() {
        hide()
    }

    private companion object {
        const val HOVER_SHOW_DELAY_MS = 250
        const val HOVER_HIDE_DELAY_MS = 280
    }
}
