package dev.insight.review.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.TreeRailMode
import dev.insight.review.TreeRailModel
import dev.insight.review.TreeRailPlacement
import dev.insight.review.treeRailColumnLefts
import dev.insight.review.treeRailColumnWidth
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.border.MatteBorder
import kotlin.math.roundToInt

/** Content displayed in the Tree board's floating summary card. */
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
 * The floating summary card on the Tree canvas: shows the summary of the hovered or selected Topic/Group card,
 * file, or folder, with clickable file/symbol links when available. By default the board places it beside the tree
 * in canvas coordinates, and readers can also drag its header to place it anywhere on the canvas or reset it.
 */
internal class TreeBoardSummaryPanel(
    private val project: Project?,
    private val linkContext: ((String?, List<String>) -> SummaryTextLinkContext?)?,
    /** Receives wheel events the card doesn't use to scroll its own text. */
    private val onWheel: (MouseWheelEvent) -> Unit = {},
    private val onClose: () -> Unit = {},
    private val onMove: (Int, Int) -> Unit = { _, _ -> },
    private val onResetPosition: () -> Unit = {},
) : JPanel(BorderLayout()) {
    private var currentDisplay: TreeSummaryDisplay? = null
    private var currentScale = 1.0

    /** The content shown now, or null when there is nothing to show. */
    val display: TreeSummaryDisplay? get() = currentDisplay

    /** Whether the card is currently placed at a custom position rather than beside the tree. */
    var isCustomPositioned: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            resetPositionButton.isVisible = value
            header.revalidate()
            header.repaint()
        }

    private val kindLabel = JLabel("Summary").apply {
        name = "charts-tree-summary-kind"
        foreground = UIUtil.getContextHelpForeground()
        toolTipText = "Drag to move summary card"
        cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
    }

    private val resetPositionButton = JButton(AllIcons.General.Reset).apply {
        name = "charts-tree-summary-reset-position"
        toolTipText = "Reset summary card to default position"
        isFocusable = false
        isBorderPainted = false
        isContentAreaFilled = false
        isOpaque = false
        isVisible = false
        margin = JBUI.emptyInsets()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { onResetPosition() }
    }

    private val closeButton = JButton(AllIcons.Actions.Close).apply {
        name = "charts-tree-summary-close"
        toolTipText = "Hide summary card"
        isFocusable = false
        isBorderPainted = false
        isContentAreaFilled = false
        isOpaque = false
        margin = JBUI.emptyInsets()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { onClose() }
    }

    private val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), 0)).apply {
        isOpaque = false
        add(resetPositionButton)
        add(closeButton)
    }

    private val header = JPanel(BorderLayout()).apply {
        name = "charts-tree-summary-header"
        isOpaque = false
        toolTipText = "Drag to move summary card"
        cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        add(kindLabel, BorderLayout.CENTER)
        add(actions, BorderLayout.EAST)
    }

    private val contentHost = ScrollableWidthPanel(VerticalLayout(JBUI.scale(6))).apply {
        isOpaque = false
    }

    private val scrollPane = JBScrollPane(contentHost).apply {
        border = null
        isOpaque = false
        viewport.isOpaque = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        verticalScrollBar.unitIncrement = JBUI.scale(16)
        // The card scrolls only while its text overflows; otherwise the wheel pans or zooms the canvas.
        isWheelScrollingEnabled = false
        addMouseWheelListener { e -> handleWheel(e) }
    }

    init {
        name = "charts-tree-summary-panel"
        background = UIUtil.getListBackground()
        isOpaque = true
        isVisible = false
        add(header, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        installHeaderDrag()
        applyScale(1.0)
    }

    private fun installHeaderDrag() {
        var dragStartInParent: Point? = null
        var panelStart: Point? = null
        val drag = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.isPopupTrigger) return
                val host = this@TreeBoardSummaryPanel.parent ?: return
                dragStartInParent = SwingUtilities.convertPoint(e.component, e.point, host)
                panelStart = Point(x, y)
                e.consume()
            }

            override fun mouseDragged(e: MouseEvent) {
                val start = dragStartInParent ?: return
                val origin = panelStart ?: return
                val host = this@TreeBoardSummaryPanel.parent ?: return
                val current = SwingUtilities.convertPoint(e.component, e.point, host)
                val dx = current.x - start.x
                val dy = current.y - start.y
                if (dx != 0 || dy != 0) {
                    onMove(origin.x + dx, origin.y + dy)
                }
                e.consume()
            }

            override fun mouseReleased(e: MouseEvent) {
                dragStartInParent = null
                panelStart = null
                e.consume()
            }
        }
        header.addMouseListener(drag)
        header.addMouseMotionListener(drag)
        kindLabel.addMouseListener(drag)
        kindLabel.addMouseMotionListener(drag)
    }

    private fun handleWheel(e: MouseWheelEvent) {
        val bar = scrollPane.verticalScrollBar
        val scrollable = bar.isVisible && bar.maximum - bar.visibleAmount > bar.minimum
        if (scrollable && !e.isControlDown && !e.isMetaDown && !e.isShiftDown) {
            val step = (e.preciseWheelRotation * bar.unitIncrement * 3).roundToInt()
            bar.value = (bar.value + step).coerceIn(bar.minimum, bar.maximum - bar.visibleAmount)
            e.consume()
        } else {
            onWheel(e)
        }
    }

    private fun applyScale(scale: Double) {
        currentScale = scale
        kindLabel.font = JBFont.small().scaledBy(scale)
        border = JBUI.Borders.customLine(JBColor.border(), (1 * scale).roundToInt().coerceAtLeast(1))
        header.border = JBUI.Borders.compound(
            JBUI.Borders.customLineBottom(JBColor.border()),
            JBUI.Borders.empty((5 * scale).roundToInt(), (10 * scale).roundToInt()),
        )
        contentHost.border = JBUI.Borders.empty((10 * scale).roundToInt())
    }

    /** Shows [display] with text scaled by [scale]; rebuilds only when either changes. */
    fun show(display: TreeSummaryDisplay?, scale: Double = currentScale) {
        val nextScale = (scale * 20).roundToInt() / 20.0
        if (display == currentDisplay && nextScale == currentScale) return
        val scrollTop = if (display == currentDisplay) scrollPane.verticalScrollBar.value else 0
        currentDisplay = display
        if (nextScale != currentScale) applyScale(nextScale)
        contentHost.removeAll()
        if (display != null) {
            kindLabel.text = display.kindLabel
            contentHost.add(bodyCard(display, nextScale))
        } else {
            kindLabel.text = "Summary"
        }
        contentHost.revalidate()
        contentHost.repaint()
        scrollPane.verticalScrollBar.value = scrollTop
    }

    private fun bodyCard(display: TreeSummaryDisplay, scale: Double) = JPanel(VerticalLayout(JBUI.scale(5))).apply {
        isOpaque = false
        border = JBUI.Borders.compound(
            MatteBorder(0, (JBUI.scale(2) * scale).roundToInt(), 0, 0, display.accent ?: JBColor.border()),
            JBUI.Borders.empty(2, (8 * scale).roundToInt(), 2, 2),
        )
        add(readOnlyText(display.title, JBFont.label().asBold().scaledBy(scale), UIUtil.getLabelForeground()).apply {
            name = "charts-tree-summary-title"
        })
        display.subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
            add(readOnlyText(sub, JBFont.small().scaledBy(scale), UIUtil.getContextHelpForeground()).apply {
                name = "charts-tree-summary-subtitle"
            })
        }
        display.topicBadge?.takeIf { it.isNotBlank() }?.let { badge ->
            add(readOnlyText(badge, JBFont.small().scaledBy(scale), UIUtil.getContextHelpForeground()).apply {
                name = "charts-tree-summary-topic"
            })
        }
        val summary = display.summary
        if (summary != null) {
            val area = readOnlyText(summary, JBFont.label().forSummaryText().scaledBy(scale), UIUtil.getLabelForeground()).apply {
                name = "charts-tree-summary-text"
            }
            if (project != null) {
                linkContext?.invoke(display.linkSourcePath, display.linkEntryFiles)?.let { ctx ->
                    installSummaryTextLinks(area, summary, project, ctx)
                }
            }
            add(area)
        } else {
            add(readOnlyText(display.missingText, JBFont.small().deriveFont(Font.ITALIC).scaledBy(scale), UIUtil.getContextHelpForeground()).apply {
                name = "charts-tree-summary-empty"
            })
        }
    }

    /** The height the card needs to show all of its content at [width]. */
    fun preferredHeightFor(width: Int): Int {
        val outer = insets
        val innerWidth = (width - outer.left - outer.right).coerceAtLeast(1)
        // Wrapped text measures its height from its width: lay out at the target width, then measure.
        contentHost.setSize(innerWidth, contentHost.height.coerceAtLeast(1))
        contentHost.doLayout()
        for (child in contentHost.components) {
            child.setSize(child.width, child.preferredSize.height)
            (child as? java.awt.Container)?.doLayout()
        }
        return outer.top + outer.bottom + header.preferredSize.height + contentHost.preferredSize.height
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
        /** Width of the card at zoom 1 and below. */
        const val DEFAULT_WIDTH = 300
        /** Gap between the card and the tree at zoom 1 and below. */
        const val GAP = 12
        /** Distance the card keeps from the canvas's top and bottom edges. */
        const val MARGIN = 12
    }
}

