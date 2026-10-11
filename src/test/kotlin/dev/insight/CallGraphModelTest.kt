package dev.insight

import dev.insight.callgraph.CallDirection
import dev.insight.callgraph.CallGraph
import dev.insight.callgraph.CallGraphEdge
import dev.insight.callgraph.CallGraphNode
import dev.insight.callgraph.LayoutMetrics
import dev.insight.callgraph.exploreCalls
import dev.insight.callgraph.layoutCallGraph
import dev.insight.callgraph.highlightFile
import dev.insight.callgraph.atDetail
import dev.insight.callgraph.GraphDetail
import dev.insight.callgraph.RelationKind
import dev.insight.callgraph.SymbolKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallGraphModelTest {
    // main -> a -> b -> c, test -> a, b -> a (cycle), a -> println (library)
    private val calls = mapOf(
        "main" to listOf("a"), "test" to listOf("a"), "a" to listOf("b", "println"),
        "b" to listOf("c", "a"), "c" to emptyList(), "println" to emptyList(),
    )
    private val callers = calls.keys.associateWith { name -> calls.filterValues { name in it }.keys.toList() }

    private fun explore(root: String, direction: CallDirection, depth: Int = 3, maxNodes: Int = 100) = runBlocking {
        exploreCalls(root, direction, depth, maxNodes, { callers.getValue(it) }, { calls.getValue(it) }, { it != "println" })
    }

    private fun edgeNames(items: List<String>, edges: Set<CallGraphEdge>) =
        edges.map { items[it.caller] to items[it.callee] }.toSet()

    @Test fun `callees are positive levels with caller to callee edges`() {
        val result = explore("a", CallDirection.CALLEES)
        assertEquals(listOf("a", "b", "println", "c"), result.items)
        assertEquals(listOf(0, 1, 1, 2), result.levels)
        assertEquals(setOf("a" to "b", "a" to "println", "b" to "c", "b" to "a"), edgeNames(result.items, result.edges))
    }

    @Test fun `callers are negative levels and keep call direction`() {
        val result = explore("a", CallDirection.CALLERS, depth = 1)
        assertEquals(listOf("a", "main", "test", "b"), result.items)
        assertEquals(listOf(0, -1, -1, -1), result.levels)
        assertTrue(("main" to "a") in edgeNames(result.items, result.edges))
    }

    @Test fun `both directions share nodes reached from either side`() {
        val result = explore("a", CallDirection.BOTH, depth = 1)
        assertEquals(1, result.items.count { it == "b" })
        assertEquals(-1, result.levels[result.items.indexOf("b")])
        assertTrue(("a" to "b") in edgeNames(result.items, result.edges))
        assertTrue(("b" to "a") in edgeNames(result.items, result.edges))
    }

    @Test fun `node reached as a caller is still expanded as a callee`() {
        val result = explore("a", CallDirection.BOTH, depth = 2)
        assertTrue(("b" to "c") in edgeNames(result.items, result.edges))
    }

    @Test fun `node limit truncates exploration`() {
        val result = explore("a", CallDirection.BOTH, maxNodes = 3)
        assertEquals(3, result.items.size)
        assertTrue(result.truncated)
        assertFalse(explore("a", CallDirection.BOTH).truncated)
    }

    @Test fun `non expandable nodes are leaves`() {
        val result = runBlocking {
            exploreCalls("main", CallDirection.CALLEES, 5, 100, { callers.getValue(it) }, { calls.getValue(it) }, { it != "a" })
        }
        assertEquals(listOf("main", "a"), result.items)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `depth must be positive`() { explore("a", CallDirection.BOTH, depth = 0) }

    @Test fun `layout puts callers left and callees right without overlap`() {
        val nodes = listOf(0 to 0, 1 to -1, 2 to -1, 3 to 1, 4 to 2).map { (id, level) ->
            CallGraphNode(id, "f$id", "", "", level, true)
        }
        val graph = CallGraph(nodes, setOf(CallGraphEdge(1, 0), CallGraphEdge(2, 0), CallGraphEdge(0, 3), CallGraphEdge(3, 4)), false)
        val layout = layoutCallGraph(graph, LayoutMetrics(100, 20, 30, 10, 5))
        val boxes = layout.boxes
        assertTrue(boxes.getValue(1).x < boxes.getValue(0).x)
        assertTrue(boxes.getValue(0).x < boxes.getValue(3).x && boxes.getValue(3).x < boxes.getValue(4).x)
        assertFalse(boxes.getValue(1).intersects(boxes.getValue(2)))
        boxes.values.forEach { assertTrue(it.maxX <= layout.size.width && it.maxY <= layout.size.height) }
    }

    @Test fun `empty project has an empty layout and highlight`() {
        val graph = CallGraph(emptyList(), emptySet(), false, projectGraph = true)
        assertTrue(layoutCallGraph(graph, LayoutMetrics(100, 20, 30, 10, 5)).boxes.isEmpty())
        assertTrue(graph.highlightFile(null).owned.isEmpty())
        assertTrue(graph.highlightFile("/missing.kt").related.isEmpty())
        assertTrue(graph.atDetail(GraphDetail.FILE).graph.nodes.isEmpty())
    }

    @Test fun `file detail collapses symbols into one node per file and merges relationships`() {
        fun node(id: Int, kind: SymbolKind, path: String) = CallGraphNode(id, "n$id", "", "", 0, true, kind, path)
        // a.kt: 0 file, 1 class, 2 fun · b.kt: 3 file, 4 fun · c.kt: 5 file, 6 fun
        val graph = CallGraph(listOf(
            node(0, SymbolKind.FILE, "/a.kt"), node(1, SymbolKind.CLASS, "/a.kt"), node(2, SymbolKind.FUNCTION, "/a.kt"),
            node(3, SymbolKind.FILE, "/b.kt"), node(4, SymbolKind.FUNCTION, "/b.kt"),
            node(5, SymbolKind.FILE, "/c.kt"), node(6, SymbolKind.FUNCTION, "/c.kt"),
        ), setOf(
            CallGraphEdge(0, 1, RelationKind.CONTAINS), CallGraphEdge(1, 2, RelationKind.CONTAINS),
            CallGraphEdge(3, 4, RelationKind.CONTAINS), CallGraphEdge(5, 6, RelationKind.CONTAINS),
            CallGraphEdge(0, 4, RelationKind.IMPORT), CallGraphEdge(2, 4, RelationKind.CALL),
            CallGraphEdge(2, 1, RelationKind.REFERENCE), CallGraphEdge(4, 6, RelationKind.REFERENCE),
            CallGraphEdge(6, 4, RelationKind.CALL),
        ), false, projectGraph = true)

        assertEquals(graph, graph.atDetail(GraphDetail.SYMBOL).graph)
        val files = graph.atDetail(GraphDetail.FILE)
        assertEquals(listOf(0, 3, 5), files.sources)
        assertEquals(listOf(0, 1, 2), files.graph.nodes.map { it.id })
        assertTrue(files.graph.nodes.all { it.kind == SymbolKind.FILE })
        assertTrue(files.graph.projectGraph)
        // Import + call between a and b become one call edge; same-file and containment edges disappear.
        assertEquals(setOf(CallGraphEdge(0, 1, RelationKind.CALL), CallGraphEdge(1, 2, RelationKind.REFERENCE),
            CallGraphEdge(2, 1, RelationKind.CALL)), files.graph.edges)
        // Users sit left of their dependencies; the b <-> c cycle does not loop forever.
        val levels = files.graph.nodes.map { it.level }
        assertEquals(0, levels[0])
        assertTrue(levels[1] > levels[0])
        val highlight = files.graph.highlightFile("/b.kt")
        assertEquals(setOf(1), highlight.owned)
        assertEquals(setOf(0, 2), highlight.related)
    }
}
