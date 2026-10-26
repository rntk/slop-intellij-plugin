package dev.insight.diff

import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** One occurrence per function. Only resolved calls supply connectors; proximity implies no relationship. */
internal data class ChangeMapConnector(val edge: ChangeEdge, val points: List<Point>)

internal data class ChangeMapGeometry(
    val nodes: Map<Int, Rectangle>,
    val connectors: List<ChangeMapConnector>,
    val size: Dimension,
)

internal const val CHANGE_MAP_PREVIEW_STEPS = 12

/** Calls in source order, with each function included once, followed by disconnected changes and tests. */
internal fun changeMapOrder(graph: ChangeGraph, cluster: ChangeCluster): List<Int> {
    val members = cluster.members.toHashSet()
    val order = mutableListOf<Int>()
    val visited = hashSetOf<Int>()
    fun visit(id: Int) {
        if (!visited.add(id)) return
        order += id
        graph.callees(id).filter { it in members && !graph.nodes[it].test }.distinct().forEach(::visit)
    }
    flowRoots(graph.nodes, graph.edges, cluster.members).forEach(::visit)
    cluster.members.filter { !graph.nodes[it].test }.forEach(::visit)
    cluster.members.forEach(::visit)
    return order
}

/**
 * A bounded-width reading grid: follow each entry's calls, then include disconnected members and tests.
 * Long calls and feedback loops use the gaps between steps, never the text of an intervening step.
 * All dimensions are supplied in screen pixels so layout and hit targets share canvas/HiDPI scaling.
 */
internal fun changeMapGeometry(
    graph: ChangeGraph,
    cluster: ChangeCluster,
    width: Int,
    stepHeight: Int,
    columnGap: Int,
    rowGap: Int,
    inset: Int,
    dotY: Int,
    limit: Int = Int.MAX_VALUE,
): ChangeMapGeometry {
    val order = changeMapOrder(graph, cluster).take(limit)
    val members = order.toHashSet()
    if (order.isEmpty()) return ChangeMapGeometry(emptyMap(), emptyList(), Dimension(width, 0))

    val columns = minOf(3, order.size, ((width + columnGap) / (stepHeight * 2 + columnGap)).coerceAtLeast(1))
    val stepWidth = ((width - inset * 2 - columnGap * (columns - 1)) / columns).coerceAtLeast(1)
    val pitch = stepHeight + rowGap
    val bounds = order.mapIndexed { index, id ->
        id to Rectangle(inset + index % columns * (stepWidth + columnGap), inset + index / columns * pitch, stepWidth, stepHeight)
    }.toMap()
    val connectors = graph.edges.distinct().filter { it.from in members && it.to in members }.mapIndexed { index, edge ->
        val from = bounds.getValue(edge.from)
        val to = bounds.getValue(edge.to)
        val start = Point(from.x + from.width, from.y + dotY)
        val end = Point(to.x, to.y + dotY)
        val points = if (from.y == to.y && to.x == from.x + stepWidth + columnGap) listOf(start, end)
        else {
            // Each connector leaves to the right, uses a row gap, then arrives from the left.
            // A few separate tracks keep parallel and returning calls distinguishable.
            val track = index % 4
            val right = start.x + columnGap * (track + 2) / 7
            val left = end.x - columnGap * (track + 2) / 7
            val lane = from.y + stepHeight + rowGap * (track + 1) / 5
            listOf(start, Point(right, start.y), Point(right, lane), Point(left, lane), Point(left, end.y), end)
        }
        ChangeMapConnector(edge, points)
    }
    val rows = (order.size + columns - 1) / columns
    return ChangeMapGeometry(bounds, connectors, Dimension(width, inset * 2 + rows * pitch))
}

