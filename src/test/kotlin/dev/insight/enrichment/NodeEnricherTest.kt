package dev.insight.enrichment

import com.google.gson.JsonObject
import dev.insight.callgraph.CallGraph
import dev.insight.callgraph.CallGraphEdge
import dev.insight.callgraph.CallGraphNode
import dev.insight.callgraph.RelationKind
import dev.insight.callgraph.SymbolKind
import dev.insight.decisions.DecisionAnswer
import dev.insight.decisions.DecisionQuestion
import dev.insight.decisions.DecisionRequest
import dev.insight.decisions.DecisionResponse
import dev.insight.decisions.DecisionUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Fakes shared by the enrichment tests: answers every requested question with deterministic values. */
internal object EnricherFakes {
    fun answer(request: DecisionRequest, noul: Double = 0.7): DecisionResponse = DecisionResponse(
        "fake",
        request.questions.mapValues { (_, question) ->
            when (question) {
                is DecisionQuestion.Noul -> DecisionAnswer.Noul(noul)
                is DecisionQuestion.Choice -> {
                    val keys = question.criteria.keys.toList()
                    DecisionAnswer.Choice(keys.first(), keys.associateWith { if (it == keys.first()) 1.0 else 0.0 }, 0.9)
                }
                is DecisionQuestion.Score -> DecisionAnswer.Score(
                    question.criteria.lastIndex.toDouble(),
                    question.criteria.indices.associate { it.toString() to question.criteria[it] },
                    question.criteria.indices.associate { it.toString() to if (it == question.criteria.lastIndex) 1.0 else 0.0 },
                    0.8,
                )
            }
        },
        DecisionUsage(100, 5),
    )

    fun facts(id: Int, name: String, container: String = "Foo", code: String = "fun $name() {}", doc: String? = null) = SymbolFacts(
        id = id, key = "src/Foo.kt:$container.$name", kind = SymbolKind.FUNCTION, name = name, container = container,
        path = "src/Foo.kt", language = "kotlin", signature = "fun $name()", code = code, doc = doc,
        contentHash = "$name:${code.hashCode()}:${doc.hashCode()}",
    )

    fun node(id: Int, name: String, container: String = "Foo", inProject: Boolean = true) =
        CallGraphNode(id, name, container, "src/Foo.kt", 0, inProject, filePath = "src/Foo.kt")
}

class NodeEnricherTest {
    private val nodes = listOf(
        EnricherFakes.node(0, "main"), EnricherFakes.node(1, "load"), EnricherFakes.node(2, "parse"),
        EnricherFakes.node(3, "println", "kotlin.io", inProject = false), EnricherFakes.node(4, "Config", ""),
    )
    private val edges = setOf(
        CallGraphEdge(0, 1), CallGraphEdge(1, 2), CallGraphEdge(1, 3), CallGraphEdge(1, 1),
        CallGraphEdge(4, 1, RelationKind.REFERENCE), CallGraphEdge(4, 1, RelationKind.CONTAINS), CallGraphEdge(2, 4),
    )
    private val graph = CallGraph(nodes, edges, truncated = false, projectGraph = true)
    private fun input(facts: Map<Int, SymbolFacts> = nodes.filter { it.inProject }.associate { it.id to EnricherFakes.facts(it.id, it.name, it.container) }) =
        EnrichmentInput("demo", graph, facts)

