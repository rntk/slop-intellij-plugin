package dev.insight.enrichment

import dev.insight.callgraph.CallGraph
import dev.insight.callgraph.CallGraphEdge
import dev.insight.callgraph.SymbolKind

/**
 * PSI-free description of one graph node, extracted once inside a read action. Everything after extraction
 * works on these facts only, so enrichment can run off the read lock and be unit tested without PSI.
 */
data class SymbolFacts(
    /** [CallGraph] node id. */
    val id: Int,
    /** Stable across rebuilds: relative path, kind, container and name (plus an ordinal for overloads). */
    val key: String,
    val kind: SymbolKind,
    val name: String,
    val container: String,
    /** Project-relative path with `/` separators; null for symbols without a file. */
    val path: String?,
    /** Lower-case language id, for example `kotlin`, `java`, `python`. */
    val language: String?,
    /** Declaration header (annotations stripped, body excluded), at most a few lines. */
    val signature: String,
    /** Declaration source, truncated to a fixed budget; for files and classes an outline instead of the full text. */
    val code: String,
    val doc: String? = null,
    /** Annotation or decorator names without arguments, for example `Test`, `Service`, `app.route`. */
    val annotations: List<String> = emptyList(),
    /** Imports of the containing file. */
    val imports: List<String> = emptyList(),
    /** Hash of [signature], [code] and [doc]; cached judgments are reused while it is unchanged. */
    val contentHash: String,
    /** Full declaration hash: semantic summary caches must also notice edits beyond the excerpt budget. */
    val sourceHash: String = contentHash,
    val codeTruncated: Boolean = false,
)

data class EnrichmentInput(
    val projectName: String,
    val graph: CallGraph,
    /** Facts by node id; nodes without facts (parameters, variables, edited code) are not classified. */
    val facts: Map<Int, SymbolFacts>,
)

data class ChoiceValue(val choice: String, val probabilities: Map<String, Double>, val confidence: Double)

/** [score] is on the rubric scale 0..[max]; [normalized] maps it to 0..1. */
data class ScoreValue(val score: Double, val max: Int, val confidence: Double) {
    val normalized: Double get() = if (max == 0) 0.0 else score / max
}

/** Raw model judgments for one symbol. Thresholds, weights and colors are applied later by the view. */
data class NodeAnnotation(
    /** [Concern.id] → probability that the symbol mainly performs that concern. Several may be high. */
    val concerns: Map<String, Double> = emptyMap(),
    val phase: ChoiceValue? = null,
    val layer: ChoiceValue? = null,
    val importance: ScoreValue? = null,
    /** Probability that the symbol is an entry point (main, CLI command, route handler, IDE action, listener). */
    val entryPoint: Double? = null,
    /** Probability that the symbol is a generic, reusable utility rather than part of a specific feature. */
    val utility: Double? = null,
    /** Project-specific [Domain.id], or [Domain.NONE]. */
    val domain: ChoiceValue? = null,
)

/** Rule-derived facts that need no model: cheap, deterministic, and used to pre-fill or override judgments. */
data class Heuristics(
    /** Tags such as `test`, `entry:main`, `concern:storage`; see [HeuristicRules]. */
    val tags: Set<String> = emptySet(),
)

data class StructuralMetrics(
    /** Distinct in-project CALL callers and callees. */
    val fanIn: Int,
    val fanOut: Int,
    /** Normalized 0..1 betweenness centrality over the CALL graph (approximate for large graphs). */
    val betweenness: Double,
    /** True when the symbol has no in-project callers. */
    val root: Boolean,
)

/** Judgments for one CALL edge, from the caller's point of view. */
data class EdgeAnnotation(
    /** Probability that the call happens only on an error, failure or exceptional path. */
    val errorPath: Double,
    /** Probability that the callee continues the caller's own operation rather than serving as an incidental helper. */
    val continuesOperation: Double,
)

/** A project-specific functional area, proposed by the generative model and editable by the user. */
data class Domain(val id: String, val name: String, val description: String) {
    companion object { const val NONE = "none" }
}

/** A semantic chain of calls that starts at an entry point and performs one logical operation. */
data class Flow(
    val id: String,
    val name: String,
    val description: String?,
    val entry: Int,
    /** Node ids in traversal order, starting with [entry]. */
    val nodes: List<Int>,
    val edges: Set<CallGraphEdge>,
)

/** Short human labels for groups in the overview: directories keyed by path, files keyed by path. */
data class GroupLabel(val path: String, val label: String, val summary: String?)

data class EnrichmentStats(
    val nodeRequests: Int = 0,
    val edgeRequests: Int = 0,
    val llmRequests: Int = 0,
    val cacheHits: Int = 0,
    val failures: Int = 0,
    val inputTokens: Long = 0,
    val durationMillis: Long = 0,
)

/** Everything one enrichment run produced. Maps are keyed by graph node id or edge of the enriched [CallGraph]. */
data class Enrichment(
    val nodes: Map<Int, NodeAnnotation>,
    val heuristics: Map<Int, Heuristics>,
    val metrics: Map<Int, StructuralMetrics>,
    val edges: Map<CallGraphEdge, EdgeAnnotation>,
    val domains: List<Domain>,
    val flows: List<Flow>,
    val groupLabels: Map<String, GroupLabel>,
    /** 0..1 composite of importance and structure with [VisibilityWeights.DEFAULT]; recomputable without inference. */
    val visibility: Map<Int, Double>,
    val stats: EnrichmentStats,
    /** Bottom-up function summaries by node id; model interpretations, see [FunctionSummarizer]. */
    val summaries: Map<Int, FunctionSummary> = emptyMap(),
    /** Rule-derived side effects by node id, see [Effects]. */
    val effects: Map<Int, Effects> = emptyMap(),
)

/** Progress callback: a short phase name and completed/total units within that phase. */
fun interface EnrichmentProgress {
    fun report(phase: String, done: Int, total: Int)
    companion object { val NONE = EnrichmentProgress { _, _, _ -> } }
}

/**
 * Persistent store of judgments keyed by content, so unchanged code is never sent twice. Keys are built by the
 * enrichers from [Questions.VERSION], content hashes and every other part of the request state. Thread-safe.
 */
interface EnrichmentCache {
    fun node(key: String): NodeAnnotation?
    fun putNode(key: String, value: NodeAnnotation)
    fun edge(key: String): EdgeAnnotation?
    fun putEdge(key: String, value: EdgeAnnotation)
    /** Generative outputs (labels, taxonomy, flow names) stored as JSON text. */
    fun text(key: String): String?
    fun putText(key: String, value: String)
    /** Persists pending writes; a no-op for in-memory caches. */
    fun flush() {}
}

class InMemoryEnrichmentCache : EnrichmentCache {
    private val nodes = java.util.concurrent.ConcurrentHashMap<String, NodeAnnotation>()
    private val edges = java.util.concurrent.ConcurrentHashMap<String, EdgeAnnotation>()
    private val texts = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun node(key: String) = nodes[key]
    override fun putNode(key: String, value: NodeAnnotation) { nodes[key] = value }
    override fun edge(key: String) = edges[key]
    override fun putEdge(key: String, value: EdgeAnnotation) { edges[key] = value }
    override fun text(key: String) = texts[key]
    override fun putText(key: String, value: String) { texts[key] = value }
}
