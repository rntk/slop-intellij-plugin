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
        assertEquals(listOf(1, 4, 5), chain.nodes)
        assertEquals(mapOf(1 to 0, 4 to 1, 5 to 1), chain.depths)
        assertEquals(setOf(CallGraphEdge(1, 4), CallGraphEdge(1, 5), CallGraphEdge(4, 5), CallGraphEdge(5, 1)), chain.edges)
        // No caller, same-name function, uncalled local function, import or reference is included.
        assertFalse(chain.nodes.any { it in setOf(0, 2, 3, 6, 7) })
    }

    @Test fun `file and synthetic folder seed their functions without including other files functions`() {
        val tree = graph.hierarchy()
        val file = tree.sourceCallChain(0)
        assertEquals(listOf(1, 2, 7, 4, 5), file.nodes)
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
        val chain = graph.hierarchy().sourceCallChain(0)
        assertEquals(size, chain.nodes.size)
        assertEquals(size - 1, chain.depths[size - 1])
    }
}
