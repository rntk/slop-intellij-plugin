package dev.insight.callgraph

import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle

data class FlowMetrics(
    val cardWidth: Int,
    /** Space between the spine and a card. */
    val gutter: Int,
    val dotRadius: Int,
    /** Vertical space between consecutive items. */
    val gap: Int,
    val pillHeight: Int,
    /** Horizontal padding inside pills and the decision shape. */
    val pillPadding: Int,
    /** Horizontal space between branch columns. */
    val branchGap: Int,
    /** Space between a sub-flow frame and its content. */
    val framePadding: Int,
    val decisionHeight: Int,
    val decisionMaxWidth: Int,
    val bannerWidth: Int,
    val margin: Int,
    /** Height of an empty branch below its label. */
    val emptyBranch: Int,
    /** Horizontal space between the columns of placed functions. */
    val laneGap: Int = margin * 2,
)

/** Text sizes, measured by the canvas with its fonts. */
interface FlowMeasure {
    /** Height of a step's card text at [width]. */
    fun card(step: FlowStep, width: Int): Int
    /** Distance from the top of a card to the middle of its first line, where the dot sits. */
    fun firstLine(): Int
    /** Width of one line of small text such as a pill label. */
    fun text(text: String): Int
    fun line(): Int
    fun banner(lane: FlowLane, width: Int): Int
}

enum class FlowLineStyle { NORMAL, OTHERWISE, ERROR }
/** [EXIT]: a branch leaving the function by `return`; [THROW]: by `throw` or `raise`. */
enum class FlowPillStyle { SUBFLOW, LOOP, CASE, OTHERWISE, ERROR, END, EXIT, THROW }

sealed interface FlowShape
data class FlowSegment(val from: Point, val to: Point, val style: FlowLineStyle = FlowLineStyle.NORMAL) : FlowShape
data class FlowDot(val center: Point, val step: FlowStep) : FlowShape
/** A card left of the spine has its text aligned towards the spine. */
data class FlowCardBox(val step: FlowStep, val bounds: Rectangle, val leftOfSpine: Boolean, val dot: Point) : FlowShape
data class FlowRegionBox(val region: FlowRegion, val bounds: Rectangle, val leftOfSpine: Boolean, val dot: Point) : FlowShape
/** [toggle] is the function whose calls (or, with [callers], whose direct callers) the pill shows or hides. */
data class FlowPill(val bounds: Rectangle, val text: String, val style: FlowPillStyle, val toggle: Int? = null,
    val callers: Boolean = false, val tooltip: String? = null, val regionKey: String? = null) : FlowShape
data class FlowDecisionBox(val bounds: Rectangle, val decision: FlowDecision) : FlowShape
data class FlowFrame(val bounds: Rectangle, val loop: Boolean) : FlowShape
data class FlowRefBox(val bounds: Rectangle, val ref: FlowRef, val dot: Point) : FlowShape
data class FlowBanner(val bounds: Rectangle, val lane: FlowLane) : FlowShape

/** [shapes] are in painting order: frames, lines, then everything drawn over them. */
data class FlowGeometry(val size: Dimension, val shapes: List<FlowShape>) {
    /** Cards by function. */
    val cards: Map<Int, FlowCardBox> by lazy { shapes.filterIsInstance<FlowCardBox>().associateBy { it.step.node } }
}

/**
 * Lays a [FlowChart] out as columns side by side, one per placed function, each headed by a banner and read like a
 * timeline: the steps hang off a vertical spine, alternating left and right on the main spine. A step's open calls
 * follow it inside a frame headed by a pill on the spine (just the pill while closed); its open callers stand above it
 * in columns that join into it, below a pill that shows or hides them. A decision forks the spine into branch columns
 * side by side, which join again below it; a branch that leaves the function stops at its exit pill instead, and the
 * spine runs on through the branches that continue, so a guard clause reads as a side exit off a straight path.
 */
fun layoutFlowChart(chart: FlowChart, metrics: FlowMetrics, measure: FlowMeasure): FlowGeometry = FlowLayouter(metrics, measure).layout(chart)

private class FlowLayouter(private val m: FlowMetrics, private val measure: FlowMeasure) {
    /** Whether the next card on an alternating spine goes left. */
    private var nextLeft = true
    private val back = mutableListOf<FlowShape>()
    private val lines = mutableListOf<FlowShape>()
    private val front = mutableListOf<FlowShape>()

    /**
     * Extents left and right of the spine, height, and how to place it with the spine at x and the top at y. The flow
     * goes on below an [open] block; a closed one ends in an exit.
     */
    private abstract class Block(val left: Int, val right: Int, val height: Int, val open: Boolean = true) {
        abstract fun place(x: Int, y: Int)
    }

