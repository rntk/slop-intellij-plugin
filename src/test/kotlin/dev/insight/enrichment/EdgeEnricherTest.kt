package dev.insight.enrichment

import dev.insight.callgraph.CallGraph
import dev.insight.callgraph.CallGraphEdge
import dev.insight.callgraph.RelationKind
import dev.insight.decisions.DecisionRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class EdgeEnricherTest {
    private val nodes = (0..3).map { EnricherFakes.node(it, "f$it") }
    private val facts = mapOf(
        0 to EnricherFakes.facts(0, "f0", code = "fun f0() { try { f1() } catch (e: Exception) { f2() } }" + "x".repeat(10_000)),
        1 to EnricherFakes.facts(1, "f1", doc = "Does the work."),
        2 to EnricherFakes.facts(2, "f2"),
    )
    private val input = EnrichmentInput("demo", CallGraph(nodes, emptySet(), false), facts)

    @Test fun `sends caller and callee state and maps answers`() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<DecisionRequest>())
        val result = EdgeEnricher({ requests += it; EnricherFakes.answer(it) }).enrich(input, listOf(CallGraphEdge(0, 1)))
        val request = requests.single()
        assertEquals(Questions.edge().keys, request.questions.keys)
        val state = request.state.asJsonObject
        assertEquals("demo", state["project"].asString)
        val caller = state["caller"].asJsonObject
        val callee = state["callee"].asJsonObject
        assertEquals(setOf("name", "kind", "container", "path", "signature", "code"), caller.keySet())
        assertEquals(EdgeEnricher.MAX_CALLER_CODE, caller["code"].asString.length)
        assertTrue(caller["code"].asString.startsWith("fun f0() { try"))
        assertEquals(setOf("name", "kind", "container", "path", "signature", "doc"), callee.keySet())
        assertEquals("Does the work.", callee["doc"].asString)
        assertEquals(EdgeAnnotation(0.7, 0.7), result.annotations[CallGraphEdge(0, 1)])
        assertEquals(EdgeEnrichmentResult(result.annotations, 1, 0, 0, 100), result)
    }

    @Test fun `skips non-call edges and edges without facts, counts failures, reuses cache`() = runBlocking {
        val calls = AtomicInteger()
        val cache = InMemoryEnrichmentCache()
        val enricher = EdgeEnricher({
            calls.incrementAndGet()
            if (it.state.asJsonObject["callee"].asJsonObject["name"].asString == "f2") throw IOException("boom")
            EnricherFakes.answer(it, noul = 0.2)
        }, cache)
        val edges = listOf(CallGraphEdge(0, 1), CallGraphEdge(0, 1), CallGraphEdge(0, 2), CallGraphEdge(0, 3), CallGraphEdge(1, 2, RelationKind.REFERENCE))
        val progress = Collections.synchronizedList(mutableListOf<Int>())
        val first = enricher.enrich(input, edges) { phase, done, total -> assertEquals("Classifying calls", phase); assertEquals(2, total); progress += done }
        assertEquals(setOf(CallGraphEdge(0, 1)), first.annotations.keys)
        assertEquals(EdgeAnnotation(0.2, 0.2), first.annotations.values.single())
        assertEquals(2, first.requests)
        assertEquals(1, first.failures)
        assertEquals(listOf(0, 1, 2), progress.sorted())

        val second = enricher.enrich(input, edges)
        assertEquals(1, second.cacheHits)
        assertEquals("the failed edge is retried", 1, second.requests)

        val edited = input.copy(facts = facts + (1 to EnricherFakes.facts(1, "f1", doc = "Now different.")))
        assertEquals("callee change invalidates", 1, enricher.enrich(edited, listOf(CallGraphEdge(0, 1))).requests)
        assertEquals(4, calls.get())
    }

    @Test fun `concurrency is bounded`() = runBlocking {
        val many = (0 until 20).map { EnricherFakes.node(it, "g$it") }
        val manyFacts = many.associate { it.id to EnricherFakes.facts(it.id, it.name) }
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val result = EdgeEnricher({
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            delay(10)
            active.decrementAndGet()
            EnricherFakes.answer(it)
        }, concurrency = 2).enrich(EnrichmentInput("demo", CallGraph(many, emptySet(), false), manyFacts), (1 until 20).map { CallGraphEdge(0, it) })
        assertEquals(19, result.annotations.size)
        assertEquals(2, peak.get())
        assertTrue(result.annotations.values.all { it == EdgeAnnotation(0.7, 0.7) })
    }
}
