package dev.insight.callgraph

import java.awt.Dimension
import java.awt.Rectangle

data class LayoutMetrics(val nodeWidth: Int, val nodeHeight: Int, val columnGap: Int, val rowGap: Int, val margin: Int)

/** [sections] is set by [layoutSections] only. */
data class GraphLayout(val boxes: Map<Int, Rectangle>, val size: Dimension, val sections: SectionFrames? = null)

/**
 * Layered layout: callers to the left of the root, callees to the right, one column per level.
 * Columns are ordered by the average row of their neighbours closer to the root (on the same side) to reduce crossings.
 */
fun layoutCallGraph(graph: CallGraph, metrics: LayoutMetrics): GraphLayout {
    if (graph.nodes.isEmpty()) return GraphLayout(emptyMap(), Dimension(0, 0))
    val columns = graph.nodes.groupBy { it.level }.mapValues { (_, nodes) -> nodes.map { it.id }.toMutableList() }
    val neighbours = HashMap<Int, MutableList<Int>>()
    for (edge in graph.edges) {
        neighbours.getOrPut(edge.caller) { mutableListOf() } += edge.callee
        neighbours.getOrPut(edge.callee) { mutableListOf() } += edge.caller
    }
    val levelOf = graph.nodes.associate { it.id to it.level }
    val row = HashMap<Int, Int>()
    columns[0]?.forEachIndexed { i, id -> row[id] = i }
    val minLevel = columns.keys.min()
    val maxLevel = columns.keys.max()
    for (level in (-1 downTo minLevel) + (1..maxLevel)) {
        val column = columns[level] ?: continue
        val inner = if (level < 0) level + 1..0 else 0 until level
        val rank = column.associateWith { id ->
            neighbours[id].orEmpty().filter { levelOf.getValue(it) in inner }.mapNotNull { row[it] }.average().takeUnless { it.isNaN() }
                ?: Double.MAX_VALUE
        }
        column.sortBy { rank.getValue(it) }
        column.forEachIndexed { i, id -> row[id] = i }
    }

    val tallest = columns.values.maxOf { it.size }
    val rowHeight = metrics.nodeHeight + metrics.rowGap
    val boxes = HashMap<Int, Rectangle>()
    for ((level, column) in columns) {
        val x = metrics.margin + (level - minLevel) * (metrics.nodeWidth + metrics.columnGap)
        val top = metrics.margin + (tallest - column.size) * rowHeight / 2
        column.forEachIndexed { i, id -> boxes[id] = Rectangle(x, top + i * rowHeight, metrics.nodeWidth, metrics.nodeHeight) }
    }
    val levels = maxLevel - minLevel + 1
    val width = 2 * metrics.margin + levels * metrics.nodeWidth + (levels - 1) * metrics.columnGap
    val height = 2 * metrics.margin + tallest * rowHeight - metrics.rowGap
    return GraphLayout(boxes, Dimension(width, height))
}
