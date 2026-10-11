package dev.insight.enrichment

import com.google.gson.JsonObject
import dev.insight.callgraph.CallGraphEdge
import dev.insight.callgraph.RelationKind
import dev.insight.decisions.DecisionAnswer
import dev.insight.decisions.DecisionRequest
import dev.insight.decisions.DecisionResponse
import dev.insight.enrichment.NodeEnricher.Companion.logFailure
import dev.insight.enrichment.NodeEnricher.Companion.put
import dev.insight.enrichment.NodeEnricher.Companion.sha256
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

data class EdgeEnrichmentResult(
    val annotations: Map<CallGraphEdge, EdgeAnnotation>,
    val requests: Int,
    val cacheHits: Int,
    val failures: Int,
    val inputTokens: Long,
)

/**
 * Judges CALL edges with one decisions request per edge carrying [Questions.edge]. The caller's code is the evidence
 * for "error path", so it is sent in full up to [MAX_CALLER_CODE] characters. Non-CALL edges and edges whose endpoints
 * have no facts are skipped; a failed request leaves the edge unannotated.
 */
class EdgeEnricher(
    private val evaluate: suspend (DecisionRequest) -> DecisionResponse,
    private val cache: EnrichmentCache = InMemoryEnrichmentCache(),
    private val concurrency: Int = 4,
) {
    init { require(concurrency > 0) { "Concurrency must be positive" } }

    suspend fun enrich(
        input: EnrichmentInput,
        edges: Collection<CallGraphEdge>,
        progress: EnrichmentProgress = EnrichmentProgress.NONE,
    ): EdgeEnrichmentResult {
        val targets = edges.distinct().filter { it.kind == RelationKind.CALL }.mapNotNull { edge ->
            val caller = input.facts[edge.caller] ?: return@mapNotNull null
            val callee = input.facts[edge.callee] ?: return@mapNotNull null
            Triple(edge, caller, callee)
        }
        val questions = Questions.edge()
        val annotations = ConcurrentHashMap<CallGraphEdge, EdgeAnnotation>()
        val requests = AtomicInteger()
        val cacheHits = AtomicInteger()
        val failures = AtomicInteger()
        val tokens = AtomicLong()
        val done = AtomicInteger()
        val semaphore = Semaphore(concurrency)
        progress.report(PHASE, 0, targets.size)
        coroutineScope {
            targets.map { (edge, caller, callee) ->
                async {
                    val state = state(input.projectName, caller, callee)
                    val key = "edge:" + sha256("v${Questions.VERSION}\n${caller.contentHash}\n${callee.contentHash}\nedge\n$state")
                    val cached = cache.edge(key)
                    if (cached != null) {
                        cacheHits.incrementAndGet()
                        annotations[edge] = cached
                    } else semaphore.withPermit {
                        try {
                            requests.incrementAndGet()
                            val response = evaluate(DecisionRequest(state, questions))
                            tokens.addAndGet(response.usage.inputTokens)
                            val annotation = annotation(response)
                            cache.putEdge(key, annotation)
                            annotations[edge] = annotation
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
        return EdgeEnrichmentResult(annotations.toMap(), requests.get(), cacheHits.get(), failures.get(), tokens.get())
    }

    private fun state(project: String, caller: SymbolFacts, callee: SymbolFacts) = JsonObject().apply {
        put("project", project)
        add("caller", JsonObject().apply {
            identity(caller)
            put("code", caller.code.take(MAX_CALLER_CODE))
        })
        add("callee", JsonObject().apply {
            identity(callee)
            put("doc", callee.doc)
        })
    }

    private fun JsonObject.identity(facts: SymbolFacts) {
        put("name", facts.name)
        put("kind", facts.kind.name.lowercase())
        put("container", facts.container)
        put("path", facts.path)
        put("signature", facts.signature)
    }

    private fun annotation(response: DecisionResponse): EdgeAnnotation {
        fun noul(id: String) = (response.answers[id] as? DecisionAnswer.Noul)?.noul
            ?: throw IllegalStateException("Missing answer $id")
        return EdgeAnnotation(errorPath = noul(Questions.ERROR_PATH), continuesOperation = noul(Questions.CONTINUES))
    }

    companion object {
        const val PHASE = "Classifying calls"
        const val MAX_CALLER_CODE = 4000
    }
}
