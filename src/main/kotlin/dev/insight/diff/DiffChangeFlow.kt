package dev.insight.diff

import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.ReviewNote
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JLabel
import javax.swing.JPanel

/** One step of the change flow; [repeat] marks a symbol drawn above, so shared callees and cycles still read as a tree. */
internal data class ChangeFlowRow(val note: ReviewNote, val depth: Int, val parent: Int?, val repeat: Boolean)

/**
 * Symbol notes joined by verified calls between them: the model chooses the steps, the call graph supplies every edge.
 * Notes that neither call nor are called by another symbol note stay out of the flow.
 */
internal data class ChangeFlow(val rows: List<ChangeFlowRow>, val edges: List<Pair<ReviewNote, ReviewNote>>, val omitted: Int, val unlinked: Int) {
    fun summary(): String = if (edges.isEmpty()) "" else "Change flow (verified calls between symbol notes): " +
        edges.take(20).joinToString("; ") { (from, to) -> "${from.evidence!!.symbol.qualifiedName} → ${to.evidence!!.symbol.qualifiedName}" } +
        if (edges.size > 20) "; …" else "."
}

internal fun changeFlow(notes: List<ReviewNote>): ChangeFlow {
    val nodes = notes.filter { it.evidence != null }.distinctBy { it.evidence!!.symbol.selector }
    val bySelector = nodes.associateBy { it.evidence!!.symbol.selector }
    // Callees keep the order in which the caller's source calls them.
    val callees = nodes.associateWith { note ->
        note.evidence!!.calls.mapNotNull { bySelector[it] }.filter { it !== note }
    }
    val edges = nodes.flatMap { from -> callees.getValue(from).map { from to it } }
    val called = edges.mapTo(mutableSetOf()) { it.second }
    val linked = nodes.filter { it in called || callees.getValue(it).isNotEmpty() }
    val rows = mutableListOf<ChangeFlowRow>()
    val drawn = mutableSetOf<ReviewNote>()
    fun visit(note: ReviewNote, depth: Int, parent: Int?) {
        if (rows.size >= MAX_FLOW_ROWS) return
        val repeat = note in drawn
        rows += ChangeFlowRow(note, depth, parent, repeat)
        if (repeat) return
        drawn += note
        val index = rows.lastIndex
        if (depth < MAX_FLOW_DEPTH) callees.getValue(note).forEach { visit(it, depth + 1, index) }
    }
    // Entry steps first; a cycle without an entry starts at its earliest note.
    linked.filter { it !in called }.forEach { visit(it, 0, null) }
    linked.forEach { if (it !in drawn) visit(it, 0, null) }
    return ChangeFlow(rows, edges, linked.count { it !in drawn }, nodes.size - linked.size)
}

private const val MAX_FLOW_ROWS = 14
private const val MAX_FLOW_DEPTH = 5

/** An indented call tree with one trunk per caller, so edges never cross. Clicking a step focuses its note card. */
internal class ChangeFlowDiagram(
    flow: ChangeFlow,
    width: Int,
    private val zoom: Double,
    focus: (ReviewNote) -> Unit,
) : JPanel(null) {
    private fun scaled(n: Int) = zoomScaled(n, zoom)
    private fun font(base: java.awt.Font) = base.zoomed(zoom)
    private val row = scaled(44)
    private val gap = scaled(6)
    private val indent = scaled(28)
    private val trunk = scaled(10)
    private val rows = flow.rows

    init {
        isOpaque = false
        rows.forEachIndexed { index, step ->
            val symbol = step.note.evidence!!.symbol
            val x = step.depth * indent
            add(JPanel(BorderLayout()).apply {
                isOpaque = true
                background = UIUtil.getListBackground()
                border = JBUI.Borders.compound(JBUI.Borders.customLine(if (step.repeat) JBColor.border() else JBUI.CurrentTheme.Focus.focusColor()),
                    JBUI.Borders.empty(2, 6))
                add(ActionLink((if (step.repeat) "↺ " else "") + symbol.qualifiedName) { focus(step.note) }.apply {
                    font = font(JBFont.label())
                    toolTipText = if (step.repeat) "Shown above · ${symbol.path}:${symbol.line}"
                        else "Show note · ${symbol.kind.name.lowercase()} ${symbol.qualifiedName} — ${symbol.path}:${symbol.line}"
                }, BorderLayout.NORTH)
                // The note's first sentence, so the flow reads as a story without opening every card.
                add(JLabel(if (step.repeat) "Calls shown above" else step.note.text.lineSequence().first().substringBefore(". ")).apply {
                    font = font(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                    toolTipText = step.note.text.take(300)
                }, BorderLayout.CENTER)
                setBounds(x, index * row, width - x, row - gap)
            })
        }
        var y = rows.size * row
        fun footnote(text: String) {
            add(JLabel(text).apply {
                font = font(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                setBounds(0, y, width, scaled(20))
            })
            y += scaled(20)
        }
        if (flow.omitted > 0) footnote("+${flow.omitted} linked symbol notes not shown")
        if (flow.unlinked > 0) footnote("${flow.unlinked} other symbol notes have no calls to or from these")
        preferredSize = Dimension(width, y)
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = UIUtil.getContextHelpForeground()
            g.stroke = BasicStroke(scaled(1).toFloat())
            rows.forEachIndexed { index, step ->
                val parent = step.parent ?: return@forEachIndexed
                val x = rows[parent].depth * indent + trunk
                val y = index * row + (row - gap) / 2
                val tip = step.depth * indent
                // Children of one caller share its trunk; the arrow points at the callee.
                g.drawLine(x, parent * row + row - gap, x, y)
                g.drawLine(x, y, tip, y)
                g.drawLine(tip, y, tip - scaled(5), y - scaled(3))
                g.drawLine(tip, y, tip - scaled(5), y + scaled(3))
            }
        } finally { g.dispose() }
    }
}
