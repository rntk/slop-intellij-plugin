package dev.insight.callgraph

enum class CallDirection(val label: String) {
    BOTH("Callers and callees"), CALLERS("Callers"), CALLEES("Callees");
    override fun toString() = label
}

enum class SymbolKind { FILE, CLASS, FUNCTION, PROPERTY, PARAMETER, VARIABLE, SYMBOL }
enum class RelationKind { CALL, CONTAINS, IMPORT, INHERITS, REFERENCE }

/** [level] is 0 for the root, negative for callers and positive for callees. */
data class CallGraphNode(
    val id: Int,
    val name: String,
    val container: String,
    val location: String,
    val level: Int,
    val inProject: Boolean,
    val kind: SymbolKind = SymbolKind.FUNCTION,
    val filePath: String? = null,
)

data class CallGraphEdge(val caller: Int, val callee: Int, val kind: RelationKind = RelationKind.CALL)

data class CallGraph(val nodes: List<CallGraphNode>, val edges: Set<CallGraphEdge>, val truncated: Boolean, val projectGraph: Boolean = false) {
    val root: CallGraphNode get() = nodes.first()
}

data class FileGraphHighlight(val owned: Set<Int>, val related: Set<Int>)

fun CallGraph.highlightFile(path: String?): FileGraphHighlight {
    val owned = nodes.filter { path != null && it.filePath == path }.mapTo(hashSetOf()) { it.id }
    val related = hashSetOf<Int>()
    edges.forEach { edge ->
        if (edge.caller in owned && edge.callee !in owned) related += edge.callee
        if (edge.callee in owned && edge.caller !in owned) related += edge.caller
    }
    return FileGraphHighlight(owned, related)
}

enum class GraphDetail(val label: String) {
    FILE("Files"), SYMBOL("Symbols");
    override fun toString() = label
}

/** [graph] node ids index [sources], the ids of the matching nodes in the graph it was derived from. */
data class DetailGraph(val graph: CallGraph, val sources: List<Int>)

/** When several relationships connect two files, the edge shows the first kind in this order. */
private val fileRelationPriority = listOf(RelationKind.INHERITS, RelationKind.CALL, RelationKind.REFERENCE, RelationKind.IMPORT)

fun CallGraph.atDetail(detail: GraphDetail): DetailGraph = when (detail) {
    GraphDetail.SYMBOL -> DetailGraph(this, nodes.map { it.id })
    GraphDetail.FILE -> fileGraph()
}

/**
 * Collapses symbols into their files: one node per FILE node, and one edge per pair of distinct files
 * with any relationship between their symbols. Users are placed left of the files they depend on.
 */
private fun CallGraph.fileGraph(): DetailGraph {
    val files = nodes.filter { it.kind == SymbolKind.FILE }
    val indexByPath = files.withIndex().associate { (index, node) -> node.filePath to index }
    val fileOf = nodes.associate { it.id to indexByPath[it.filePath] }
    val merged = LinkedHashMap<Pair<Int, Int>, RelationKind>()
    for (edge in edges) {
        if (edge.kind == RelationKind.CONTAINS) continue
        val from = fileOf[edge.caller] ?: continue
        val to = fileOf[edge.callee] ?: continue
        if (from == to) continue
        merged.merge(from to to, edge.kind) { a, b -> minOf(a, b, compareBy(fileRelationPriority::indexOf)) }
    }
    // Longest chain of users above each file; edges closing a cycle are ignored.
    val users = merged.keys.groupBy({ it.second }, { it.first })
    val levels = IntArray(files.size) { -1 }
    val visiting = BooleanArray(files.size)
    fun level(file: Int): Int {
        if (levels[file] >= 0) return levels[file]
        visiting[file] = true
        levels[file] = users[file].orEmpty().filterNot { visiting[it] }.maxOfOrNull { level(it) + 1 } ?: 0
        visiting[file] = false
        return levels[file]
    }
    val fileNodes = files.mapIndexed { index, node -> node.copy(id = index, level = level(index)) }
    val fileEdges = merged.mapTo(linkedSetOf()) { (pair, kind) -> CallGraphEdge(pair.first, pair.second, kind) }
    return DetailGraph(copy(nodes = fileNodes, edges = fileEdges), files.map { it.id })
}

data class Exploration<T>(val items: List<T>, val levels: List<Int>, val edges: Set<CallGraphEdge>, val truncated: Boolean)

/**
 * Breadth-first exploration that is independent of PSI so it can be unit tested.
 * Items are compared with equals(); only items accepted by [expandable] (and the root) are expanded.
 */
suspend fun <T : Any> exploreCalls(
    root: T,
    direction: CallDirection,
    maxDepth: Int,
    maxNodes: Int,
    callers: suspend (T) -> List<T>,
    callees: suspend (T) -> List<T>,
    expandable: suspend (T) -> Boolean = { true },
): Exploration<T> {
    require(maxDepth > 0) { "Depth must be positive" }
    require(maxNodes > 0) { "Node limit must be positive" }
    val ids = LinkedHashMap<T, Int>().apply { put(root, 0) }
    val levels = mutableListOf(0)
    val edges = LinkedHashSet<CallGraphEdge>()
    var truncated = false

    suspend fun walk(sign: Int, neighbours: suspend (T) -> List<T>) {
        val visited = hashSetOf(root)
        var frontier = listOf(root)
        for (depth in 1..maxDepth) {
            val next = mutableListOf<T>()
            for (item in frontier) {
                if (item != root && !expandable(item)) continue
                val from = ids.getValue(item)
                for (neighbour in neighbours(item)) {
                    val to = ids[neighbour] ?: if (ids.size >= maxNodes) {
                        truncated = true
                        continue
                    } else {
                        ids.size.also { ids[neighbour] = it; levels += sign * depth }
                    }
                    // A node first reached from the other side keeps its level but is still expanded in this direction.
                    if (visited.add(neighbour)) next += neighbour
                    edges += if (sign < 0) CallGraphEdge(to, from) else CallGraphEdge(from, to)
                }
            }
            frontier = next
        }
    }

    if (direction != CallDirection.CALLEES) walk(-1, callers)
    if (direction != CallDirection.CALLERS) walk(1, callees)
    return Exploration(ids.keys.toList(), levels, edges, truncated)
}
