package dev.insight

import dev.insight.callgraph.*
import org.junit.Assert.*
import org.junit.Test

class SourceCallChainTest {
    private val graph = CallGraph(
        listOf(
            CallGraphNode(0, "A.kt", "", "", 0, true, SymbolKind.FILE, "/src/a/A.kt"),
            CallGraphNode(1, "entry", "", "", 0, true, filePath = "/src/a/A.kt"),
            CallGraphNode(2, "other", "", "", 0, true, filePath = "/src/a/A.kt"),
            CallGraphNode(3, "B.kt", "", "", 0, true, SymbolKind.FILE, "/src/b/B.kt"),
            CallGraphNode(4, "branch", "", "", 0, true, filePath = "/src/b/B.kt"),
            CallGraphNode(5, "shared", "", "", 0, true, filePath = "/src/b/B.kt"),
            CallGraphNode(6, "entry", "", "", 0, true, filePath = "/src/b/B.kt"),
            CallGraphNode(7, "local", "", "", 0, true, filePath = "/src/a/A.kt"),
        ), linkedSetOf(
            CallGraphEdge(0, 1, RelationKind.CONTAINS), CallGraphEdge(0, 2, RelationKind.CONTAINS),
            CallGraphEdge(3, 4, RelationKind.CONTAINS), CallGraphEdge(3, 5, RelationKind.CONTAINS),
            CallGraphEdge(3, 6, RelationKind.CONTAINS), CallGraphEdge(1, 7, RelationKind.CONTAINS),
            CallGraphEdge(1, 4), CallGraphEdge(1, 5), CallGraphEdge(4, 5), CallGraphEdge(5, 1),
            CallGraphEdge(6, 1), CallGraphEdge(2, 6, RelationKind.REFERENCE), CallGraphEdge(0, 3, RelationKind.IMPORT),
        ), false, true,
    )

    @Test fun `exact node follows all outgoing branches once and preserves cycle and shared edges`() {
        val chain = graph.hierarchy().sourceCallChain(1)
        assertEquals(listOf(6, 1, 4, 5), chain.nodes)
        assertEquals(mapOf(6 to -1, 1 to 0, 4 to 1, 5 to 1), chain.depths)
        assertEquals(setOf(CallGraphEdge(1, 4), CallGraphEdge(1, 5), CallGraphEdge(4, 5), CallGraphEdge(5, 1), CallGraphEdge(6, 1)), chain.edges)
        // The recursive caller 5 stays with the callees; no same-name function, uncalled local function, import or reference is included.
        assertFalse(chain.nodes.any { it in setOf(0, 2, 3, 7) })
    }

    @Test fun `callers are transitive, farthest first, and placed before the source`() {
        val graph = CallGraph((0..4).map { CallGraphNode(it, "f$it", "", "", 0, true) },
            linkedSetOf(CallGraphEdge(0, 1), CallGraphEdge(1, 2), CallGraphEdge(3, 2), CallGraphEdge(2, 4)), false)
        val chain = graph.hierarchy().sourceCallChain(2)
        assertEquals(listOf(0, 1, 3, 2, 4), chain.nodes)
        assertEquals(mapOf(0 to -2, 1 to -1, 3 to -1, 2 to 0, 4 to 1), chain.depths)
        assertEquals(graph.edges, chain.edges)
    }

    @Test fun `file and synthetic folder seed their functions without including other files functions`() {
        val tree = graph.hierarchy()
        val file = tree.sourceCallChain(0)
        assertEquals(listOf(6, 1, 2, 7, 4, 5), file.nodes)
        val folder = tree.nodes.single { it.kind == SymbolKind.DIRECTORY && it.name == "a/" }.id
        assertEquals(file, tree.sourceCallChain(folder))
    }

    @Test fun `leaf function includes itself and invalid source is empty`() {
        assertEquals(listOf(7), graph.hierarchy().sourceCallChain(7).nodes)
        assertTrue(graph.hierarchy().sourceCallChain(-1).nodes.isEmpty())
    }

    @Test fun `long call chains are complete and do not recurse on the stack`() {
        val size = 10_000
        val graph = CallGraph((0 until size).map { CallGraphNode(it, "f$it", "", "", 0, true) },
            (1 until size).mapTo(linkedSetOf()) { CallGraphEdge(it - 1, it) }, false)
        val chain = graph.hierarchy().sourceCallChain(0, Int.MAX_VALUE)
        assertEquals(size, chain.nodes.size)
        assertEquals(size - 1, chain.depths[size - 1])
        val callers = graph.hierarchy().sourceCallChain(size - 1, Int.MAX_VALUE)
        assertEquals((0 until size).toList(), callers.nodes)
        assertEquals(-(size - 1), callers.depths[0])
    }

    @Test fun `only the nearest callers and called functions are opened`() {
        // 5 is called by 0..4 and calls 6..10, which call 11..15.
        val edges = linkedSetOf<CallGraphEdge>()
        (0..4).forEach { edges += CallGraphEdge(it, 5) }
        (6..10).forEach { edges += CallGraphEdge(5, it); edges += CallGraphEdge(it, it + 5) }
        val graph = CallGraph((0..15).map { CallGraphNode(it, "f$it", "", "", 0, true) }, edges, false)
        val index = CallIndex(graph)
        val chain = graph.hierarchy().sourceCallChain(5, limit = 3, index = index)
        assertEquals(listOf(0, 1, 2, 5, 6, 7, 8), chain.nodes)
        assertEquals(index.edgesAmong(chain.nodes), chain.edges)
        assertEquals(listOf(3, 4), chain.hidden(index, 5, CallDirection.CALLERS))
        assertEquals(listOf(9, 10), chain.hidden(index, 5, CallDirection.CALLEES))
        assertEquals(listOf(5, 6, 7, 8), graph.hierarchy().sourceCallChain(5, limit = 3, direction = CallDirection.CALLEES).nodes)
        assertEquals(listOf(0, 1, 2, 5), graph.hierarchy().sourceCallChain(5, limit = 3, direction = CallDirection.CALLERS).nodes)

        // More callers go directly left of the function, more callees directly right of it.
        val more = chain.expand(index, 5, CallDirection.BOTH)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 9, 10, 6, 7, 8), more.nodes)
        assertEquals(-1, more.depths[3])
        assertEquals(1, more.depths[9])
        assertTrue(more.hidden(index, 5, CallDirection.BOTH).isEmpty())
        val deeper = chain.expand(index, 6, CallDirection.CALLEES)
        assertEquals(listOf(0, 1, 2, 5, 6, 11, 7, 8), deeper.nodes)
        assertEquals(2, deeper.depths[11])
        assertTrue(CallGraphEdge(6, 11) in deeper.edges)

        // A function shown on request sits left of a column it calls, otherwise right of it.
        assertEquals(listOf(0, 1, 2, 5, 6, 12, 7, 8), chain.add(index, 12, 6).nodes)
        assertEquals(listOf(0, 1, 2, 4, 5, 6, 7, 8), chain.add(index, 4, 5).nodes)
        assertSame(chain, chain.add(index, 6, 5))

        val closed = deeper.remove(index, 6)
        assertEquals(listOf(0, 1, 2, 5, 11, 7, 8), closed.nodes)
        assertFalse(closed.edges.any { it.caller == 6 || it.callee == 6 })
        val single = graph.hierarchy().sourceCallChain(15, limit = 0)
        assertSame(single, single.remove(index, 15))
    }
}
