package dev.insight.enrichment

import com.google.gson.JsonParser
import dev.insight.graph.CallGraph
import dev.insight.graph.CallGraphEdge
import dev.insight.graph.CallGraphNode
import dev.insight.graph.CallOccurrence
import dev.insight.graph.CallOperation
import dev.insight.graph.SymbolKind
import dev.insight.llm.Completion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FunctionSummariesTest {
    /** 0 handle → 1 save → 2 write; 1 → 3 log; 4 ↔ 5 mutually recursive; 6 is a test. */
    private val names = listOf("handle", "save", "write", "log", "even", "odd", "testSave")
    private val edges = listOf(0 to 1, 1 to 2, 1 to 3, 4 to 5, 5 to 4, 6 to 1).map { (a, b) -> CallGraphEdge(a, b) }
    private val graph = CallGraph(names.mapIndexed { id, name ->
        CallGraphNode(id, "$name()", "Users", "Users.kt:$id", 0, true, SymbolKind.FUNCTION, "/p/Users.kt")
    }, edges.toCollection(LinkedHashSet()), false, projectGraph = true)
    private val calls = edges.groupBy { it.caller }

    private fun facts(code: (Int) -> String = { "fun ${names[it]}() {\n  a()\n  b()\n  c()\n}" }, hash: (Int) -> String = { "h$it" }) =
        names.indices.associateWith { id ->
            SymbolFacts(id, "k$id", SymbolKind.FUNCTION, names[id], "Users", "Users.kt", "kotlin", "fun ${names[id]}()", code(id),
                contentHash = hash(id), sourceHash = hash(id))
        }
    private val heuristics = mapOf(2 to Heuristics(setOf("concern:storage")), 6 to Heuristics(setOf("test")))

    @Test fun `components come callees first and cycles stay together`() {
        val order = stronglyConnected(names.indices.toList()) { id -> calls[id].orEmpty().map { it.callee } }
        val position = order.withIndex().flatMap { (i, c) -> c.map { it to i } }.toMap()
        assertTrue(position.getValue(2) < position.getValue(1) && position.getValue(1) < position.getValue(0))
        assertEquals(position.getValue(4), position.getValue(5))
        assertEquals(names.size - 1, order.size)
    }

    @Test fun `effects come from rules and reach every caller`() {
        val effects = Effects.compute(graph, calls, facts(code = { if (it == 3) "throw X()" else "" }), heuristics)
        assertEquals(setOf("storage"), effects.getValue(2).own)
        assertEquals(setOf("storage"), effects.getValue(0).reached)
        assertEquals(setOf(Effects.THROWS), effects.getValue(3).own)
        assertTrue(Effects.THROWS !in effects.getValue(1).reached)
        assertNull(effects[4])
    }

    private fun answer(request: String): String {
        val functions = JsonParser.parseString(request).asJsonObject.getAsJsonArray("functions")
        return """{"functions":[""" + functions.joinToString(",") { f ->
            val o = f.asJsonObject
            val role = if (o.get("name").asString.endsWith("log")) "logging" else "core"
            """{"id":${o.get("id")},"label":"Do ${o.get("name").asString}","description":"d","role":"$role","single":false}"""
        } + "]}"
    }

    @Test fun `summaries are written bottom-up with callee summaries and cached per function`() = runBlocking {
        val cache = InMemoryEnrichmentCache()
        val prompts = mutableListOf<String>()
        val summarizer = FunctionSummarizer({ request ->
            val user = request.messages.last().content
            prompts += user
            Completion(answer(user))
        }, cache, SummaryOptions(batchSize = 2, concurrency = 1))
        val input = EnrichmentInput("demo", graph, facts())
        val first = summarizer.summarize(input, calls, heuristics, emptyMap())
        assertNull("tests are not summarized", first.summaries[6])
        assertEquals(FunctionRole.LOGGING, first.summaries.getValue(3).role)
        assertEquals("Do Users.handle", first.summaries.getValue(0).label)
        // The caller's prompt carries its callees' summaries, not their source.
        assertTrue(prompts.single { "\"Users.handle\"" in it }.contains("Users.save: Do Users.save [core]"))
        assertEquals(0, first.cacheHits)

        prompts.clear()
        assertEquals(first.summaries, summarizer.summarize(input, calls, heuristics, emptyMap()).summaries)
        assertTrue(prompts.joinToString("\n"), prompts.isEmpty())

        // Editing write re-summarizes only write: its callers re-run only if its summary text changes.
        val edited = EnrichmentInput("demo", graph, facts(hash = { if (it == 2) "new" else "h$it" }))
        val again = summarizer.summarize(edited, calls, heuristics, emptyMap())
        assertEquals(1, prompts.size)
        assertTrue("Users.write" in prompts.single())
        assertEquals(1, again.requests)
    }

    @Test fun `trivial leaves skip the model and invalid answers are dropped`() = runBlocking {
        var requests = 0
        val summarizer = FunctionSummarizer({ requests++; Completion("""{"functions":[{"id":1,"label":"x","role":"bogus"},{"id":1,"label":"y"},{"id":9,"label":"z"}]}""") },
            InMemoryEnrichmentCache(), SummaryOptions(batchSize = 10))
        val result = summarizer.summarize(EnrichmentInput("demo", graph, facts(code = { if (it == 2) "fun write() = Unit" else "a\nb\nc\nd" })),
            calls, heuristics, emptyMap())
        assertNull(result.summaries[2])
        assertTrue(result.summaries.isEmpty())
        assertTrue(result.failures > 0)
        assertTrue(requests > 0)
        val decoded = FunctionSummarizer.decode(JsonParser.parseString("""{"functions":[{"id":1,"label":"x","role":"bogus","single":true}]}""").asJsonObject, 1)
        assertEquals(FunctionSummary("x", null, null, true), decoded.getValue(1))
    }

    @Test fun `unknown and external calls survive short source and invalidate summary caches when resolved`() = runBlocking {
        val prompts = mutableListOf<String>()
        val summarizer = FunctionSummarizer({ request ->
            val user = request.messages.last().content
            prompts += user
            Completion(answer(user))
        }, InMemoryEnrichmentCache())
        val occurrence = CallOccurrence(0, 0, "/p/Users.kt", 10, 30, "llm.complete", "llm.complete()",
            CallOperation.INVOKE, emptyList(), unresolvedTarget = 7, receiver = "llm")
        val boundary = CallGraphNode(7, "llm.complete", "", "Users.kt:2", 0, false, SymbolKind.SYMBOL, unresolved = true)
        val unknownGraph = graph.copy(nodes = graph.nodes + boundary, edges = setOf(CallGraphEdge(0, 7)))
        val input = EnrichmentInput("demo", unknownGraph, facts(code = { "llm.complete()" }).filterKeys { it == 0 }, listOf(occurrence))
        val first = summarizer.summarize(input, unknownGraph.edges.groupBy { it.caller }, emptyMap(), emptyMap())
        assertEquals(1, first.requests)
        val evidence = JsonParser.parseString(prompts.single()).asJsonObject.getAsJsonArray("functions")[0]
            .asJsonObject.getAsJsonArray("callEvidence")[0].asJsonObject
        assertEquals("unresolved", evidence.get("resolution").asString)
        assertEquals("llm.complete", evidence.get("expression").asString)
        assertTrue(evidence.getAsJsonArray("candidates").isEmpty)

        val resolvedGraph = unknownGraph.copy(nodes = graph.nodes + boundary.copy(name = "complete", kind = SymbolKind.FUNCTION, unresolved = false))
        val resolved = input.copy(graph = resolvedGraph, occurrences = listOf(occurrence.copy(targets = listOf(7), unresolvedTarget = null)))
        val second = summarizer.summarize(resolved, resolvedGraph.edges.groupBy { it.caller }, emptyMap(), emptyMap())
        assertEquals("Resolution evidence participates in the cache key", 1, second.requests)
        assertEquals(2, prompts.size)
        assertTrue(prompts.last().contains("\"external\":true"))
        assertEquals(0, summarizer.summarize(resolved, resolvedGraph.edges.groupBy { it.caller }, emptyMap(), emptyMap()).requests)
    }
}
