package dev.insight.review.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JViewport
import javax.swing.KeyStroke
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.math.max
import kotlin.math.min

/** A small fact shown on a tile: [value] large (e.g. "12"), [label] small beside/below it (e.g. "files"). */
internal data class ReviewTileStat(val value: String, val label: String)

/** One tile: a stable [id], its [title], a one- or two-sentence [hint], and what a click does. */
internal class ReviewTile(val id: String, val title: String, val hint: String, val open: () -> Unit)

/**
 * A grid of clickable tiles, one per view of a review. Tiles wrap to the available width (a tile is MIN_TILE_WIDTH..MAX_TILE_WIDTH
 * wide, all tiles in a row the same height). Clicking an enabled tile, or pressing Enter/Space on a focused one, calls its open.
 * All methods are EDT-only.
 */
internal class ReviewTileGrid(tiles: List<ReviewTile>) : JPanel(TileGridLayout()), Scrollable {
    /** The tiles in order; each tile component has name "review-tile-<id>" (for tests) and accessibleName = title. */
    val tiles: List<ReviewTile> = tiles.toList()

    private val cards: Map<String, TileCard>

    val tileComponents: Map<String, JComponent>

    var selectedId: String? = null
        private set

    init {
        isOpaque = false
        val byId = LinkedHashMap<String, TileCard>()
        for (tile in this.tiles) {
            val card = TileCard(tile)
            byId[tile.id] = card
            add(card)
        }
        cards = byId
        tileComponents = byId
    }

    /** Replaces the stats shown at the bottom of tile [id]; empty hides the stats row. */
    fun setStats(id: String, stats: List<ReviewTileStat>) {
        val card = cards[id] ?: return
        card.setStats(stats)
        revalidate()
        repaint()
    }

    /** Disabled tiles are greyed, not clickable, and show [reason] as tooltip when given; enabled ones show the hint as tooltip. */
    fun setTileEnabled(id: String, enabled: Boolean, reason: String? = null) {
        val card = cards[id] ?: return
        card.setTileEnabled(enabled, reason)
        repaint()
    }

    fun isTileEnabled(id: String): Boolean = cards[id]?.tileEnabled ?: false

    /** Highlights the tile of the currently shown view (accent border); null clears. */
    fun setSelected(id: String?) {
        val next = id?.takeIf { it in cards }
        selectedId = next
        for ((cardId, card) in cards) card.setTileSelected(cardId == next)
    }

    /** Clicks tile [id] as a user would: no-op when disabled. */
    fun click(id: String) {
        cards[id]?.activate()
    }

    // Scrollable: follow the viewport width, scroll vertically only.
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        max(JBUI.scale(16), if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width)

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false

    companion object {
        const val MIN_TILE_WIDTH = 220
        const val MAX_TILE_WIDTH = 340

        internal const val TILE_GAP = 12
        internal const val MIN_TILE_HEIGHT = 120
    }
}

/** Wraps tiles into rows: as many columns as fit at the minimum width, stretched up to the maximum; rows share one height. */
private class TileGridLayout : LayoutManager {
    override fun addLayoutComponent(name: String?, comp: java.awt.Component?) {}

    override fun removeLayoutComponent(comp: java.awt.Component?) {}

    override fun preferredLayoutSize(parent: java.awt.Container): Dimension = synchronized(parent.treeLock) {
        val insets = parent.insets
        val horizontal = insets.left + insets.right
        var width = (parent.parent as? JViewport)?.width ?: parent.width
        if (width <= 0) {
            val gap = JBUI.scale(ReviewTileGrid.TILE_GAP)
            width = 3 * JBUI.scale(ReviewTileGrid.MIN_TILE_WIDTH) + 2 * gap + horizontal
        }
        val height = arrange(parent, width, place = false)
        Dimension(width, height)
    }

    override fun minimumLayoutSize(parent: java.awt.Container): Dimension = synchronized(parent.treeLock) {
        val insets = parent.insets
        Dimension(
            JBUI.scale(ReviewTileGrid.MIN_TILE_WIDTH) + insets.left + insets.right,
            JBUI.scale(ReviewTileGrid.MIN_TILE_HEIGHT) + insets.top + insets.bottom,
        )
    }

    override fun layoutContainer(parent: java.awt.Container) {
        synchronized(parent.treeLock) { arrange(parent, parent.width, place = true) }
    }

