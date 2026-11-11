package dev.insight.ui

import com.intellij.openapi.actionSystem.ActionButtonComponent
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.AbstractButton
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JList
import javax.swing.JScrollBar
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.JViewport
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.plaf.basic.BasicButtonUI
import javax.swing.table.JTableHeader
import javax.swing.text.JTextComponent

/**
 * A button that floats on the edge of a page, like the tool window buttons on the edges of the IDE: an [icon] and its text
 * written along the edge, or the icon alone, or a thin handle that unfolds while the pointer is on it. An [EdgeTabHost] places
 * it and picks its form, so it takes no room from the page and stays off the page's controls.
 */
internal class EdgeTab(text: String, icon: Icon) : JButton(text, icon) {
    internal var form = EdgeTabForm.FULL
        set(value) { if (field != value) { field = value; repaint() } }
    internal var side = EdgeSide.LEFT
        set(value) { if (field != value) { field = value; repaint() } }
    internal var hovered = false
        private set
    /** Called when the pointer enters or leaves, so the host can unfold or fold a handle. */
    internal var onHover: () -> Unit = {}

    init {
        setUI(BasicButtonUI())
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isRolloverEnabled = true
        font = JBFont.label()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hovered = true; repaint(); onHover() }
            override fun mouseExited(e: MouseEvent) { hovered = false; repaint(); onHover() }
        })
    }

    /** The sizes of each form, from the current font, icon and text. */
    fun edgeSize(): EdgeTabSize {
        val metrics = getFontMetrics(font)
        val iconSize = icon?.iconHeight?.takeIf { it > 0 } ?: JBUI.scale(14)
        val pad = JBUI.scale(PAD)
        val thickness = maxOf(metrics.height, iconSize) + 2 * JBUI.scale(3)
        val iconLength = iconSize + 2 * pad
        val textLength = text.takeIf { it.isNotEmpty() }?.let { metrics.stringWidth(it) + JBUI.scale(GAP) } ?: 0
        return EdgeTabSize(thickness, (iconLength + textLength).coerceAtMost(JBUI.scale(MAX_LENGTH)), iconLength, JBUI.scale(HANDLE))
    }

    override fun getPreferredSize(): Dimension = edgeSize().let { Dimension(it.thickness, it.fullLength) }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            if (form == EdgeTabForm.HANDLE && !hovered) {
                g2.color = JBUI.CurrentTheme.Focus.focusColor()
                g2.fillRoundRect(0, 0, width, height, width, width)
                return
            }
            // Rounded on the side away from the edge; the edge side runs past the bounds, so it stays square.
            val arc = JBUI.scale(10).toDouble()
            val shape = if (side == EdgeSide.LEFT) RoundRectangle2D.Double(-arc, 0.0, width - 1 + arc, height - 1.0, arc, arc)
            else RoundRectangle2D.Double(0.0, 0.0, width - 1 + arc, height - 1.0, arc, arc)
            val base = if (hovered || model.isPressed) JBUI.CurrentTheme.ActionButton.hoverBackground() else UIUtil.getPanelBackground()
            g2.color = Color(base.red, base.green, base.blue, 235)
            g2.fill(shape)
            g2.color = if (isFocusOwner) JBUI.CurrentTheme.Focus.focusColor() else JBColor.border()
            g2.draw(shape)
            val pad = JBUI.scale(PAD)
            val iconHeight = paintBackChevron(g2, pad)
            if (form != EdgeTabForm.FULL || text.isNullOrEmpty()) return
            // Written along the edge and read from the bottom up, as the IDE writes the names of its tool window buttons.
            g2.font = font
            g2.color = if (isEnabled) foreground else UIUtil.getInactiveTextColor()
            val metrics = g2.fontMetrics
            val top = pad + iconHeight + JBUI.scale(GAP)
            val label = ellipsize(text, height - pad - top) { metrics.stringWidth(it) }
            g2.translate(width / 2 + (metrics.ascent - metrics.descent) / 2, height - pad)
            g2.rotate(-Math.PI / 2)
            g2.drawString(label, 0, 0)
        } finally {
            g2.dispose()
        }
    }

    /** Paints the tab's icon, or a back chevron when the icon is an unrendered placeholder in headless tests. */
    private fun paintBackChevron(g2: Graphics2D, y: Int): Int {
        val icon = icon
        val size = icon?.iconHeight?.takeIf { it > 0 } ?: JBUI.scale(14)
        if (icon != null && icon.iconWidth > 0 && icon.iconHeight > 0 && icon.javaClass.name != "com.intellij.ui.IconManager\$EmptyIcon") {
            icon.paintIcon(this, g2, (width - icon.iconWidth) / 2, y)
            return size
        }
        val cx = width / 2
        val cy = y + size / 2
        val arm = JBUI.scale(4)
        val stroke = g2.stroke
        g2.color = if (isEnabled) foreground else UIUtil.getInactiveTextColor()
        g2.stroke = java.awt.BasicStroke(JBUI.scale(1).coerceAtLeast(1) * 1.5f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
        g2.drawLine(cx - arm / 2, cy, cx + arm / 2, cy - arm)
        g2.drawLine(cx - arm / 2, cy, cx + arm / 2, cy + arm)
        g2.drawLine(cx - arm / 2, cy, cx + arm, cy)
        g2.stroke = stroke
        return size
    }

    private companion object {
        const val PAD = 4
        const val GAP = 4
        const val HANDLE = 4
        const val MAX_LENGTH = 320
    }
}