    fun layout(chart: FlowChart): FlowGeometry {
        if (chart.lanes.isEmpty()) return FlowGeometry(Dimension(0, 0), emptyList())
        var x = m.margin
        var bottom = 0
        for (lane in chart.lanes) {
            nextLeft = true
            val body = sequence(blocks(listOf(lane.root), alternate = true, parent = null) + end())
            val bannerHeight = measure.banner(lane, m.bannerWidth)
            val spine = x + maxOf(body.left, m.bannerWidth / 2)
            front += FlowBanner(Rectangle(spine - m.bannerWidth / 2, m.margin, m.bannerWidth, bannerHeight), lane)
            val top = m.margin + bannerHeight + m.gap * 2
            body.place(spine, top)
            x = spine + maxOf(body.right, m.bannerWidth - m.bannerWidth / 2) + m.laneGap
            bottom = maxOf(bottom, top + body.height)
        }
        return FlowGeometry(Dimension(x - m.laneGap + m.margin, bottom + m.margin), back + lines + front)
    }

    private fun line(x1: Int, y1: Int, x2: Int, y2: Int, style: FlowLineStyle = FlowLineStyle.NORMAL) {
        if (x1 != x2 || y1 != y2) lines += FlowSegment(Point(x1, y1), Point(x2, y2), style)
    }

    /** Blocks of [items] inside the calls of [parent] (null at the top of a column). */
    private fun blocks(items: List<FlowItem>, alternate: Boolean, parent: Int?): List<Block> = items.flatMap { item ->
        when (item) {
            is FlowStep -> listOfNotNull(callers(item, parent), step(item, alternate), subflow(item, alternate))
            is FlowRef -> listOf(ref(item))
            is FlowExit -> listOf(exit(item))
            is FlowDecision -> listOf(decision(item, parent))
            is FlowLoop -> listOf(frame("↻ " + item.label, blocks(item.items, alternate, parent), FlowPillStyle.LOOP, null, loop = true))
            is FlowRegion -> listOfNotNull(region(item, alternate), if (item.expanded)
                frame("▾ ${item.plan.title}", blocks(item.items, alternate, parent), FlowPillStyle.SUBFLOW, null,
                    loop = false, regionKey = item.plan.evidence.key) else null)
        }
    }

    /**
     * The pill showing or hiding the direct callers of [step], with the open callers above it in columns that converge
     * into the function below; they are alternatives, not consecutive calls. A closed pill is left out when the only
     * caller is [parent], whose calls the step is drawn in.
     */
    private fun callers(step: FlowStep, parent: Int?): Block? {
        val upstream = step.upstream
        if (step.callers == 0 || (upstream == null && step.callers == 1 && parent != null)) return null
        val label = (if (upstream != null) "▾ " else "▸ ") + "${step.callers} caller${if (step.callers == 1) "" else "s"} of ${step.card.title.substringAfterLast('.')}"
        val width = pillWidth(label)
        val toggle = object : Block(width / 2, width - width / 2, m.pillHeight) {
            override fun place(x: Int, y: Int) {
                line(x, y, x, y + height)
                front += FlowPill(pillAt(x, y, width), label, FlowPillStyle.SUBFLOW, step.node, callers = true)
            }
        }
        if (upstream.isNullOrEmpty()) return toggle
        val columns = upstream.map { caller ->
            when (caller) {
                is FlowStep -> sequence(listOfNotNull(callers(caller, null), step(caller, alternate = false), subflow(caller, alternate = false)))
                is FlowRef -> ref(caller)
                else -> error("Callers are steps or references")
            }
        }
        var cursor = 0
        val centres = columns.map { column ->
            val centre = cursor + column.left
            cursor += column.left + column.right + m.branchGap
            centre
        }
        val spine = (centres.first() + centres.last()) / 2
        val total = cursor - m.branchGap
        val contentHeight = columns.maxOf { it.height }
        val height = contentHeight + m.gap
        val above = object : Block(spine, total - spine, height) {
            override fun place(x: Int, y: Int) {
                val xs = centres.map { x - spine + it }
                val merge = y + height
                columns.forEachIndexed { index, column ->
                    // Keep immediate callers level even when their upstream paths have different depths.
                    column.place(xs[index], y + contentHeight - column.height)
                    line(xs[index], y + contentHeight, xs[index], merge)
                }
                line(xs.first(), merge, xs.last(), merge)
            }
        }
        return sequence(listOf(above, toggle))
    }

