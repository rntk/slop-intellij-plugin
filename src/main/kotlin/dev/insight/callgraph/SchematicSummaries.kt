package dev.insight.callgraph

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.insight.enrichment.EnrichmentInput
import dev.insight.enrichment.EnrichmentProgress
import dev.insight.enrichment.EnrichmentService
import dev.insight.enrichment.FunctionSummary
import dev.insight.enrichment.HeuristicRules
import dev.insight.enrichment.Heuristics
import dev.insight.enrichment.SummaryOptions
import dev.insight.enrichment.SymbolFactsExtractor
import dev.insight.enrichment.flowCalls
import dev.insight.enrichment.liftCalls
import java.util.concurrent.ConcurrentHashMap

/** Bounds an explicit request; folded descendants are never added to it. */
const val SCHEMATIC_SUMMARY_LIMIT = 20

/**
 * Local summaries of explicitly requested functions, shared by all levels. A summary describes the symbol's source;
 * it never claims to condense unopened descendants. Content caching is handled by the enrichment service.
 */
class SchematicSummaries(private val project: Project, private val snapshot: () -> CallGraphResult) {
    private val known = ConcurrentHashMap<Int, FunctionSummary>()
    /** Functions already included in a run; test code may be skipped. */
    private val covered = ConcurrentHashMap.newKeySet<Int>()
    private val pending = ConcurrentHashMap.newKeySet<Int>()

    operator fun get(id: Int): FunctionSummary? = known[id]

    /** True when [id] was part of a finished run. */
    fun isCovered(id: Int): Boolean = id in covered

    /** Whether summarizing from [roots] would include any function no run has included yet. */
    fun needs(roots: Collection<Int>): Boolean = roots.any { it !in covered && it !in pending && isFunction(snapshot().graph, it) }

    fun isPending(id: Int): Boolean = id in pending

    /**
     * Summarizes only the requested functions. Returns the number of failed functions.
     */
    suspend fun summarize(roots: Collection<Int>, progress: EnrichmentProgress = EnrichmentProgress.NONE): Int {
        val result = snapshot()
        val lifted = liftCalls(result.graph)
        val requested = roots.distinct().filter { it !in covered && isFunction(lifted, it) }
            .take(SCHEMATIC_SUMMARY_LIMIT).filter { pending.add(it) }.toSet()
        if (requested.isEmpty()) return 0
        try {
            val facts = smartReadAction(project) { SymbolFactsExtractor.extract(project, result, only = requested) }
            val input = EnrichmentInput(project.name, lifted, facts, result.occurrences)
            val summarized = project.service<EnrichmentService>().summarizeFunctions(input, flowCalls(lifted, facts),
                facts.mapValues { Heuristics(HeuristicRules.tags(it.value)) },
                requested.associateWith { 1.0 },
                // An unopened function has no known callees yet; it cannot safely be classified as a trivial leaf.
                SummaryOptions(maxFunctions = SCHEMATIC_SUMMARY_LIMIT, trivialLines = 0), progress)
            known.putAll(summarized.summaries)
            covered.addAll(requested)
            return summarized.failures
        } finally {
            pending.removeAll(requested)
        }
    }

    private fun isFunction(graph: CallGraph, id: Int) = graph.nodes.getOrNull(id)?.let { it.inProject && it.kind == SymbolKind.FUNCTION } == true
}
