package dev.insight.callgraph

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.chat.ToolInputException

class ChartGroupingTest : BasePlatformTestCase() {
    private val graph = CallGraph((0..6).map {
        CallGraphNode(it, "function$it", "Example", "Example.kt:${it + 1}", 0, true)
    }, (1..6).mapTo(linkedSetOf()) { CallGraphEdge(0, it) }, false)
    private val sites = listOf(CallSite(1, 10), CallSite(2, 20), CallSite(1, 30), CallSite(3, 40))
    private fun source(sites: List<SourceSite> = this.sites) = FlowSource(graph.edges.groupBy { it.caller }, emptyMap(),
        mapOf(0 to sites), effectsOf = { if (it == 2) setOf("storage") else emptySet() }, isEntryPoint = { it == 3 }) {
        FlowCard("function$it", entryPoint = it == 3)
    }
    private fun canvas() = FlowChartCanvas({}, {}).apply { show(source()); add(FlowStart(0)) }
    private val block get() = FlowRegionEvidence(0, sites)
    private fun group(vararg members: Int) = ChartCallGroup(members.map { sites[it].occurrenceId }, "Prepare request", "Checks inputs. Example.kt:1")

    fun `test named groups collapse calls preserve effects and drill down without losing repeated occurrences`() {
        val canvas = canvas()
        val json = JsonParser.parseString(groupChartCalls(graph, canvas, 1, 1, 0, block.key, listOf(group(0, 1, 2)))).asJsonObject
        assertTrue(canvas.board.compact)
        val region = canvas.chart!!.regions.single()
        assertEquals("Prepare request", region.plan.title)
        assertEquals("Checks inputs. Example.kt:1", region.presentation().card.description)
        assertEquals(setOf("storage"), region.effects)
        assertTrue(region.presentation().card.details!!.contains("storage"))
        assertFalse(region.expanded)
        assertNull(canvas.chart!!.step(1))
        assertNull(canvas.chart!!.step(2))
        assertNotNull(canvas.chart!!.step(3)) // Unrelated call stays visible.
        assertEquals(1, json["ungrouped_occurrence_ids"].asJsonArray.size())
        val state = JsonParser.parseString(chartChatState(1, graph, canvas, 0)).asJsonObject
        val key = state["regions"].asJsonArray.single().asJsonObject["region_key"].asString
        assertEquals(region.plan.evidence.key, key)
        setChartRegionExpansion(canvas, 1, 1, listOf(key), true)
        assertEquals(3, canvas.chart!!.regions.single().items.size)
        assertEquals(1, canvas.chart!!.regions.single().items.filterIsInstance<FlowRef>().size)
        assertNotNull(canvas.chart!!.step(2))
        setChartRegionExpansion(canvas, 1, 1, listOf(key), false)
        assertNull(canvas.chart!!.step(2))
        var checkpoints = 0
        setChartRegionExpansion(canvas, 1, 1, listOf(key), false) { checkpoints++ }
        assertEquals(0, checkpoints)
        // Function references can still reveal members using the existing connected placement.
        assertNull(openChartReference(graph, canvas, 1, 1, 2))
        assertTrue(canvas.chart!!.regions.single().expanded)
        // Regrouping starts closed even when the same region key was previously open.
        groupChartCalls(graph, canvas, 1, 1, 0, block.key, listOf(group(0, 1, 2)))
        assertFalse(canvas.chart!!.regions.single().expanded)
    }

    fun `test rejects stale blocks invented reordered disconnected and overlapping groups atomically`() {
        val canvas = canvas()
        var checkpoints = 0
        fun apply(groups: List<ChartCallGroup>, revision: Int = 1, blockKey: String = block.key) =
            groupChartCalls(graph, canvas, 1, revision, 0, blockKey, groups) { checkpoints++ }
        val before = canvas.saveState()
        for (groups in listOf(listOf(group(1, 0)), listOf(group(0, 2)), listOf(group(0, 0)),
            listOf(group(0, 1), group(1, 2)), listOf(ChartCallGroup(listOf("999/1", "20/2"), "Invented")))) {
            assertThrows(ToolInputException::class.java) { apply(groups) }
            assertEquals(before, canvas.saveState())
        }
        assertThrows(ToolInputException::class.java) { apply(listOf(group(0, 1)), revision = 0) }
        assertThrows(ToolInputException::class.java) { apply(listOf(group(0, 1)), blockKey = "old") }
        assertEquals(0, checkpoints)
        apply(listOf(group(0, 1)))
        val key = canvas.chart!!.regions.single().plan.evidence.key
        val grouped = canvas.saveState()
        assertThrows(ToolInputException::class.java) { setChartRegionExpansion(canvas, 1, 1, listOf(key, "missing"), true) }
        assertThrows(ToolInputException::class.java) { setChartRegionExpansion(canvas, 2, 1, listOf(key), true) }
        assertEquals(grouped, canvas.saveState())
        // A newly read source snapshot invalidates the old block even within a graph revision.
        canvas.show(source(sites.map { it.copy(offset = it.offset + 1) }))
        assertThrows(ToolInputException::class.java) { apply(listOf(group(0, 1))) }
    }

