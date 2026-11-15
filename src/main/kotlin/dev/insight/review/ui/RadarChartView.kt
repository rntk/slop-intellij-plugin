package dev.insight.review.ui

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.UIUtil
import dev.insight.review.RadarChart
import dev.insight.review.RadarSeries
import dev.insight.review.radarRingValue
import dev.insight.review.radarScale
import dev.insight.review.radarValueText
import dev.insight.ui.zoomScaled
import dev.insight.ui.zoomed
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import java.util.Locale
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A radar of [chart]: one spoke per entry at the picked level, clockwise from the top in reading order, and one polygon per
 * series. Hovering a spoke shows its path and counts; clicking a file's spoke calls [openFile].
 */
internal class RadarChartPanel(
    val chart: RadarChart,
    private val availableWidth: Int,
    private val zoom: Double,
    private val logarithmic: Boolean,
    private val openFile: (String) -> Unit,
) : JComponent() {
    private val axes = chart.axes
    private val max = axes.maxOfOrNull { axis -> chart.series.maxOf { it.value(axis.lines) } } ?: 0
    private var hovered: Int? = null

    init {
        name = "radar-chart"
        isOpaque = false
        val side = availableWidth.coerceIn(scaled(MIN_SIZE), scaled(MAX_SIZE))
        preferredSize = Dimension(availableWidth.coerceAtLeast(side), side)
        toolTipText = ""
        val mouse = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val axis = axisAt(e.point)
                if (axis != hovered) { hovered = axis; repaint() }
                cursor = if (axis?.let { axes[it].path } != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            }
            override fun mouseExited(e: MouseEvent) { if (hovered != null) { hovered = null; repaint() } }
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                axisAt(e.point)?.let { axes[it].path }?.let(openFile)
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val axis = axisAt(event.point)?.let(axes::get) ?: return null
        val counts = axisValueText(axis)
        val files = if (axis.files.size == 1) "1 file" else String.format(Locale.US, "%,d files", axis.files.size)
        val symbols = if (chart.series.any { it.isSymbol }) {
            "<br>" + RadarSeries.entries.filter { it.isSymbol }.joinToString("<br>") { "${it.label}: ${it.value(axis.lines)}" } + "<br>" + radarSymbolCoverage(axis.files)
        } else ""
        val open = if (axis.path != null) "<br>Click to open" else ""
        return "<html><b>${escape(axis.label)}</b><br>${escape(axis.tooltip).replace("\n", "<br>")}<br>$counts · $files$symbols$open</html>"
    }

    private fun axisValueText(axis: dev.insight.review.RadarAxis): String =
        if (chart.series.any { it.isSymbol } && axis.files.none { it.symbolCounts != null }) "Symbols unavailable"
        else radarValueText(axis.lines, chart.series)

    private fun scaled(n: Int) = zoomScaled(n, zoom)
    private val center get() = Point(width / 2, height / 2)
    private val radius get() = (minOf(width, height) / 2 - scaled(LABEL_ROOM)).coerceAtLeast(scaled(40))

    /** Spoke [index]'s angle: the first at twelve o'clock, then clockwise. */
    private fun angle(index: Int) = -PI / 2 + 2 * PI * index / axes.size.coerceAtLeast(1)

    /** The spoke nearest the pointer, within the chart and its labels. */
    private fun axisAt(point: Point): Int? {
        if (axes.isEmpty()) return null
        val dx = (point.x - center.x).toDouble()
        val dy = (point.y - center.y).toDouble()
        val distance = hypot(dx, dy)
        if (distance < scaled(8) || distance > radius + scaled(LABEL_ROOM)) return null
        val pointer = atan2(dy, dx)
        return axes.indices.minByOrNull { index ->
            val diff = abs(pointer - angle(index)) % (2 * PI)
            minOf(diff, 2 * PI - diff)
        }
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            if (axes.isEmpty()) return
            paintGrid(g)
            for (series in chart.series) paintSeries(g, series)
            paintLabels(g)
            paintLegend(g)
        } finally { g.dispose() }
    }

    private fun point(index: Int, share: Double): Pair<Double, Double> =
        center.x + cos(angle(index)) * radius * share to center.y + sin(angle(index)) * radius * share

    private fun paintGrid(g: Graphics2D) {
        val grid = JBColor.border()
        val help = UIUtil.getContextHelpForeground()
        g.font = JBFont.small().zoomed(zoom)
        g.stroke = BasicStroke(1f)
        for (ring in 1..RINGS) {
            val share = ring.toDouble() / RINGS
            g.color = grid
            g.draw(polygon(axes.indices.map { point(it, share) }))
            g.color = help
            // Ring values sit between the first two spokes, clear of the first spoke's label.
            val between = -PI / 2 + PI / axes.size.coerceAtLeast(2)
            val text = String.format(Locale.US, "%,d", radarRingValue(ring, RINGS, max, logarithmic))
            g.drawString(text, (center.x + cos(between) * radius * share).toFloat() + scaled(3),
                (center.y + sin(between) * radius * share).toFloat() - scaled(2))
        }
        for (index in axes.indices) {
            val (x, y) = point(index, 1.0)
            g.color = if (index == hovered) JBColor.namedColor("Focus.borderColor", JBColor.BLUE) else grid
            g.drawLine(center.x, center.y, x.toInt(), y.toInt())
        }
    }

    private fun paintSeries(g: Graphics2D, series: RadarSeries) {
        val color = color(series)
        val points = axes.indices.map { point(it, radarScale(series.value(axes[it].lines), max, logarithmic)) }
        val shape = polygon(points)
        g.color = ColorUtil.withAlpha(color, 0.14)
        g.fill(shape)
        g.color = color
        g.stroke = BasicStroke(zoomScaled(2, zoom).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(shape)
        val dot = scaled(3)
        for ((x, y) in points) g.fillOval(x.toInt() - dot, y.toInt() - dot, 2 * dot, 2 * dot)
    }

    private fun paintLabels(g: Graphics2D) {
        val bold = JBFont.small().asBold().zoomed(zoom)
        val plain = JBFont.small().zoomed(zoom)
        val help = UIUtil.getContextHelpForeground()
        val room = scaled(LABEL_ROOM) * 2 - scaled(8)
        for ((index, axis) in axes.withIndex()) {
            val (x, y) = point(index, 1.0 + scaled(10).toDouble() / radius)
            val cosine = cos(angle(index))
            val lines = listOf(clip(axis.label, room, g, bold) to bold, clip(axisValueText(axis), room, g, plain) to plain)
            val heights = lines.map { (_, font) -> g.getFontMetrics(font).height }
            var top = y - heights.sum() * (1 - sin(angle(index))) / 2
            for ((line, font) in lines) {
                val metrics = g.getFontMetrics(font)
                val width = metrics.stringWidth(line)
                val left = when {
                    cosine > 0.2 -> x
                    cosine < -0.2 -> x - width
                    else -> x - width / 2.0
                }
                g.font = font
                g.color = if (font == plain) help else if (index == hovered) JBColor.namedColor("Focus.borderColor", JBColor.BLUE) else UIUtil.getLabelForeground()
                g.drawString(line, left.toFloat(), (top + metrics.ascent).toFloat())
                top += metrics.height
            }
        }
    }

    private fun paintLegend(g: Graphics2D) {
        val font = JBFont.small().zoomed(zoom)
        val metrics = g.getFontMetrics(font)
        g.font = font
        var y = scaled(4)
        val swatch = scaled(10)
        for (series in chart.series) {
            val x = width - scaled(8) - swatch - scaled(6) - metrics.stringWidth(series.label)
            g.color = color(series)
            g.fillRoundRect(x, y + (metrics.height - swatch) / 2, swatch, swatch, scaled(3), scaled(3))
            g.color = UIUtil.getLabelForeground()
            g.drawString(series.label, x + swatch + scaled(6), y + metrics.ascent)
            y += metrics.height + scaled(2)
        }
        if (logarithmic) {
            g.color = UIUtil.getContextHelpForeground()
            val text = "Log scale"
            g.drawString(text, width - scaled(8) - metrics.stringWidth(text), y + metrics.ascent)
        }
    }

    private fun clip(text: String, width: Int, g: Graphics2D, font: java.awt.Font): String {
        val metrics = g.getFontMetrics(font)
        if (metrics.stringWidth(text) <= width) return text
        var end = text.length
        while (end > 1 && metrics.stringWidth(text.take(end) + "…") > width) end--
        return text.take(end) + "…"
    }

    private fun polygon(points: List<Pair<Double, Double>>) = Path2D.Double().apply {
        points.forEachIndexed { index, (x, y) -> if (index == 0) moveTo(x, y) else lineTo(x, y) }
        closePath()
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        const val RINGS = 4
        const val MIN_SIZE = 360
        const val MAX_SIZE = 760
        /** Room around the chart for each spoke's label and counts. */
        const val LABEL_ROOM = 90
        /** Unscaled width the radar card asks for. */
        const val DEFAULT_WIDTH = 860

        private val ADDED = JBColor(0x2E9E6B, 0x4DB383)
        private val REMOVED = JBColor(0xD04A5A, 0xDB6B78)
        private val SOURCE = JBColor(0x4C7DD9, 0x5F8FE6)

        fun color(series: RadarSeries): Color = when (series) {
            RadarSeries.ADDED -> ADDED
            RadarSeries.REMOVED -> REMOVED
            RadarSeries.SOURCE, RadarSeries.FUNCTIONS -> SOURCE
            RadarSeries.CLASSES -> JBColor(0x9163C7, 0xB48ADD)
            RadarSeries.PROPERTIES -> JBColor(0x2A9393, 0x55BABA)
            RadarSeries.VARIABLES -> JBColor(0xCB8B25, 0xE5B354)
            RadarSeries.CONSTANTS -> JBColor(0xC85992, 0xE18BB9)
            RadarSeries.PARAMETERS -> JBColor(0x7D9636, 0xA9BE67)
            RadarSeries.OTHER -> JBColor(0x9A6750, 0xBF9380)
            RadarSeries.COMMENTS -> JBColor(0x76808C, 0xA0A9B5)
        }
    }
}

/** The status line above a radar: its spokes, files and totals. */
internal fun radarStatus(chart: RadarChart): String {
    val files = chart.axes.sumOf { it.files.size }
    val spokes = chart.axes.sumOf { it.merged }
    return String.format(Locale.US, "%,d %s · %,d %s · %s", spokes, if (spokes == 1) "entry" else "entries",
        files, if (files == 1) "file" else "files", radarValueText(chart.total, chart.series)) +
        if (RadarSeries.ADDED in chart.series) "" else " · " + radarSymbolCoverage(chart.axes.flatMap { it.files })
}

/** Missing PSI is reported as unavailable instead of implying a measured zero. */
private fun radarSymbolCoverage(files: List<dev.insight.review.ReviewSource>): String {
    val parsed = files.count { it.symbolCounts != null }
    return "Symbols available for $parsed of ${files.size} files"
}