    private fun sequence(blocks: List<Block>, style: FlowLineStyle = FlowLineStyle.NORMAL): Block {
        val height = blocks.sumOf { it.height } + m.gap * (blocks.size - 1).coerceAtLeast(0)
        return object : Block(blocks.maxOfOrNull { it.left } ?: 0, blocks.maxOfOrNull { it.right } ?: 0, height, blocks.lastOrNull()?.open ?: true) {
            override fun place(x: Int, y: Int) {
                var top = y
                blocks.forEachIndexed { index, block ->
                    block.place(x, top)
                    top += block.height
                    if (index < blocks.lastIndex && block.open) line(x, top, x, top + m.gap, style)
                    top += m.gap
                }
            }
        }
    }

    private fun step(step: FlowStep, alternate: Boolean, region: FlowRegion? = null): Block {
        val left = alternate && nextLeft
        if (alternate) nextLeft = !nextLeft
        val textHeight = measure.card(step, m.cardWidth)
        val height = maxOf(textHeight, measure.firstLine() + m.dotRadius)
        val wide = m.gutter + m.cardWidth
        return object : Block(if (left) wide else m.dotRadius, if (left) m.dotRadius else wide, height) {
            override fun place(x: Int, y: Int) {
                val dot = Point(x, y + measure.firstLine())
                line(x, y, x, y + height)
                val bounds = Rectangle(if (left) x - wide else x + m.gutter, y, m.cardWidth, textHeight)
                if (region == null) {
                    front += FlowDot(dot, step)
                    front += FlowCardBox(step, bounds, left, dot)
                } else front += FlowRegionBox(region, bounds, left, dot)
            }
        }
    }

    private fun region(region: FlowRegion, alternate: Boolean): Block =
        step(region.presentation(), alternate, region)

    /** The calls of [step] in a frame headed by a pill that hides them, or just a pill showing them while closed. */
    private fun subflow(step: FlowStep, alternate: Boolean): Block? {
        if (step.calls == 0) return null
        val name = step.card.title.substringAfterLast('.')
        if (step.items.isEmpty()) {
            val text = "▸ inside $name · ${step.calls} call${if (step.calls == 1) "" else "s"}"
            val width = pillWidth(text)
            return object : Block(width / 2, width - width / 2, m.pillHeight) {
                override fun place(x: Int, y: Int) {
                    line(x, y, x, y + height)
                    front += FlowPill(pillAt(x, y, width), text, FlowPillStyle.SUBFLOW, step.node)
                }
            }
        }
        return frame("▾ inside $name", blocks(step.items, alternate, step.node), FlowPillStyle.SUBFLOW, step.node, loop = false)
    }

    private fun pillWidth(text: String) = measure.text(text) + m.pillPadding * 2

    /** A pill centred on the spine at x with its top at y. */
    private fun pillAt(x: Int, y: Int, width: Int) = Rectangle(x - width / 2, y, width, m.pillHeight)

    /**
     * Content in a rounded frame whose top edge carries a pill on the spine. The flow goes on below the frame even when
     * its content ends in an exit: that leaves the function called, not the one framing it, and a loop may not run at all.
     */
    private fun frame(text: String, content: List<Block>, style: FlowPillStyle, toggle: Int?, loop: Boolean,
        regionKey: String? = null): Block {
        val pillWidth = pillWidth(text)
        val inner = sequence(content)
        val left = maxOf(inner.left, pillWidth / 2) + m.framePadding
        val right = maxOf(inner.right, pillWidth - pillWidth / 2) + m.framePadding
        val height = m.pillHeight + m.gap + inner.height + m.framePadding
        return object : Block(left, right, height) {
            override fun place(x: Int, y: Int) {
                back += FlowFrame(Rectangle(x - left, y + m.pillHeight / 2, left + right, height - m.pillHeight / 2), loop)
                line(x, y, x, y + m.pillHeight + m.gap)
                front += FlowPill(pillAt(x, y, pillWidth), text, style, toggle, regionKey = regionKey)
                inner.place(x, y + m.pillHeight + m.gap)
                if (inner.open) line(x, y + m.pillHeight + m.gap + inner.height, x, y + height)
            }
        }
    }

    private fun ref(ref: FlowRef): Block {
        val text = "↺ step ${ref.number} · ${ref.title}"
        val width = measure.text(text)
        val height = measure.line()
        return object : Block(m.dotRadius, m.gutter + width, height) {
            override fun place(x: Int, y: Int) {
                line(x, y, x, y + height)
                val dot = Point(x, y + height / 2)
                front += FlowRefBox(Rectangle(x + m.gutter, y, width, height), ref, dot)
            }
        }
    }

