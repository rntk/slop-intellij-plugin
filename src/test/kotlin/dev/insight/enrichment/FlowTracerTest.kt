package dev.insight.enrichment

import dev.insight.callgraph.CallGraph
import dev.insight.callgraph.CallGraphEdge
import dev.insight.callgraph.CallGraphNode
import dev.insight.callgraph.RelationKind
import dev.insight.callgraph.SymbolKind
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FlowTracerTest {
    private fun input(names: List<String>, calls: List<Pair<Int, Int>>) = EnrichmentInput("demo", CallGraph(
        names.mapIndexed { id, name -> CallGraphNode(id, "$name()", "Service", "", 0, true, SymbolKind.FUNCTION, "/p/S.kt") },
        calls.mapTo(linkedSetOf()) { (from, to) -> CallGraphEdge(from, to) }, false, projectGraph = true,
    ), names.indices.associateWith { id ->
        SymbolFacts(id, "S.kt|${names[id]}", SymbolKind.FUNCTION, names[id], "dev.demo.Service", "S.kt", "kotlin", "fun ${names[id]}()", "", contentHash = "$id")
    })

    private val entry = NodeAnnotation(entryPoint = 0.9)
    private fun edge(from: Int, to: Int) = CallGraphEdge(from, to)

    /**
     * 0 handle (entry) → 1 load → 2 parse; 0 → 3 format (utility); 0 → 4 report (error path);
     * 1 → 5 refresh (another entry) → 6 repaint; 7 testHandle (test entry) → 1; 0 → 8 helper (incidental call).
     */
    private val names = listOf("handle", "load", "parse", "format", "report", "refresh", "repaint", "testHandle", "helper")
    private val calls = listOf(0 to 1, 1 to 2, 0 to 3, 0 to 4, 1 to 5, 5 to 6, 7 to 1, 0 to 8)
    private val annotations = mapOf(0 to entry, 3 to NodeAnnotation(utility = 0.9), 5 to entry, 7 to entry)
    private val edgeAnnotations = mapOf(
        edge(0, 1) to EdgeAnnotation(0.0, 0.9),
        edge(0, 4) to EdgeAnnotation(0.8, 0.9),
        edge(0, 8) to EdgeAnnotation(0.0, 0.2),
    )

    @Test fun `follows operation calls, stops at utilities, error paths, incidental calls and other entry points`() = runBlocking {
        val result = FlowTracer(null, InMemoryEnrichmentCache())
            .trace(input(names, calls), annotations, edgeAnnotations, mapOf(7 to Heuristics(setOf("test", "entry:test"))))
        val flow = result.flows.single()
        assertEquals(listOf(0, 1, 2, 5), flow.nodes)
        assertEquals(setOf(edge(0, 1), edge(1, 2), edge(1, 5)), flow.edges)
        assertEquals("Service.handle flow", flow.name)
        assertNull(flow.description)
        assertEquals(0, result.llmRequests)
        // The flow of entry 5 (refresh → repaint) is too small.
        assertEquals(0, flow.entry)
        assertEquals(flow.id, FlowTracer(null, InMemoryEnrichmentCache()).trace(input(names, calls), annotations, edgeAnnotations, emptyMap())
            .flows.first { it.entry == 0 }.id)
    }

    @Test fun `detects entries by heuristic tags and optionally includes tests`() = runBlocking {
        val heuristics = mapOf(7 to Heuristics(setOf("entry:test")))
        val tests = mapOf(7 to entry.copy(phase = ChoiceValue("test", emptyMap(), 1.0)))
        val tracer = { includeTests: Boolean -> FlowTracer(null, InMemoryEnrichmentCache(), FlowOptions(includeTests = includeTests)) }
        assertEquals(setOf(7), tracer(false).trace(input(names, calls), emptyMap(), emptyMap(), heuristics).flows.map { it.entry }.toSet())
        assertEquals(emptyList<Flow>(), tracer(false).trace(input(names, calls), tests, emptyMap(), emptyMap()).flows)
        assertEquals(listOf(7), tracer(true).trace(input(names, calls), tests, emptyMap(), emptyMap()).flows.map { it.entry })
    }

    @Test fun `caps flow size and depth`() = runBlocking {
        val chain = (0 until 10).map { "f$it" }
        val links = (0 until 9).map { it to it + 1 }
        val sized = FlowTracer(null, InMemoryEnrichmentCache(), FlowOptions(maxFlowNodes = 4)).trace(input(chain, links), mapOf(0 to entry), emptyMap(), emptyMap())
        assertEquals(listOf(0, 1, 2, 3), sized.flows.single().nodes)
        val deep = FlowTracer(null, InMemoryEnrichmentCache(), FlowOptions(maxDepth = 2)).trace(input(chain, links), mapOf(0 to entry), emptyMap(), emptyMap())
        assertEquals(listOf(0, 1, 2), deep.flows.single().nodes)
    }

    @Test fun `keeps the larger of mostly overlapping flows and ranks by size and importance`() = runBlocking {
        // 0 → 2..6 and 1 → 2..5: flow 1 lies inside flow 0. 7 → 8, 9 is a separate small flow.
        val names = (0..9).map { "f$it" }
        val links = (2..6).map { 0 to it } + (2..5).map { 1 to it } + listOf(7 to 8, 7 to 9)
        val important = NodeAnnotation(entryPoint = 0.9, importance = ScoreValue(4.0, 4, 1.0))
        val result = FlowTracer(null, InMemoryEnrichmentCache(), FlowOptions(maxFlows = 1))
            .trace(input(names, links), mapOf(0 to entry, 1 to entry, 7 to important), emptyMap(), emptyMap())
        assertEquals(listOf(0), result.flows.map { it.entry })
        val all = FlowTracer(null, InMemoryEnrichmentCache()).trace(input(names, links), mapOf(0 to entry, 1 to entry, 7 to important), emptyMap(), emptyMap())
        assertEquals(listOf(0, 7), all.flows.map { it.entry })
    }

    @Test fun `names flows with the model in batches, falling back per flow`() = runBlocking {
        val prompts = mutableListOf<String>()
        val model: suspend (CompletionRequest) -> Completion = { request ->
            prompts += request.messages.last().content
            Completion("""{"flows":[{"id":1,"name":"Load data.","description":"Loads and parses data."},{"id":9,"name":"Ghost"}]}""")
        }
        val names = (0..9).map { "f$it" }
        val links = listOf(0 to 1, 0 to 2, 5 to 6, 5 to 7)
        val cache = InMemoryEnrichmentCache()
        val result = FlowTracer(model, cache).trace(input(names, links), mapOf(0 to entry, 5 to entry), emptyMap(), emptyMap())
        assertEquals(listOf("Load data", "Service.f5 flow"), result.flows.map { it.name })
        assertEquals("Loads and parses data.", result.flows[0].description)
        assertEquals(1, result.llmRequests)
        assertTrue(prompts.single(), "[1] entry Service.f0 — fun f0()\n  - Service.f1 — fun f1()" in prompts.single())
        // Cached: no further requests.
        assertEquals(0, FlowTracer(model, cache).trace(input(names, links), mapOf(0 to entry, 5 to entry), emptyMap(), emptyMap()).llmRequests)
        val failed = FlowTracer({ throw IOException("down") }, InMemoryEnrichmentCache())
            .trace(input(names, links), mapOf(0 to entry), emptyMap(), emptyMap())
        assertEquals(listOf("Service.f0 flow"), failed.flows.map { it.name })
        assertEquals(1, failed.failures)
    }

    @Test fun `continues from an interface method into its implementations`() = runBlocking {
        fun node(id: Int, name: String, kind: SymbolKind) = CallGraphNode(id, name, "", "", 0, true, kind)
        // 0 send → 2 Backend.reply (interface); 3 LlmBackend implements Backend, 4 LlmBackend.reply → 5 complete.
        val nodes = listOf(node(0, "send()", SymbolKind.FUNCTION), node(1, "Backend", SymbolKind.CLASS), node(2, "reply()", SymbolKind.FUNCTION),
            node(3, "LlmBackend", SymbolKind.CLASS), node(4, "reply()", SymbolKind.FUNCTION), node(5, "complete()", SymbolKind.FUNCTION))
        val graph = CallGraph(nodes, linkedSetOf(CallGraphEdge(0, 2), CallGraphEdge(1, 2, RelationKind.CONTAINS), CallGraphEdge(3, 4, RelationKind.CONTAINS),
            CallGraphEdge(3, 1, RelationKind.INHERITS), CallGraphEdge(4, 2, RelationKind.INHERITS), CallGraphEdge(4, 5)), false, projectGraph = true)
        assertEquals(mapOf(2 to listOf(4)), overrides(graph))
        val facts = nodes.associate { it.id to SymbolFacts(it.id, "k$it.id", it.kind, it.name, "", "S.kt", "kotlin", it.name, "", contentHash = "${it.id}") }
        val flow = FlowTracer(null, InMemoryEnrichmentCache()).trace(EnrichmentInput("demo", graph, facts), mapOf(0 to entry), emptyMap(), emptyMap()).flows.single()
        assertEquals(listOf(0, 2, 4, 5), flow.nodes)
        assertTrue(CallGraphEdge(2, 4, RelationKind.INHERITS) in flow.edges)
    }

    @Test fun `generic dispatch keeps a flow above the minimum size`() = runBlocking {
        fun node(id: Int, name: String) = CallGraphNode(id, name, "", "", 0, true)
        val nodes = listOf(node(0, "send()"), node(1, "reply(T)"), node(2, "reply(String)"), node(3, "complete()"))
        val graph = CallGraph(nodes, linkedSetOf(CallGraphEdge(0, 1), CallGraphEdge(2, 1, RelationKind.INHERITS),
            CallGraphEdge(2, 3)), false, projectGraph = true)
        val flow = FlowTracer(null, InMemoryEnrichmentCache())
            .trace(EnrichmentInput("demo", graph, emptyMap()), mapOf(0 to entry), emptyMap(), emptyMap()).flows.single()
        assertEquals(listOf(0, 1, 2, 3), flow.nodes)
        assertTrue(CallGraphEdge(1, 2, RelationKind.INHERITS) in flow.edges)
    }

    @Test fun `resolved languages exclude same named methods without an override relationship`() = runBlocking {
        for (language in listOf("java", "kotlin", "python")) {
            val nodes = listOf(
                CallGraphNode(0, "send()", "", "", 0, true),
                CallGraphNode(1, "Base", "", "", 0, true, SymbolKind.CLASS),
                CallGraphNode(2, "reply()", "Base", "", 0, true),
                CallGraphNode(3, "Child", "", "", 0, true, SymbolKind.CLASS),
                CallGraphNode(4, "reply()", "Child", "", 0, true),
                CallGraphNode(5, "reply()", "Child", "", 0, true),
            )
            // Method 4 overrides the base; method 5 has the same name but no resolved override (private/static).
            val graph = CallGraph(nodes, setOf(CallGraphEdge(0, 2),
                CallGraphEdge(1, 2, RelationKind.CONTAINS), CallGraphEdge(3, 4, RelationKind.CONTAINS),
                CallGraphEdge(3, 5, RelationKind.CONTAINS), CallGraphEdge(3, 1, RelationKind.INHERITS),
                CallGraphEdge(4, 2, RelationKind.INHERITS)), false)
            val facts = nodes.associate { it.id to SymbolFacts(it.id, "n${it.id}", it.kind, it.name, it.container,
                "S", language, it.name, "", contentHash = "${it.id}") }
            val expected = if (language == "python") listOf(4, 5) else listOf(4)
            assertEquals(mapOf(2 to expected), overrides(graph, facts))
            val flow = FlowTracer(null, InMemoryEnrichmentCache())
                .trace(EnrichmentInput("demo", graph, facts), mapOf(0 to entry), emptyMap(), emptyMap()).flows.single()
            assertEquals(listOf(0, 2) + expected, flow.nodes)
            val unresolved = graph.copy(edges = graph.edges - CallGraphEdge(4, 2, RelationKind.INHERITS))
            assertEquals(if (language == "python") mapOf(2 to listOf(4, 5)) else emptyMap<Int, List<Int>>(),
                overrides(unresolved, facts))
            if (language != "python") {
                val extension = if (language == "java") "java" else "kt"
                assertTrue(overrides(unresolved.copy(nodes = nodes.map { it.copy(filePath = "/p/S.$extension") })).isEmpty())
            }
        }
    }
}