/**
 * The Tree canvas's Topic or Group cards, painted in hierarchical columns to the right of the tree (leaf level
 * innermost, broadest level outermost) and aligned with the rows they cover. Cards are part of the canvas: they pan
 * with it, and each column is as wide as its titles need, so deep hierarchies grow the board to the right.
 */
internal class TreeBoardRail(
    val model: TreeRailModel,
    private val metricsFor: (Font) -> FontMetrics,
    /** Screen x of the innermost column's left edge. */
    private val startX: () -> Double,
    /** Screen scale of the cards; see [dev.insight.review.treeCanvasCardScale]. */
    private val scale: () -> Double,
    /** Screen top and bottom of a tree row. */
    private val rowScreenY: (Int) -> Pair<Double, Double>,
) {
    private var placements: List<TreeRailPlacement> = emptyList()
    private var hoveredKey: String? = null
    private var highlightedAncestorKeys: Set<String> = emptySet()
    private var selectedKey: String? = null
    private var matchingKeys: Set<String> = emptySet()

    private val titleFont: Font get() = JBFont.label().asBold().forReviewName()
    private val placeholderFont: Font get() = JBFont.label().deriveFont(Font.ITALIC).forReviewName()
    private val countFont: Font get() = JBFont.small()
    private val emptyFont: Font get() = JBFont.small().deriveFont(Font.ITALIC)
    private val headingFont: Font get() = JBFont.small().asBold()

    private val padLeft get() = 1 + JBUI.scale(ACCENT_WIDTH) + JBUI.scale(8)
    private val padRight get() = 1 + JBUI.scale(8)

    /** Each column's width at zoom 1, measured once from the titles of its cards. */
    val baseColumnWidths: List<Int> by lazy {
        val columns = model.visibleDepth.coerceAtLeast(1)
        val title = metricsFor(titleFont)
        val placeholder = metricsFor(placeholderFont)
        val count = metricsFor(countFont)
        List(columns) { column ->
            val level = model.visibleDepth - 1 - column
            val widths = model.cards.filter { it.level == level }.map { card ->
                val metrics = if (card.placeholder) placeholder else title
                maxOf(metrics.stringWidth(card.name), count.stringWidth(fileCountText(card.files.size)))
            }
            treeRailColumnWidth(widths, padLeft + padRight, JBUI.scale(MIN_CARD_WIDTH), JBUI.scale(MAX_CARD_WIDTH))
        }
    }

    /** Width of all columns and their gaps at zoom 1. */
    val baseWidth: Int
        get() = if (model.cards.isEmpty()) {
            metricsFor(emptyFont).stringWidth(emptyText()) + JBUI.scale(8)
        } else {
            baseColumnWidths.sum() + (baseColumnWidths.size - 1) * JBUI.scale(COLUMN_GAP)
        }

    fun setPlacements(next: List<TreeRailPlacement>) {
        placements = next
    }

    fun updateHighlights(hovered: String?, selected: String?, matching: Set<String>) {
        hoveredKey = hovered
        highlightedAncestorKeys = model.ancestorCardKeys(hovered) + model.ancestorCardKeys(selected)
        selectedKey = selected
        matchingKeys = matching
    }

    fun isCardHighlighted(key: String): Boolean =
        key == hoveredKey || key == selectedKey || key in matchingKeys || key in highlightedAncestorKeys

    /** The first placement of the card [key] in canvas coordinates, or null when it isn't placed. */
    fun cardScreenBounds(key: String): Rectangle? =
        allPlacementBounds().firstOrNull { it.first.card.key == key }?.second

    /** The placement of the card [key] that anchors its summary: the first one in view, else the first one. */
    fun anchorBounds(key: String, viewHeight: Int): Rectangle? {
        val bounds = allPlacementBounds().filter { it.first.card.key == key }.map { it.second }
        return bounds.firstOrNull { it.y + it.height > 0 && it.y < viewHeight } ?: bounds.firstOrNull()
    }

    fun allPlacementBounds(): List<Pair<TreeRailPlacement, Rectangle>> {
        if (placements.isEmpty() || model.visibleDepth <= 0) return emptyList()
        val s = scale()
        val widths = baseColumnWidths
        val lefts = treeRailColumnLefts(startX(), widths, JBUI.scale(COLUMN_GAP), s)
        return placements.mapNotNull { placement ->
            val column = placement.column.takeIf { it in widths.indices } ?: return@mapNotNull null
            val top = rowScreenY(placement.firstRow).first.roundToInt() + 1
            val bottom = rowScreenY(placement.lastRow).second.roundToInt() - 1
            placement to Rectangle(lefts[column].roundToInt(), top, (widths[column] * s).roundToInt(), (bottom - top).coerceAtLeast(4))
        }
    }

    fun hitTest(point: Point): TreeRailPlacement? =
        allPlacementBounds().firstOrNull { (_, rect) -> rect.contains(point) }?.first

    fun tooltip(placement: TreeRailPlacement): String = "${placement.card.fullPath} · ${countText(placement)}"

    private fun emptyText(): String =
        "No ${model.mode.plural} yet (${if (model.mode == TreeRailMode.GROUPING) "/grouping" else "/topics"})"

    private fun countText(placement: TreeRailPlacement): String {
        val total = placement.card.files.size
        return if (placement.fileCountInSpan < total) "${placement.fileCountInSpan} of $total files" else fileCountText(total)
    }

    private fun fileCountText(count: Int) = if (count == 1) "1 file" else "$count files"

    fun paint(g: Graphics2D, viewWidth: Int, viewHeight: Int) {
        val s = scale()
        val left = startX()
        if (left > viewWidth) return
        val firstRow = rowScreenY(0)

        // A heading above the innermost column names what the cards show.
        g.font = headingFont.scaledBy(s)
        g.color = UIUtil.getContextHelpForeground()
        g.drawString(model.mode.title, left.toFloat(), (firstRow.first - JBUI.scale(4) * s).toFloat())

        if (model.cards.isEmpty()) {
            g.font = emptyFont.scaledBy(s)
            val metrics = g.fontMetrics
            val center = (firstRow.first + firstRow.second) / 2.0
            g.drawString(emptyText(), left.toFloat(), (center + (metrics.ascent - metrics.descent) / 2.0).toFloat())
            return
        }

        val boldTitle = titleFont.scaledBy(s)
        val italicTitle = placeholderFont.scaledBy(s)
        val counts = countFont.scaledBy(s)
        val boldMetrics = g.getFontMetrics(boldTitle)
        val placeholderMetrics = g.getFontMetrics(italicTitle)
        val countMetrics = g.getFontMetrics(counts)

        val accentW = (JBUI.scale(ACCENT_WIDTH) * s).roundToInt()
        val textPadLeft = (padLeft * s).roundToInt()
        val textPadRight = (padRight * s).roundToInt()
        val padY = (JBUI.scale(4) * s).roundToInt()
        val titleGap = (JBUI.scale(2) * s).roundToInt()
        val arc = JBUI.scale(6) * s

        for ((placement, rect) in allPlacementBounds()) {
            if (rect.y + rect.height < 0 || rect.y > viewHeight || rect.x > viewWidth || rect.x + rect.width < 0) continue
            val card = placement.card
            val hovered = card.key == hoveredKey
            val selected = card.key == selectedKey
            val active = isCardHighlighted(card.key)

            val shape = RoundRectangle2D.Double(rect.x.toDouble(), rect.y.toDouble(), rect.width - 1.0, rect.height - 1.0, arc, arc)
            g.color = when {
                selected -> UIUtil.getListSelectionBackground(false)
                hovered -> JBUI.CurrentTheme.List.Hover.background(true)
                else -> UIUtil.getListBackground()
            }
            g.fill(shape)

            val oldClip = g.clip
            g.clip(shape)
            try {
                g.color = card.family?.let(DocumentColumnsPanel::accent) ?: JBColor.border()
                g.fillRect(rect.x, rect.y, accentW, rect.height)

                val textX = rect.x + textPadLeft
                val textW = (rect.width - textPadLeft - textPadRight).coerceAtLeast(8)
                val font = if (card.placeholder) italicTitle else boldTitle
                val metrics = if (card.placeholder) placeholderMetrics else boldMetrics
                g.color = if (card.placeholder) UIUtil.getContextHelpForeground() else UIUtil.getLabelForeground()
                val fullBlock = metrics.height + countMetrics.height + padY * 2 + titleGap
                if (rect.height >= fullBlock) {
                    val maxLines = ((rect.height - padY * 2 - countMetrics.height - titleGap) / metrics.height).coerceAtLeast(1)
                    val lines = wrapLines(card.name, metrics, textW, maxLines)
                    val blockHeight = lines.size * metrics.height + titleGap + countMetrics.height
                    // Keep the title in view while the card's top is scrolled above the canvas.
                    val maxTop = rect.y + rect.height - padY - blockHeight
                    val startY = if (rect.y < 0) minOf(padY, maxTop).coerceAtLeast(rect.y + padY) else rect.y + padY
                    g.font = font
                    for ((index, line) in lines.withIndex()) {
                        g.drawString(line, textX, startY + index * metrics.height + metrics.ascent)
                    }
                    g.font = counts
                    g.color = UIUtil.getContextHelpForeground()
                    g.drawString(ellipsize(countText(placement), countMetrics, textW), textX,
                        startY + lines.size * metrics.height + titleGap + countMetrics.ascent)
                } else if (rect.height >= 6) {
                    // Short cards keep one title line, shrunk to their height when needed.
                    val fitFont = if (metrics.height <= rect.height) font
                    else font.deriveFont((font.size2D * rect.height / metrics.height).coerceAtLeast(6f))
                    val fitMetrics = g.getFontMetrics(fitFont)
                    g.font = fitFont
                    val baseline = (rect.y + (rect.height + fitMetrics.ascent - fitMetrics.descent) / 2.0).roundToInt()
                    g.drawString(ellipsize(card.name, fitMetrics, textW), textX, baseline)
                }
            } finally {
                g.clip = oldClip
            }

            g.color = if (active) JBUI.CurrentTheme.Focus.focusColor() else JBColor.border()
            g.stroke = BasicStroke(if (active) 1.5f * s.toFloat() else s.toFloat())
            g.draw(shape)
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
        const val MIN_CARD_WIDTH = 140
        const val MAX_CARD_WIDTH = 560
        const val COLUMN_GAP = 8
        /** Gap between the tree's widest row and the innermost column at zoom 1. */
        const val TREE_GAP = 32
        const val ACCENT_WIDTH = 4
    }
}

private fun Font.scaledBy(scale: Double): Font = if (scale == 1.0) this else deriveFont((size2D * scale).toFloat())

/** A vertical panel that tracks its scroll viewport width so wrapped text areas wrap cleanly without horizontal scroll. */
private class ScrollableWidthPanel(layout: LayoutManager) : JPanel(layout), Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        visibleRect.height.coerceAtLeast(JBUI.scale(32))
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = false
}