/** [text] cut to fit [room] with an ellipsis, measured by [width]; empty where not even the ellipsis fits. */
internal fun ellipsize(text: String, room: Int, width: (String) -> Int): String {
    if (width(text) <= room) return text
    var end = text.length
    while (end > 0 && width(text.take(end).trimEnd() + "…") > room) end--
    return if (end == 0) "" else text.take(end).trimEnd() + "…"
}

/**
 * Shows [content] with [tab] floating on its edge. The tab goes where it covers none of the content's controls and moves when
 * they move; where no place is free it folds into a thin handle, and the page keeps all of its room either way.
 */
internal class EdgeTabHost(private val content: JComponent, private val tab: EdgeTab) : JLayeredPane() {
    private var spot: EdgeTabSpot? = null
    /** Follows the content's controls as the page scrolls and relayouts, while the host is showing. */
    private val timer = Timer(REFRESH_MS) { place() }

    init {
        add(content, DEFAULT_LAYER as Any)
        add(tab, PALETTE_LAYER as Any)
        tab.onHover = ::place
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                if (isShowing) { timer.start(); SwingUtilities.invokeLater(::place) } else timer.stop()
            }
        }
    }

    override fun isOptimizedDrawingEnabled() = false
    override fun getPreferredSize(): Dimension = if (isPreferredSizeSet) super.getPreferredSize() else content.preferredSize
    override fun getMinimumSize(): Dimension = if (isMinimumSizeSet) super.getMinimumSize() else content.minimumSize

    override fun doLayout() {
        content.setBounds(0, 0, width, height)
        // The content's controls take their places first, so the tab is placed against the new layout.
        content.validate()
        place()
    }

    /** Moves the tab off the content's controls; it stays put under the pointer, where a handle unfolds instead. */
    internal fun place() {
        if (width <= 0 || height <= 0) return
        val size = tab.edgeSize()
        val next = spot?.takeIf { tab.hovered } ?: EdgeTabPlacement.place(width, height, size, obstacles(size.thickness), spot).also { spot = it }
        tab.form = next.form
        tab.side = next.side
        val bounds = if (next.form == EdgeTabForm.HANDLE && tab.hovered) Rectangle(0, next.bounds.y, size.thickness, next.bounds.height)
        else next.bounds
        if (tab.bounds != bounds) {
            tab.bounds = bounds
            repaint()
        }
    }

    /** The places of the content's controls along both edges, in this host's coordinates; internal for tests. */
    internal fun obstacles(thickness: Int): List<Rectangle> {
        val reach = thickness + JBUI.scale(4)
        val strips = listOf(Rectangle(0, 0, reach, height), Rectangle(width - reach, 0, reach, height))
        val found = mutableListOf<Rectangle>()
        fun visit(component: Component, clip: Rectangle) {
            if (!component.isVisible || component === tab) return
            // Blitting a viewport under the floating tab would copy the tab along with the scrolled content.
            if (component is JViewport && component.scrollMode != JViewport.SIMPLE_SCROLL_MODE) {
                component.scrollMode = JViewport.SIMPLE_SCROLL_MODE
            }
            val bounds = SwingUtilities.convertRectangle(component.parent, component.bounds, this).intersection(clip)
            if (bounds.isEmpty || strips.none { it.intersects(bounds) }) return
            if (component is JTabbedPane) (0 until component.tabCount).forEach { index ->
                component.getBoundsAt(index)?.let { found += SwingUtilities.convertRectangle(component, it, this).intersection(bounds) }
            }
            if (isControl(component)) found += bounds
            else if (component is Container) component.components.forEach { visit(it, bounds) }
        }
        visit(content, Rectangle(0, 0, width, height))
        return found
    }

    private fun isControl(component: Component): Boolean = when (component) {
        is EdgeTab -> false
        is AbstractButton, is JComboBox<*>, is JSlider, is JSpinner, is JScrollBar, is JTree, is JList<*>, is JTable, is JTableHeader,
        is HyperlinkLabel, is ActionButtonComponent -> true
        is JTextComponent -> true
        else -> component.javaClass.name == TAB_LABEL ||
            (component.isCursorSet && component.cursor.type == Cursor.HAND_CURSOR)
    }

    private companion object {
        const val REFRESH_MS = 300
        /** The header of a tab in the IDE's own tabs, which are not a [JTabbedPane]. */
        const val TAB_LABEL = "com.intellij.ui.tabs.impl.TabLabel"
    }
}