    fun `test grouping and ungrouping are undoable and reset on graph rebuild`() {
        val canvas = canvas()
        val restore = ChartRestorePoint(canvas)
        val before = canvas.saveState()
        restore.begin()
        groupChartCalls(graph, canvas, 1, 1, 0, block.key, listOf(group(0, 1))) { restore.checkpoint() }
        assertTrue(restore.restore())
        assertEquals(before, canvas.saveState())
        groupChartCalls(graph, canvas, 1, 1, 0, block.key, listOf(group(0, 1)))
        canvas.show(source()) // Background summary/source refresh preserves chat overrides.
        assertEquals("Prepare request", canvas.chart!!.regions.single().plan.title)
        groupChartCalls(graph, canvas, 1, 1, 0, block.key, emptyList())
        assertTrue(canvas.chart!!.regions.isEmpty())
        assertNotNull(canvas.chart!!.step(2))
        assertTrue(canvas.board.map { it }.regionOverrides.isEmpty())
    }

    fun `test enabling compact mode keeps the nested owner visible`() {
        val graph = graph.copy(edges = graph.edges + CallGraphEdge(6, 0) + CallGraphEdge(6, 5))
        val source = source().copy(calls = graph.edges.groupBy { it.caller },
            callSites = mapOf(6 to listOf(CallSite(0, 1), CallSite(5, 2)), 0 to sites))
        val canvas = FlowChartCanvas({}, {}).apply { show(source); add(FlowStart(6)); openCalls(setOf(0)) }
        assertNotNull(canvas.chart!!.step(0))
        groupChartCalls(graph, canvas, 1, 1, 0, block.key, listOf(group(0, 1)))
        assertNotNull(canvas.chart!!.step(0))
        assertEquals(1, canvas.chart!!.lanes.size)
        assertFalse(canvas.chart!!.regions.single { it.plan.title == "Prepare request" }.expanded)
        assertTrue(canvas.chart!!.regions.single { it.plan.evidence.owner == 6 }.expanded)
    }

    fun `test outline offers bounded occurrence blocks with branch and exit boundaries and folding`() {
        val branch = ControlFrame(ControlKind.BRANCH, 50, 0, "valid", subject = "valid")
        val extra = listOf(CallSite(4, 50, listOf(branch)), CallSite(5, 60, listOf(branch)),
            ExitSite(70, listOf(branch), "return", "return"), CallSite(6, 80))
        val source = source(sites + extra).copy(folded = setOf(2))
        val outline = JsonParser.parseString(chartFunctionOutline(graph, 1, 1, listOf(0), source.callSites,
            groupingSource = source)).asJsonObject["functions"].asJsonArray.single().asJsonObject
        val blocks = outline["groupable_blocks"].asJsonArray.map { it.asJsonObject }
        assertEquals(2, blocks.size)
        assertEquals(listOf("10/1", "30/1", "40/3"), blocks[0]["occurrences"].asJsonArray.map { it.asJsonObject["occurrence_id"].asString })
        assertEquals(listOf("50/4", "60/5"), blocks[1]["occurrences"].asJsonArray.map { it.asJsonObject["occurrence_id"].asString })
        val canvas = FlowChartCanvas({}, {}).apply { show(source); add(FlowStart(0)) }
        assertEquals(canvas.groupingBlocks(0).map { it.key }, blocks.map { it["block_key"].asString })
        groupChartCalls(graph, canvas, 1, 1, 0, blocks[0]["block_key"].asString,
            listOf(ChartCallGroup(listOf("10/1", "30/1", "40/3"), "Main stage")))
        assertTrue(canvas.chart!!.regions.first().presentation().card.details!!.contains("Contains entry point"))
        assertTrue(canvas.chart!!.lanes.single().root.items.any { it is FlowDecision })
    }
}
