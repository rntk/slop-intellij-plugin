package dev.insight.callgraph

/** How many callers and how many called functions the side-by-side view opens at first, and adds per request. */
const val SIDE_BY_SIDE_LIMIT = 3

/**
 * Original symbol ids in reading order; never ids from a folded or filtered view.
 * Callers come first (farthest caller first, negative depths), then the source's own functions (depth 0),
 * then what they call in breadth-first order (positive depths). A function appears once: one that both calls
 * and is called by the source (a cycle) is placed with the callees. [edges] are the calls between shown functions.
 */
data class SourceCallChain(val nodes: List<Int>, val depths: Map<Int, Int>, val edges: Set<CallGraphEdge>)

/** Direct call relationships of a graph, for building and growing [SourceCallChain]s. */
class CallIndex(graph: CallGraph) {
    private val calls = graph.edges.filter { it.kind == RelationKind.CALL }
    private val callees = calls.groupBy({ it.caller }, { it.callee })
    private val callers = calls.groupBy({ it.callee }, { it.caller })

    fun callees(id: Int): List<Int> = callees[id].orEmpty()
    fun callers(id: Int): List<Int> = callers[id].orEmpty()

    fun edgesAmong(nodes: Collection<Int>): Set<CallGraphEdge> {
        val shown = nodes.toHashSet()
        return calls.filterTo(linkedSetOf()) { it.caller in shown && it.callee in shown }
    }
}

/**
 * The nearest [limit] callers and [limit] called functions of [source], breadth first.
 * [direction] leaves out callers or called functions entirely.
 */
fun GraphHierarchy.sourceCallChain(
    source: Int,
    limit: Int = SIDE_BY_SIDE_LIMIT,
    direction: CallDirection = CallDirection.BOTH,
    index: CallIndex = CallIndex(graph),
): SourceCallChain {
    if (source !in nodes.indices) return SourceCallChain(emptyList(), emptyMap(), emptySet())
    val seeds = linkedSetOf<Int>()
    if (nodes[source].kind in setOf(SymbolKind.DIRECTORY, SymbolKind.FILE, SymbolKind.CLASS)) {
        val pending = ArrayDeque<Int>().apply { add(source) }
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (nodes[id].kind == SymbolKind.FUNCTION || index.callees(id).isNotEmpty()) seeds += id
            pending.addAll(children[id])
        }
    } else seeds += source
    /** Functions in [skip] are walked through but neither shown nor counted towards [limit]. */
    fun walk(next: (Int) -> List<Int>, step: Int, skip: Set<Int>): Map<Int, Int> {
        val depths = linkedMapOf<Int, Int>()
        val visited = seeds.associateWithTo(hashMapOf()) { 0 }
        val pending = ArrayDeque(seeds)
        while (pending.isNotEmpty() && depths.size < limit) {
            val id = pending.removeFirst()
            for (other in next(id)) {
                if (other in visited) continue
                val depth = visited.getValue(id) + step
                visited[other] = depth
                pending.add(other)
                if (other !in skip) depths[other] = depth
                if (depths.size >= limit) break
            }
        }
        return depths
    }
    val callees = if (direction == CallDirection.CALLERS) emptyMap() else walk(index::callees, 1, seeds)
    // Callers are followed through callees too: a caller of a recursive callee is still a transitive caller.
    val upstream = if (direction == CallDirection.CALLEES) emptyMap() else walk(index::callers, -1, seeds + callees.keys)
    val depths = linkedMapOf<Int, Int>()
    upstream.entries.sortedBy { it.value }.forEach { depths[it.key] = it.value }
    seeds.forEach { depths[it] = 0 }
    depths.putAll(callees)
    return SourceCallChain(depths.keys.toList(), depths, index.edgesAmong(depths.keys))
}

/** Adds up to [limit] direct callers (left of [id]) and/or functions it calls (right of [id]) that are not shown yet. */
fun SourceCallChain.expand(index: CallIndex, id: Int, direction: CallDirection, limit: Int = SIDE_BY_SIDE_LIMIT): SourceCallChain {
    val depth = depths[id] ?: return this
    var chain = this
    if (direction != CallDirection.CALLEES) chain = chain.insert(index, hidden(index, id, CallDirection.CALLERS).take(limit), id, depth - 1)
    if (direction != CallDirection.CALLERS) chain = chain.insert(index, hidden(index, id, CallDirection.CALLEES).take(limit), id, depth + 1)
    return chain
}

/** Shows [id] next to [near]: left of it when it calls [near], otherwise right of it. */
fun SourceCallChain.add(index: CallIndex, id: Int, near: Int): SourceCallChain {
    if (id in depths) return this
    val depth = depths[near] ?: return this
    return if (near in index.callees(id)) insert(index, listOf(id), near, depth - 1) else insert(index, listOf(id), near, depth + 1)
}

fun SourceCallChain.remove(index: CallIndex, id: Int): SourceCallChain {
    if (id !in depths || nodes.size <= 1) return this
    val left = nodes - id
    return SourceCallChain(left, depths - id, index.edgesAmong(left))
}

/** Direct callers or called functions of [id] that the chain does not show. */
fun SourceCallChain.hidden(index: CallIndex, id: Int, direction: CallDirection): List<Int> {
    val neighbours = when (direction) {
        CallDirection.CALLERS -> index.callers(id)
        CallDirection.CALLEES -> index.callees(id)
        CallDirection.BOTH -> index.callers(id) + index.callees(id)
    }
    return neighbours.filter { it !in depths }.distinct()
}

/** Callers ([depth] below the anchor's) go directly left of [anchor]; everything else directly right of it. */
private fun SourceCallChain.insert(index: CallIndex, ids: List<Int>, anchor: Int, depth: Int): SourceCallChain {
    val added = ids.filter { it !in depths }
    if (added.isEmpty()) return this
    val at = nodes.indexOf(anchor) + if (depth < depths.getValue(anchor)) 0 else 1
    val shown = nodes.subList(0, at) + added + nodes.subList(at, nodes.size)
    return SourceCallChain(shown, depths + added.associateWith { depth }, index.edgesAmong(shown))
}
