package dev.insight.board.ui

import com.intellij.openapi.Disposable
import com.intellij.util.ui.JBUI
import dev.insight.board.BoardAnchor
import dev.insight.board.BoardChange
import dev.insight.board.BoardLayoutResult
import dev.insight.board.BoardMetrics
import dev.insight.board.BoardModel
import dev.insight.board.BoardRect
import dev.insight.board.GroupBlock
import dev.insight.board.boardColumns
import dev.insight.board.boardLayout
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** What the reader can do from a board. */
interface BoardPanelActions {
    /** Opens the code in the IDE. */
    fun navigate(anchor: BoardAnchor)

    /** Starts a chat question about this block. */
    fun askAbout(blockId: String)

    /** The reader removes a block; the host edits the model, and the panel follows. */
    fun remove(blockId: String)
}

/**
 * The explainer board as a scrollable Swing page: one card per block, placed by [boardLayout] in as many columns as the
 * viewport width holds, with the board's arrows painted over the cards. Follows [model] until disposed. Add it to a
 * JBScrollPane; it tracks the viewport width and scrolls vertically (and horizontally only when two columns do not fit).
 * Ctrl+wheel zooms. EDT only.
 */
class BoardPanel(private val model: BoardModel, private val actions: BoardPanelActions) : JPanel(null), Scrollable, Disposable {
    private val cards = LinkedHashMap<String, BoardCard>()
    private var style = BoardStyle(1.0)
    private var version = 0
    private var cachedKey: Pair<Int, Int>? = null
    private var cached: BoardLayoutResult? = null
    private val subscription: Disposable
    private val flashTimer = Timer(FLASH_MILLIS) { cards.values.forEach { it.flashing = false } }.apply { isRepeats = false }

    private val host = object : BoardCardHost {
        override fun navigate(anchor: BoardAnchor) = actions.navigate(anchor)
        override fun ask(blockId: String) = actions.askAbout(blockId)
        override fun remove(blockId: String) = actions.remove(blockId)
        override fun select(blockId: String) = this@BoardPanel.select(blockId)
    }

    /** The scale of fonts and metrics, clamped to [MIN_ZOOM]..[MAX_ZOOM]. */
    var zoom: Double = 1.0
        set(value) {
            val next = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
            if (next == field) return
            field = next
            style = BoardStyle(next)
            sync(rebuild = true)
        }

    /** The block the reader last clicked or [focus]ed, or null. */
    var selectedBlockId: String? = null
        private set

    /** Each block's card, in board order (tests and hosts). */
    internal val blockComponents: Map<String, JComponent>
        get() = model.board.blocks.mapNotNull { b -> cards[b.id]?.let { b.id to it } }.toMap()

