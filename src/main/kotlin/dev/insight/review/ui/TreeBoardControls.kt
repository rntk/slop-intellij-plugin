package dev.insight.review.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToggleButton

internal const val TREE_CONTROL_HEIGHT_DP = 24

private fun withAlpha(color: Color, alpha: Int): Color =
    Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))

private fun configurePillGraphics(g: Graphics): Graphics2D = (g.create() as Graphics2D).apply {
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
}

/**
 * Compact rounded pill toggle for Tree board layers (`Preview`, `Summary card`, `Inline`, `Topics`).
 * Keeps constant width across states and supports a [partial] indicator dot when a subset of items is active.
 */
internal class PillToggleButton(
    text: String,
    icon: Icon? = null,
    name: String,
    tooltip: String = "",
    selected: Boolean = false,
    private val accentProvider: () -> Color = { JBUI.CurrentTheme.Focus.focusColor() },
) : JToggleButton(text, icon, selected) {
    private var hovered = false

    /** Shows an accent indicator dot when `!isSelected && partial` (e.g. only some inline summaries are expanded). */
    var partial: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }

    init {
        this.name = name
        toolTipText = tooltip.takeIf { it.isNotEmpty() }
        font = JBFont.small()
        isFocusable = false
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        iconTextGap = JBUI.scale(4)
        border = JBUI.Borders.empty(0, 9)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovered = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false
                repaint()
            }
        })
    }

    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        return Dimension(base.width, JBUI.scale(TREE_CONTROL_HEIGHT_DP))
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val accent = accentProvider()
        val active = isSelected
        val g2 = configurePillGraphics(g)
        try {
            val arc = JBUI.scale(10).toDouble()
            val rect = RoundRectangle2D.Double(0.5, 0.5, width - 1.0, height - 1.0, arc, arc)
            when {
                active -> {
                    g2.color = withAlpha(accent, if (JBColor.isBright()) 34 else 52)
                    g2.fill(rect)
                    g2.color = withAlpha(accent, 130)
                    g2.stroke = BasicStroke(1f)
                    g2.draw(rect)
                }
                hovered -> {
                    g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
                    g2.fill(rect)
                    g2.color = withAlpha(JBColor.border(), 160)
                    g2.stroke = BasicStroke(1f)
                    g2.draw(rect)
                }
                else -> {
                    g2.color = if (partial) withAlpha(accent, 110) else withAlpha(JBColor.border(), 95)
                    g2.stroke = BasicStroke(1f)
                    g2.draw(rect)
                }
            }
            if (!active && partial) {
                val dotSize = JBUI.scale(5).toDouble()
                g2.color = accent
                g2.fill(Ellipse2D.Double(width - dotSize - JBUI.scale(4), JBUI.scale(3).toDouble(), dotSize, dotSize))
            }
        } finally {
            g2.dispose()
        }
        foreground = if (active || hovered || partial) UIUtil.getLabelForeground() else UIUtil.getContextHelpForeground()
        super.paintComponent(g)
    }
}

/** Compact square icon button matching the height and hover style of [PillToggleButton]. */
internal class ToolbarIconButton(
    icon: Icon,
    name: String,
    tooltip: String,
    private val onClick: () -> Unit,
) : JButton(icon) {
    private var hovered = false

    init {
        this.name = name
        toolTipText = tooltip
        isFocusable = false
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        margin = JBUI.emptyInsets()
        border = JBUI.Borders.empty(0, 4)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { onClick() }
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovered = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false
                repaint()
            }
        })
    }

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(TREE_CONTROL_HEIGHT_DP), JBUI.scale(TREE_CONTROL_HEIGHT_DP))
    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        if (model.isPressed || hovered) {
            val g2 = configurePillGraphics(g)
            try {
                val arc = JBUI.scale(8).toDouble()
                val rect = RoundRectangle2D.Double(1.0, 1.0, width - 2.0, height - 2.0, arc, arc)
                g2.color = if (model.isPressed) JBUI.CurrentTheme.ActionButton.pressedBackground()
                else JBUI.CurrentTheme.ActionButton.hoverBackground()
                g2.fill(rect)
            } finally {
                g2.dispose()
            }
        }
        super.paintComponent(g)
    }
}

/**
 * Segmented pill switch (`Off | Topics | Groups`) rendered inside a single rounded track.
 * Keeps every option visible at fixed width so toggling or switching modes never shifts the toolbar.
 */
