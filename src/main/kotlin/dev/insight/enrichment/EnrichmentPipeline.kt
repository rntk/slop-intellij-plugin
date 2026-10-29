package dev.insight.enrichment

import dev.insight.callgraph.CallGraph
import dev.insight.callgraph.CallGraphEdge
import dev.insight.callgraph.RelationKind
import dev.insight.callgraph.SymbolKind
import dev.insight.decisions.DecisionFn
import dev.insight.decisions.DecisionRequest
import dev.insight.decisions.DecisionResponse
import dev.insight.llm.CompletionFn
import kotlinx.coroutines.delay
import java.net.http.HttpTimeoutException

data class EnrichmentOptions(
    val labels: Boolean = true,
    val taxonomy: Boolean = true,
    val nodes: Boolean = true,
    val edges: Boolean = true,
    val flows: Boolean = true,
    /** Bottom-up function summaries for the compact flow chart; one generative request per batch of uncached functions. */
    val summaries: Boolean = true,
    /** Edge judgments cost one request each; beyond this, edges of the least visible callers are skipped. */
    val maxEdges: Int = 4000,
    val decisionConcurrency: Int = 4,
    /** Extra attempts for decisions requests that time out. */
    val timeoutRetries: Int = 2,
    val flowOptions: FlowOptions = FlowOptions(),
    val summaryOptions: SummaryOptions = SummaryOptions(),
)

/**
 * Runs every enrichment phase over PSI-free [EnrichmentInput]. Rules and structure come first because they are free;
 * generative labels precede the taxonomy that they summarise; the taxonomy precedes node judgments that choose a
 * domain; node and edge judgments precede the flows traced over them. Function summaries are written bottom-up after
 * visibility is known, which ranks them when [SummaryOptions.maxFunctions] applies; rule effects need no model. Each
 * phase degrades on failure instead of failing the run. [complete] may be null to skip the generative phases.
 */
