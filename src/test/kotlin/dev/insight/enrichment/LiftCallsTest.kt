package dev.insight.enrichment

import dev.insight.graph.CallGraph
import dev.insight.graph.CallGraphEdge
import dev.insight.graph.CallGraphNode
import dev.insight.graph.RelationKind
import dev.insight.graph.SymbolKind
import org.junit.Assert.assertEquals
import org.junit.Test

class LiftCallsTest {
    private fun node(id: Int, kind: SymbolKind) = CallGraphNode(id, "n$id", "", "", 0, true, kind)

    @Test
    fun `calls from locals move to the enclosing function`() {
        val nodes = listOf(node(0, SymbolKind.FUNCTION), node(1, SymbolKind.VARIABLE), node(2, SymbolKind.VARIABLE), node(3, SymbolKind.FUNCTION))
        val graph = CallGraph(nodes, setOf(
            CallGraphEdge(0, 1, RelationKind.CONTAINS), CallGraphEdge(1, 2, RelationKind.CONTAINS),
            CallGraphEdge(2, 3), CallGraphEdge(1, 0), CallGraphEdge(0, 3), CallGraphEdge(1, 3, RelationKind.REFERENCE),
        ), false)
        val lifted = liftCalls(graph).edges
        // The nested local's call becomes the function's own call (deduplicated); a recursive call via a local is dropped.
        assertEquals(setOf(CallGraphEdge(0, 1, RelationKind.CONTAINS), CallGraphEdge(1, 2, RelationKind.CONTAINS),
            CallGraphEdge(0, 3), CallGraphEdge(1, 3, RelationKind.REFERENCE)), lifted)
    }
}
