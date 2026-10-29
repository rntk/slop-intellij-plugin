package dev.insight.diff

import com.intellij.ui.ColorUtil
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.graph.SymbolKind
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Polygon
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One point per function: [metric] before the change on x, after it on y, so points above the dashed diagonal grew and
 * points below it shrank. Added functions lie on x = 0 (hollow circles) and removed ones on y = 0 (hollow diamonds).
 * A point's size follows the function's lines of code. Clicking a point opens the diff of its file.
 */
internal class SemanticComplexityChart(
    internal val changes: SemanticChanges,
    internal val metric: Metric,
    chartWidth: Int,
    chartHeight: Int,
    private val open: (DeclarationMetrics) -> Unit,
) : JComponent() {
    /** [before] is 0 for an added function and [after] 0 for a removed one; [center] is the point's pixel, after jitter. */
    internal data class Placed(val declaration: DeclarationMetrics, val before: Int, val after: Int, val center: Point, val radius: Int) {
        val added get() = declaration.before == null
        val removed get() = declaration.after == null
    }

    private val functions = changes.declarations.filter { it.kind == SymbolKind.FUNCTION }
    private val axisStep: Int
    private val axisMax: Int
    private val plot = Rectangle(JBUI.scale(56), JBUI.scale(34), chartWidth - JBUI.scale(56) - JBUI.scale(24), chartHeight - JBUI.scale(34) - JBUI.scale(46))
    private val inset = JBUI.scale(MAX_RADIUS + 2)
    private val placed: List<Placed>

    init {
        val highest = functions.maxOfOrNull { maxOf(it.value(metric, false) ?: 0, it.value(metric, true) ?: 0) } ?: 0
        axisStep = sequence { var magnitude = 1; while (true) { yield(magnitude); yield(2 * magnitude); yield(5 * magnitude); magnitude *= 10 } }
            .first { highest.toDouble() / it <= 8 }
        axisMax = maxOf(axisStep, ceil(highest.toDouble() / axisStep).toInt() * axisStep)
        placed = place()
        preferredSize = Dimension(chartWidth, chartHeight)
        toolTipText = ""
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.button == MouseEvent.BUTTON1) at(e.point)?.let { open(it.declaration) } }
        })
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                cursor = if (at(e.point) != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            }
        })
    }

    internal fun placedPoints(): List<Placed> = placed
    internal fun hasFunctions() = functions.isNotEmpty()

    /** The point nearest to [point] among those it falls inside. */
    internal fun at(point: Point): Placed? = placed.filter { it.center.distance(point) <= it.radius + JBUI.scale(2) }
        .minByOrNull { it.center.distance(point) }

    override fun getToolTipText(event: MouseEvent): String? = at(event.point)?.let { p ->
        val d = p.declaration
        "<html><b>${escape(d.qualifiedName)}</b><br>${escape(d.path)}:${d.line}<br>" +
            "${escape(metric.label)}: ${d.value(metric, false) ?: "–"} → ${d.value(metric, true) ?: "–"}" +
            (if (p.added) " (added)" else if (p.removed) " (removed)" else "") + "</html>"
    }

    private fun x(value: Int) = plot.x + inset + ((plot.width - 2 * inset).toDouble() * value / axisMax).roundToInt()
    private fun y(value: Int) = plot.y + plot.height - inset - ((plot.height - 2 * inset).toDouble() * value / axisMax).roundToInt()

    /** Points sharing a coordinate spread on a spiral in a fixed order, so they stay apart and stay in place across refreshes. */
    private fun place(): List<Placed> {
        val largest = functions.maxOfOrNull { size(it) } ?: 1
        val step = JBUI.scale(5).toDouble()
        return functions.sortedWith(compareBy({ it.path }, { it.line }, { it.qualifiedName }))
            .groupBy { (it.value(metric, false) ?: 0) to (it.value(metric, true) ?: 0) }
            .flatMap { (at, group) ->
                group.mapIndexed { index, d ->
                    val angle = index * GOLDEN_ANGLE
                    val radius = JBUI.scale(MIN_RADIUS) + (JBUI.scale(MAX_RADIUS - MIN_RADIUS) * sqrt(size(d).toDouble() / maxOf(1, largest))).roundToInt()
                    val spread = step * sqrt(index.toDouble())
                    Placed(d, at.first, at.second, Point(x(at.first) + (spread * cos(angle)).roundToInt(), y(at.second) + (spread * sin(angle)).roundToInt()), radius)
                }
            }
    }

    private fun size(d: DeclarationMetrics) = maxOf(d.value(Metric.CODE_LINES, false) ?: 0, d.value(Metric.CODE_LINES, true) ?: 0)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = UIUtil.getListBackground()
            g2.fillRect(0, 0, width, height)
            val small = JBFont.small()
            g2.font = small
            val metrics = g2.fontMetrics
            g2.color = JBColor.border()
            g2.stroke = BasicStroke(1f)
            g2.drawRect(plot.x, plot.y, plot.width, plot.height)
            g2.color = UIUtil.getContextHelpForeground()
            for (tick in 0..axisMax step axisStep) {
                val label = tick.toString()
                g2.drawString(label, x(tick) - metrics.stringWidth(label) / 2, plot.maxY.toInt() + metrics.height)
                g2.drawString(label, plot.x - JBUI.scale(6) - metrics.stringWidth(label), y(tick) + metrics.ascent / 2)
                g2.color = ColorUtil.withAlpha(Gray._128, 40 / 255.0)
                g2.drawLine(x(tick), plot.y, x(tick), plot.maxY.toInt())
                g2.drawLine(plot.x, y(tick), plot.maxX.toInt(), y(tick))
                g2.color = UIUtil.getContextHelpForeground()
            }
            g2.drawString("before", plot.x + (plot.width - metrics.stringWidth("before")) / 2, height - JBUI.scale(6))
            val rotated = g2.create() as Graphics2D
            rotated.rotate(-Math.PI / 2, JBUI.scale(12).toDouble(), plot.y + plot.height / 2.0)
            rotated.drawString("after", JBUI.scale(12) - metrics.stringWidth("after") / 2, plot.y + plot.height / 2 + metrics.ascent / 2)
            rotated.dispose()
            g2.color = JBColor.border()
            g2.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 4f), 0f)
            g2.drawLine(x(0), y(0), x(axisMax), y(axisMax))
            g2.stroke = BasicStroke(1.5f)
            placed.sortedByDescending { it.radius }.forEach { paint(g2, it) }
            legend(g2)
        } finally { g2.dispose() }
    }

    private fun paint(g2: Graphics2D, p: Placed) {
        val color = when { p.after > p.before -> ADDED; p.after < p.before -> REMOVED; else -> SHARED }
        val r = p.radius
        val shape: java.awt.Shape = if (p.removed) Polygon(
            intArrayOf(p.center.x, p.center.x + r, p.center.x, p.center.x - r),
            intArrayOf(p.center.y - r, p.center.y, p.center.y + r, p.center.y), 4,
        ) else java.awt.geom.Ellipse2D.Double((p.center.x - r).toDouble(), (p.center.y - r).toDouble(), 2.0 * r, 2.0 * r)
        if (!p.added && !p.removed) {
            g2.color = ColorUtil.withAlpha(color, 150 / 255.0)
            g2.fill(shape)
        }
        g2.color = color
        g2.draw(shape)
    }

    private fun legend(g2: Graphics2D) {
        g2.font = JBFont.small()
        val metrics = g2.fontMetrics
        var x = plot.x
        val baseline = JBUI.scale(8) + metrics.ascent
        g2.color = UIUtil.getLabelForeground()
        val title = "${metric.label} per function"
        g2.drawString(title, x, baseline)
        x += metrics.stringWidth(title) + JBUI.scale(24)
        val r = JBUI.scale(5)
        val mid = baseline - metrics.ascent / 2
        fun entry(text: String, marker: (Int) -> Unit) {
            marker(x + r)
            g2.color = UIUtil.getContextHelpForeground()
            g2.drawString(text, x + 2 * r + JBUI.scale(4), baseline)
            x += 2 * r + JBUI.scale(4) + metrics.stringWidth(text) + JBUI.scale(16)
        }
        g2.stroke = BasicStroke(1.5f)
        entry("changed") { cx -> g2.color = SHARED; g2.fillOval(cx - r, mid - r, 2 * r, 2 * r) }
        entry("added (before = 0)") { cx -> g2.color = ADDED; g2.drawOval(cx - r, mid - r, 2 * r, 2 * r) }
        entry("removed (after = 0)") { cx ->
            g2.color = REMOVED
            g2.drawPolygon(intArrayOf(cx, cx + r, cx, cx - r), intArrayOf(mid - r, mid, mid + r, mid), 4)
        }
        entry("dashed: unchanged · green above, red below · size = code lines") { cx ->
            g2.color = JBColor.border()
            g2.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(3f, 3f), 0f)
            g2.drawLine(cx - r, mid, cx + r, mid)
        }
    }

    private companion object {
        const val MIN_RADIUS = 3
        const val MAX_RADIUS = 14
        const val GOLDEN_ANGLE = 2.399963229728653
        val REMOVED = JBColor(Color(0xC0392B), Color(0xE8836E))
        val ADDED = JBColor(Color(0x1E8449), Color(0x6FCF97))
        val SHARED = JBColor(Color(0x7F8C8D), Color(0x9AA0A6))
        fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
