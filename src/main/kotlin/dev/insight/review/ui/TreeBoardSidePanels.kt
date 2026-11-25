package dev.insight.review.ui

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.TreeRailCard
import dev.insight.review.TreeRailMode
import dev.insight.review.TreeRailModel
import dev.insight.review.TreeRailPlacement
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.border.MatteBorder
import kotlin.math.roundToInt

/** Content displayed in the left summary margin of the Tree board. */
internal data class TreeSummaryDisplay(
    val kindLabel: String,
    val title: String,
    val subtitle: String? = null,
    val topicBadge: String? = null,
    val summary: String? = null,
    val missingText: String = "No summary yet",
    val accent: Color? = null,
    val linkSourcePath: String? = null,
    val linkEntryFiles: List<String> = emptyList(),
)

/**
 * Left-column summary panel in the Tree board's Document-style layout: shows the summary of the hovered or selected
 * Topic/Group card, file, or folder, with clickable file/symbol links when available.
 */
internal class TreeBoardSummaryPanel(
    private val project: Project?,
    private val linkContext: ((String?, List<String>) -> SummaryTextLinkContext?)?,
) : JPanel(BorderLayout()) {
    private var currentDisplay: TreeSummaryDisplay? = null

    private val kindLabel = JLabel("Summary").apply {
        name = "charts-tree-summary-kind"
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
    }

    private val header = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.compound(
            JBUI.Borders.customLineBottom(JBColor.border()),
            JBUI.Borders.empty(6, 10),
        )
        add(kindLabel, BorderLayout.CENTER)
    }

    private val contentHost = ScrollableWidthPanel(VerticalLayout(JBUI.scale(6))).apply {
        isOpaque = false
        border = JBUI.Borders.empty(10)
    }

    private val scrollPane = JBScrollPane(contentHost).apply {
        border = null
        isOpaque = false
        viewport.isOpaque = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        verticalScrollBar.unitIncrement = JBUI.scale(16)
    }

    init {
        name = "charts-tree-summary-panel"
        background = UIUtil.getListBackground()
        isOpaque = true
        border = MatteBorder(0, 0, 0, 1, JBColor.border())
        preferredSize = Dimension(JBUI.scale(DEFAULT_WIDTH), 0)
        minimumSize = Dimension(JBUI.scale(MIN_WIDTH), 0)
        add(header, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    fun show(display: TreeSummaryDisplay?) {
        if (display == currentDisplay) return
        currentDisplay = display
        contentHost.removeAll()
        if (display == null) {
            kindLabel.text = "Summary"
            contentHost.add(JLabel("Hover or select an entry to read its summary.").apply {
                name = "charts-tree-summary-empty"
                font = JBFont.small().deriveFont(Font.ITALIC)
                foreground = UIUtil.getContextHelpForeground()
            })
        } else {
            kindLabel.text = display.kindLabel
            val bodyCard = JPanel(VerticalLayout(JBUI.scale(5))).apply {
                isOpaque = false
                border = JBUI.Borders.compound(
                    MatteBorder(0, JBUI.scale(2), 0, 0, display.accent ?: JBColor.border()),
                    JBUI.Borders.empty(2, 8, 2, 2),
                )
                add(readOnlyText(display.title, JBFont.label().asBold(), UIUtil.getLabelForeground()).apply {
                    name = "charts-tree-summary-title"
                })
                display.subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
                    add(readOnlyText(sub, JBFont.small(), UIUtil.getContextHelpForeground()).apply {
                        name = "charts-tree-summary-subtitle"
                    })
                }
                display.topicBadge?.takeIf { it.isNotBlank() }?.let { badge ->
                    add(readOnlyText(badge, JBFont.small(), UIUtil.getContextHelpForeground()).apply {
                        name = "charts-tree-summary-topic"
                    })
                }
                val summary = display.summary
                if (summary != null) {
                    val area = readOnlyText(summary, JBFont.label().forSummaryText(), UIUtil.getLabelForeground()).apply {
                        name = "charts-tree-summary-text"
                    }
                    if (project != null) {
                        linkContext?.invoke(display.linkSourcePath, display.linkEntryFiles)?.let { ctx ->
                            installSummaryTextLinks(area, summary, project, ctx)
                        }
                    }
                    add(area)
                } else {
                    add(readOnlyText(display.missingText, JBFont.small().deriveFont(Font.ITALIC), UIUtil.getContextHelpForeground()).apply {
                        name = "charts-tree-summary-empty"
                    })
                }
            }
            contentHost.add(bodyCard)
        }
        contentHost.revalidate()
        contentHost.repaint()
        scrollPane.verticalScrollBar.value = 0
    }

    private fun readOnlyText(value: String, textFont: Font, color: Color): JTextArea = JTextArea(value).apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        border = null
        font = textFont
        foreground = color
        putClientProperty("caretWidth", 0)
    }

    companion object {
        const val DEFAULT_WIDTH = 280
        const val MIN_WIDTH = 200
    }
}

