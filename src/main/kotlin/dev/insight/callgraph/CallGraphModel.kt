package dev.insight.callgraph

enum class CallDirection(val label: String) {
    BOTH("Callers and callees"), CALLERS("Callers"), CALLEES("Callees");
    override fun toString() = label
}

enum class SymbolKind { DIRECTORY, FILE, CLASS, FUNCTION, PROPERTY, PARAMETER, VARIABLE, SYMBOL }
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
    /** Number of descendants folded into a collapsed group of a [DetailGraph]; 0 otherwise. */
    val hidden: Int = 0,
    /** True for an expanded group of a [DetailGraph]; its members are shown next to it. */
    val expanded: Boolean = false,
    /** This node represents an unresolved source occurrence, rather than a declaration. */
    val unresolved: Boolean = false,
    /** Bounded declaration text for external boundary cards; their bodies are never traversed. */
    val signature: String? = null,
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
