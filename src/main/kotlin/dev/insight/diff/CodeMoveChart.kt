package dev.insight.diff

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Line2D
import javax.swing.JComponent
import kotlin.math.roundToInt

/**
 * Files that lost code on the left, the files that received it on the right; a line's width is the number of moved
 * lines. With a [focus] file, only that file fans out to its destinations, to the code that stayed and to the code
 * removed without reappearing elsewhere, each placed by its share of the file.
 */
internal class CodeMoveChart(
    internal val moves: CodeMoves,
    internal val focus: String?,
    private val chartWidth: Int,
    /** Opens the diffs of the given paths. */
    private val open: (List<String>) -> Unit,
    /** Shows one source file on its own. */
    private val focusSource: (String) -> Unit,
) : JComponent() {
    internal data class Node(val label: String, val tooltip: String, val paths: List<String>, val color: Color,
                             val left: Boolean, val y: Int, val bounds: Rectangle, val source: String? = null)
    internal data class Link(val from: Node, val to: Node, val lines: Int, val stroke: Float, val color: Color, val tooltip: String)

    private val font = JBFont.small()
    private val metrics = getFontMetrics(font)
    private val row = metrics.height + JBUI.scale(8)
    private val top = metrics.height + JBUI.scale(16)
    private val leftX = (chartWidth * 0.38).roundToInt()
    private val rightX = (chartWidth * 0.62).roundToInt()
    private val source = focus?.let { path -> moves.sources.firstOrNull { it.path == path } }
    internal val nodes: List<Node>
    internal val links: List<Link>

    init {
        val built = if (source != null) fan(source) else flows()
        nodes = built.first
        links = built.second
        preferredSize = Dimension(chartWidth, top + maxOf(1, nodes.count { it.left }, nodes.count { !it.left }) * row + JBUI.scale(8))
        toolTipText = ""
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                val node = nodeAt(e)
                when {
                    node?.source != null -> focusSource(node.source)
                    node != null -> open(node.paths)
                    else -> linkAt(e)?.let { open(it.to.paths) }
                }
            }
        })
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                cursor = if (nodeAt(e) == null && linkAt(e) == null) Cursor.getDefaultCursor() else Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }
        })
    }

    /** Every source file with its destinations; clicking a source shows it on its own. */
    private fun flows(): Pair<List<Node>, List<Link>> {
        val destinations = moves.sources.flatMap { it.targets }.groupBy { it.path }
            .map { (path, targets) -> path to targets.sumOf { it.lines } }.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        val rows = maxOf(moves.sources.size, destinations.size)
        val names = shortNames(moves.sources.map { it.path } + destinations.map { it.first })
        val left = moves.sources.mapIndexed { index, file ->
            val share = percent(file.share(file.moved))
            node("${names.getValue(file.path)}  $share moved", left = true, y = rowY(index, moves.sources.size, rows), color = PALETTE[index % PALETTE.size],
                paths = listOf(file.path), source = file.path,
                tooltip = "<b>${escape(file.path)}</b><br>$share of ${file.lines} lines moved to ${files(file.targets.size)} · click to show this file alone")
        }
        val right = destinations.mapIndexed { index, (path, lines) ->
            node("${names.getValue(path)}  $lines lines", left = false, y = rowY(index, destinations.size, rows), color = UIUtil.getLabelForeground(),
                paths = listOf(path), tooltip = "<b>${escape(path)}</b><br>Received $lines moved lines · click to open its diff")
        }
        val byPath = right.associateBy { it.paths.single() }
        val max = moves.sources.flatMap { it.targets }.maxOfOrNull { it.lines } ?: 1
        val links = moves.sources.zip(left).flatMap { (file, from) ->
            file.targets.map { target ->
                val to = byPath.getValue(target.path)
                Link(from, to.copy(paths = listOf(file.path, target.path)), target.lines, stroke(target.lines, max), from.color,
                    "${escape(file.path)} → ${escape(target.path)}<br>${target.lines} lines · ${percent(file.share(target.lines))} of the file" + blocks(target))
            }
        }
        return left + right to links
    }

    /** One file fanning out to where its code went, ordered by share. */
    private fun fan(file: FileMoves): Pair<List<Node>, List<Link>> {
        data class Share(val label: String, val lines: Int, val paths: List<String>, val color: Color, val tooltip: String)
        val names = shortNames(listOf(file.path) + file.targets.map { it.path })
        val shares = file.targets.mapIndexed { index, target ->
            Share(names.getValue(target.path), target.lines, listOf(file.path, target.path), PALETTE[index % PALETTE.size],
                "${escape(file.path)} → ${escape(target.path)}<br>${target.lines} lines" + blocks(target))
        } + listOfNotNull(
            Share("Stayed", file.stayed, listOf(file.path), STAYED, "Lines of the file that the change kept").takeIf { file.stayed > 0 },
            Share("Removed", file.deleted, listOf(file.path), REMOVED, "Lines removed or rewritten without reappearing in another changed file")
                .takeIf { file.deleted > 0 },
        )
        val sorted = shares.sortedByDescending { it.lines }
        val rows = maxOf(1, sorted.size)
        val origin = node(names.getValue(file.path), left = true, y = top + rows * row / 2, color = UIUtil.getLabelForeground(),
            paths = listOf(file.path), tooltip = "<b>${escape(file.path)}</b><br>${file.lines} significant lines before the change · click to open its diff")
        val max = sorted.maxOfOrNull { it.lines } ?: 1
        val right = sorted.mapIndexed { index, share ->
            node("${percent(file.share(share.lines))}  ${share.label}", left = false, y = rowY(index, sorted.size, rows), color = share.color,
                paths = share.paths, tooltip = share.tooltip + "<br>${percent(file.share(share.lines))} of ${file.lines} lines")
        }
        val links = sorted.zip(right).map { (share, to) -> Link(origin, to, share.lines, stroke(share.lines, max), share.color, share.tooltip) }
        return listOf(origin) + right to links
    }

    private fun rowY(index: Int, count: Int, rows: Int): Int = top + ((rows - count) * row) / 2 + index * row + row / 2

    private fun node(label: String, left: Boolean, y: Int, color: Color, paths: List<String>, tooltip: String, source: String? = null): Node {
        val gap = JBUI.scale(8)
        val available = leftX - gap * 2
        val text = clip(label, available)
        val width = metrics.stringWidth(text)
        val x = if (left) leftX - gap - width else rightX + gap
        return Node(text, tooltip, paths, color, left, y, Rectangle(x, y - metrics.height / 2, width, metrics.height), source)
    }

    private fun clip(text: String, width: Int): String {
        if (metrics.stringWidth(text) <= width) return text
        var end = text.length
        while (end > 1 && metrics.stringWidth("…" + text.substring(text.length - end)) > width) end--
        return "…" + text.substring(text.length - end)
    }

    private fun stroke(lines: Int, max: Int): Float = JBUI.scale(1.5f) + JBUI.scale(10f) * lines / maxOf(1, max)

    private fun nodeAt(event: MouseEvent) = nodes.firstOrNull { it.bounds.contains(event.point) }

    private fun linkAt(event: MouseEvent) = links.filter {
        Line2D.ptSegDist(leftX.toDouble(), it.from.y.toDouble(), rightX.toDouble(), it.to.y.toDouble(), event.x.toDouble(), event.y.toDouble()) <=
            maxOf(JBUI.scale(4).toDouble(), it.stroke / 2.0)
    }.maxByOrNull { it.stroke }

    override fun getToolTipText(event: MouseEvent): String? =
        (nodeAt(event)?.tooltip ?: linkAt(event)?.tooltip)?.let { "<html>$it</html>" }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = UIUtil.getListBackground()
            g2.fillRect(0, 0, width, height)
            g2.font = font
            g2.color = UIUtil.getContextHelpForeground()
            fun centered(text: String, x: Int) = g2.drawString(text, x - metrics.stringWidth(text) / 2, metrics.ascent + JBUI.scale(4))
            centered("Before", leftX)
            centered("After", rightX)
            g2.color = ColorUtil.withAlpha(UIUtil.getContextHelpForeground(), 0.3)
            g2.drawLine(leftX, top - JBUI.scale(4), leftX, height - JBUI.scale(4))
            g2.drawLine(rightX, top - JBUI.scale(4), rightX, height - JBUI.scale(4))
            links.sortedByDescending { it.stroke }.forEach {
                g2.color = ColorUtil.withAlpha(it.color, 0.7)
                g2.stroke = BasicStroke(it.stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(leftX, it.from.y, rightX, it.to.y)
            }
            val dot = JBUI.scale(7)
            nodes.forEach {
                g2.color = it.color
                g2.fillOval((if (it.left) leftX else rightX) - dot / 2, it.y - dot / 2, dot, dot)
                g2.drawString(it.label, it.bounds.x, it.bounds.y + metrics.ascent)
            }
        } finally { g2.dispose() }
    }

    private companion object {
        val PALETTE = listOf(0x3A2BD9, 0x9B4DE0, 0xE8517A, 0xF28C1F, 0xD9A400, 0x1E8C93).map { JBColor(Color(it), Color(it).brighter()) }
        val STAYED = JBColor(Color(0x7F8C8D), Color(0x9AA5A6))
        val REMOVED = JBColor(Color(0xC0392B), Color(0xE8836E))

        fun percent(share: Double): String = if (share > 0 && share < 0.01) "<1%" else "${(share * 100).roundToInt()}%"
        fun files(count: Int) = if (count == 1) "1 file" else "$count files"
        fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        fun blocks(target: MoveTarget): String = "<br>" + target.blocks.take(8).joinToString("<br>") {
            "lines ${it.fromLines.first}–${it.fromLines.last} → ${it.toLines.first}–${it.toLines.last}"
        } + if (target.blocks.size > 8) "<br>… ${target.blocks.size - 8} more" else ""

        /** File names, with as many parent directories as it takes to tell equal names apart. */
        fun shortNames(paths: List<String>): Map<String, String> {
            val distinct = paths.distinct()
            fun name(path: String, depth: Int) = path.split('/').takeLast(depth).joinToString("/")
            return distinct.associateWith { path ->
                var depth = 1
                while (depth < path.count { it == '/' } + 1 && distinct.any { it != path && name(it, depth) == name(path, depth) }) depth++
                name(path, depth)
            }
        }
    }
}
