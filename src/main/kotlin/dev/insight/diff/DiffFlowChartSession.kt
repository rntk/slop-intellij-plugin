package dev.insight.diff

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightVirtualFile
import dev.insight.callgraph.CallSiteAnalyzer
import dev.insight.callgraph.DiffService
import dev.insight.callgraph.ProgressiveSchematic
import dev.insight.callgraph.nodeLineRanges
import dev.insight.enrichment.EnrichmentSnapshot
import dev.insight.enrichment.FlowChartLens
import dev.insight.enrichment.SymbolFactsExtractor
import dev.insight.graph.CallGraphResult
import dev.insight.graph.SymbolKind

/**
 * A diff's flow chart in an editor tab: [chart] over the exploration [result], titled [title]. [stamps] are the document
 * stamps of the charted files when it was prepared; [unavailable] lists changed files that gave no evidence.
 */
internal class DiffFlowChartFile(
    name: String,
    val title: String,
    val result: CallGraphResult,
    val chart: DiffFlowChart,
    val stamps: Map<String, Long>,
    val unavailable: List<String>,
) : LightVirtualFile("Flowchart: $name")

/**
 * Charts the change around [path] as flow: the same evidence as the diff canvas (changed files whose source matches the
 * reviewed after-side, resolved whole, and the selected file's connected component of calls, references and
 * inheritance), with declarations mapped to the lines this diff changes. Labels come from [enrichment] where it
 * describes this code. Call off the EDT.
 */
internal suspend fun prepareDiffFlowChart(project: Project, snapshot: ReviewSnapshot, path: String,
    enrichment: EnrichmentSnapshot?): DiffFlowChartFile {
    val selected = snapshot.files.firstOrNull { it.path == path } ?: error("$path is no longer in this diff.")
    val eligible = matchingDiffSources(project, snapshot)
    val source = eligible[selected.path]
        ?: error("${selected.path} has no current source matching the reviewed after-side. Refresh the diff or check out its target revision before opening it on a flowchart.")
    val exploration = ProgressiveSchematic.open(project, source)
        ?: error("${selected.path} cannot be shown on a flowchart. Check that its language plugin is enabled.")
    val (supported, connections) = relateDiffFiles(project, snapshot, exploration, eligible, "flowchart")
    check(selected.path in supported) { "${selected.path} cannot be shown on a flowchart. Check that its language plugin is enabled." }
    val component = listOf(path) + relatedDiffFiles(path, connections).map { it.path }
    val reviews = component.map { charted -> snapshot.files.first { it.path == charted } }
    // Both sides with the line separators of the current source, whose text is the after-side (checked again below).
    val texts = reviews.associate { review ->
        review.path to (review.before?.let(StringUtil::convertLineSeparators) to StringUtil.convertLineSeparators(checkNotNull(review.after)))
    }
    // Line comparison needs no PSI.
    val lineChanges = texts.mapValues { (_, sides) -> sides.first?.let { DiffService.lineChanges(it, sides.second) }.orEmpty() }
    val result = exploration.result
    val lens = FlowChartLens(enrichment, result.graph)
    val absolute = component.associateBy { supported.getValue(it).path }
    return smartReadAction(project) {
        val documents = PsiDocumentManager.getInstance(project)
        val stamps = reviews.associate { review ->
            val psi = PsiManager.getInstance(project).findFile(supported.getValue(review.path))
            check(psi?.text == texts.getValue(review.path).second) {
                "${review.path} changed while preparing the flowchart. Refresh the diff and retry."
            }
            review.path to (documents.getDocument(psi!!)?.modificationStamp ?: psi.modificationStamp)
        }
        val ranges = nodeLineRanges(project, result, absolute.keys)
        val changes = ranges.entries.groupBy({ absolute.getValue(it.value.first) }, { it.key to it.value.second })
            .flatMap { (reviewPath, declared) ->
                val (before, after) = texts.getValue(reviewPath)
                declarationChanges(declared.toMap(), lineChanges.getValue(reviewPath), after, before).entries
            }.associate { it.key to it.value }
        val order = component.withIndex().associate { it.value to it.index }
        val functions = result.graph.nodes.filter { it.kind == SymbolKind.FUNCTION && it.filePath in absolute }
        val changed = functions.filter { it.id in changes }
            .sortedWith(compareBy({ order.getValue(absolute.getValue(it.filePath!!)) }, { ranges[it.id]?.second?.first ?: 0 }))
            .map { it.id }
        val owners = functions.map { it.id }.filter { it in lens.calls }
        val callees = owners.flatMap { owner -> lens.calls[owner].orEmpty().map { it.callee } }.distinct()
        val sites = CallSiteAnalyzer.collect(result, owners, callees)
        val facts = SymbolFactsExtractor.extract(project, result, only = (functions.map { it.id } + callees).toSet())
        val chart = DiffFlowChart(result.graph, lens, changes, changed, sites, facts)
        DiffFlowChartFile(source.name, "${source.name} · ${snapshot.comparison.summary}", result, chart,
            stamps.mapKeys { supported.getValue(it.key).path },
            snapshot.files.filter { it.path !in supported }.map { it.path })
    }
}
