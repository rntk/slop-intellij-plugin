package dev.insight.review.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.graph.RelationKind
import dev.insight.review.PreviewPlacement
import dev.insight.review.PreviewSide
import dev.insight.review.headingToPreview
import dev.insight.review.treePreviewPlacement
import dev.insight.review.CodeSnippet
import dev.insight.review.TreeConnectionOccurrence
import dev.insight.review.TreeConnectionSite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.KeyStroke
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Floating preview window showing short, visually separated source-code snippets from both files
 * for every resolved occurrence (calls, usages, inheritance, imports) of a Tree connection link.
 * Like the source preview, it docks to the board's edge beside the linked row and can be pinned and resized.
 */
internal class TreeConnectionPreviewPanel(
    private val fromPath: String,
    private val toPath: String,
    site: TreeConnectionSite,
    pinned: Boolean,
    private val openAt: ((String, Int) -> Unit)? = null,
    private val openContext: (() -> Unit)? = null,
    private val onClose: () -> Unit = {},
    project: Project? = null,
    explain: (suspend () -> String)? = null,
    private val onTogglePin: () -> Unit = {},
    onResize: (dx: Int, done: Boolean) -> Unit = { _, _ -> },
    private val onEscape: () -> Unit = {},
) : JPanel(BorderLayout()) {

    internal var onExplanationShown: () -> Unit = {}

    val pair: Pair<String, String> get() = fromPath to toPath

    // Asking for an explanation pins the window, so the answer doesn't vanish when the pointer moves on.
    private val explanation = if (project != null && explain != null) {
        TreeConnectionExplanationPanel(project, site, explain, onRequest = { if (!isPinned) onTogglePin() }) { onExplanationShown() }
    } else null

    private val entries = site.occurrences.ifEmpty {
        listOf(TreeConnectionOccurrence(RelationKind.REFERENCE, site.fromLine, site.toLine))
    }
    private val total = maxOf(site.totalOccurrences, entries.size)

    private val summaryLabel = JLabel().apply {
        name = "charts-tree-connection-summary"
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
    }

    private val pinButton = InplaceButton(IconButton("Pin preview", AllIcons.General.Pin_tab, AllIcons.General.Pin_tab)) {
        onTogglePin()
    }.apply {
        name = "charts-tree-connection-pin"
        cursor = Cursor.getDefaultCursor()
    }

    private val grip = PreviewResizeGrip("charts-tree-connection-grip", { isPinned }, onResize)

    private lateinit var header: JPanel

    var isPinned: Boolean = pinned
        set(value) {
            field = value
            updatePinned()
        }

    var side: PreviewSide = PreviewSide.RIGHT
        set(value) {
            if (field == value) return
            field = value
            grip.side = value
            remove(grip)
            add(grip, if (value == PreviewSide.RIGHT) BorderLayout.WEST else BorderLayout.EAST)
            revalidate()
        }

    fun cancelExplanation() {
        explanation?.cancel()
    }

    /** Distance from the window's top to its title's middle, to line the title up with the linked row. */
    fun titleCenter(): Int = insets.top + header.preferredSize.height / 2

    private fun updatePinned() {
        summaryLabel.text = summaryText(entries, total, isPinned)
        val icon = if (isPinned) AllIcons.General.PinSelected else AllIcons.General.Pin_tab
        pinButton.setIcons(icon, null, icon)
        pinButton.toolTipText = if (isPinned) "Unpin preview (or click the link again)" else "Pin preview (or click the link), so it stays while you explore"
        if (::header.isInitialized) header.background = if (isPinned) TreeSourcePreviewPanel.pinnedHeaderColor else TreeSourcePreviewPanel.headerColor
        repaint()
    }

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
        minimumSize = Dimension(0, JBUI.scale(MIN_PANE_HEIGHT))
    }

    init {
        name = "charts-tree-connection-preview"
        isOpaque = true
        background = UIUtil.getListBackground()
        border = JBUI.Borders.customLine(JBColor.border(), 1)
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "charts-tree-connection-escape")
        actionMap.put("charts-tree-connection-escape", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = onEscape()
        })

        val fromName = fileName(fromPath)
        val toName = fileName(toPath)

        header = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            name = "charts-tree-connection-header"
            isOpaque = true
            background = TreeSourcePreviewPanel.headerColor
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineBottom(JBColor.border()),
                JBUI.Borders.empty(6, 10, 6, 4),
            )
            val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(JLabel("$fromName → $toName").apply {
                    name = "charts-tree-connection-title"
                    font = JBFont.label().asBold()
                    toolTipText = "$fromPath → $toPath"
                })
                add(summaryLabel)
            }
            val actionsRow = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                explanation?.let { add(it.button) }
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
                add(pinButton)
                add(InplaceButton(IconButton("Close preview (Esc)", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) {
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
        add(grip, BorderLayout.WEST)
        updatePinned()
        if (explanation != null) {
            val splitter = OnePixelSplitter(true, EXPLANATION_SPLIT_PROPORTION).apply {
                name = "charts-tree-connection-split"
                setHonorComponentsMinimumSize(true)
                firstComponent = scrollPane
                secondComponent = explanation
            }
            add(splitter, BorderLayout.CENTER)
        } else {
            add(scrollPane, BorderLayout.CENTER)
        }
    }

    override fun getPreferredSize(): Dimension {
        val raw = super.getPreferredSize()
        val width = raw.width.coerceIn(JBUI.scale(MIN_WIDTH), JBUI.scale(MAX_WIDTH))
        val height = if (explanation?.isVisible == true) {
            raw.height.coerceIn(JBUI.scale(EXPLANATION_MIN_HEIGHT), JBUI.scale(EXPLANATION_MAX_HEIGHT))
        } else {
            raw.height.coerceAtMost(JBUI.scale(MAX_HEIGHT))
        }
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
        const val MIN_WIDTH = 560
        const val MAX_WIDTH = 780
        const val MAX_HEIGHT = 420
        const val EXPLANATION_MIN_HEIGHT = 500
        const val EXPLANATION_MAX_HEIGHT = 640
        const val MIN_PANE_HEIGHT = 80
        const val EXPLANATION_SPLIT_PROPORTION = 0.48f
    }
}

private class TreeConnectionExplanationPanel(
    private val project: Project,
    private val site: TreeConnectionSite,
    private val explain: suspend () -> String,
    private val onRequest: () -> Unit = {},
    private val onShown: () -> Unit = {},
) : JPanel(BorderLayout()) {
    private val scope = project.service<LinkedTextLinks>().coroutineScope
    private var request: Job? = null

    val button = JButton("Explain connection").apply {
        name = "charts-tree-explain-connection"
        addActionListener { requestExplanation() }
    }

    init {
        name = "charts-tree-connection-explanation"
        minimumSize = Dimension(0, JBUI.scale(80))
        isVisible = false
        site.explanation?.let(::showSummary)
    }

    fun cancel() {
        request?.cancel()
    }

    private fun requestExplanation() {
        if (request?.isActive == true || project.isDisposed) return
        onRequest()
        button.isEnabled = false
        showStatus("Explaining connection…")
        request = scope.launch(Dispatchers.EDT) {
            try {
                val summary = withContext(Dispatchers.IO) { explain() }
                check(summary.isNotBlank()) { "The model returned an empty explanation" }
                showSummary(summary)
                site.explanation = summary
            } catch (e: CancellationException) {
                // Cancelled from the progress UI rather than by closing the preview: allow another attempt.
                if (isActive) {
                    showStatus("Explanation cancelled.")
                    button.isEnabled = true
                }
                throw e
            } catch (e: Exception) {
                if (e is ControlFlowException) throw e
                logger<TreeConnectionExplanationPanel>().warn("Could not explain tree connection", e)
                showStatus("Could not explain this connection. Try again.")
                button.isEnabled = true
            }
        }
    }

    private fun showSummary(summary: String) {
        button.isEnabled = false
        val pane = LinkedTextPane(project, scope).apply {
            border = JBUI.Borders.empty(8, 10)
            markdown = summary
        }
        showContent(JBScrollPane(pane).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            verticalScrollBar.unitIncrement = JBUI.scale(16)
        })
        onShown()
    }

    private fun showStatus(message: String) {
        showContent(JLabel(message).apply {
            border = JBUI.Borders.empty(8, 10)
            foreground = UIUtil.getContextHelpForeground()
            verticalAlignment = SwingConstants.TOP
        })
    }

    private fun showContent(content: JComponent) {
        removeAll()
        add(content, BorderLayout.CENTER)
        isVisible = true
        revalidate()
        repaint()
        parent?.revalidate()
        parent?.repaint()
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

/** A Tree link whose occurrences the floating link window shows; [linkedPath] is the row at its far end. */
internal data class TreeConnectionTarget(val from: String, val to: String, val site: TreeConnectionSite, val linkedPath: String) {
    val pair: Pair<String, String> get() = from to to
}

/**
 * Decides which Tree link the floating link window shows and where, the same way [TreeSourcePreviewController] does
 * for rows: the window docks to the board's edge beside the linked row, follows the hovered link after a short wait,
 * waits longer while the pointer heads for it, and stays while the pointer is inside. Clicking a link pins its window
 * until the reader clicks the link again, closes the window, or presses Escape.
 */
internal class TreeConnectionPreviewController(
    private val host: JComponent,
    private val openAt: ((String, Int) -> Unit)? = null,
    private val openContext: ((String, String, TreeConnectionSite, RelativePoint) -> Unit)? = null,
    private val project: Project? = null,
    private val explainConnection: (suspend (String, String, TreeConnectionSite) -> String)? = null,
    /** The link's anchor bounds on [host], or null while it is hidden. */
    private val anchorBounds: (TreeConnectionTarget) -> Rectangle? = { null },
    /** Returns keyboard focus to the board, on Escape inside the window. */
    private val escape: () -> Unit = {},
    private val changed: () -> Unit = {},
) : Disposable {
    private var hovered: TreeConnectionTarget? = null
    var pinned: TreeConnectionTarget? = null
        private set
    private var dismissed: Pair<String, String>? = null
    private var preferredWidth: Int? = null
    private var target: TreeConnectionTarget? = null

    /** The window on the board, if any. */
    var activePanel: TreeConnectionPreviewPanel? = null
        private set
    var placement: PreviewPlacement? = null
        private set
    val activePair: Pair<String, String>? get() = activePanel?.pair
    val isPinned: Boolean get() = pinned != null && pinned?.pair == activePair

    private var lastAnchor: Rectangle? = null
    private var lastPointer: Point? = null
    private var headingToWindow = false
    private val timer = Timer(0) { apply() }.apply { isRepeats = false }

    private fun desired(): TreeConnectionTarget? =
        (pinned ?: hovered)?.takeUnless { it.pair == dismissed && it.pair != pinned?.pair }

    /** Follows the hovered link, or none; a change waits for the reader to settle on a link. */
    fun track(hover: TreeConnectionTarget?) {
        if (hover?.pair == hovered?.pair) return
        hovered = hover
        if (dismissed != null && hover?.pair != dismissed) dismissed = null
        if (pinned != null) return
        val next = desired()
        if (next?.pair == activePair) {
            timer.stop()
            return
        }
        schedule(when {
            headingToWindow && activePanel != null -> TreeSourcePreviewController.HOLD_DELAY_MS
            next == null -> TreeSourcePreviewController.LEAVE_DELAY_MS
            activePanel == null -> TreeSourcePreviewController.OPEN_DELAY_MS
            else -> TreeSourcePreviewController.SWITCH_DELAY_MS
        })
    }

    /** Pins [link], or unpins it when it is already pinned. */
    fun togglePin(link: TreeConnectionTarget) {
        pin(if (pinned?.pair == link.pair) null else link)
    }

    fun pin(link: TreeConnectionTarget?) {
        pinned = link
        dismissed = null
        apply(force = true)
    }

    /** Notes where the pointer moved on [host], to tell whether it is heading for the window. */
    fun pointerMoved(point: Point) {
        val previous = lastPointer
        lastPointer = point
        val current = placement
        headingToWindow = previous != null && current != null && activePanel?.isVisible == true &&
            headingToPreview(previous.x, previous.y, point.x, point.y, current, JBUI.scale(TreeSourcePreviewController.INTENT_SLACK))
        if (headingToWindow && timer.isRunning) schedule(TreeSourcePreviewController.HOLD_DELAY_MS)
    }

    /** Applies any pending change now; tests use it in place of waiting. */
    fun flush() {
        if (timer.isRunning) apply()
    }

    /** Hides the window until the reader moves on to another link, and unpins it. */
    fun dismiss() {
        val current = activePair ?: return
        pinned = null
        dismissed = current
        apply(force = true)
    }

    /** Closes the window and forgets the hovered and pinned links, as when the links change. */
    fun hide() {
        timer.stop()
        hovered = null
        pinned = null
        dismissed = null
        close()
    }

    private fun schedule(delay: Int) {
        timer.stop()
        timer.initialDelay = delay
        timer.start()
    }

    /** Shows what the window should show now; [force] skips the hold while the pointer is inside the window. */
    private fun apply(force: Boolean = false) {
        timer.stop()
        if (!force && pinned == null && pointerInside()) {
            schedule(TreeSourcePreviewController.LEAVE_DELAY_MS)
            return
        }
        val next = desired() ?: return close()
        var panel = activePanel
        if (panel == null || panel.pair != next.pair) {
            close(notify = false)
            panel = build(next)
            activePanel = panel
            host.add(panel, 0)
            lastAnchor = null
        }
        target = next
        panel.isPinned = pinned?.pair == next.pair
        panel.isVisible = true
        position()
        changed()
    }

    private fun close(notify: Boolean = true) {
        val panel = activePanel ?: return
        panel.cancelExplanation()
        host.remove(panel)
        activePanel = null
        target = null
        placement = null
        host.repaint()
        if (notify) changed()
    }

    private fun build(link: TreeConnectionTarget): TreeConnectionPreviewPanel {
        val (from, to, site) = link
        lateinit var panel: TreeConnectionPreviewPanel
        panel = TreeConnectionPreviewPanel(
            fromPath = from,
            toPath = to,
            site = site,
            pinned = false,
            openAt = openAt,
            openContext = openContext?.let { callback ->
                { callback(from, to, site, RelativePoint(host, placement?.let { Point(it.x, it.y) } ?: Point())) }
            },
            onClose = { dismiss() },
            project = project,
            explain = explainConnection?.let { callback -> suspend { callback(from, to, site) } },
            onTogglePin = { togglePin(link) },
            onResize = { dx, _ -> resizeBy(dx) },
            onEscape = {
                dismiss()
                escape()
            },
        )
        panel.onExplanationShown = { if (activePanel === panel) position() }
        return panel
    }

    private fun resizeBy(dx: Int) {
        val current = placement ?: return
        if (dx == 0) return
        preferredWidth = (current.width + dx).coerceIn(
            JBUI.scale(TreeSourcePreviewController.MIN_WIDTH),
            maxOf(JBUI.scale(TreeSourcePreviewController.MIN_WIDTH), host.width - JBUI.scale(TreeSourcePreviewController.MARGIN) * 2),
        )
        position()
    }

    /** Places the window beside its linked row; called when it changes, resizes, or the board does. */
    fun position() {
        val panel = activePanel ?: return
        val link = target ?: return
        if (host.width <= 0 || host.height <= 0) return
        val anchor = anchorBounds(link)?.also { lastAnchor = it } ?: lastAnchor ?: Rectangle(0, host.height / 3, 0, 0)
        val next = treePreviewPlacement(
            viewWidth = host.width,
            viewHeight = host.height,
            anchorTop = anchor.y,
            anchorBottom = anchor.y + anchor.height,
            anchorLeft = anchor.x,
            anchorRight = anchor.x + anchor.width,
            width = preferredWidth ?: panel.preferredSize.width,
            naturalHeight = panel.preferredSize.height,
            margin = JBUI.scale(TreeSourcePreviewController.MARGIN),
            gap = JBUI.scale(TreeSourcePreviewController.GAP),
            minWidth = JBUI.scale(TreeSourcePreviewController.MIN_WIDTH),
            minHeight = JBUI.scale(TreeSourcePreviewController.MIN_HEIGHT),
            headerOffset = panel.titleCenter() - anchor.height / 2,
        )
        placement = next
        panel.side = next.side
        val bounds = Rectangle(next.x, next.y, next.width, next.height)
        if (panel.bounds != bounds) {
            panel.bounds = bounds
            panel.revalidate()
        }
        host.repaint()
    }

    /** The linked row's screen bounds, which the window's notch points at. */
    fun anchor(): Rectangle? = target?.let(anchorBounds) ?: lastAnchor

    /** Whether the pointer is over the window, which keeps what it shows. */
    fun pointerInside(): Boolean {
        val panel = activePanel?.takeIf { it.isShowing } ?: return false
        val pointer = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull() ?: return false
        val origin = runCatching { panel.locationOnScreen }.getOrNull() ?: return false
        return Rectangle(origin.x, origin.y, panel.width, panel.height).contains(pointer)
    }

    /** Whether keyboard focus is inside the window, such as in its explanation. */
    fun hasFocus(): Boolean = activePanel?.let {
        SwingUtilities.isDescendingFrom(java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return false, it)
    } == true

    override fun dispose() = hide()
}
