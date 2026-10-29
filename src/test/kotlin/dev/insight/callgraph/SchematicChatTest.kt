package dev.insight.callgraph

import com.google.gson.JsonParser
import dev.insight.agent.ToolInputException
import dev.insight.graph.CallGraph
import dev.insight.graph.CallGraphEdge
import dev.insight.graph.CallGraphNode
import dev.insight.graph.CallOccurrence
import dev.insight.graph.RelationKind
import dev.insight.graph.SymbolKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchematicChatTest {
    @Test
    fun `queries reuse edge indexes until the snapshot changes`() {
        var scans = 0
        fun counted(edges: Set<CallGraphEdge>) = object : AbstractSet<CallGraphEdge>() {
            override val size get() = edges.size
            override fun iterator(): Iterator<CallGraphEdge> {
                scans++
                return edges.iterator()
            }
        }
        var current = graph.copy(edges = counted(graph.edges))
        val occurrences = emptyList<CallOccurrence>()
        val chat = SchematicGraph(current, occurrences, { null }, snapshot = { current to occurrences })
        repeat(3) {
            assertTrue(chat.paths(1, emptyList(), 4, 8).contains("#4 register"))
            chat.inspect(2)
            assertEquals(listOf(4, 5), chat.callees(2))
        }
        assertEquals(1, scans)
        current = current.copy(edges = counted(setOf(CallGraphEdge(1, 4))))
        assertEquals(emptyList<Int>(), chat.callees(2))
        assertEquals(listOf(4), chat.callees(1))
        assertEquals(2, scans)
    }

    @Test
    fun `shortest call paths are breadth first and bounded`() {
        val calls = mapOf(0 to listOf(1, 2), 1 to listOf(3), 2 to listOf(3), 3 to listOf(4), 5 to listOf(4))
        val next = { id: Int -> calls[id].orEmpty() }
        assertEquals(listOf(listOf(0, 1, 3, 4), listOf(0, 2, 3, 4)), shortestCallPaths(listOf(0), 4, 8, 3, next))
        assertEquals(listOf(listOf(0, 1, 3, 4)), shortestCallPaths(listOf(0), 4, 8, 1, next))
        assertEquals(emptyList<List<Int>>(), shortestCallPaths(listOf(0), 4, 2, 3, next))
        // Several starts: the nearest one wins.
        assertEquals(listOf(listOf(5, 4)), shortestCallPaths(listOf(0, 5), 4, 8, 3, next))
        assertEquals(listOf(listOf(3)), shortestCallPaths(listOf(3), 3, 8, 3, next))
    }

    @Test
    fun `cycles do not repeat a symbol in a chain`() {
        val calls = mapOf(0 to listOf(1), 1 to listOf(0, 2))
        assertEquals(listOf(listOf(0, 1, 2)), shortestCallPaths(listOf(0), 2, 8, 3) { calls[it].orEmpty() })
    }

    private val graph = CallGraph(listOf(
        node(0, "Server.kt", SymbolKind.FILE),
        node(1, "start"),
        node(2, "addHandler"),
        node(3, "Router", SymbolKind.CLASS),
        node(4, "register"),
        CallGraphNode(5, "println", "kotlin.io", "", 0, inProject = false, filePath = null),
    ), setOf(
        CallGraphEdge(0, 1, RelationKind.CONTAINS),
        CallGraphEdge(3, 4, RelationKind.CONTAINS),
        CallGraphEdge(1, 2), CallGraphEdge(2, 4), CallGraphEdge(2, 5),
    ), truncated = false, projectGraph = true)

    private fun node(id: Int, name: String, kind: SymbolKind = SymbolKind.FUNCTION) =
        CallGraphNode(id, name, "", "Server.kt:${id + 1}", 0, inProject = true, kind = kind, filePath = "/project/src/Server.kt")

    private val schematicGraph = SchematicGraph(graph, emptyList(), { null }, relative = { it.removePrefix("/project/") })

    @Test
    fun `symbols are described with ids, relative paths and their calls`() {
        assertEquals("#2 function addHandler · src/Server.kt", schematicGraph.line(2))
        assertEquals("#5 function println in kotlin.io (external)", schematicGraph.line(5))
        val inspected = schematicGraph.inspect(2)
        assertTrue(inspected, "Calls (2):\n  #4 function register · src/Server.kt\n  #5 function println" in inspected)
        assertTrue(inspected, "Called by (1):\n  #1 function start" in inspected)
        assertTrue(schematicGraph.inspect(4).contains("Declared in: #3 class Router"))
        assertTrue(schematicGraph.find("handler", null, 10).startsWith("#2 function addHandler"))
        assertTrue(schematicGraph.find("Router", SymbolKind.FUNCTION, 10).startsWith("No symbols"))
    }

    @Test
    fun `call paths start from the file's blocks unless told otherwise and never enter external code`() {
        assertEquals("Chain 1: #1 start → #2 addHandler → #4 register", schematicGraph.paths(null, listOf(1), 4, 8))
        assertEquals("Chain 1: #2 addHandler → #4 register", schematicGraph.paths(2, listOf(1), 4, 8))
        assertTrue(schematicGraph.paths(4, listOf(1), 1, 8).startsWith("No static call chain"))
    }

    @Test
    fun `canvas tools validate chains before touching the canvas`() = runBlocking {
        val shown = mutableListOf<Pair<List<Int>, Boolean>>()
        val canvas = object : SchematicCanvas {
            override suspend fun snapshot() = "snapshot"
            override suspend fun showChain(chain: List<Int>, openLast: Boolean) = "shown".also { shown += chain to openLast }
            override suspend fun reset() = "reset"
        }
        val tool = schematicCanvasTools(canvas).single { it.definition.name == "show_call_chain" }
        // Local models sometimes quote ids or keep the # of the context.
        tool.call(JsonParser.parseString("""{"symbol_ids":[1,"2","#4"],"open_last":true}""").asJsonObject)
        assertEquals(listOf(listOf(1, 2, 4) to true), shown)
        for (bad in listOf("""{"symbol_ids":[]}""", """{"symbol_ids":["x"]}""", """{"symbol_ids":[-1]}""")) {
            val failed = runCatching { tool.call(JsonParser.parseString(bad).asJsonObject) }.exceptionOrNull()
            assertTrue(bad, failed is ToolInputException)
        }
        assertEquals(1, shown.size)
    }

    @Test
    fun `reading tools never change the canvas`() = runBlocking {
        var touched = false
        val canvas = object : SchematicCanvas {
            override suspend fun snapshot() = "snapshot"
            override suspend fun showChain(chain: List<Int>, openLast: Boolean) = "shown".also { touched = true }
            override suspend fun reset() = "reset".also { touched = true }
        }
        val tools = schematicGraphTools(schematicGraph) { listOf(1) }.associateBy { it.definition.name }
        assertEquals(setOf("find_symbols", "inspect_symbol", "read_symbol", "call_path"), tools.keys)
        assertTrue(tools.getValue("call_path").call(JsonParser.parseString("""{"to":4}""").asJsonObject).startsWith("Chain 1: #1 start"))
        assertTrue(tools.getValue("inspect_symbol").call(JsonParser.parseString("""{"id":"#2"}""").asJsonObject).startsWith("#2 function"))
        assertFalse(touched)
        assertEquals(setOf("show_call_chain", "get_canvas", "reset_canvas"), schematicCanvasTools(canvas).map { it.definition.name }.toSet())
    }
}
