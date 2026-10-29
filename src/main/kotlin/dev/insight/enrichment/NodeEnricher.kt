package dev.insight.enrichment

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.insight.callgraph.RelationKind
import com.intellij.openapi.diagnostic.logger
import dev.insight.decisions.DecisionAnswer
import dev.insight.decisions.DecisionApiException
import dev.insight.decisions.DecisionFn
import dev.insight.decisions.DecisionQuestion
import dev.insight.decisions.DecisionRequest
import dev.insight.decisions.DecisionResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import dev.insight.core.Hashing
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

data class NodeEnrichmentResult(
    val annotations: Map<Int, NodeAnnotation>,
    val requests: Int,
    val cacheHits: Int,
    val failures: Int,
    val inputTokens: Long,
)

/**
 * Classifies symbols with one decisions request per symbol carrying every [Questions.node] question. The state holds
 * the symbol's facts plus short descriptors of its in-project CALL neighbours, so the model sees how it is used.
 * Answers are cached by everything that shapes the request; a failed request leaves the symbol unannotated.
 */
class NodeEnricher(
    private val evaluate: DecisionFn,
    private val cache: EnrichmentCache = InMemoryEnrichmentCache(),
    private val concurrency: Int = 4,
    private val maxNeighbours: Int = 12,
) {
    init { require(concurrency > 0) { "Concurrency must be positive" } }

    suspend fun enrich(
        input: EnrichmentInput,
        ids: Collection<Int>,
        domains: List<Domain>,
        progress: EnrichmentProgress = EnrichmentProgress.NONE,
    ): NodeEnrichmentResult {
        val targets = ids.distinct().mapNotNull { input.facts[it] }
        val questions = Questions.node(domains)
        val domainsHash = sha256(domains.joinToString("\n") { "${it.id}\t${it.name}\t${it.description}" })
        val neighbours = Neighbours(input)
        val annotations = ConcurrentHashMap<Int, NodeAnnotation>()
        val requests = AtomicInteger()
        val cacheHits = AtomicInteger()
        val failures = AtomicInteger()
        val tokens = AtomicLong()
        val done = AtomicInteger()
        val semaphore = Semaphore(concurrency)
        progress.report(PHASE, 0, targets.size)
        coroutineScope {
            targets.map { facts ->
                async {
                    val state = state(facts, neighbours.callers(facts.id), neighbours.callees(facts.id))
                    val key = "node:" + sha256("v${Questions.VERSION}\n${facts.contentHash}\n$domainsHash\n$state")
                    val cached = cache.node(key)
                    if (cached != null) {
                        cacheHits.incrementAndGet()
                        annotations[facts.id] = cached
                    } else semaphore.withPermit {
                        try {
                            requests.incrementAndGet()
                            val response = evaluate(DecisionRequest(state, questions))
                            tokens.addAndGet(response.usage.inputTokens)
                            val annotation = annotation(response, questions)
                            cache.putNode(key, annotation)
                            annotations[facts.id] = annotation
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logFailure(failures.incrementAndGet(), e)
                        }
                    }
                    progress.report(PHASE, done.incrementAndGet(), targets.size)
                }
            }.awaitAll()
        }
        return NodeEnrichmentResult(annotations.toMap(), requests.get(), cacheHits.get(), failures.get(), tokens.get())
    }

    /** Distinct in-project CALL neighbours as sorted `container.name` descriptors, capped at [maxNeighbours]. */
    private inner class Neighbours(input: EnrichmentInput) {
        private val callers = HashMap<Int, MutableSet<String>>()
        private val callees = HashMap<Int, MutableSet<String>>()

        init {
            val nodes = input.graph.nodes.associateBy { it.id }
            for (edge in input.graph.edges) {
                if (edge.kind != RelationKind.CALL || edge.caller == edge.callee) continue
                val caller = nodes[edge.caller]?.takeIf { it.inProject } ?: continue
                val callee = nodes[edge.callee]?.takeIf { it.inProject } ?: continue
                callers.getOrPut(callee.id) { sortedSetOf() } += describe(caller.container, caller.name)
                callees.getOrPut(caller.id) { sortedSetOf() } += describe(callee.container, callee.name)
            }
        }

        fun callers(id: Int) = callers[id].orEmpty().take(maxNeighbours)
        fun callees(id: Int) = callees[id].orEmpty().take(maxNeighbours)
    }

    private fun state(facts: SymbolFacts, callers: List<String>, callees: List<String>) = JsonObject().apply {
        add("symbol", JsonObject().apply {
            put("name", facts.name)
            put("kind", facts.kind.name.lowercase())
            put("container", facts.container)
            put("path", facts.path)
            put("language", facts.language)
            put("signature", facts.signature)
            put("doc", facts.doc)
            put("annotations", facts.annotations)
            put("code", facts.code)
        })
        put("callers", callers)
        put("callees", callees)
    }

    private fun annotation(response: DecisionResponse, questions: Map<String, DecisionQuestion>): NodeAnnotation {
        val answers = response.answers
        fun noul(id: String) = (answers[id] as? DecisionAnswer.Noul)?.noul
        fun choice(id: String) = (answers[id] as? DecisionAnswer.Choice)?.let { ChoiceValue(it.choice, it.probabilities, it.confidence) }
        val importanceLevels = (questions[Questions.IMPORTANCE] as DecisionQuestion.Score).criteria.size
        return NodeAnnotation(
            concerns = answers.entries.mapNotNull { (id, answer) ->
                val concern = Questions.concernId(id) ?: return@mapNotNull null
                (answer as? DecisionAnswer.Noul)?.let { concern to it.noul }
            }.toMap(),
            phase = choice(Questions.PHASE),
            layer = choice(Questions.LAYER),
            importance = (answers[Questions.IMPORTANCE] as? DecisionAnswer.Score)
                ?.let { ScoreValue(it.score, importanceLevels - 1, it.confidence) },
            entryPoint = noul(Questions.ENTRY_POINT),
            utility = noul(Questions.UTILITY),
            domain = choice(Questions.DOMAIN),
        )
    }

    internal companion object {
        const val PHASE = "Classifying symbols"
        private val LOG = logger<NodeEnricher>()

        /** Logs the first few failures of a run; API validation errors explain themselves in the response body. */
        fun logFailure(count: Int, error: Exception) {
            if (count > 5) return
            val detail = (error as? DecisionApiException)?.responseBody?.take(300)?.let { ": $it" }.orEmpty()
            LOG.warn("Decisions request failed ($count)$detail", error)
        }

        fun sha256(text: String): String = Hashing.sha256Of(text)

        fun describe(container: String, name: String) = if (container.isBlank()) name else "$container.$name"

        /** Adds [value] unless it is null or blank, so the state stays free of empty fields. */
        fun JsonObject.put(key: String, value: String?) { if (!value.isNullOrBlank()) addProperty(key, value) }

        fun JsonObject.put(key: String, values: List<String>) {
            if (values.isNotEmpty()) add(key, JsonArray().apply { values.forEach(::add) })
        }
    }
}