    /** Returns the total height (insets included); positions the tiles when [place]. */
    private fun arrange(parent: java.awt.Container, width: Int, place: Boolean): Int {
        val insets = parent.insets
        val comps = parent.components.filter { it.isVisible }
        if (comps.isEmpty()) return insets.top + insets.bottom
        val gap = JBUI.scale(ReviewTileGrid.TILE_GAP)
        val minW = JBUI.scale(ReviewTileGrid.MIN_TILE_WIDTH)
        val maxW = JBUI.scale(ReviewTileGrid.MAX_TILE_WIDTH)
        val minH = JBUI.scale(ReviewTileGrid.MIN_TILE_HEIGHT)
        val avail = max(1, width - insets.left - insets.right)
        val cols = ((avail + gap) / (minW + gap)).coerceIn(1, comps.size)
        val tileW = min(maxW, max(1, (avail - (cols - 1) * gap) / cols))
        var y = insets.top
        for ((rowIndex, row) in comps.chunked(cols).withIndex()) {
            if (rowIndex > 0) y += gap
            val rowH = row.maxOf { c ->
                max(minH, (c as? TileCard)?.heightForWidth(tileW) ?: c.getPreferredSize().height)
            }
            if (place) {
                var x = insets.left
                for (c in row) {
                    c.setBounds(x, y, tileW, rowH)
                    x += tileW + gap
                }
            }
            y += rowH
        }
        return y + insets.bottom
    }
}

/** A text area that never takes mouse events, so clicks and tooltips reach the card underneath. */
private class PassThroughTextArea : JTextArea() {
    override fun contains(x: Int, y: Int): Boolean = false
}

private class TileCard(private val tile: ReviewTile) : JPanel(null) {
    var tileEnabled: Boolean = true
        private set
    private var hovered = false
    private var pressed = false
    private var selected = false

