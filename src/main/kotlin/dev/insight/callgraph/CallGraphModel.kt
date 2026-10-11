package dev.insight.callgraph

enum class CallDirection(val label: String) {
    BOTH("Callers and callees"), CALLERS("Callers"), CALLEES("Callees");
    override fun toString() = label
}

/** [level] is 0 for the root, negative for callers and positive for callees. */
data class CallGraphNode(
    val id: Int,
    val name: String,
    val container: String,
    val location: String,
    val level: Int,
    val inProject: Boolean,
)

data class CallGraphEdge(val caller: Int, val callee: Int)

data class CallGraph(val nodes: List<CallGraphNode>, val edges: Set<CallGraphEdge>, val truncated: Boolean) {
    val root: CallGraphNode get() = nodes.first()
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
