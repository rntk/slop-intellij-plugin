package dev.insight.callgraph

/** Original symbol ids in breadth-first call order; never ids from a folded or filtered view. */
data class SourceCallChain(val nodes: List<Int>, val depths: Map<Int, Int>, val edges: Set<CallGraphEdge>)

fun GraphHierarchy.sourceCallChain(source: Int): SourceCallChain {
    if (source !in nodes.indices) return SourceCallChain(emptyList(), emptyMap(), emptySet())
    val calls = graph.edges.filter { it.kind == RelationKind.CALL }.groupBy { it.caller }
    val seeds = linkedSetOf<Int>()
    if (nodes[source].kind in setOf(SymbolKind.DIRECTORY, SymbolKind.FILE, SymbolKind.CLASS)) {
        val pending = ArrayDeque<Int>().apply { add(source) }
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (nodes[id].kind == SymbolKind.FUNCTION || calls.containsKey(id)) seeds += id
            pending.addAll(children[id])
        }
    } else seeds += source
    val depths = linkedMapOf<Int, Int>()
    val pending = ArrayDeque<Int>()
    seeds.forEach { depths[it] = 0; pending.add(it) }
    val edges = linkedSetOf<CallGraphEdge>()
    while (pending.isNotEmpty()) {
        val id = pending.removeFirst()
        for (edge in calls[id].orEmpty()) {
            edges += edge
            if (edge.callee !in depths) {
                depths[edge.callee] = depths.getValue(id) + 1
                pending.add(edge.callee)
            }
        }
    }
    return SourceCallChain(depths.keys.toList(), depths, edges)
}