    /** A pill where the flow leaves the function; nothing joins below it. */
    private fun exit(exit: FlowExit): Block {
        val width = pillWidth(exit.keyword)
        val style = if (exit.keyword == "return") FlowPillStyle.EXIT else FlowPillStyle.THROW
        return object : Block(width / 2, width - width / 2, m.pillHeight, open = false) {
            override fun place(x: Int, y: Int) { front += FlowPill(pillAt(x, y, width), exit.keyword, style, tooltip = exit.text) }
        }
    }

    private fun end(): Block {
        val width = pillWidth("End")
        return object : Block(width / 2, width - width / 2, m.pillHeight) {
            override fun place(x: Int, y: Int) { front += FlowPill(pillAt(x, y, width), "End", FlowPillStyle.END) }
        }
    }

    private fun decision(decision: FlowDecision, parent: Int?): Block {
        val shapeWidth = (measure.text(decision.question) + m.pillPadding * 2 + m.decisionHeight).coerceAtMost(m.decisionMaxWidth)
        class Column(val branch: FlowBranch, val pillWidth: Int, val content: Block?) {
            val style = when (branch.kind) {
                BranchKind.CASE -> FlowLineStyle.NORMAL
                BranchKind.OTHERWISE -> FlowLineStyle.OTHERWISE
                BranchKind.ERROR -> FlowLineStyle.ERROR
            }
            val left = maxOf(pillWidth / 2, content?.left ?: 0)
            val right = maxOf(pillWidth - pillWidth / 2, content?.right ?: 0)
            val height = m.pillHeight + (content?.let { m.gap + it.height } ?: m.emptyBranch)
        }
        // Branches never alternate: their cards sit right of the branch line.
        val columns = decision.branches.map { branch ->
            val label = ControlText.clip(branch.label).ifEmpty { "…" }
            Column(branch.copy(label = label), pillWidth(label),
                branch.items.takeIf { it.isNotEmpty() }?.let { sequence(blocks(it, alternate = false, parent), FlowLineStyle.NORMAL) })
        }
        val centres = mutableListOf<Int>()
        var cursor = 0
        columns.forEach { column ->
            centres += cursor + column.left
            cursor += column.left + column.right + m.branchGap
        }
        // Branches that go on join below the decision; the spine runs through them, so a guard's exit stands aside.
        val going = columns.indices.filter { columns[it].content?.open != false }
        val through = going.ifEmpty { columns.indices.toList() }
        val spine = (centres[through.first()] + centres[through.last()]) / 2
        val total = cursor - m.branchGap
        val left = maxOf(spine, shapeWidth / 2)
        val right = maxOf(total - spine, shapeWidth - shapeWidth / 2)
        val columnsHeight = columns.maxOf { it.height }
        val bar = m.decisionHeight + m.gap
        val columnTop = bar + m.gap
        val merge = columnTop + columnsHeight + m.gap
        val height = merge + m.gap
        return object : Block(left, right, height, open = going.isNotEmpty()) {
            override fun place(x: Int, y: Int) {
                front += FlowDecisionBox(Rectangle(x - shapeWidth / 2, y, shapeWidth, m.decisionHeight), decision)
                line(x, y + m.decisionHeight, x, y + bar)
                val xs = centres.map { x - spine + it }
                line(xs.first(), y + bar, xs.last(), y + bar)
                if (going.isNotEmpty()) line(xs[going.first()], y + merge, xs[going.last()], y + merge)
                columns.forEachIndexed { index, column ->
                    val cx = xs[index]
                    val top = y + columnTop
                    val label = column.branch.label
                    front += FlowPill(pillAt(cx, top, column.pillWidth), label, when (column.branch.kind) {
                        BranchKind.CASE -> FlowPillStyle.CASE
                        BranchKind.OTHERWISE -> FlowPillStyle.OTHERWISE
                        BranchKind.ERROR -> FlowPillStyle.ERROR
                    })
                    val content = column.content
                    // Past a side exit the path that goes on is the main one, even through an `else`.
                    val style = if (going.size < columns.size && index in going) FlowLineStyle.NORMAL else column.style
                    if (content == null) {
                        line(cx, y + bar, cx, y + merge, style)
                    } else {
                        val start = top + m.pillHeight + m.gap
                        line(cx, y + bar, cx, start, style)
                        content.place(cx, start)
                        if (content.open) line(cx, start + content.height, cx, y + merge, style)
                    }
                }
                if (going.isNotEmpty()) line(x, y + merge, x, y + height)
            }
        }
    }
}
