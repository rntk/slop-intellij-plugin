package dev.insight.callgraph

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.ui.accent
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.CubicCurve2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import javax.swing.JPanel
import javax.swing.ToolTipManager
import kotlin.math.atan2
import kotlin.math.max

private val rootFill = JBColor(Color(232, 232, 252), Color(58, 58, 92))

/** Theme-aware Java2D rendering of a [CallGraph]: callers on the left, callees on the right. */
class CallGraphCanvas(private val onSelect: (Int?) -> Unit, private val onOpen: (Int) -> Unit) : JPanel() {
    private var graph: CallGraph? = null
    private var layout: GraphLayout? = null
    var selected: Int? = null
        private set

    init {
        ToolTipManager.sharedInstance().registerComponent(this)
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                select(nodeAt(e.point))
                if (e.clickCount == 2) selected?.let(onOpen)
            }
        })
    }

    fun show(graph: CallGraph?) {
        this.graph = graph
        layout = graph?.let {
            layoutCallGraph(it, LayoutMetrics(JBUI.scale(220), JBUI.scale(46), JBUI.scale(80), JBUI.scale(14), JBUI.scale(20)))
        }
        select(null)
        revalidate()
        repaint()
    }

    private fun select(id: Int?) {
        if (id == selected) return
        selected = id
        onSelect(id)
        repaint()
    }

    private fun nodeAt(point: Point) = layout?.boxes?.entries?.firstOrNull { it.value.contains(point) }?.key

    override fun getPreferredSize(): Dimension = layout?.size ?: Dimension(0, 0)

    override fun getToolTipText(event: MouseEvent): String? {
        val node = nodeAt(event.point)?.let { graph?.nodes?.get(it) } ?: return null
        val lines = listOf("<b>${StringUtil.escapeXmlEntities(node.name)}</b>", node.container, node.location,
            if (node.inProject) "" else "Library code")
        return lines.filter { it.isNotEmpty() }.joinToString("<br>", "<html>", "</html>") { it }
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val graph = graph ?: return
        val boxes = layout?.boxes ?: return
        val canvas = g.create() as Graphics2D
        try {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            for (edge in graph.edges) {
                val highlighted = selected == edge.caller || selected == edge.callee
                canvas.color = if (highlighted) accent else JBColor.GRAY
                canvas.stroke = BasicStroke(JBUI.scale(if (highlighted) 2 else 1).toFloat())
                drawEdge(canvas, boxes.getValue(edge.caller), boxes.getValue(edge.callee))
            }
            for (node in graph.nodes) drawNode(canvas, node, boxes.getValue(node.id))
        } finally { canvas.dispose() }
    }

    private fun drawEdge(g: Graphics2D, from: Rectangle, to: Rectangle) {
        val bend = JBUI.scale(40).toDouble()
        val curve = when {
            from == to -> CubicCurve2D.Double(from.maxX, from.centerY - 6, from.maxX + bend, from.y - bend / 2,
                from.maxX + bend, from.maxY + bend / 2, from.maxX, from.centerY + 6)
            from.x < to.x -> {
                val dx = max(bend, (to.x - from.maxX) / 2)
                CubicCurve2D.Double(from.maxX, from.centerY, from.maxX + dx, from.centerY,
                    to.x - dx, to.centerY, to.x.toDouble(), to.centerY)
            }
            from.x == to.x -> CubicCurve2D.Double(from.maxX, from.centerY, from.maxX + bend, from.centerY,
                to.maxX + bend, to.centerY, to.maxX, to.centerY)
            // Calls back towards the root, for example recursion through several functions.
            else -> CubicCurve2D.Double(from.centerX, from.maxY, from.centerX, from.maxY + bend,
                to.centerX, to.maxY + bend, to.centerX, to.maxY)
        }
        g.draw(curve)
        arrowHead(g, curve.ctrlP2, curve.p2)
    }

    private fun arrowHead(g: Graphics2D, from: Point2D, tip: Point2D) {
        val angle = atan2(tip.y - from.y, tip.x - from.x)
        val size = JBUI.scale(8).toDouble()
        val head = Path2D.Double().apply {
            moveTo(tip.x, tip.y)
            lineTo(tip.x - size * Math.cos(angle - 0.4), tip.y - size * Math.sin(angle - 0.4))
            lineTo(tip.x - size * Math.cos(angle + 0.4), tip.y - size * Math.sin(angle + 0.4))
            closePath()
        }
        g.fill(head)
    }

    private fun drawNode(g: Graphics2D, node: CallGraphNode, box: Rectangle) {
        val arc = JBUI.scale(10)
        val isRoot = node.level == 0
        g.color = if (isRoot) rootFill else UIUtil.getPanelBackground()
        g.fillRoundRect(box.x, box.y, box.width, box.height, arc, arc)
        g.color = if (node.id == selected || isRoot) accent else JBColor.border()
        g.stroke = if (node.inProject) BasicStroke(JBUI.scale(if (node.id == selected) 2 else 1).toFloat())
        else BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 4f), 0f)
        g.drawRoundRect(box.x, box.y, box.width, box.height, arc, arc)

        val padding = JBUI.scale(8)
        val textWidth = box.width - 2 * padding
        g.font = font.deriveFont(Font.BOLD)
        g.color = if (node.inProject) JBColor.foreground() else UIUtil.getContextHelpForeground()
        g.drawString(fit(node.name, g.fontMetrics, textWidth), box.x + padding, box.y + box.height / 2 - JBUI.scale(3))
        g.font = font.deriveFont(font.size2D - 1f)
        g.color = UIUtil.getContextHelpForeground()
        val detail = node.container.ifEmpty { node.location }
        g.drawString(fit(detail, g.fontMetrics, textWidth), box.x + padding, box.y + box.height / 2 + g.fontMetrics.ascent)
    }

    private fun fit(text: String, metrics: FontMetrics, width: Int): String {
        if (metrics.stringWidth(text) <= width) return text
        var end = text.length
        while (end > 0 && metrics.stringWidth(text.take(end) + "…") > width) end--
        return text.take(end) + "…"
    }
}
