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
import java.util.concurrent.ConcurrentHashMap

/** Most functions summarized for one level of a schematic; the nearest are kept, deeper ones are summarized when opened. */
const val SCHEMATIC_SUMMARY_LIMIT = 300

/**
 * Bottom-up summaries of the functions a schematic reaches, shared by all its levels: a function's summary is written
 * from its callees' summaries, so each one condenses the whole tree below it. Summaries are cached by content, so
 * summarizing an overlapping set again only costs requests for what was never summarized. Thread-safe.
 */
class SchematicSummaries(private val project: Project, private val result: CallGraphResult, private val lifted: CallGraph) {
    private val known = ConcurrentHashMap<Int, FunctionSummary>()
    /** Functions already included in a run, whether or not they got a summary (trivial and test code do not). */
    private val covered = ConcurrentHashMap.newKeySet<Int>()
    private val calls = lifted.edges.filter { it.kind == RelationKind.CALL && it.caller != it.callee }.groupBy({ it.caller }, { it.callee })

    operator fun get(id: Int): FunctionSummary? = known[id]

    /** True when [id] was part of a finished run; without a summary it is trivial, test code, or its request failed. */
    fun isCovered(id: Int): Boolean = id in covered

    /** Whether summarizing from [roots] would include any function no run has included yet. */
    fun needs(roots: Collection<Int>): Boolean = roots.any { it !in covered && isFunction(it) }

    /**
     * Summarizes the functions reachable from [roots], nearest first up to [SCHEMATIC_SUMMARY_LIMIT]. Classes among
     * [roots] stand for the functions they declare. Returns the number of failed functions.
     */
    suspend fun summarize(roots: Collection<Int>, progress: EnrichmentProgress = EnrichmentProgress.NONE): Int {
        val starts = roots.flatMap { id -> if (lifted.nodes.getOrNull(id)?.kind == SymbolKind.CLASS) members(id) else listOf(id) }
        val depths = reachable(starts.filter(::isFunction), SCHEMATIC_SUMMARY_LIMIT) { id -> calls[id].orEmpty().filter(::isFunction) }
        if (depths.isEmpty()) return 0
        val facts = smartReadAction(project) { SymbolFactsExtractor.extract(project, result, only = depths.keys) }
        val input = EnrichmentInput(project.name, lifted, facts, result.occurrences)
        val summarized = project.service<EnrichmentService>().summarizeFunctions(input, flowCalls(lifted, facts),
            facts.mapValues { Heuristics(HeuristicRules.tags(it.value)) },
            // Nearer functions rank higher, should the summarizer have to leave some out.
            depths.mapValues { 1.0 / (1 + it.value) },
            SummaryOptions(maxFunctions = SCHEMATIC_SUMMARY_LIMIT), progress)
        known.putAll(summarized.summaries)
        covered.addAll(depths.keys)
        return summarized.failures
    }

    private fun isFunction(id: Int) = lifted.nodes.getOrNull(id)?.let { it.inProject && it.kind == SymbolKind.FUNCTION } == true

    private fun members(id: Int): List<Int> = lifted.edges.filter { it.kind == RelationKind.CONTAINS && it.caller == id }.map { it.callee }
}