internal class SegmentedPillControl<T>(
    name: String,
    private val items: List<Segment<T>>,
    initial: T,
    private val accentProvider: () -> Color = { JBUI.CurrentTheme.Focus.focusColor() },
    private val onSelect: (T) -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(1), 0)) {
    data class Segment<T>(
        val value: T,
        val label: String,
        val componentName: String,
        val tooltip: String,
    )

    var selected: T = initial
        set(value) {
            if (field == value) return
            field = value
            syncButtons()
            repaint()
        }

    private val buttons = LinkedHashMap<T, SegmentButton>()

    init {
        this.name = name
        isOpaque = false
        border = JBUI.Borders.empty(2)
        for (item in items) {
            val btn = SegmentButton(item).apply {
                addActionListener {
                    if (selected != item.value) {
                        selected = item.value
                        onSelect(item.value)
                    } else {
                        isSelected = true
                    }
                }
            }
            buttons[item.value] = btn
            add(btn)
        }
        syncButtons()
    }

    fun buttonFor(value: T): JToggleButton? = buttons[value]

    private fun syncButtons() {
        for ((value, button) in buttons) {
            button.isSelected = (value == selected)
        }
    }

    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        return Dimension(base.width, JBUI.scale(TREE_CONTROL_HEIGHT_DP))
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = configurePillGraphics(g)
        try {
            val arc = JBUI.scale(10).toDouble()
            val rect = RoundRectangle2D.Double(0.5, 0.5, width - 1.0, height - 1.0, arc, arc)
            g2.color = trackBg
            g2.fill(rect)
            g2.color = withAlpha(JBColor.border(), 140)
            g2.stroke = BasicStroke(1f)
            g2.draw(rect)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    private inner class SegmentButton(segment: Segment<T>) : JToggleButton(segment.label) {
        private var hovered = false

        init {
            name = segment.componentName
            toolTipText = segment.tooltip
            font = JBFont.small()
            isFocusable = false
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            border = JBUI.Borders.empty(0, 8)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    repaint()
                }
            })
        }

        override fun getPreferredSize(): Dimension {
            val base = super.getPreferredSize()
            return Dimension(base.width, JBUI.scale(TREE_CONTROL_HEIGHT_DP - 4))
        }

        override fun paintComponent(g: Graphics) {
            val active = isSelected
            val g2 = configurePillGraphics(g)
            try {
                val arc = JBUI.scale(8).toDouble()
                val rect = RoundRectangle2D.Double(0.5, 0.5, width - 1.0, height - 1.0, arc, arc)
                when {
                    active -> {
                        g2.color = activeThumbBg
                        g2.fill(rect)
                        g2.color = withAlpha(accentProvider(), 110)
                        g2.stroke = BasicStroke(1f)
                        g2.draw(rect)
                    }
                    hovered -> {
                        g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
                        g2.fill(rect)
                    }
                }
            } finally {
                g2.dispose()
            }
            foreground = if (active || hovered) UIUtil.getLabelForeground() else UIUtil.getContextHelpForeground()
            super.paintComponent(g)
        }
    }

    private companion object {
        val trackBg = JBColor(Color(0xECEEF2), Color(0x303236))
        val activeThumbBg = JBColor(Color.WHITE, Color(0x45484F))
    }
}

/** Subtle vertical hairline separating control clusters in the Tree toolbar or HUDs. */
internal class ToolbarDivider : JComponent() {
    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(9), JBUI.scale(TREE_CONTROL_HEIGHT_DP))
    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            val x = width / 2
            val inset = JBUI.scale(5)
            g2.color = withAlpha(JBColor.border(), 150)
            g2.drawLine(x, inset, x, (height - inset).coerceAtLeast(inset))
        } finally {
            g2.dispose()
        }
    }
}

/**
 * Translucent rounded HUD pill floating on top of [TreeBoardPanel.canvas].
 * Intercepts pointer events so clicking or scrolling inside the HUD never triggers canvas pan, zoom, or deselection.
 */
internal open class FloatingCanvasHud(
    layout: LayoutManager = FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0),
    private val bgProvider: () -> Color = { defaultBg },
    private val borderProvider: () -> Color = { withAlpha(JBColor.border(), 180) },
) : JPanel(layout) {
    init {
        isOpaque = false
        border = JBUI.Borders.empty(3, 6)
        val blocker = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = e.consume()
            override fun mouseReleased(e: MouseEvent) = e.consume()
            override fun mouseClicked(e: MouseEvent) = e.consume()
            override fun mouseMoved(e: MouseEvent) = e.consume()
            override fun mouseDragged(e: MouseEvent) = e.consume()
            override fun mouseWheelMoved(e: MouseWheelEvent) = e.consume()
        }
        addMouseListener(blocker)
        addMouseMotionListener(blocker)
        addMouseWheelListener(blocker)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = configurePillGraphics(g)
        try {
            val arc = JBUI.scale(12).toDouble()
            val rect = RoundRectangle2D.Double(0.5, 0.5, width - 1.0, height - 1.0, arc, arc)
            g2.color = bgProvider()
            g2.fill(rect)
            g2.color = borderProvider()
            g2.stroke = BasicStroke(1f)
            g2.draw(rect)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    companion object {
        val defaultBg = JBColor(Color(255, 255, 255, 232), Color(43, 45, 48, 232))
    }
}

/**
 * Floating accent banner shown near the top-center of the Tree canvas while Focus mode is active.
 */
internal class FocusModeChip(
    exitButton: JButton,
    private val accentProvider: () -> Color = { JBUI.CurrentTheme.Focus.focusColor() },
) : FloatingCanvasHud(
    layout = BorderLayout(JBUI.scale(6), 0),
    bgProvider = { defaultBg },
    borderProvider = { withAlpha(accentProvider(), 150) },
) {
    private val textLabel = JLabel().apply {
        font = JBFont.small().asBold()
        foreground = UIUtil.getLabelForeground()
    }

    init {
        isVisible = false
        border = JBUI.Borders.empty(3, 8, 3, 6)
        add(JLabel(AllIcons.General.Filter), BorderLayout.WEST)
        add(textLabel, BorderLayout.CENTER)
        val trailing = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(ToolbarDivider())
            add(exitButton)
        }
        add(trailing, BorderLayout.EAST)
    }

    fun update(path: String?) {
        if (path == null) {
            isVisible = false
            textLabel.text = ""
            toolTipText = null
            return
        }
        val shortName = path.substringAfterLast('/').ifEmpty { path }
        textLabel.text = "Focused: $shortName"
        toolTipText = "Focused on $path · Press Esc or Show all to exit"
        isVisible = true
    }
}