    init {
        name = "board-panel"
        isOpaque = true
        background = BoardColors.page
        subscription = model.subscribe { change -> sync(rebuild = change == BoardChange.Reset) }
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = select(null)
        })
        addMouseWheelListener { e ->
            if (e.isControlDown || e.isMetaDown) {
                e.consume()
                zoom *= WHEEL_ZOOM.pow(-e.preciseWheelRotation)
            } else {
                // Listening for the wheel takes it from the scroll pane; plain scrolling goes back to it.
                (SwingUtilities.getAncestorOfClass(JScrollPane::class.java, this) as? JScrollPane)
                    ?.let { it.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, it)) }
            }
        }
        sync(rebuild = true)
    }

    /** Selects [blockId], scrolls its card into view and flashes its border for a moment. */
    fun focus(blockId: String) {
        val card = cards[blockId] ?: return
        select(blockId)
        if (card.width <= 0) doLayout()
        scrollRectToVisible(card.bounds)
        cards.values.forEach { it.flashing = it === card }
        flashTimer.restart()
    }

    /** The largest zoom at which the whole board fits the viewport (or the panel when not in one). */
    fun zoomToFit() {
        val viewport = parent as? JViewport
        val availableWidth = viewport?.width ?: width
        val availableHeight = viewport?.height ?: height
        if (availableWidth <= 0 || availableHeight <= 0 || cards.isEmpty()) return
        var best: Double? = null
        var next = zoom
        for (attempt in 0 until FIT_ATTEMPTS) {
            zoom = next
            val size = preferredSize
            val ratio = min(availableWidth.toDouble() / size.width, availableHeight.toDouble() / size.height)
            if (ratio >= 1.0) best = max(best ?: 0.0, zoom)
            if (ratio in 0.98..1.02) break
            next = (zoom * ratio).coerceIn(MIN_ZOOM, MAX_ZOOM)
            if (next == zoom) break
        }
        zoom = best ?: MIN_ZOOM
        revalidate()
        repaint()
    }

    override fun dispose() {
        subscription.dispose()
        flashTimer.stop()
    }

    private fun select(blockId: String?) {
        selectedBlockId = blockId?.takeIf { it in cards }
        cards.forEach { (id, card) -> card.selected = id == selectedBlockId }
        repaint()
    }

    /** Matches the cards to the board: new blocks get cards, changed ones new cards, removed ones lose theirs. */
    private fun sync(rebuild: Boolean) {
        val board = model.board
        for ((id, card) in cards.entries.toList()) {
            if (rebuild || board.block(id) != card.block) {
                remove(card)
                cards.remove(id)
            }
        }
        for (block in board.blocks) {
            if (block.id in cards) continue
            val card = BoardCard(block, style, host)
            cards[block.id] = card
            add(card)
        }
        // Group frames are painted first, under the cards they hold; Swing paints the last component first.
        for (card in cards.values) if (card.block is GroupBlock) setComponentZOrder(card, componentCount - 1)
        if (selectedBlockId?.let { it in cards } != true) selectedBlockId = null
        cards.forEach { (id, card) -> card.selected = id == selectedBlockId }
        version++
        revalidate()
        repaint()
    }

    private fun margin() = style.px(16)

    private fun metrics() = BoardMetrics(gap = style.px(16), groupPadding = style.px(12),
        groupHeader = max(style.px(28), getFontMetrics(style.bold).height + style.px(10)))

    private fun viewportWidth(): Int = (parent as? JViewport)?.width?.takeIf { it > 0 } ?: width

    /** The board laid out for the current viewport width and zoom; remembered until either or the board changes. */
    internal fun currentLayout(): BoardLayoutResult {
        val viewportWidth = viewportWidth()
        val key = version to viewportWidth
        cached?.let { if (cachedKey == key) return it }
        val metrics = metrics()
        val columnWidth = style.px(COLUMN_WIDTH)
        val columns = boardColumns(max(0, viewportWidth - 2 * margin()), columnWidth, metrics.gap)
        val result = boardLayout(model.board, columns, columnWidth, metrics) { block, width ->
            val card = cards[block.id]
            when {
                card == null -> metrics.groupHeader
                card.kind == BoardCard.Kind.GROUP -> metrics.groupHeader
                else -> card.heightFor(width)
            }
        }
        cached = result
        cachedKey = key
        return result
    }

    override fun doLayout() {
        val layout = currentLayout()
        val margin = margin()
        for ((id, card) in cards) {
            val rect = layout.rects[id]
            card.isVisible = rect != null
            if (rect != null) card.setBounds(margin + rect.x, margin + rect.y, rect.width, rect.height)
        }
    }

    override fun getPreferredSize(): Dimension {
        if (isPreferredSizeSet) return super.getPreferredSize()
        val layout = currentLayout()
        return Dimension(layout.width + 2 * margin(), layout.height + 2 * margin())
    }

    override fun paintChildren(g: Graphics) {
        super.paintChildren(g)
        paintEdges(g)
    }

    private fun Rectangle.toBoard() = BoardRect(x, y, width, height)

    private fun paintEdges(g: Graphics) {
        val edges = model.board.edges
        if (edges.isEmpty()) return
        val g2 = g.board()
        try {
            val head = style.px(9).toDouble()
            g2.stroke = BasicStroke(style.px(1).toFloat() * 1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.font = style.small
            val metrics = g2.fontMetrics
            for (edge in edges) {
                val from = cards[edge.from]?.takeIf { it.isVisible && it.width > 0 } ?: continue
                val to = cards[edge.to]?.takeIf { it.isVisible && it.width > 0 } ?: continue
                val (a, b) = edgeSegment(from.bounds.toBoard(), to.bounds.toBoard()) ?: continue
                val angle = atan2(b.y - a.y, b.x - a.x)
                val tipBase = Pair(b.x - cos(angle) * head * 0.8, b.y - sin(angle) * head * 0.8)
                g2.color = BoardColors.edge
                g2.draw(java.awt.geom.Line2D.Double(a.x, a.y, tipBase.first, tipBase.second))
                val arrow = Path2D.Double().apply {
                    moveTo(b.x, b.y)
                    lineTo(b.x - head * cos(angle - ARROW_SPREAD), b.y - head * sin(angle - ARROW_SPREAD))
                    lineTo(b.x - head * cos(angle + ARROW_SPREAD), b.y - head * sin(angle + ARROW_SPREAD))
                    closePath()
                }
                g2.fill(arrow)
                val label = edge.label?.takeIf { it.isNotBlank() } ?: continue
                val text = fitText(label, style.px(COLUMN_WIDTH) / 2, metrics)
                val padX = style.px(6)
                val w = metrics.stringWidth(text) + 2 * padX
                val h = metrics.height + style.px(2)
                val x = (a.x + b.x) / 2 - w / 2.0
                val y = (a.y + b.y) / 2 - h / 2.0
                val pill = RoundRectangle2D.Double(x, y, w.toDouble(), h.toDouble(), h.toDouble(), h.toDouble())
                g2.color = BoardColors.page
                g2.fill(pill)
                g2.color = BoardColors.edge
                g2.draw(pill)
                g2.color = BoardColors.muted
                g2.drawString(text, (x + padX).toFloat(), (y + style.px(1) + metrics.ascent).toFloat())
            }
        } finally {
            g2.dispose()
        }
    }

    // Scrollable: follow the viewport width unless the narrowest board is wider; scroll vertically.
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        max(JBUI.scale(16), if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width)

    override fun getScrollableTracksViewportWidth(): Boolean {
        val viewport = parent as? JViewport ?: return false
        return viewport.width >= preferredSize.width
    }

    override fun getScrollableTracksViewportHeight(): Boolean {
        val viewport = parent as? JViewport ?: return false
        return viewport.height > preferredSize.height
    }

    companion object {
        const val MIN_ZOOM = 0.3
        const val MAX_ZOOM = 2.0

        /** A layout column's width at 100% zoom, before IDE scaling. */
        internal const val COLUMN_WIDTH = 300
        private const val WHEEL_ZOOM = 1.1
        private const val FLASH_MILLIS = 1200
        private const val FIT_ATTEMPTS = 6
        private val ARROW_SPREAD = Math.PI / 7
    }
}
