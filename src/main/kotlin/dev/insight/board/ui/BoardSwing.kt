package dev.insight.board.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.insight.board.Severity
import dev.insight.board.Tone
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.math.max
import kotlin.math.roundToInt

/** Fonts and metrics of the board at [zoom]: label-font based, scaled by the IDE scale and the zoom. */
internal class BoardStyle(val zoom: Double) {
    fun px(value: Int): Int = max(1, (JBUI.scale(value) * zoom).roundToInt())

    private val base: Font = JBUI.Fonts.label()
    private val size: Float = base.size2D * zoom.toFloat()
    val body: Font = base.deriveFont(Font.PLAIN, size)
    val italic: Font = base.deriveFont(Font.ITALIC, size)
    val bold: Font = base.deriveFont(Font.BOLD, size)
    val small: Font = base.deriveFont(Font.PLAIN, size * 0.85f)
    val smallBold: Font = base.deriveFont(Font.BOLD, size * 0.85f)
    val smallItalic: Font = base.deriveFont(Font.ITALIC, size * 0.85f)
    val mono: Font = Font(Font.MONOSPACED, Font.PLAIN, 12).deriveFont(size * 0.95f)

    fun heading(level: Int): Font = base.deriveFont(Font.BOLD, size * when (level) {
        1 -> 1.7f
        2 -> 1.4f
        else -> 1.15f
    })
}

/** Board colours for light and dark themes. Evidence and interpretation cards differ by a faint tint. */
internal object BoardColors {
    val page = JBColor.namedColor("Panel.background", JBColor(0xF7F8FA, 0x1E1F22))
    val text = JBColor.namedColor("Label.foreground", JBColor(0x1E1F22, 0xDFE1E5))
    val muted = JBColor(0x6C707E, 0x868A91)
    val link: Color = JBUI.CurrentTheme.Link.Foreground.ENABLED
    val evidence = JBColor(0xFFFFFF, 0x2B2D30)
    val interpretation = JBColor(0xF8F6FD, 0x2F2D37)
    val interpretationNote = JBColor(0xF1EDFA, 0x37334A)
    val border = JBColor(0xD9DCE3, 0x43454A)
    val selected = JBColor.namedColor("Component.focusColor", JBColor(0x3574F0, 0x3574F0))
    val flash = JBColor(0xE9A800, 0xD6AE58)
    val edge = JBColor(0x8C93A1, 0x7A808A)
    val code = JBColor(0xFAFAFB, 0x232427)
    val chip = JBColor(0xF0F2F5, 0x393B40)
    val tableHeader = JBColor(0xF0F2F5, 0x323438)
    val added = JBColor(0xE3F5E7, 0x294436)
    val removed = JBColor(0xFDE8E8, 0x4A2B2E)
    val hunk = JBColor(0xEAEEF5, 0x2F343D)
    val group = JBColor(0xF3F4F7, 0x252629)

    fun tone(tone: Tone): Color = when (tone) {
        Tone.NOTE -> JBColor(0x3574F0, 0x548AF7)
        Tone.INSIGHT -> JBColor(0x208A3C, 0x5FB865)
        Tone.WARNING -> JBColor(0xC27D04, 0xE0A133)
        Tone.QUESTION -> JBColor(0x8A55D6, 0xA67CEB)
    }

    fun toneTint(tone: Tone): Color = when (tone) {
        Tone.NOTE -> JBColor(0xEDF3FF, 0x25324D)
        Tone.INSIGHT -> JBColor(0xEBF6EE, 0x253627)
        Tone.WARNING -> JBColor(0xFFF5E5, 0x3D3223)
        Tone.QUESTION -> JBColor(0xF4EEFD, 0x342B42)
    }

    fun severity(severity: Severity): Color = when (severity) {
        Severity.INFO -> tone(Tone.NOTE)
        Severity.WARNING -> tone(Tone.WARNING)
        Severity.ERROR -> JBColor(0xDB3B4B, 0xE55765)
    }

    fun severityTint(severity: Severity): Color = when (severity) {
        Severity.INFO -> toneTint(Tone.NOTE)
        Severity.WARNING -> toneTint(Tone.WARNING)
        Severity.ERROR -> JBColor(0xFDECEC, 0x47292C)
    }
}