class EnrichmentPipeline(
    private val evaluate: DecisionFn,
    private val complete: CompletionFn?,
    private val cache: EnrichmentCache,
    private val options: EnrichmentOptions = EnrichmentOptions(),
) {
    /** Edge keys of the result refer to [liftCalls] of the input graph. */
    suspend fun run(original: EnrichmentInput, progress: EnrichmentProgress = EnrichmentProgress.NONE): Enrichment {
        val input = original.copy(graph = liftCalls(original.graph))
        val started = System.currentTimeMillis()
        val graph = input.graph
        val heuristics = input.facts.mapValues { (_, facts) -> Heuristics(HeuristicRules.tags(facts)) }
        val metrics = StructuralAnalysis.metrics(graph)

        val labels = if (options.labels && complete != null) GroupLabeler(complete, cache).label(input, progress) else null
        val domains = if (options.taxonomy && complete != null) {
            progress.report("Discovering functional areas", 0, 1)
            TaxonomyDiscovery(complete, cache).discover(input, labels?.labels.orEmpty()).also { progress.report("Discovering functional areas", 1, 1) }
        } else emptyList()

        val classified = input.facts.values.filter { it.kind in CLASSIFIED }.map { it.id }
        val nodes = if (options.nodes) {
            NodeEnricher(::evaluateWithRetry, cache, options.decisionConcurrency).enrich(input, classified, domains, progress)
        } else null
        val annotations = nodes?.annotations.orEmpty()
        val visibility = graph.nodes.associate { node ->
            node.id to visibility(annotations[node.id], metrics[node.id], heuristics[node.id])
        }

        val edges = if (options.edges) {
            EdgeEnricher(::evaluateWithRetry, cache, options.decisionConcurrency).enrich(input, judgedEdges(input, visibility), progress)
        } else null
        val calls = flowCalls(graph, input.facts)
        val effects = Effects.compute(graph, calls, input.facts, heuristics)
        val summaries = if (options.summaries && complete != null) {
            FunctionSummarizer(complete, cache, options.summaryOptions).summarize(input, calls, heuristics, visibility, progress)
        } else null
        val flows = if (options.flows) {
            FlowTracer(complete, cache, options.flowOptions).trace(input, annotations, edges?.annotations.orEmpty(), heuristics, progress)
        } else null
        cache.flush()

        return Enrichment(
            nodes = annotations,
            heuristics = heuristics.filterValues { it.tags.isNotEmpty() },
            metrics = metrics,
            edges = edges?.annotations.orEmpty(),
            domains = domains,
            flows = flows?.flows.orEmpty(),
            groupLabels = labels?.labels.orEmpty(),
            visibility = visibility,
            summaries = summaries?.summaries.orEmpty(),
            effects = effects,
            stats = EnrichmentStats(
                nodeRequests = nodes?.requests ?: 0,
                edgeRequests = edges?.requests ?: 0,
                llmRequests = (labels?.requests ?: 0) + (if (domains.isNotEmpty()) 1 else 0) + (flows?.llmRequests ?: 0) + (summaries?.requests ?: 0),
                cacheHits = (nodes?.cacheHits ?: 0) + (edges?.cacheHits ?: 0) + (summaries?.cacheHits ?: 0),
                failures = (nodes?.failures ?: 0) + (edges?.failures ?: 0) + (labels?.failures ?: 0) + (flows?.failures ?: 0) + (summaries?.failures ?: 0),
                inputTokens = (nodes?.inputTokens ?: 0) + (edges?.inputTokens ?: 0),
                durationMillis = System.currentTimeMillis() - started,
            ),
        )
    }

    /** A timeout usually means the server queue is long, not that the request is bad: try again after a pause. */
    private suspend fun evaluateWithRetry(request: DecisionRequest): DecisionResponse {
        repeat(options.timeoutRetries) { attempt ->
            try {
                return evaluate(request)
            } catch (e: HttpTimeoutException) {
                com.intellij.openapi.diagnostic.Logger.getInstance(EnrichmentPipeline::class.java)
                    .info("Decision request timed out (attempt ${attempt + 1}); retrying", e)
                delay(2_000L shl attempt)
            }
        }
        return evaluate(request)
    }

    /** In-project calls between classified symbols, most visible callers first, capped at [EnrichmentOptions.maxEdges]. */
    private fun judgedEdges(input: EnrichmentInput, visibility: Map<Int, Double>): List<CallGraphEdge> =
        input.graph.edges
            .filter { edge ->
                edge.kind == RelationKind.CALL && edge.caller != edge.callee &&
                    input.facts[edge.caller]?.kind in CALLERS && input.facts[edge.callee]?.kind in CLASSIFIED
            }
            .sortedWith(compareByDescending<CallGraphEdge> { visibility[it.caller] ?: 0.0 }.thenBy { it.caller }.thenBy { it.callee })
            .take(options.maxEdges)

    private companion object {
        val CLASSIFIED = setOf(SymbolKind.FUNCTION, SymbolKind.CLASS)
        val CALLERS = setOf(SymbolKind.FUNCTION, SymbolKind.PROPERTY)
    }
}

/**
 * The analyzer attributes a call to its nearest declaration, which may be a local variable or parameter
 * (`val panel = Panel(project)` yields `panel → Panel`). Judgments, metrics and flows reason about functions, so such
 * CALL edges are re-attributed to the nearest enclosing declaration that is not a local, following CONTAINS edges.
 */
fun liftCalls(graph: CallGraph): CallGraph = graph.copy(edges = liftedEdges(graph).values.toCollection(LinkedHashSet()))

/** Original relationships mapped to the relationships used by enrichment. Lifted self-calls are omitted. */
internal fun liftedEdges(graph: CallGraph): Map<CallGraphEdge, CallGraphEdge> {
    val parent = HashMap<Int, Int>()
    graph.edges.forEach { if (it.kind == RelationKind.CONTAINS && it.caller != it.callee) parent.putIfAbsent(it.callee, it.caller) }
    fun owner(id: Int): Int {
        var current = id
        val seen = hashSetOf(current)
        while (graph.nodes.getOrNull(current)?.kind in LOCALS) current = parent[current]?.takeIf(seen::add) ?: return current
        return current
    }
    return graph.edges.mapNotNull { edge ->
        val lifted = if (edge.kind != RelationKind.CALL) edge
        else edge.copy(caller = owner(edge.caller)).takeIf { it.caller != it.callee }
        lifted?.let { edge to it }
    }.toMap()
}

private val LOCALS = setOf(SymbolKind.VARIABLE, SymbolKind.PARAMETER)
