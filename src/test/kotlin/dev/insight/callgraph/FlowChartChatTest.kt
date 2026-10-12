package dev.insight.callgraph

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.chat.ToolInputException
import org.junit.Assert.assertThrows

class FlowChartChatTest : BasePlatformTestCase() {
    private val graph = CallGraph(
        (0..9).map { CallGraphNode(it, "function$it", "Example", "Example.kt:${it + 1}", 0, true, filePath = "/project/Example.kt") },
        setOf(CallGraphEdge(0, 1), CallGraphEdge(1, 2)), false,
    )

    private fun canvas() = FlowChartCanvas({}, {}).apply {
        show(FlowSource(graph.edges.groupBy { it.caller }, graph.edges.groupBy { it.callee }.mapValues { (_, edges) -> edges.map { it.caller } }) {
            FlowCard(graph.nodes[it].name)
        })
    }

    fun `test actions reject stale IDs and invalid batches without changing the chart`() {
        val canvas = canvas()
        assertThrows(ToolInputException::class.java) { applyChartChatAction(graph, canvas, 2, "add_chart_nodes", 1, listOf(0)) }
        assertThrows(ToolInputException::class.java) { applyChartChatAction(graph, canvas, 2, "add_chart_nodes", 2, listOf(0, 99)) }
        assertTrue(canvas.board.starts.isEmpty())
        assertThrows(ToolInputException::class.java) { applyChartChatAction(graph, canvas, 2, "highlight_chart_nodes", 2, listOf(0)) }
        assertTrue(canvas.chatHighlights.isEmpty())
    }

    fun `test chart changes are idempotent and search preserves chat highlights`() {
        val canvas = canvas()
        fun apply(action: String, vararg nodes: Int) = applyChartChatAction(graph, canvas, 1, action, 1, nodes.toList())
        apply("add_chart_nodes", 0)
        apply("add_chart_nodes", 0)
        assertEquals(1, canvas.board.starts.size)
        apply("expand_chart_calls", 1)
        apply("expand_chart_calls", 1)
        assertNotNull(canvas.chart?.step(2))
        apply("expand_chart_callers", 1)
        apply("expand_chart_callers", 1)
        assertTrue(1 in canvas.board.openCallers)
        apply("highlight_chart_nodes", 1, 2)
        canvas.markMatches(setOf(0))
        assertEquals(setOf(1, 2), canvas.chatHighlights)
        assertEquals(1, canvas.selectedNode)
        apply("highlight_chart_nodes")
        assertTrue(canvas.chatHighlights.isEmpty())
        assertEquals(setOf(0), canvas.searchMatches)
        apply("highlight_chart_nodes", 1)
        canvas.clear()
        assertTrue(canvas.chatHighlights.isEmpty())
    }

    fun `test added functions continue visible steps and only unconnected ones become columns`() {
        val graph = CallGraph(
            (0..6).map { CallGraphNode(it, "function$it", "Example", "Example.kt:${it + 1}", 0, true, filePath = "/project/Example.kt") },
            setOf(CallGraphEdge(0, 1), CallGraphEdge(1, 2), CallGraphEdge(3, 0), CallGraphEdge(4, 5)), false,
        )
        val canvas = FlowChartCanvas({}, {}).apply {
            show(FlowSource(graph.edges.groupBy { it.caller }, graph.edges.groupBy { it.callee }.mapValues { (_, edges) -> edges.map { it.caller } }) {
                FlowCard(graph.nodes[it].name)
            })
        }
        fun add(vararg nodes: Int) = JsonParser.parseString(applyChartChatAction(graph, canvas, 1, "add_chart_nodes", 1, nodes.toList()))
            .asJsonObject["placements"].asJsonArray.map { it.asJsonObject }
        add(0)
        val placed = add(2, 3, 6, 5, 4)
        assertEquals(listOf(0, 6, 4), canvas.board.starts.map { it.node })
        assertEquals(listOf("in_calls_of", "in_callers_of", "new_column", "revealed_by_batch", "new_column"),
            placed.map { it["placement"].asString })
        assertEquals(1, placed[0]["via_node_id"].asInt)
        assertEquals(0, placed[1]["via_node_id"].asInt)
        assertTrue(1 in canvas.board.openCalls && 0 in canvas.board.openCallers)
        assertEquals(2, canvas.selectedNode)
        assertEquals("already_visible", add(1).single()["placement"].asString)
        assertEquals(3, canvas.board.starts.size)
    }

    fun `test paginated state exposes selection expansions and actual calls`() {
        val canvas = canvas()
        (0..9).forEach { canvas.add(FlowStart(it)) }
        canvas.highlightChat(setOf(9))
        val json = JsonParser.parseString(chartChatState(7, graph, canvas, 0)).asJsonObject
        assertEquals(7, json["revision"].asInt)
        assertEquals(9, json["selected_node"].asInt)
        assertEquals(10, json["steps_total"].asInt)
        assertEquals(8, json["next_offset"].asInt)
        assertEquals(8, json["steps"].asJsonArray.size())
        assertEquals(2, json["calls"].asJsonArray.size())
        val next = JsonParser.parseString(chartChatState(7, graph, canvas, 8)).asJsonObject
        assertEquals(2, next["steps"].asJsonArray.size())
        assertTrue(next["steps"].asJsonArray.last().asJsonObject["highlighted"].asBoolean)
    }
}
