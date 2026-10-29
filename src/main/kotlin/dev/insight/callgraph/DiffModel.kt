package dev.insight.callgraph

import com.intellij.ui.JBColor
import dev.insight.graph.CallGraph
import dev.insight.graph.DetailGraph
import dev.insight.graph.Emphasis
import dev.insight.graph.GraphDecorations
import dev.insight.graph.GraphHierarchy
import dev.insight.graph.LegendEntry
import dev.insight.graph.NodeDecoration
import dev.insight.graph.RelationKind
import dev.insight.graph.SymbolKind
import java.awt.Color

/**
 * One changed file of a VCS change set. [path] is absolute and system-independent (the new path for renames);
 * [hunks] are 1-based inclusive line ranges on the new side, like the Python reference (`src/diff/diff.py`).
 */
data class FileDiff(
    val path: String,
    val oldPath: String?,
    val status: Status,
    val added: Int,
    val removed: Int,
    val hunks: List<IntRange>,
) {
    enum class Status { ADDED, MODIFIED, DELETED, RENAMED }
}

/**
 * A change set mapped onto an application graph: [changed] symbols overlap a hunk, [neighborhood] adds their callers
 * and callees within the hop budget. Ids are node ids of [graph], which are also [GraphHierarchy] ids.
 */
data class DiffResult(
    val base: String,
    val files: List<FileDiff>,
    val changed: Set<Int>,
    val neighborhood: Set<Int>,
    val graph: CallGraph? = null,
) {
    val added: Int get() = files.sumOf { it.added }
    val removed: Int get() = files.sumOf { it.removed }
    val byPath: Map<String, FileDiff> by lazy { files.associateBy { it.path } }
}

/** A changed region reported by a line comparison: 0-based, end-exclusive lines on the old (1) and new (2) side. */
data class LineChange(val start1: Int, val end1: Int, val start2: Int, val end2: Int)

private fun normalizePath(path: String) = path.replace('\\', '/')

/** Lines as git counts them: a trailing newline does not start another line. */
fun lineCount(text: CharSequence): Int {
    if (text.isEmpty()) return 0
    val newlines = text.count { it == '\n' }
    return if (text.last() == '\n') newlines else newlines + 1
}

/**
 * New-side hunks for line changes, widened by [contextLines] like git's default unified context (`-U3`), clamped to
 * the file and merged when they touch. A pure deletion becomes a single anchor line, `max(start2, 1)` in 1-based
 * terms, matching git's `+N,0` hunk header. [newLineCount] is the line count of the new content.
 */
fun hunksOf(changes: List<LineChange>, newLineCount: Int, contextLines: Int = 3): List<IntRange> {
    val context = contextLines.coerceAtLeast(0)
    val last = newLineCount.coerceAtLeast(1)
    val ranges = changes.sortedBy { it.start2 }.map { change ->
        val deletion = change.end2 <= change.start2
        val (lo, hi) = when {
            deletion && context == 0 -> maxOf(change.start2, 1).let { it to it }
            else -> (change.start2 + 1 - context) to (maxOf(change.end2, change.start2) + context)
        }
        val start = lo.coerceIn(1, last)
        start..hi.coerceIn(start, last)
    }
    val merged = mutableListOf<IntRange>()
    for (range in ranges) {
        val previous = merged.lastOrNull()
        if (previous != null && range.first <= previous.last + 1) merged[merged.lastIndex] = previous.first..maxOf(previous.last, range.last)
        else merged += range
    }
    return merged
}

/** A [FileDiff] for a modified or renamed file from its line changes. */
fun fileDiffOf(path: String, oldPath: String?, changes: List<LineChange>, newLineCount: Int, contextLines: Int = 3): FileDiff =
    FileDiff(
        normalizePath(path), oldPath?.let(::normalizePath),
        if (oldPath != null && normalizePath(oldPath) != normalizePath(path)) FileDiff.Status.RENAMED else FileDiff.Status.MODIFIED,
        changes.sumOf { (it.end2 - it.start2).coerceAtLeast(0) }, changes.sumOf { (it.end1 - it.start1).coerceAtLeast(0) },
        hunksOf(changes, newLineCount, contextLines),
    )

/** A new file: one hunk over the whole file, as in git's `@@ -0,0 +1,N @@`. */
fun addedFileDiff(path: String, newLineCount: Int) =
    FileDiff(normalizePath(path), null, FileDiff.Status.ADDED, newLineCount, 0, listOf(1..newLineCount.coerceAtLeast(1)))

/** A removed file is listed but has no new-side lines, so it never maps onto a node. */
fun deletedFileDiff(path: String, oldLineCount: Int) =
    FileDiff(normalizePath(path), normalizePath(path), FileDiff.Status.DELETED, 0, oldLineCount, emptyList())

/**
 * Nodes whose file has a hunk and whose line range intersects it (`start <= hi && end >= lo`). Deleted files are
 * skipped. [nodeRanges] maps node ids to their file path and 1-based inclusive lines.
 */
fun mapChangesToNodes(diffs: List<FileDiff>, nodeRanges: Map<Int, Pair<String, IntRange>>): Set<Int> {
    val hunksByFile = HashMap<String, MutableList<IntRange>>()
    for (diff in diffs) {
        if (diff.status == FileDiff.Status.DELETED) continue
        hunksByFile.getOrPut(normalizePath(diff.path)) { mutableListOf() } += diff.hunks
    }
    val changed = linkedSetOf<Int>()
    for ((id, located) in nodeRanges) {
        val hunks = hunksByFile[normalizePath(located.first)] ?: continue
        val lines = located.second
        if (hunks.any { lines.first <= it.last && lines.last >= it.first }) changed += id
    }
    return changed
}