    private val titleArea = textArea(tile.title, scaledFont(JBFont.label().asBold().biggerOn(2f), 1.1f), strong = true)
    private val hintArea = textArea(tile.hint, scaledFont(JBFont.small(), 1.2f), strong = false)
    private val statsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        isVisible = false
    }

    init {
        name = "review-tile-${tile.id}"
        (this as javax.accessibility.Accessible).accessibleContext.accessibleName = tile.title
        isOpaque = false
        isFocusable = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = tile.hint
        add(titleArea)
        add(hintArea)
        add(statsPanel)

        val mouse = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovered = tileEnabled
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false
                pressed = false
                repaint()
            }

            override fun mousePressed(e: MouseEvent) {
                pressed = tileEnabled && SwingUtilities.isLeftMouseButton(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                val fire = pressed && SwingUtilities.isLeftMouseButton(e) && contains(e.point)
                pressed = false
                if (fire) activate()
            }
        }
        addMouseListener(mouse)
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = repaint()
            override fun focusLost(e: FocusEvent) = repaint()
        })
        val activateAction = object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) = activate()
        }
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "activate-tile")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "activate-tile")
        actionMap.put("activate-tile", activateAction)
    }

    fun activate() {
        if (tileEnabled) tile.open()
    }

    fun setTileEnabled(enabled: Boolean, reason: String?) {
        tileEnabled = enabled
        if (!enabled) {
            hovered = false
            pressed = false
        }
        isFocusable = enabled
        cursor = Cursor.getPredefinedCursor(if (enabled) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
        toolTipText = if (enabled) tile.hint else reason ?: tile.hint
        repaint()
    }

    fun setTileSelected(value: Boolean) {
        if (selected == value) return
        selected = value
        repaint()
    }

    fun setStats(stats: List<ReviewTileStat>) {
        statsPanel.removeAll()
        for ((index, stat) in stats.withIndex()) {
            if (index > 0) statsPanel.add(Box.createHorizontalStrut(JBUI.scale(16)))
            statsPanel.add(statView(stat))
        }
        statsPanel.isVisible = stats.isNotEmpty()
        statsPanel.revalidate()
        revalidate()
        repaint()
    }

    private fun statView(stat: ReviewTileStat): JComponent {
        val box = JPanel()
        box.layout = BoxLayout(box, BoxLayout.X_AXIS)
        box.isOpaque = false
        box.alignmentY = BOTTOM_ALIGNMENT
        val value = JLabel(stat.value)
        value.name = "review-tile-stat-value"
        value.font = JBFont.label().asBold().biggerOn(8f)
        value.foreground = textColor()
        value.alignmentY = BOTTOM_ALIGNMENT
        val label = JLabel(stat.label)
        label.name = "review-tile-stat-label"
        label.font = JBFont.small()
        label.foreground = hintColor()
        label.alignmentY = BOTTOM_ALIGNMENT
        label.border = BorderFactory.createEmptyBorder(0, JBUI.scale(4), JBUI.scale(4), 0)
        box.add(value)
        box.add(label)
        return box
    }

    private fun scaledFont(base: java.awt.Font, factor: Float): java.awt.Font = base.deriveFont(base.size2D * factor)

    private fun textArea(text: String, textFont: java.awt.Font, strong: Boolean): JTextArea = PassThroughTextArea().apply {
        this.text = text
        font = textFont
        isEditable = false
        isFocusable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        border = null
        foreground = if (strong) textColor() else hintColor()
    }

    private fun textColor(): Color = JBColor.lazy { if (tileEnabled) UIUtil.getLabelForeground() else UIUtil.getInactiveTextColor() }

    private fun hintColor(): Color = JBColor.lazy { if (tileEnabled) UIUtil.getContextHelpForeground() else UIUtil.getInactiveTextColor() }

    private fun padding(): Int = JBUI.scale(14)

    private fun wrappedHeight(area: JTextArea, width: Int): Int {
        area.setSize(max(1, width), 10_000)
        return area.preferredSize.height
    }

    /** Height this tile needs when it is [width] wide (at least the minimum tile height). */
    fun heightForWidth(width: Int): Int {
        val pad = padding()
        val inner = max(1, width - 2 * pad)
        var h = pad + wrappedHeight(titleArea, inner) + JBUI.scale(4) + wrappedHeight(hintArea, inner)
        if (statsPanel.isVisible) h += JBUI.scale(12) + statsPanel.preferredSize.height
        h += pad
        return max(JBUI.scale(ReviewTileGrid.MIN_TILE_HEIGHT), h)
    }

    override fun getPreferredSize(): Dimension =
        if (isPreferredSizeSet) super.getPreferredSize()
        else {
            val w = JBUI.scale(ReviewTileGrid.MIN_TILE_WIDTH)
            Dimension(w, heightForWidth(w))
        }

    override fun getMinimumSize(): Dimension =
        Dimension(JBUI.scale(ReviewTileGrid.MIN_TILE_WIDTH), JBUI.scale(ReviewTileGrid.MIN_TILE_HEIGHT))

    override fun doLayout() {
        val pad = padding()
        val inner = max(1, width - 2 * pad)
        val titleH = wrappedHeight(titleArea, inner)
        titleArea.setBounds(pad, pad, inner, titleH)
        val hintY = pad + titleH + JBUI.scale(4)
        hintArea.setBounds(pad, hintY, inner, wrappedHeight(hintArea, inner))
        if (statsPanel.isVisible) {
            val statsH = statsPanel.preferredSize.height
            statsPanel.setBounds(pad, max(hintY, height - pad - statsH), inner, statsH)
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val arc = JBUI.scale(12).toFloat()
            g2.color = when {
                hovered && tileEnabled -> HOVER_BACKGROUND
                else -> TILE_BACKGROUND
            }
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))

            val borderWidth = if (selected) 2f else 1f
            g2.color = if (selected) JBUI.CurrentTheme.Focus.focusColor() else JBColor.border()
            g2.stroke = BasicStroke(borderWidth)
            g2.draw(
                RoundRectangle2D.Float(
                    borderWidth / 2, borderWidth / 2, width - borderWidth, height - borderWidth, arc, arc,
                ),
            )
            if (isFocusOwner && tileEnabled) {
                val inset = JBUI.scale(3).toFloat()
                g2.color = JBUI.CurrentTheme.Focus.focusColor()
                g2.stroke = BasicStroke(1f)
                g2.draw(
                    RoundRectangle2D.Float(inset, inset, width - 2 * inset, height - 2 * inset, arc - inset, arc - inset),
                )
            }
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        val TILE_BACKGROUND: Color = JBColor.namedColor("ReviewTile.background", JBColor(0xFFFFFF, 0x313438))
        val HOVER_BACKGROUND: Color = JBColor.namedColor("ReviewTile.hoverBackground", JBColor(0xEEF3FD, 0x3A3D42))
    }
}