    @Test fun `sends symbol state with in-project call neighbours and maps answers`() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<DecisionRequest>())
        val domains = listOf(Domain("cfg", "Configuration", "loading settings"))
        val enricher = NodeEnricher({ requests += it; EnricherFakes.answer(it) })
        val result = enricher.enrich(input(), listOf(1), domains)

        val request = requests.single()
        assertEquals(Questions.node(domains).keys, request.questions.keys)
        val state = request.state.asJsonObject
        assertFalse(state.has("project"))
        val symbol = state["symbol"].asJsonObject
        assertEquals("load", symbol["name"].asString)
        assertEquals("function", symbol["kind"].asString)
        assertEquals("Foo", symbol["container"].asString)
        assertEquals("kotlin", symbol["language"].asString)
        assertFalse("null doc and empty annotations are omitted", symbol.has("doc") || symbol.has("annotations"))
        assertEquals(listOf("Foo.main"), state["callers"].asJsonArray.map { it.asString })
        assertEquals("external callee, self call and non-CALL edges are excluded", listOf("Foo.parse"), state["callees"].asJsonArray.map { it.asString })

        val annotation = result.annotations.getValue(1)
        assertEquals(Questions.concerns.map { it.id }.toSet(), annotation.concerns.keys)
        assertEquals(0.7, annotation.concerns.getValue("config"), 1e-9)
        assertEquals("bootstrap", annotation.phase?.choice)
        assertEquals("presentation", annotation.layer?.choice)
        assertEquals(ScoreValue(4.0, 4, 0.8), annotation.importance)
        assertEquals(1.0, annotation.importance!!.normalized, 1e-9)
        assertEquals(0.7, annotation.entryPoint!!, 1e-9)
        assertEquals(0.7, annotation.utility!!, 1e-9)
        assertEquals("cfg", annotation.domain?.choice)
        assertEquals(NodeEnrichmentResult(result.annotations, 1, 0, 0, 100), result)
    }

    @Test fun `neighbours are capped, sorted and described without blank containers`() = runBlocking {
        val many = (10 until 30).map { EnricherFakes.node(it, "c$it") }
        val g = CallGraph(nodes + many, edges + many.map { CallGraphEdge(it.id, 4) }, false)
        var state: JsonObject? = null
        NodeEnricher({ state = it.state.asJsonObject; EnricherFakes.answer(it) }, maxNeighbours = 5)
            .enrich(EnrichmentInput("demo", g, input().facts), listOf(4), emptyList())
        assertEquals(listOf("Foo.c10", "Foo.c11", "Foo.c12", "Foo.c13", "Foo.c14"), state!!["callers"].asJsonArray.map { it.asString })
        assertEquals("Config", state["symbol"].asJsonObject["name"].asString)
        assertFalse(state["symbol"].asJsonObject.has("container"))
        assertFalse(state.has("callees"))
    }

    @Test fun `cache is reused until content, neighbours or domains change`() = runBlocking {
        val calls = AtomicInteger()
        val cache = InMemoryEnrichmentCache()
        val enricher = NodeEnricher({ calls.incrementAndGet(); EnricherFakes.answer(it) }, cache)
        val first = enricher.enrich(input(), listOf(0, 1, 2), emptyList())
        assertEquals(3, first.requests)

        val second = enricher.enrich(input(), listOf(0, 1, 2), emptyList())
        assertEquals(0, second.requests)
        assertEquals(3, second.cacheHits)
        assertEquals(first.annotations, second.annotations)

        val changed = input().facts.toMutableMap().apply { put(2, EnricherFakes.facts(2, "parse", code = "fun parse() { TODO() }")) }
        val third = enricher.enrich(input(changed), listOf(0, 1, 2), emptyList())
        assertEquals("only the changed symbol is re-sent", 1, third.requests)

        val withDomain = enricher.enrich(input(), listOf(0), listOf(Domain("a", "A", "first")))
        assertEquals(1, withDomain.requests)
        val renamedDomain = enricher.enrich(input(), listOf(0), listOf(Domain("a", "A", "second")))
        assertEquals("domain description is part of the key", 1, renamedDomain.requests)

        val rewired = CallGraph(nodes, edges - CallGraphEdge(0, 1), false)
        val fourth = enricher.enrich(EnrichmentInput("demo", rewired, input().facts), listOf(0), emptyList())
        assertEquals("losing a callee invalidates", 1, fourth.requests)
        assertEquals(7, calls.get())
    }

    @Test fun `failures are counted and skipped, nodes without facts are ignored`() = runBlocking {
        val progress = Collections.synchronizedList(mutableListOf<Triple<String, Int, Int>>())
        val enricher = NodeEnricher({ if (it.state.asJsonObject["symbol"].asJsonObject["name"].asString == "parse") throw IOException("boom") else EnricherFakes.answer(it) })
        val result = enricher.enrich(input(), listOf(0, 1, 2, 3), emptyList()) { phase, done, total -> progress += Triple(phase, done, total) }
        assertEquals(setOf(0, 1), result.annotations.keys)
        assertEquals(1, result.failures)
        assertEquals(3, result.requests)
        assertEquals(200, result.inputTokens)
        assertEquals(Triple("Classifying symbols", 0, 3), progress.first())
        assertEquals((0..3).toList(), progress.map { it.second }.sorted())
        assertTrue(progress.all { it.third == 3 })
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is rethrown`(): Unit = runBlocking {
        NodeEnricher({ throw CancellationException("stop") }).enrich(input(), listOf(0), emptyList())
    }

    @Test fun `concurrency is bounded`() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val many = (10 until 40).map { EnricherFakes.node(it, "f$it") }
        val facts = many.associate { it.id to EnricherFakes.facts(it.id, it.name) }
        val enricher = NodeEnricher({
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            delay(10)
            active.decrementAndGet()
            EnricherFakes.answer(it)
        }, concurrency = 3)
        val result = enricher.enrich(EnrichmentInput("demo", CallGraph(many, emptySet(), false), facts), facts.keys, emptyList())
        assertEquals(30, result.annotations.size)
        assertEquals(3, peak.get())
    }

    @Test fun `sha256 is hex`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", NodeEnricher.sha256(""))
    }
}