/**
 * Right-column rail panel in the Tree board's Document-style layout: renders Topic or Group cards in hierarchical
 * columns (leaf level innermost next to the tree, broadest level outermost), vertically aligned with the matching
 * file rows in the tree canvas.
 */
internal class TreeBoardRailPanel(
    private val model: TreeRailModel,
    allowGrouping: Boolean,
    mode: TreeRailMode,
    level: Int?,
    onModeChanged: (TreeRailMode) -> Unit,
    onLevelChanged: (Int?) -> Unit,
    private val onHover: (TreeRailCard?) -> Unit,
    private val onSelect: (TreeRailCard) -> Unit,
    private val rowScreenBoundsY: (Int) -> Pair<Double, Double>,
    private val canvasYOffset: () -> Int,
    private val onWheelScroll: (MouseWheelEvent) -> Unit,
) : JPanel(BorderLayout()) {
    private var placements: List<TreeRailPlacement> = emptyList()
    private var lastHoveredKey: String? = null
    private var lastSelectedKey: String? = null
    private var lastMatchingKeys: Set<String> = emptySet()

    internal val railCanvas: JComponent = RailCanvas()

    init {
        name = "charts-tree-rail-panel"
        background = UIUtil.getListBackground()
        isOpaque = true
        border = MatteBorder(0, 1, 0, 0, JBColor.border())
        val columnCount = model.visibleDepth.coerceAtLeast(1)
        val cardWidth = JBUI.scale(when (columnCount) {
            1 -> 190
            2 -> 145
            3 -> 130
            else -> 120
        })
        val preferredWidth = if (model.cards.isEmpty()) {
            JBUI.scale(DEFAULT_WIDTH)
        } else {
            maxOf(
                JBUI.scale(MIN_WIDTH),
                columnCount * cardWidth + (columnCount - 1) * JBUI.scale(COLUMN_GAP) + 2 * JBUI.scale(RAIL_PAD_X),
            )
        }
        preferredSize = Dimension(preferredWidth, 0)
        minimumSize = Dimension(JBUI.scale(MIN_WIDTH), 0)

        val headerLeft: JComponent = if (allowGrouping) {
            JComboBox(arrayOf(TreeRailMode.TOPICS.title, TreeRailMode.GROUPING.title)).apply {
                name = "charts-tree-rail-mode"
                font = JBFont.small()
                isFocusable = false
                selectedIndex = if (mode == TreeRailMode.GROUPING) 1 else 0
                toolTipText = "Switch between code Topics and change Groups"
                addActionListener {
                    val chosen = if (selectedIndex == 1) TreeRailMode.GROUPING else TreeRailMode.TOPICS
                    if (chosen != mode) onModeChanged(chosen)
                }
            }
        } else {
            JLabel(mode.title).apply {
                name = "charts-tree-rail-title"
                font = JBFont.small().asBold()
                foreground = UIUtil.getContextHelpForeground()
            }
        }

        val header = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineBottom(JBColor.border()),
                JBUI.Borders.empty(4, 8),
            )
            add(headerLeft, BorderLayout.WEST)
            if (this@TreeBoardRailPanel.model.depth > 1) {
                val railModel = this@TreeBoardRailPanel.model
                val options = (0 until railModel.depth - 1).map { "${it + 1}" } + "All"
                val levelCombo = JComboBox(options.toTypedArray()).apply {
                    name = "charts-tree-rail-level"
                    font = JBFont.small()
                    isFocusable = false
                    selectedIndex = if (level == null || level >= railModel.depth - 1) options.lastIndex else level.coerceIn(0, railModel.depth - 2)
                    toolTipText = "Show ${railModel.mode.plural} up to the selected depth"
                    addActionListener {
                        val nextLevel = if (selectedIndex >= railModel.depth - 1) null else selectedIndex
                        if (nextLevel != level) onLevelChanged(nextLevel)
                    }
                }
                add(levelCombo, BorderLayout.EAST)
            }
        }
        add(header, BorderLayout.NORTH)

        if (model.cards.isEmpty()) {
            val command = if (mode == TreeRailMode.GROUPING) "/grouping" else "/topics"
            val emptyPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(8)
                add(JLabel("No ${mode.plural} yet ($command)").apply {
                    name = "charts-tree-rail-empty"
                    font = JBFont.small().deriveFont(Font.ITALIC)
                    foreground = UIUtil.getContextHelpForeground()
                }, BorderLayout.NORTH)
            }
            add(emptyPanel, BorderLayout.CENTER)
        } else {
            add(railCanvas, BorderLayout.CENTER)
        }
    }

    fun setPlacements(next: List<TreeRailPlacement>) {
        if (placements == next) return
        placements = next
        railCanvas.repaint()
    }

    fun repaintRail() {
        railCanvas.repaint()
    }

    fun updateHighlights(hoveredKey: String?, selectedKey: String?, matchingKeys: Set<String>) {
        if (hoveredKey == lastHoveredKey && selectedKey == lastSelectedKey && matchingKeys == lastMatchingKeys) return
        lastHoveredKey = hoveredKey
        lastSelectedKey = selectedKey
        lastMatchingKeys = matchingKeys
        railCanvas.repaint()
    }

    fun cardScreenBounds(key: String): Rectangle? =
        allPlacementBounds().firstOrNull { it.first.card.key == key }?.second

    fun allPlacementBounds(): List<Pair<TreeRailPlacement, Rectangle>> {
        if (placements.isEmpty() || model.visibleDepth <= 0) return emptyList()
        val columnCount = model.visibleDepth.coerceAtLeast(1)
        val padX = JBUI.scale(RAIL_PAD_X)
        val gap = JBUI.scale(COLUMN_GAP)
        val colW = ((railCanvas.width - 2 * padX - (columnCount - 1) * gap) / columnCount).coerceAtLeast(JBUI.scale(60))
        val offset = canvasYOffset()
        return placements.map { placement ->
            val x = padX + placement.column * (colW + gap)
            val rawTop = rowScreenBoundsY(placement.firstRow).first - offset
            val rawBottom = rowScreenBoundsY(placement.lastRow).second - offset
            val y = rawTop.roundToInt() + 1
            val h = (rawBottom.roundToInt() - 1 - y).coerceAtLeast(10)
            placement to Rectangle(x, y, colW, h)
        }
    }

    private fun countText(placement: TreeRailPlacement): String {
        val total = placement.card.files.size
        return when {
            placement.fileCountInSpan < total -> "${placement.fileCountInSpan} of $total files"
            total == 1 -> "1 file"
            else -> "$total files"
        }
    }

    private inner class RailCanvas : JComponent() {
        init {
            name = "charts-tree-rail-canvas"
            isOpaque = true
            background = UIUtil.getListBackground()
            toolTipText = ""

            val mouse = object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val hit = hitTest(e.point)?.takeUnless { it.card.placeholder }
                    onHover(hit?.card)
                    cursor = if (hit != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
                }

                override fun mouseExited(e: MouseEvent) {
                    onHover(null)
                    cursor = Cursor.getDefaultCursor()
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 1) {
                        hitTest(e.point)?.takeUnless { it.card.placeholder }?.let { onSelect(it.card) }
                    }
                }
            }
            addMouseListener(mouse)
            addMouseMotionListener(mouse)
            addMouseWheelListener { onWheelScroll(it) }
        }

        private fun hitTest(point: Point): TreeRailPlacement? =
            allPlacementBounds().firstOrNull { (_, rect) -> rect.contains(point) }?.first

        override fun getToolTipText(event: MouseEvent): String? =
            hitTest(event.point)?.let { placement -> "${placement.card.fullPath} · ${countText(placement)}" }

        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                UISettings.setupAntialiasing(g)

                g.color = UIUtil.getListBackground()
                g.fillRect(0, 0, width, height)

                val boldTitleFont = JBFont.label().asBold().forReviewName()
                val placeholderFont = JBFont.label().deriveFont(Font.ITALIC).forReviewName()
                val countFont = JBFont.small()
                val boldMetrics = g.getFontMetrics(boldTitleFont)
                val placeholderMetrics = g.getFontMetrics(placeholderFont)
                val countMetrics = g.getFontMetrics(countFont)

                val accentW = JBUI.scale(ACCENT_WIDTH)
                val padLeft = 1 + accentW + JBUI.scale(6)
                val padRight = 1 + JBUI.scale(5)
                val padY = JBUI.scale(4)
                val titleGap = JBUI.scale(2)

                for ((placement, rect) in allPlacementBounds()) {
                    if (rect.y + rect.height < 0 || rect.y > height) continue
                    val card = placement.card
                    val key = card.key
                    val hovered = key == lastHoveredKey
                    val selected = key == lastSelectedKey
                    val matched = key in lastMatchingKeys
                    val active = hovered || selected || matched

                    g.color = when {
                        selected -> UIUtil.getListSelectionBackground(false)
                        hovered -> JBUI.CurrentTheme.List.Hover.background(true)
                        else -> UIUtil.getListBackground()
                    }
                    g.fillRect(rect.x, rect.y, rect.width, rect.height)

                    g.color = card.family?.let(DocumentColumnsPanel::accent) ?: JBColor.border()
                    g.fillRect(rect.x + 1, rect.y + 1, accentW, (rect.height - 2).coerceAtLeast(1))

                    g.color = if (active) JBUI.CurrentTheme.Focus.focusColor() else JBColor.border()
                    g.drawRect(rect.x, rect.y, (rect.width - 1).coerceAtLeast(1), (rect.height - 1).coerceAtLeast(1))

                    val textX = rect.x + padLeft
                    val textW = (rect.width - padLeft - padRight).coerceAtLeast(8)
                    val titleFont = if (card.placeholder) placeholderFont else boldTitleFont
                    val titleMetrics = if (card.placeholder) placeholderMetrics else boldMetrics
                    val titleColor = if (card.placeholder) UIUtil.getContextHelpForeground() else UIUtil.getLabelForeground()

                    val oldClip = g.clip
                    g.clipRect(
                        rect.x + 1 + accentW,
                        rect.y + 1,
                        (rect.width - 2 - accentW).coerceAtLeast(1),
                        (rect.height - 2).coerceAtLeast(1),
                    )
                    try {
                        val fullBlockMinHeight = titleMetrics.height + countMetrics.height + padY * 2 + titleGap
                        if (rect.height >= fullBlockMinHeight) {
                            val availableForTitle = rect.height - padY * 2 - countMetrics.height - titleGap
                            val maxLines = (availableForTitle / titleMetrics.height).coerceAtLeast(1)
                            val lines = wrapLines(card.name, titleMetrics, textW, maxLines)
                            val headerHeight = lines.size * titleMetrics.height + titleGap + countMetrics.height
                            val maxTop = rect.y + rect.height - padY - headerHeight
                            val startY = if (rect.y < 0) {
                                minOf(JBUI.scale(4), maxTop).coerceAtLeast(rect.y + padY)
                            } else {
                                rect.y + padY
                            }
                            g.font = titleFont
                            g.color = titleColor
                            for ((idx, line) in lines.withIndex()) {
                                g.drawString(line, textX, startY + idx * titleMetrics.height + titleMetrics.ascent)
                            }
                            g.font = countFont
                            g.color = UIUtil.getContextHelpForeground()
                            val countY = startY + lines.size * titleMetrics.height + titleGap + countMetrics.ascent
                            g.drawString(ellipsize(countText(placement), countMetrics, textW), textX, countY)
                        } else if (rect.height >= 10) {
                            val line = ellipsize(card.name, titleMetrics, textW)
                            g.font = titleFont
                            g.color = titleColor
                            val baselineY = if (rect.y < 0 && rect.height >= titleMetrics.height + 2) {
                                val maxTop = rect.y + rect.height - titleMetrics.height - 1
                                val top = minOf(JBUI.scale(2), maxTop).coerceAtLeast(rect.y + 1)
                                top + titleMetrics.ascent
                            } else {
                                (rect.y + (rect.height + titleMetrics.ascent - titleMetrics.descent) / 2.0).roundToInt()
                            }
                            g.drawString(line, textX, baselineY)
                        }
                    } finally {
                        g.clip = oldClip
                    }
                }
            } finally {
                g.dispose()
            }
        }
    }

    private fun ellipsize(text: String, metrics: FontMetrics, maxWidth: Int): String {
        if (metrics.stringWidth(text) <= maxWidth) return text
        val ellipsis = "…"
        if (metrics.stringWidth(ellipsis) > maxWidth) return ""
        var end = text.length
        while (end > 0 && metrics.stringWidth(text.substring(0, end) + ellipsis) > maxWidth) {
            end--
        }
        return text.substring(0, end) + ellipsis
    }

    private fun wrapLines(text: String, metrics: FontMetrics, maxWidth: Int, maxLines: Int): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        var wordIdx = 0
        while (wordIdx < words.size) {
            val word = words[wordIdx]
            if (current.isEmpty()) {
                if (metrics.stringWidth(word) <= maxWidth) {
                    current.append(word)
                } else {
                    lines += ellipsize(word, metrics, maxWidth)
                    if (lines.size == maxLines) {
                        if (wordIdx < words.lastIndex) {
                            lines[lines.lastIndex] = ellipsize(lines.last() + "…", metrics, maxWidth)
                        }
                        return lines
                    }
                }
                wordIdx++
            } else {
                val candidate = "$current $word"
                if (metrics.stringWidth(candidate) <= maxWidth) {
                    current.append(' ').append(word)
                    wordIdx++
                } else {
                    lines += current.toString()
                    current = StringBuilder()
                    if (lines.size == maxLines) {
                        lines[lines.lastIndex] = ellipsize(lines.last() + " " + words.subList(wordIdx, words.size).joinToString(" "), metrics, maxWidth)
                        return lines
                    }
                }
            }
        }
        if (current.isNotEmpty()) {
            lines += current.toString()
        }
        return if (lines.size > maxLines) {
            val kept = lines.take(maxLines).toMutableList()
            kept[kept.lastIndex] = ellipsize(kept.last() + "…", metrics, maxWidth)
            kept
        } else {
            lines
        }
    }

    companion object {
        const val DEFAULT_WIDTH = 250
        const val MIN_WIDTH = 180
        const val COLUMN_GAP = 6
        const val RAIL_PAD_X = 6
        const val ACCENT_WIDTH = 4
    }
}

/** A vertical panel that tracks its scroll viewport width so wrapped text areas wrap cleanly without horizontal scroll. */
private class ScrollableWidthPanel(layout: LayoutManager) : JPanel(layout), Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        visibleRect.height.coerceAtLeast(JBUI.scale(32))
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = false
}
