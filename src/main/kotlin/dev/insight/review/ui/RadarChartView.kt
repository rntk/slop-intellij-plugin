package dev.insight.review.ui

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.UIUtil
import dev.insight.review.RadarAxis
import dev.insight.review.RadarChart
import dev.insight.review.RadarSeries
import dev.insight.review.ScopedRadar
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
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.util.Locale
import javax.swing.JComponent
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A radar of [chart]: one spoke per entry at the picked level, clockwise from the top in reading order, and one polygon per
 * series. Hovering a spoke shows its path and counts; clicking a file's spoke calls [openFile], and clicking a legend
 * entry isolates or restores that series.
 */
internal class RadarChartPanel private constructor(
    val chart: RadarChart,
    private val availableWidth: Int,
    private val zoom: Double,
    private val logarithmic: Boolean,
    private val openFile: (String) -> Unit,
    selectedSeries: RadarSeries?,
    private val axisMenu: ((RadarAxis) -> JPopupMenu?)?,
    private val selectSeries: (RadarSeries?) -> Unit,
    private val scoped: ScopedRadar?,
) : JComponent() {
    constructor(
        chart: RadarChart,
        availableWidth: Int,
        zoom: Double,
        logarithmic: Boolean,
        openFile: (String) -> Unit,
        selectedSeries: RadarSeries? = null,
        axisMenu: ((RadarAxis) -> JPopupMenu?)? = null,
        selectSeries: (RadarSeries?) -> Unit = {},
    ) : this(chart, availableWidth, zoom, logarithmic, openFile, selectedSeries, axisMenu, selectSeries, null)

    constructor(
        scoped: ScopedRadar,
        availableWidth: Int,
        zoom: Double,
        logarithmic: Boolean,
        openFile: (String) -> Unit,
    ) : this(scoped.chart, availableWidth, zoom, logarithmic, openFile, null, null, {}, scoped)

    private val axes = chart.axes
    var selectedSeries: RadarSeries? = selectedSeries?.takeIf { it in chart.series }
        private set
    val shownSeries: List<RadarSeries>
        get() = selectedSeries?.takeIf { it in chart.series }?.let(::listOf) ?: chart.series
    internal fun scopedMetricValue(axis: RadarAxis): Long? = scoped?.measures?.get(axis)?.value
    private fun value(axis: RadarAxis, series: RadarSeries): Long = scopedMetricValue(axis) ?: series.value(axis.lines).toLong()
    private val max: Long
        get() = axes.maxOfOrNull { axis -> shownSeries.maxOf { value(axis, it) } } ?: 0L
    private var hovered: Int? = null
    private var hoveredSeries: RadarSeries? = null

    init {
        name = "radar-chart"
        isOpaque = false
        val side = availableWidth.coerceIn(scaled(MIN_SIZE), scaled(MAX_SIZE))
        preferredSize = Dimension(availableWidth.coerceAtLeast(side), side)
        toolTipText = ""
        val mouse = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val series = seriesAt(e.point)
                val axis = if (series != null) null else axisAt(e.point)
                if (axis != hovered || series != hoveredSeries) {
                    hovered = axis
                    hoveredSeries = series
                    repaint()
                }
                cursor = if ((series != null && chart.series.size > 1) || axis?.let { axes[it].path } != null)
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            }
            override fun mouseExited(e: MouseEvent) {
                if (hovered != null || hoveredSeries != null) {
                    hovered = null
                    hoveredSeries = null
                    repaint()
                }
            }
            override fun mousePressed(e: MouseEvent) = showAxisMenu(e)
            override fun mouseReleased(e: MouseEvent) = showAxisMenu(e)

            private fun showAxisMenu(e: MouseEvent) {
                if (e.isPopupTrigger) contextMenuAt(e.point)?.show(this@RadarChartPanel, e.x, e.y)
            }

            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                seriesAt(e.point)?.let { series ->
                    if (chart.series.size > 1) {
                        this@RadarChartPanel.selectedSeries = if (this@RadarChartPanel.selectedSeries == series) null else series
                        selectSeries(this@RadarChartPanel.selectedSeries)
                        repaint()
                    }
                    return
                }
                axisAt(e.point)?.let { axes[it].path }?.let(openFile)
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    internal fun contextMenuAt(point: Point): JPopupMenu? {
        val menu = axisMenu ?: return null
        if (seriesAt(point) != null) return null
        val axis = axisAt(point)?.let(axes::get) ?: return null
        return menu(axis)?.takeIf { it.componentCount > 0 }
    }

    override fun getToolTipText(event: MouseEvent): String? {
        seriesAt(event.point)?.let { series ->
            val total = String.format(Locale.US, "%,d", axes.sumOf { value(it, series) })
            val hint = when {
                series == selectedSeries -> " · Click to show all"
                chart.series.size > 1 -> " · Click to show only ${series.label.lowercase(Locale.US)}"
                else -> ""
            }
            return "<html><b>${escape(seriesLabel(series))}</b><br>$total total$hint</html>"
        }
        val axis = axisAt(event.point)?.let(axes::get) ?: return null
        val counts = axisValueText(axis)
        val fileCount = scoped?.measures?.get(axis)?.files ?: axis.files.size
        val files = if (fileCount == 1) "1 file" else String.format(Locale.US, "%,d files", fileCount)
        val symbols = if (chart.series.any { it.isSymbol }) {
            "<br>" + RadarSeries.entries.filter { it.isSymbol }.joinToString("<br>") { "${it.label}: ${it.value(axis.lines)}" } + "<br>" + radarSymbolCoverage(axis.files)
        } else ""
        val open = if (axis.path != null) "<br>Click to open" else ""
        return "<html><b>${escape(axis.label)}</b><br>${escape(axis.tooltip).replace("\n", "<br>")}<br>$counts · $files$symbols$open</html>"
    }

    private fun axisValueText(axis: RadarAxis): String {
        if (scoped != null) {
            val value = scoped.measures.getValue(axis).value
            return String.format(Locale.US, "%,d %s", value, scoped.metric.unitText(value))
        }
        return if (chart.series.any { it.isSymbol } && axis.files.none { it.symbolCounts != null }) "Symbols unavailable"
        else radarValueText(axis.lines, chart.series, selectedSeries)
    }

    private fun seriesLabel(series: RadarSeries) = scoped?.metric?.label ?: series.label

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

    private fun legendBounds(): List<Pair<RadarSeries, Rectangle>> {
        if (width <= 0) return emptyList()
        val plain = JBFont.small().zoomed(zoom)
        val bold = JBFont.small().asBold().zoomed(zoom)
        val height = getFontMetrics(plain).height
        val swatch = scaled(10)
        val gap = scaled(6)
        var y = scaled(4)
        return chart.series.map { series ->
            val font = if (series == selectedSeries) bold else plain
            val rowWidth = swatch + gap + getFontMetrics(font).stringWidth(seriesLabel(series))
            val bounds = Rectangle(width - scaled(8) - rowWidth, y, rowWidth, height)
            y += height + scaled(2)
            series to bounds
        }
    }

    private fun seriesAt(point: Point): RadarSeries? =
        legendBounds().firstOrNull { (_, bounds) -> bounds.contains(point) }?.first

    internal fun legendPoint(series: RadarSeries): Point? =
        legendBounds().firstOrNull { it.first == series }?.second?.let { Point(it.centerX.toInt(), it.centerY.toInt()) }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            if (axes.isEmpty()) return
            paintGrid(g)
            for (series in shownSeries) paintSeries(g, series)
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
            if (axes.size < 3) {
                val r = radius * share
                g.draw(Ellipse2D.Double(center.x - r, center.y - r, 2 * r, 2 * r))
            } else {
                g.draw(polygon(axes.indices.map { point(it, share) }))
            }
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
        val points = axes.indices.map { point(it, radarScale(value(axes[it], series), max, logarithmic)) }
        g.stroke = BasicStroke(zoomScaled(2, zoom).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        if (points.size < 3) {
            g.color = color
            for ((x, y) in points) g.drawLine(center.x, center.y, x.toInt(), y.toInt())
        } else {
            val shape = polygon(points)
            g.color = ColorUtil.withAlpha(color, 0.14)
            g.fill(shape)
            g.color = color
            g.draw(shape)
        }
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
            val labelRoom = if (scoped == null) room else {
                val available = when {
                    cosine > 0.2 -> width - x
                    cosine < -0.2 -> x
                    else -> 2 * minOf(x, width - x)
                }
                minOf(room, available.toInt() - scaled(4))
            }
            if (labelRoom <= 0) continue
            val lines = listOf(clip(axis.label, labelRoom, g, bold) to bold,
                clip(axisValueText(axis), labelRoom, g, plain) to plain)
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
        val plain = JBFont.small().zoomed(zoom)
        val bold = JBFont.small().asBold().zoomed(zoom)
        val swatch = scaled(10)
        val gap = scaled(6)
        val entries = legendBounds()
        for ((series, bounds) in entries) {
            val selected = series == selectedSeries
            val dimmed = selectedSeries != null && !selected
            val font = if (selected) bold else plain
            val metrics = g.getFontMetrics(font)
            val baseColor = color(series)
            g.color = if (dimmed) ColorUtil.withAlpha(baseColor, 0.35) else baseColor
            g.fillRoundRect(bounds.x, bounds.y + (bounds.height - swatch) / 2, swatch, swatch, scaled(3), scaled(3))
            g.font = font
            g.color = when {
                series == hoveredSeries && chart.series.size > 1 -> JBColor.namedColor("Focus.borderColor", JBColor.BLUE)
                dimmed -> UIUtil.getContextHelpForeground()
                else -> UIUtil.getLabelForeground()
            }
            g.drawString(seriesLabel(series), bounds.x + swatch + gap, bounds.y + metrics.ascent)
        }
        if (logarithmic) {
            g.font = plain
            val metrics = g.getFontMetrics(plain)
            g.color = UIUtil.getContextHelpForeground()
            val text = "Log scale"
            val y = entries.lastOrNull()?.second?.let { it.y + it.height + scaled(2) } ?: scaled(4)
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
internal fun radarStatus(chart: RadarChart, selected: RadarSeries? = null): String {
    val files = chart.axes.sumOf { it.files.size }
    val spokes = chart.axes.sumOf { it.merged }
    return String.format(Locale.US, "%,d %s · %,d %s · %s", spokes, if (spokes == 1) "entry" else "entries",
        files, if (files == 1) "file" else "files", radarValueText(chart.total, chart.series, selected)) +
        if (RadarSeries.ADDED in chart.series) "" else " · " + radarSymbolCoverage(chart.axes.flatMap { it.files })
}

/** Missing PSI is reported as unavailable instead of implying a measured zero. */
private fun radarSymbolCoverage(files: List<dev.insight.review.ReviewSource>): String {
    val parsed = files.count { it.symbolCounts != null }
    return "Symbols available for $parsed of ${files.size} files"
}