/** A component whose height follows from its width, like wrapped text; [StackLayout] asks it directly. */
internal interface HeightForWidth {
    fun heightFor(width: Int): Int
}

/** [component]'s height at [width]: asked directly, or by sizing it to the width and reading its preferred height. */
internal fun heightOf(component: Component, width: Int): Int = when (component) {
    is HeightForWidth -> component.heightFor(width)
    else -> {
        component.setSize(width, max(component.height, 1))
        component.preferredSize.height
    }
}

/** Children stacked top to bottom at the container's inner width, each as tall as it needs at that width. */
internal class StackLayout(private val gap: Int) : LayoutManager {
    override fun addLayoutComponent(name: String?, comp: Component?) {}

    override fun removeLayoutComponent(comp: Component?) {}

    override fun preferredLayoutSize(parent: Container): Dimension {
        val insets = parent.insets
        val inner = max(1, parent.width - insets.left - insets.right)
        val shown = parent.components.filter { it.isVisible }
        val height = shown.sumOf { heightOf(it, inner) } + gap * max(0, shown.size - 1)
        return Dimension(parent.width, height + insets.top + insets.bottom)
    }

    override fun minimumLayoutSize(parent: Container): Dimension = preferredLayoutSize(parent)

    override fun layoutContainer(parent: Container) {
        val insets = parent.insets
        val inner = max(1, parent.width - insets.left - insets.right)
        var y = insets.top
        for (child in parent.components.filter { it.isVisible }) {
            val height = heightOf(child, inner)
            child.setBounds(insets.left, y, inner, height)
            y += height + gap
        }
    }
}

internal fun Graphics.board(): Graphics2D = (create() as Graphics2D).apply {
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
}

/** [text] cut to [width] with a trailing "…" when it does not fit. */
internal fun fitText(text: String, width: Int, metrics: FontMetrics): String {
    if (metrics.stringWidth(text) <= width) return text
    var end = text.length
    while (end > 0 && metrics.stringWidth(text.substring(0, end) + "…") > width) end--
    return text.substring(0, end) + "…"
}

/** Wrapped plain text in one font and colour; its height follows its width. */
internal class WrapText(text: String, font: Font, color: Color) : JComponent(), HeightForWidth {
    var text: String = text
        set(value) {
            field = value
            revalidate()
            repaint()
        }

    init {
        this.font = font
        foreground = color
        isOpaque = false
    }

    private fun lines(width: Int): List<String> {
        val metrics = getFontMetrics(font)
        return wrapLines(text, width - insets.left - insets.right, metrics::stringWidth)
    }

    override fun heightFor(width: Int): Int = lines(width).size * getFontMetrics(font).height + insets.top + insets.bottom

    override fun getPreferredSize(): Dimension {
        if (isPreferredSizeSet) return super.getPreferredSize()
        val width = if (width > 0) width else JBUI.scale(300)
        return Dimension(width, heightFor(width))
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.board()
        try {
            g2.font = font
            g2.color = foreground
            val metrics = g2.fontMetrics
            var y = insets.top + metrics.ascent
            for (line in lines(width)) {
                g2.drawString(line, insets.left, y)
                y += metrics.height
            }
        } finally {
            g2.dispose()
        }
    }
}

/** A text link: link colour, hand cursor, underlined on hover; a left click runs [action]. */
internal class LinkLabel(text: String, font: Font, private val action: () -> Unit) : JLabel(text) {
    private var hover = false

    init {
        this.font = font
        foreground = BoardColors.link
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) activate()
            }

            override fun mouseEntered(e: MouseEvent) {
                hover = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hover = false
                repaint()
            }
        })
    }

    /** Runs the link as a click would. */
    fun activate() {
        if (isEnabled) action()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (!hover || text.isNullOrEmpty()) return
        val baseline = getBaseline(width, height)
        if (baseline < 0) return
        val metrics = getFontMetrics(font)
        g.color = foreground
        val x = insets.left + (icon?.let { it.iconWidth + iconTextGap } ?: 0)
        g.drawLine(x, baseline + 1, x + minOf(metrics.stringWidth(text), width - x), baseline + 1)
    }
}