/** Editorial-style dots, compact titles, summaries and orthogonal call arrows, with native keyboard-focusable links. */
internal class ChangeFlowMap(
    private val flows: ChangeFlows,
    cluster: ChangeCluster,
    width: Int,
    private val zoom: Double,
    private val selected: Int?,
    focus: (Int) -> Unit,
    limit: Int = Int.MAX_VALUE,
) : JPanel(null) {
    private fun scaled(value: Int) = zoomScaled(value, zoom)
    private fun font(base: Font) = base.zoomed(zoom)
    private val help get() = UIUtil.getContextHelpForeground()
    internal val geometry = changeMapGeometry(flows.graph, cluster, width, scaled(108), scaled(38), scaled(42), scaled(32), scaled(13), limit)

    init {
        isOpaque = false
        toolTipText = "Arrows are resolved calls, not runtime order. Select a step to inspect its source."
        geometry.nodes.forEach { (id, bounds) ->
            val node = flows.graph.nodes[id]
            val note = flows.nodeNotes[id]
            val step = JPanel(null).apply {
                isOpaque = false
                name = "map-node:$id"
                toolTipText = "${node.qualifiedName} · ${node.path}:${node.line} · ${node.status.id}"
            }
            val textX = scaled(18)
            val textWidth = (bounds.width - textX - scaled(4)).coerceAtLeast(1)
            var y = scaled(3)
            fun line(component: JComponent, height: Int) {
                component.setBounds(textX, y, textWidth, height)
                step.add(component)
                y += height
            }
            line(ActionLink(node.qualifiedName) { focus(id) }.apply {
                font = font(JBFont.label().asBold())
                foreground = if (selected == id) ACCENT else UIUtil.getLabelForeground()
                toolTipText = "Inspect ${node.qualifiedName} · ${node.path}:${node.line}"
                accessibleContext.accessibleName = "Inspect ${node.qualifiedName}, ${node.status.id}"
            }, scaled(21))
            val summary = note?.summary ?: when {
                node.trivial != null -> node.trivial.id.replaceFirstChar { it.uppercase() }
                !node.changed -> "Unchanged · connects these changes"
                else -> node.header
            }
            line(JTextArea().apply {
                isEditable = false
                isOpaque = false
                isFocusable = false
                border = null
                font = font(JBFont.small()).let { if (note == null) it else it.deriveFont(it.style or Font.ITALIC) }
                foreground = help
                text = abbreviated(summary, textWidth, getFontMetrics(font))
                toolTipText = summary
                accessibleContext.accessibleDescription = summary
            }, scaled(48))
            line(JLabel(buildString {
                append(node.status.badge)
                if (node.changed) append("  +${node.added} −${node.removed}")
                if (node.test) append(" · test")
                if (node.trivial != null || note?.importance == ChangeImportance.MINOR) append(" · minor")
                if (note?.attention != null) append(" · ⚑ verify")
            }).apply {
                font = font(JBFont.small())
                foreground = if (note?.attention != null) ChangeFlowsPanel.ATTENTION else help
                toolTipText = note?.attention ?: "${node.status.id} · changed lines from source comparison"
            }, scaled(17))
            line(JLabel("${node.path.substringAfterLast('/')}:${node.line}").apply {
                font = font(JBFont.small())
                foreground = help
                toolTipText = "${node.path}:${node.line}"
            }, scaled(16))
            step.bounds = bounds
            add(step)
        }
        val calls = geometry.connectors.size
        val hidden = cluster.members.size - geometry.nodes.size
        val caption = JLabel(when {
            hidden > 0 -> "${geometry.nodes.size} of ${cluster.members.size} steps · $calls calls shown · expand below to see all connections"
            calls == 0 -> "No resolved calls between these steps · select a step to inspect"
            else -> "$calls resolved ${if (calls == 1) "call" else "calls"} · select a step to inspect · arrows show calls, not runtime order"
        }).apply {
            font = font(JBFont.small())
            foreground = help
        }
        caption.setBounds(scaled(24), geometry.size.height, (width - scaled(48)).coerceAtLeast(1), scaled(26))
        add(caption)
        preferredSize = Dimension(width, geometry.size.height + scaled(30))
        minimumSize = Dimension(0, preferredSize.height)
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            fun connected(connector: ChangeMapConnector) = connector.edge.from == selected || connector.edge.to == selected
            // The selected step's relationships paint last, so they remain visible in a dense map.
            geometry.connectors.sortedBy { connected(it) }.forEach { connector ->
                g.color = if (connected(connector)) ACCENT else help
                g.stroke = BasicStroke((if (connected(connector)) scaled(2) else scaled(1)).toFloat())
                val points = connector.points
                val path = Path2D.Double().apply {
                    moveTo(points.first().x.toDouble(), points.first().y.toDouble())
                    points.drop(1).forEach { lineTo(it.x.toDouble(), it.y.toDouble()) }
                }
                g.draw(path)
                val end = points.last()
                val before = points[points.lastIndex - 1]
                val angle = atan2((end.y - before.y).toDouble(), (end.x - before.x).toDouble())
                val length = scaled(5)
                for (offset in listOf(-0.55, 0.55)) g.drawLine(end.x, end.y,
                    (end.x - length * cos(angle + offset)).roundToInt(), (end.y - length * sin(angle + offset)).roundToInt())
            }
            geometry.nodes.forEach { (id, bounds) ->
                val node = flows.graph.nodes[id]
                val color = when {
                    id == selected -> ACCENT
                    node.status == ChangeStatus.ADDED -> ChangeFlowsPanel.ADDED
                    node.status == ChangeStatus.DELETED -> ChangeFlowsPanel.DELETED
                    node.status == ChangeStatus.CONTEXT || node.trivial != null -> help
                    else -> ChangeFlowsPanel.CHANGED
                }
                val x = bounds.x + scaled(5)
                val y = bounds.y + scaled(13)
                if (id == selected) {
                    g.color = ACCENT
                    g.stroke = BasicStroke(scaled(1).toFloat())
                    g.drawOval(x - scaled(8), y - scaled(8), scaled(16), scaled(16))
                }
                g.color = color
                if (node.status == ChangeStatus.CONTEXT) g.drawOval(x - scaled(4), y - scaled(4), scaled(8), scaled(8))
                else g.fillOval(x - scaled(4), y - scaled(4), scaled(8), scaled(8))
            }
            g.color = JBColor.border()
            g.drawLine(scaled(24), geometry.size.height, width - scaled(24), geometry.size.height)
        } finally { g.dispose() }
    }

    override fun getToolTipText(event: MouseEvent): String? {
        geometry.connectors.firstOrNull { connector -> connector.points.zipWithNext().any { (a, b) ->
            java.awt.geom.Line2D.ptSegDist(a.x.toDouble(), a.y.toDouble(), b.x.toDouble(), b.y.toDouble(), event.x.toDouble(), event.y.toDouble()) <= scaled(4)
        } }?.let {
            return "${flows.graph.nodes[it.edge.from].qualifiedName} calls ${flows.graph.nodes[it.edge.to].qualifiedName}"
        }
        return super.getToolTipText(event)
    }

    companion object {
        private val ACCENT: Color = JBColor(Color(0xC45D3E), Color(0xE59072))

        /** Wrap at word boundaries, with an ellipsis instead of silently clipping the interpretation. */
        private fun abbreviated(text: String, width: Int, metrics: java.awt.FontMetrics): String {
            val maxLines = 3
            val words = text.trim().split(Regex("\\s+"))
            val lines = mutableListOf<String>()
            var line = ""
            for (word in words) {
                val next = if (line.isEmpty()) word else "$line $word"
                if (metrics.stringWidth(next) <= width) { line = next; continue }
                if (line.isNotEmpty()) { lines += line; line = "" }
                if (lines.size == maxLines) break
                // Long identifiers can exceed a whole line; retain the full value in the tooltip.
                if (metrics.stringWidth(word) > width) {
                    var clipped = word
                    while (clipped.isNotEmpty() && metrics.stringWidth("$clipped…") > width) clipped = clipped.dropLast(1)
                    lines += "$clipped…"
                    if (lines.size == maxLines) break
                } else line = word
            }
            if (line.isNotEmpty() && lines.size < maxLines) lines += line
            if (lines.joinToString(" ") != text.trim().replace(Regex("\\s+"), " ") && lines.size == maxLines) {
                var last = lines.last().removeSuffix("…")
                while (last.isNotEmpty() && metrics.stringWidth("$last…") > width) last = last.dropLast(1)
                lines[lines.lastIndex] = "$last…"
            }
            return lines.joinToString("\n")
        }
    }
}
