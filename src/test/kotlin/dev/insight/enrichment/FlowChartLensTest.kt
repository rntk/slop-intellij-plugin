package dev.insight.enrichment

import dev.insight.callgraph.CallGraph
import dev.insight.callgraph.CallGraphEdge
import dev.insight.callgraph.CallGraphNode
import dev.insight.callgraph.CallSite
import dev.insight.callgraph.FlowBoard
import dev.insight.callgraph.FlowStart
import dev.insight.callgraph.buildFlowChart
import dev.insight.callgraph.RelationKind
import dev.insight.callgraph.SymbolKind
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

class FlowChartLensTest {
    /** 0 handle → 1 load → 2 parse; 0 → 3 format (utility); 0 → 4 report (error path); 1 → 5 panel (local) → 6 render. */
    private val names = listOf("handle", "load", "parse", "format", "report", "panel", "render")
    private val graph = CallGraph(
        names.mapIndexed { id, name ->
            CallGraphNode(id, if (id == 5) name else "$name()", "Service in demo.service", "service.py:${id + 1}", 0, true,
                if (id == 5) SymbolKind.VARIABLE else SymbolKind.FUNCTION, "/p/service.py")
        },
        linkedSetOf(CallGraphEdge(0, 1), CallGraphEdge(1, 2), CallGraphEdge(0, 3), CallGraphEdge(0, 4), CallGraphEdge(5, 6),
            CallGraphEdge(1, 5, RelationKind.CONTAINS)),
        false, projectGraph = true,
    )

    private fun snapshot(): EnrichmentSnapshot {
        val facts = mapOf(0 to SymbolFacts(0, "k0", SymbolKind.FUNCTION, "handle", "Service in demo.service", "service.py", "python",
            "def handle(self)", "", doc = "Handles a request.\n\nLonger details.", contentHash = "0"))
        val enrichment = Enrichment(
            nodes = mapOf(0 to NodeAnnotation(entryPoint = 0.9, layer = ChoiceValue("api", emptyMap(), 0.9),
                concerns = mapOf("network" to 0.8)), 3 to NodeAnnotation(utility = 0.9)),
            heuristics = emptyMap(), metrics = emptyMap(),
            edges = mapOf(CallGraphEdge(0, 4) to EdgeAnnotation(errorPath = 0.9, continuesOperation = 0.9)),
            domains = emptyList(), flows = emptyList(), groupLabels = emptyMap(), visibility = emptyMap(), stats = EnrichmentStats(),
        )
        return EnrichmentSnapshot(graph, EnrichmentInput("demo", graph, facts), enrichment, Path.of("report.json"))
    }
    private fun callees(lens: FlowChartLens, id: Int) = lens.calls[id].orEmpty().map { it.callee }.toSet()

    @Test fun `without enrichment every in-project call is followed, including calls from locals`() {
        val lens = FlowChartLens(null, graph)
        assertEquals(setOf(1, 3, 4), callees(lens, 0))
        assertEquals(setOf(2, 6), callees(lens, 1))
    }

    @Test fun `with enrichment utilities and error paths remain explorable along with all callers`() {
        val lens = FlowChartLens(snapshot(), graph)
        assertEquals(setOf(1, 3, 4), callees(lens, 0))
        assertEquals(listOf(0), lens.callers[3])
        assertEquals(listOf(0), lens.callers[4])
        assertEquals(listOf(1), lens.callers[6])
        assertNull(lens.callers[0])
    }

    @Test fun `cards carry titles, entry markers, locations, doc summaries and judgments`() {
        val lens = FlowChartLens(snapshot(), graph)
        val chart = buildFlowChart(FlowBoard(listOf(FlowStart(0)), openCalls = setOf(0, 1)), lens.source(mapOf(0 to listOf(CallSite(1, 10)))))
        val entry = chart.lanes.single().root.card
        assertEquals("Service.handle", entry.title)
        assertEquals("▶ entry point · service.py:1", entry.meta)
        assertEquals("Handles a request.", entry.description)
        assertEquals("api · network", entry.details)
        assertEquals(listOf("Service.handle", "Service.load", "Service.parse", "Service.render", "Service.format", "Service.report"), chart.steps.map { it.card.title })
        assertEquals("service.py:2", lens.card(1).meta)
    }

    @Test fun `cards prefer current source facts over saved enrichment`() {
        val snapshot = snapshot()
        val fact = snapshot.input.facts.getValue(0).copy(doc = "Current caller documentation.")
        val card = FlowChartLens(snapshot, graph).source(facts = mapOf(0 to fact)).card(0)
        assertEquals("Current caller documentation.", card.description)
        assertTrue(card.meta.orEmpty().contains("entry point"))
    }

    @Test fun `an enriched flow becomes a column headed by its entry`() {
        val flow = Flow("f1", "Handle a request", "Loads and parses.", 0, listOf(0, 1, 2), setOf(CallGraphEdge(0, 1)))
        val snapshot = snapshot().let { EnrichmentSnapshot(it.graph, it.input, it.enrichment.copy(flows = listOf(flow)), it.report) }
        val lens = FlowChartLens(snapshot, graph)
        assertEquals(FlowStart(0, "Handle a request", "Loads and parses."), lens.start("f1"))
        assertNull(lens.start("missing"))
    }

    @Test fun `summaries title compact cards, and only effect-free logging or plumbing is folded`() {
        val base = snapshot()
        val enrichment = base.enrichment.copy(
            summaries = mapOf(
                0 to FunctionSummary("Handle request", "Serves one request.", FunctionRole.CORE, single = false),
                3 to FunctionSummary("Format output", null, FunctionRole.PLUMBING, single = true),
                4 to FunctionSummary("Report failure", null, FunctionRole.LOGGING, single = true),
            ),
            effects = mapOf(4 to Effects(emptySet(), setOf("storage")), 0 to Effects(setOf("network"), setOf("network", "storage"))),
        )
        val lens = FlowChartLens(EnrichmentSnapshot(graph, base.input, enrichment, base.report), graph)
        assertEquals(setOf(3), lens.folded)
        val card = lens.compactCard(0)
        assertEquals("Handle request", card.title)
        assertEquals("▶ entry point · Service.handle · LLM summary", card.meta)
        assertTrue(card.details!!.startsWith("core · "))
        assertTrue(card.details!!.endsWith("network · storage via calls"))
        assertEquals("handle", lens.card(0).title.substringAfter('.'))
        val stale = base.input.facts.getValue(0).copy(sourceHash = "edited")
        assertTrue("summary predates source edits" in lens.compactCard(0, stale).meta!!)
        assertTrue(lens.freshSummaries(mapOf(0 to stale)).isEmpty())
        // Opening skips the folded helper and does not open single steps.
        assertEquals(setOf(0, 1), lens.openCalls(0, budget = 10))
        assertEquals(setOf(0), FlowChartLens(base, graph).openCalls(0))
    }
}
