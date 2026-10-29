package dev.insight.callgraph

import dev.insight.graph.CallGraph
import dev.insight.graph.GraphSections
import dev.insight.graph.Section
import dev.insight.graph.SectionFooter
import java.awt.Dimension
import java.awt.Rectangle

/** How the canvas arranges the displayed graph. */
enum class GraphView(val label: String) {
    GRAPH("Graph"), SECTIONS("Sections");
    override fun toString() = label
}

data class SectionMetrics(
    val nodeWidth: Int,
    val nodeHeight: Int,
    /** Gap between node columns inside a section. */
    val columnGap: Int,
    val rowGap: Int,
    /** Space between a section's border and its nodes; edges between sections bend inside it. */
    val padding: Int,
    val sectionGap: Int,
    val margin: Int,
    /** A column holding more nodes wraps into further columns. */
    val maxRows: Int,
)

/** Heights of the text blocks, measured by the canvas with its fonts; [width] is the space available. */
interface SectionText {
    fun banner(sections: GraphSections, width: Int): Int
    fun title(): Int
    fun annotation(section: Section, width: Int): Int
    fun footer(footer: SectionFooter, width: Int): Int
}

/**
 * Rectangles of the section chrome. [columns] span a section from its title band to the bottom of its nodes;
 * the title band is [titleHeight] tall and the annotation below it [annotationHeight], equal for all sections.
 */
data class SectionFrames(
    val banner: Rectangle,
    val columns: List<Rectangle>,
    val titleHeight: Int,
    val annotationHeight: Int,
    val footer: Rectangle?,
)

/**
 * Places each section in its own column, left to right in [GraphSections.sections] order. Inside a section, nodes
 * form sub-columns by [CallGraphNode.level] (users left of what they use), each ordered by the average row of
 * neighbours placed before it to reduce crossings. Columns are centred vertically and all sections share one height,
 * so the nodes line up across sections.
 */
fun layoutSections(graph: CallGraph, sections: GraphSections, metrics: SectionMetrics, text: SectionText): GraphLayout {
    val members = List(sections.sections.size) { mutableListOf<Int>() }
    for (node in graph.nodes) sections.sectionOf[node.id]?.let { members.getOrNull(it)?.add(node.id) }
    val levelOf = graph.nodes.associate { it.id to it.level }
    val columns = members.map { ids ->
        ids.groupBy { levelOf.getValue(it) }.toSortedMap().values.flatMap { it.chunked(metrics.maxRows.coerceAtLeast(1)) }
            .map { it.toMutableList() }
    }
    val tallest = columns.flatten().maxOfOrNull { it.size } ?: 0

    val neighbours = HashMap<Int, MutableList<Int>>()
    for (edge in graph.edges) {
        if (edge.caller == edge.callee) continue
        neighbours.getOrPut(edge.caller) { mutableListOf() } += edge.callee
        neighbours.getOrPut(edge.callee) { mutableListOf() } += edge.caller
    }
    val row = HashMap<Int, Double>()
    for (column in columns.flatten()) {
        val rank = column.withIndex().associate { (index, id) ->
            id to (neighbours[id].orEmpty().mapNotNull { row[it] }.average().takeUnless { it.isNaN() } ?: (Double.MAX_VALUE / 2 + index))
        }
        column.sortBy { rank.getValue(it) }
        val top = (tallest - column.size) / 2.0
        column.forEachIndexed { i, id -> row[id] = top + i }
    }

    val minimum = metrics.nodeWidth + 2 * metrics.padding
    val widths = columns.map { maxOf(minimum, 2 * metrics.padding + it.size * metrics.nodeWidth + (it.size - 1).coerceAtLeast(0) * metrics.columnGap) }
    val contentWidth = widths.sum() + (widths.size - 1).coerceAtLeast(0) * metrics.sectionGap
    val bannerHeight = text.banner(sections, contentWidth)
    val titleHeight = text.title()
    val annotationHeight = sections.sections.zip(widths).maxOfOrNull { (section, width) -> text.annotation(section, width - metrics.padding) } ?: 0
    val top = metrics.margin + bannerHeight + metrics.sectionGap
    val bodyTop = top + titleHeight + annotationHeight
    val rowHeight = metrics.nodeHeight + metrics.rowGap
    val bodyHeight = 2 * metrics.padding + maxOf(1, tallest) * rowHeight - metrics.rowGap
    val bottom = bodyTop + bodyHeight

    val boxes = HashMap<Int, Rectangle>()
    val frames = ArrayList<Rectangle>()
    var x = metrics.margin
    for ((index, sectionColumns) in columns.withIndex()) {
        val width = widths[index]
        frames += Rectangle(x, top, width, bottom - top)
        val used = sectionColumns.size * metrics.nodeWidth + (sectionColumns.size - 1).coerceAtLeast(0) * metrics.columnGap
        var columnX = x + (width - used) / 2
        for (column in sectionColumns) {
            for (id in column) {
                val y = bodyTop + metrics.padding + (row.getValue(id) * rowHeight).toInt()
                boxes[id] = Rectangle(columnX, y, metrics.nodeWidth, metrics.nodeHeight)
            }
            columnX += metrics.nodeWidth + metrics.columnGap
        }
        x += width + metrics.sectionGap
    }
    val footer = sections.footer?.let { Rectangle(metrics.margin, bottom + metrics.sectionGap, contentWidth, text.footer(it, contentWidth)) }
    val height = (footer?.let { it.y + it.height } ?: bottom) + metrics.margin
    return GraphLayout(boxes, Dimension(contentWidth + 2 * metrics.margin, height),
        SectionFrames(Rectangle(metrics.margin, metrics.margin, contentWidth, bannerHeight), frames, titleHeight, annotationHeight, footer))
}