/**
 * Drops changed nodes that contain another changed node, so an edited method marks the method rather than its class
 * and file as well. [parent] is the containment parent of each id (-1 for roots), as in [GraphHierarchy.parent].
 */
fun keepInnermost(changed: Set<Int>, parent: IntArray): Set<Int> {
    val containers = hashSetOf<Int>()
    for (id in changed) {
        var current = parent.getOrElse(id) { -1 }
        while (current >= 0 && containers.add(current)) current = parent.getOrElse(current) { -1 }
    }
    return changed.filterTo(linkedSetOf()) { it !in containers }
}

/**
 * Port of the Python `prune_to_changed_neighborhood`: the changed nodes plus their transitive callers (up to
 * [maxUp] hops) and callees (up to [maxDown] hops), each walked as a multi-source breadth-first search over edges of
 * [kinds]. Null means unlimited, 0 keeps only the changed nodes in that direction. Without seeds in the graph every
 * node id is returned, as there is nothing to prune around.
 */
fun pruneToChangedNeighborhood(
    graph: CallGraph,
    changed: Set<Int>,
    maxUp: Int?,
    maxDown: Int?,
    kinds: Set<RelationKind> = setOf(RelationKind.CALL),
): Set<Int> {
    val ids = graph.nodes.mapTo(hashSetOf()) { it.id }
    val seeds = changed.filterTo(linkedSetOf()) { it in ids }
    if (seeds.isEmpty()) return ids
    val callees = HashMap<Int, MutableList<Int>>()
    val callers = HashMap<Int, MutableList<Int>>()
    for (edge in graph.edges) {
        if (edge.kind !in kinds || edge.caller !in ids || edge.callee !in ids) continue
        callees.getOrPut(edge.caller) { mutableListOf() } += edge.callee
        callers.getOrPut(edge.callee) { mutableListOf() } += edge.caller
    }
    fun walk(adjacency: Map<Int, List<Int>>, maxHops: Int?): Set<Int> {
        val distance = HashMap<Int, Int>().apply { seeds.forEach { put(it, 0) } }
        val frontier = ArrayDeque(seeds)
        while (frontier.isNotEmpty()) {
            val id = frontier.removeFirst()
            val hops = distance.getValue(id)
            if (maxHops != null && hops >= maxHops) continue
            for (next in adjacency[id].orEmpty()) {
                if (next !in distance) {
                    distance[next] = hops + 1
                    frontier.addLast(next)
                }
            }
        }
        return distance.keys
    }
    return walk(callers, maxUp) + walk(callees, maxDown)
}

/**
 * For "changed only": the neighborhood plus its enclosing classes and files (not directories), so the filtered view
 * keeps each symbol under its file. Hierarchy ids.
 */
fun changedOnlyNodes(tree: GraphHierarchy, diff: DiffResult): Set<Int> {
    val keep = HashSet(diff.neighborhood)
    for (id in diff.neighborhood) {
        if (id !in tree.nodes.indices) continue
        tree.ancestors(id).filterTo(keep) { tree.nodes[it].kind != SymbolKind.DIRECTORY }
    }
    return keep
}

/** Amber stripe and legend colour for changed symbols. */
val CHANGED_COLOR: Color = JBColor(Color(0xE0A100), Color(0xD9A62E))

/**
 * Overlays [diff] on [base] decorations of the displayed [view]: shown nodes holding changed symbols are emphasized
 * with a "Δ" badge (files with their +added −removed counts) and an amber stripe unless the lens already colours
 * them, nodes holding callers or callees of a change keep normal emphasis, and the rest turn faint.
 */
fun decorateDiff(base: GraphDecorations, view: DetailGraph, diff: DiffResult): GraphDecorations {
    val changedCounts = diff.changed.mapNotNull(view::shownId).groupingBy { it }.eachCount()
    val nearby = (diff.neighborhood - diff.changed).mapNotNullTo(hashSetOf(), view::shownId)
    val nodes = LinkedHashMap(base.nodes)
    for (node in view.graph.nodes) {
        val existing = base.nodes[node.id] ?: NodeDecoration()
        val count = changedCounts[node.id]
        val file = node.filePath?.takeIf { node.kind == SymbolKind.FILE }?.let(diff.byPath::get)
        nodes[node.id] = when {
            count != null -> {
                val badge = buildString {
                    append(if (node.hidden > 0 && count > 1) "Δ$count" else "Δ")
                    if (file != null) append(" +${file.added} −${file.removed}")
                }
                val line = if (count == 1) "Changed vs ${diff.base}" else "Changed vs ${diff.base}: $count symbols"
                existing.copy(
                    emphasis = Emphasis.STRONG,
                    badges = listOf(badge, existing.badges).filter { it.isNotEmpty() }.joinToString(" "),
                    colors = existing.colors.ifEmpty { listOf(CHANGED_COLOR to 1.0) },
                    tooltip = listOfNotNull(line, existing.tooltip).joinToString("\n"),
                )
            }
            node.id in nearby -> existing.copy(
                emphasis = if (existing.emphasis == Emphasis.FAINT) Emphasis.NORMAL else existing.emphasis,
                tooltip = listOfNotNull("Calls or is called by changed code", existing.tooltip).joinToString("\n"),
            )
            else -> existing.copy(emphasis = Emphasis.FAINT)
        }
    }
    return base.copy(nodes = nodes, legend = base.legend + LegendEntry("Δ changed vs ${diff.base}", CHANGED_COLOR))
}
