package dev.insight

import dev.insight.callgraph.CallDirection
import dev.insight.callgraph.CallGraph
import dev.insight.callgraph.CallGraphEdge
import dev.insight.callgraph.CallGraphNode
import dev.insight.callgraph.LayoutMetrics
import dev.insight.callgraph.exploreCalls
import dev.insight.callgraph.layoutCallGraph
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
}
