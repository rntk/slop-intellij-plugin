package dev.insight.codereview

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The most frequent words of the source in scope, the most frequent nearest the middle. Larger and stronger words occur more
 * often. Clicking a word calls [open] with the click, so the host can open its files.
 */
internal class CodeWordCloud(
    internal val words: CodeWords,
    private val chartWidth: Int,
    private val chartHeight: Int,
    private val open: (CodeWord, MouseEvent) -> Unit,
) : JComponent() {
    private data class Placed(val word: CodeWord, val bounds: Rectangle, val font: Font, val baseline: Int, val color: Color)
    private var placed: List<Placed>? = null

    init {
        preferredSize = Dimension(chartWidth, chartHeight)
        toolTipText = ""
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { at(e)?.let { open(it.word, e) } }
        })
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                cursor = if (at(e) != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            }
        })
    }

    internal fun placedWords(): Map<String, Rectangle> = placement().associate { it.word.word to it.bounds }

    private fun at(e: MouseEvent) = placement().firstOrNull { it.bounds.contains(e.point) }

    override fun getToolTipText(event: MouseEvent): String? = at(event)?.word?.let { word ->
        val files = word.files.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        "<html><b>${escape(word.word)}</b> · ${word.count} times in ${files(files.size)}<br>" +
            files.take(10).joinToString("<br>") { "${escape(it.key)} · ${it.value}" } +
            (if (files.size > 10) "<br>… ${files.size - 10} more" else "") + "</html>"
    }

    /**
     * Places the most frequent words first, each on an elliptic spiral out from the middle until it finds a free spot;
     * the spiral starts at a word-dependent angle so the words scatter, and stay in place across refreshes. Words that
     * find no room inside the chart are left out.
     */
    private fun placement(): List<Placed> = placed ?: run {
        val base = JBFont.label()
        val max = words.words.maxOfOrNull { it.count } ?: 1
        // Placed words by horizontal band, so a candidate is only checked against its neighbours.
        val band = JBUI.scale(BAND)
        val taken = hashMapOf<Int, MutableList<Rectangle>>()
        fun bands(r: Rectangle) = r.y / band..(r.y + r.height) / band
        val gap = JBUI.scale(2)
        val centerX = chartWidth / 2.0
        val centerY = chartHeight / 2.0
        val aspect = chartWidth.toDouble() / chartHeight
        val step = JBUI.scale(SPIRAL_STEP).toDouble()
        val maxRadius = sqrt(centerX * centerX + centerY * centerY)
        words.words.mapNotNull { word ->
            val weight = sqrt(word.count.toDouble() / max)
            val font = base.deriveFont(JBUI.scale(MIN_FONT) + (JBUI.scale(MAX_FONT - MIN_FONT) * weight).toFloat())
            val metrics = getFontMetrics(font)
            val width = metrics.stringWidth(word.word)
            val height = metrics.ascent + metrics.descent
            if (width > chartWidth || height > chartHeight) return@mapNotNull null
            val start = (word.word.hashCode() and Int.MAX_VALUE) % 360 * PI / 180
            var angle = 0.0
            while (true) {
                val radius = step * angle / (2 * PI)
                if (radius / aspect > maxRadius) break
                val x = (centerX + radius * cos(start + angle) - width / 2.0).roundToInt()
                val y = (centerY + radius / aspect * sin(start + angle) - height / 2.0).roundToInt()
                // Small steps near the middle, about one step of arc further out.
                angle += minOf(ANGLE_STEP, step / maxOf(radius, 1.0))
                if (x < 0 || y < 0 || x + width > chartWidth || y + height > chartHeight) continue
                val bounds = Rectangle(x, y, width, height)
                val padded = Rectangle(x - gap, y - gap, width + 2 * gap, height + 2 * gap)
                if (bands(padded).none { b -> taken[b]?.any { it.intersects(padded) } == true }) {
                    bands(bounds).forEach { taken.getOrPut(it) { mutableListOf() } += bounds }
                    return@mapNotNull Placed(word, bounds, font, y + metrics.ascent, color(weight))
                }
            }
            null
        }.also { placed = it }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = UIUtil.getListBackground()
            g2.fillRect(0, 0, width, height)
            placement().forEach { word ->
                g2.font = word.font
                g2.color = word.color
                g2.drawString(word.word.word, word.bounds.x, word.baseline)
            }
        } finally { g2.dispose() }
    }

    private companion object {
        const val MIN_FONT = 10
        const val MAX_FONT = 40
        const val BAND = 16
        /** How far the spiral moves out per turn. */
        const val SPIRAL_STEP = 6
        const val ANGLE_STEP = 0.35
        val RARE_LIGHT = Color(0x8A9199)
        val RARE_DARK = Color(0x8C9298)
        val FREQUENT_LIGHT = Color(0x1F5FA8)
        val FREQUENT_DARK = Color(0x7FB2EC)

        /** Grey for the rarest words, blue for the most frequent, in both the light and the dark theme. */
        fun color(weight: Double): Color = JBColor(mix(RARE_LIGHT, FREQUENT_LIGHT, weight), mix(RARE_DARK, FREQUENT_DARK, weight))

        fun mix(from: Color, to: Color, t: Double) = Color((from.red + (to.red - from.red) * t).roundToInt(),
            (from.green + (to.green - from.green) * t).roundToInt(), (from.blue + (to.blue - from.blue) * t).roundToInt())

        fun files(count: Int) = if (count == 1) "1 file" else "$count files"

        fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
