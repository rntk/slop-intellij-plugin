package dev.insight.review.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.LineChartMetric
import dev.insight.review.PackedCircle
import dev.insight.review.RadarSeries
import dev.insight.review.ReviewLineChart
import dev.insight.review.packCircles
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.util.Locale
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.math.min

/**
 * Packed circles for one directory, with the metric of the selected tab as each file's area. Clicking a folder walks
 * into it, clicking a file opens it, and clicking the shown directory's background or Backspace walks back up.
 */
internal class CircleChartPanel(
    private val chart: ReviewLineChart,
    path: String?,
    rootLabel: String,
    diameter: Int,
    private val navigate: (String) -> Unit,
    private val open: (String) -> Unit,
    private val scope: ReviewLineChart.Entry? = null,
    private val contextMenu: ((ReviewLineChart.Entry) -> JPopupMenu)? = null,
) : JPanel(BorderLayout(0, JBUI.scale(4))) {
    private val metric = chart.metric
    private val root = scope ?: chart.root
    val directory = chart.directory(path).let {
        if (scope == null || scope.path.isEmpty() || ReviewLineChart.isBeneath(it.path, scope.path)) it else scope
    }
    internal val packed: PackedCircle? = packCircles(chart, directory, MAX_DEPTH)
    private val baseColor: Color = RadarChartPanel.color(metric.diffSeries ?: RadarSeries.SOURCE)
    internal val canvas = CircleCanvas(diameter)

    init {
        name = "charts-circles"
        isOpaque = false
        isFocusable = true
        getAccessibleContext().accessibleName = "${metric.label} circles"
        add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            if (scope == null || directory.path != root.path) add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                name = "charts-circles-breadcrumbs"
                isOpaque = false
                chart.breadcrumbs(directory).dropWhile { it.path != root.path }.forEachIndexed { index, entry ->
                    if (index > 0) add(JLabel(" / ").apply { font = JBFont.small(); foreground = UIUtil.getContextHelpForeground() })
                    val title = if (index == 0) rootLabel + entry.path.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty() else entry.label
                    add((if (entry.path == directory.path) JLabel(title) else ActionLink(title) { navigate(entry.path) }).apply {
                        name = "charts-circles-crumb:${entry.path}"
                        toolTipText = entry.path.ifEmpty { rootLabel }
                        font = JBFont.small()
                    })
                }
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(JLabel(valueText(directory.value) + if (metric == LineChartMetric.ITEMS) "" else " · " + fileCount(directory.files)).apply {
                    name = "charts-circles-total"
                    font = JBFont.small()
                    foreground = metric.diffSeries?.let(RadarChartPanel::color) ?: UIUtil.getLabelForeground()
                    toolTipText = metric.description
                })
                if (scope == null) add(JLabel(AllIcons.General.ContextHelp).apply {
                    toolTipText = "Click a folder to walk in, a file to open it, the background to go up · Backspace goes up"
                    accessibleContext.accessibleName = "Chart navigation help"
                    accessibleContext.accessibleDescription = toolTipText
                })
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(if (packed == null) JLabel(if (metric.hidesEmpty) "No ${metric.plural} here" else "Nothing to measure here").apply {
            name = "charts-circles-empty"
            font = chartFont(font)
        } else canvas, BorderLayout.CENTER)
        installKeys()
    }

    /** Walks into a folder or opens a file; the shown directory walks up within the chart's scope. */
    fun activate(circle: PackedCircle) = when {
        !circle.entry.directory -> open(circle.entry.path)
        circle.depth == 0 -> up()
        else -> navigate(circle.entry.path)
    }

    fun up() {
        if (directory.path != root.path) navigate(directory.path.substringBeforeLast('/', ""))
    }

    private fun installKeys() {
        fun bind(key: Int, id: String, action: () -> Unit) {
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), id)
            actionMap.put(id, object : AbstractAction() { override fun actionPerformed(e: ActionEvent) = action() })
        }
        bind(KeyEvent.VK_RIGHT, "charts-circles-next") { canvas.step(1) }
        bind(KeyEvent.VK_DOWN, "charts-circles-next-down") { canvas.step(1) }
        bind(KeyEvent.VK_LEFT, "charts-circles-previous") { canvas.step(-1) }
        bind(KeyEvent.VK_UP, "charts-circles-previous-up") { canvas.step(-1) }
        bind(KeyEvent.VK_ENTER, "charts-circles-activate") { canvas.current?.let(::activate) }
        bind(KeyEvent.VK_BACK_SPACE, "charts-circles-parent") { up() }
        bind(KeyEvent.VK_ESCAPE, "charts-circles-parent-escape") { up() }
    }

    internal inner class CircleCanvas(private val diameter: Int) : JComponent() {
        /** The hovered circle, or the one the arrow keys picked among the shown directory's entries. */
        var current: PackedCircle? = null
            private set
        private val circles = packed?.flatten().orEmpty()

        init {
            name = "charts-circles-canvas"
            preferredSize = Dimension(diameter, diameter)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = ""
            val mouse = object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) = hover(circleAt(e.x, e.y))
                override fun mouseExited(e: MouseEvent) = hover(null)
                override fun mousePressed(e: MouseEvent) {
                    this@CircleChartPanel.requestFocusInWindow()
                    popup(e)
                }
                override fun mouseReleased(e: MouseEvent) = popup(e)
                override fun mouseClicked(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e) || e.isPopupTrigger) return
                    val circle = circleAt(e.x, e.y)
                    if (circle == null) up() else activate(circle)
                }
            }
            addMouseListener(mouse)
            addMouseMotionListener(mouse)
        }

        private fun popup(e: MouseEvent) {
            if (!e.isPopupTrigger) return
            val entry = (circleAt(e.x, e.y) ?: packed)?.entry ?: return
            contextMenu?.invoke(entry)?.show(this, e.x, e.y)
        }

        private fun hover(circle: PackedCircle?) {
            if (circle === current) return
            current = circle
            repaint()
        }

        /** Moves the keyboard pick among the shown directory's entries. */
        fun step(delta: Int) {
            val top = packed?.let { if (it.entry.directory) it.children else listOf(it) }.orEmpty()
            if (top.isEmpty()) return
            val index = top.indexOfFirst { it === current || current?.let { c -> ReviewLineChart.isBeneath(c.entry.path, it.entry.path) } == true }
            hover(top[if (index < 0) (if (delta > 0) 0 else top.lastIndex) else (index + delta).mod(top.size)])
        }

        private val scale get() = packed?.let { (min(width, height) / 2.0 - JBUI.scale(MARGIN)).coerceAtLeast(1.0) / it.r } ?: 1.0
        private fun sx(x: Double) = width / 2.0 + x * scale
        private fun sy(y: Double) = height / 2.0 + y * scale

        /** The deepest shown circle under a screen point, or null outside the directory's circle. */
        fun circleAt(x: Int, y: Int): PackedCircle? =
            packed?.hit((x - width / 2.0) / scale, (y - height / 2.0) / scale)

        /** The screen centre of the circle at [path], for tests. */
        fun centreOf(path: String): java.awt.Point? = circles.firstOrNull { it.entry.path == path }
            ?.let { java.awt.Point(sx(it.x).toInt(), sy(it.y).toInt()) }

        override fun getToolTipText(event: MouseEvent): String? {
            val circle = circleAt(event.x, event.y) ?: return null
            val entry = circle.entry
            val what = when {
                circle.depth == 0 && entry.directory -> if (directory.path == root.path) "" else " · Click to go up"
                entry.directory -> " · Click to explore"
                metric.diffSeries == null -> " · Open file"
                else -> " · View diff"
            }
            val files = if (entry.directory && metric != LineChartMetric.ITEMS) " · " + fileCount(entry.files) else ""
            return "${entry.path.ifEmpty { entry.label }} · ${valueText(entry.value)}$files$what"
        }

        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                val highlight = current
                for (circle in circles) {
                    val r = circle.r * scale
                    val shape = Ellipse2D.Double(sx(circle.x) - r, sy(circle.y) - r, 2 * r, 2 * r)
                    g.color = fill(circle)
                    g.fill(shape)
                    g.color = if (circle.entry.directory) alpha(baseColor, 140) else alpha(baseColor, 200)
                    g.stroke = BasicStroke(JBUI.scale(1).toFloat())
                    g.draw(shape)
                }
                // The hovered circle, and the shown entry that holds it, stand out over their neighbours.
                if (highlight != null && (highlight.depth > 0 || !highlight.entry.directory)) {
                    val r = highlight.r * scale
                    g.color = JBUI.CurrentTheme.Focus.focusColor()
                    g.stroke = BasicStroke(JBUI.scale(2).toFloat())
                    g.draw(Ellipse2D.Double(sx(highlight.x) - r, sy(highlight.y) - r, 2 * r, 2 * r))
                }
                paintLabels(g)
            } finally { g.dispose() }
        }

        private fun fill(circle: PackedCircle): Color = when {
            circle.depth == 0 && circle.entry.directory -> alpha(baseColor, 18)
            circle.entry.directory -> alpha(baseColor, 30 + 14 * circle.depth)
            else -> alpha(baseColor, if (circle === current) 200 else 130)
        }

        /** Labels the shown directory's entries, and nested files roomy enough for their name. */
        private fun paintLabels(g: Graphics2D) {
            val labelFont = chartFont(JBFont.label())
            val small = chartFont(JBFont.small())
            for (circle in circles) {
                if (circle.entry.directory && (circle.depth == 0 || circle.depth > 2)) continue
                val r = circle.r * scale
                val nested = circle.entry.directory && circle.children.isNotEmpty()
                g.font = if (circle.depth <= 1) labelFont else small
                val metrics = g.fontMetrics
                val label = circle.entry.label + if (circle.entry.directory) "/" else ""
                if (metrics.stringWidth(label) > 1.8 * r || metrics.height > r) continue
                val x = sx(circle.x)
                // Folders with nested circles carry their name near the top rim, clear of their contents.
                val y = if (nested) sy(circle.y) - r + metrics.ascent + JBUI.scale(4) else sy(circle.y) + metrics.ascent / 2.0 - metrics.descent / 2.0
                drawHalo(g, label, x - metrics.stringWidth(label) / 2.0, y)
                if (!nested && circle.depth <= 1) {
                    g.font = small
                    val value = number(circle.entry.value)
                    val vm = g.fontMetrics
                    if (vm.stringWidth(value) <= 1.6 * r && metrics.height + vm.height < 1.6 * r) {
                        drawHalo(g, value, x - vm.stringWidth(value) / 2.0, y + vm.height)
                    }
                }
            }
        }

        private fun drawHalo(g: Graphics2D, text: String, x: Double, y: Double) {
            g.color = alpha(UIUtil.getListBackground(), 170)
            val m = g.fontMetrics
            g.fillRoundRect(x.toInt() - 2, (y - m.ascent).toInt(), m.stringWidth(text) + 4, m.height, 6, 6)
            g.color = UIUtil.getLabelForeground()
            g.drawString(text, x.toFloat(), y.toFloat())
        }
    }

    private fun alpha(color: Color, alpha: Int) = Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))

    private fun number(value: Long) = String.format(Locale.US, "%,d", value)
    private fun valueText(value: Long) = "${number(value)} ${metric.unitText(value)}"
    private fun fileCount(files: Int) = "${number(files.toLong())} ${if (files == 1) "file" else "files"}"
    private fun chartFont(font: java.awt.Font) = font.deriveFont(font.size2D * FONT_SCALE)

    private companion object {
        const val FONT_SCALE = 1.32f
        /** Nesting shown below the directory: its entries and two more levels. */
        const val MAX_DEPTH = 3
        const val MARGIN = 4
    }
}
