package dev.insight.enrichment

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportRawProgress
import dev.insight.callgraph.CallGraph
import dev.insight.callgraph.CallGraphResult
import dev.insight.callgraph.FlowRegionEvidence
import dev.insight.callgraph.SymbolKind
import dev.insight.chat.ChatSettings
import dev.insight.llm.LlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/** One enrichment of one graph build. Ids in [enrichment] refer to [graph]. */
class EnrichmentSnapshot(val graph: CallGraph, val input: EnrichmentInput, val enrichment: Enrichment, val report: Path)

/**
 * Enriches the application graph with model judgments and keeps the latest result. Judgments are cached by content,
 * so enriching again after a rebuild only sends changed symbols.
 */
@Service(Service.Level.PROJECT)
class EnrichmentService(private val project: Project, val scope: CoroutineScope) {
    @Volatile var latest: EnrichmentSnapshot? = null
        private set
    private val cache by lazy { FileEnrichmentCache.forProject(project) }

    /** Where [enrich] writes the readable [EnrichmentReport]; outside the project so nothing is added to version control. */
    val reportPath: Path get() = Path.of(PathManager.getSystemPath(), "insight-enrichment", "${project.locationHash}-report.json")

    /** Reuses the existing LLM endpoint and persistent cache; never runs network requests on the EDT. */
    suspend fun summarizeSubflows(candidates: List<FlowRegionEvidence>, facts: Map<Int, SymbolFacts>,
        known: Map<Int, FunctionSummary> = emptyMap()): SubflowSummaries =
        withContext(Dispatchers.IO) { SubflowSummarizer(llm()::complete, cache).summarize(candidates, facts, known) }

    /** Bottom-up summaries of [input]'s functions (see [FunctionSummarizer]); cached judgments are shared with [enrich]. */
    suspend fun summarizeFunctions(input: EnrichmentInput, calls: Map<Int, List<dev.insight.callgraph.CallGraphEdge>>,
        heuristics: Map<Int, Heuristics>, visibility: Map<Int, Double>, options: SummaryOptions,
        progress: EnrichmentProgress = EnrichmentProgress.NONE): SummaryResult =
        withContext(Dispatchers.IO) { FunctionSummarizer(llm()::complete, cache, options).summarize(input, calls, heuristics, visibility, progress) }

    /** Copies full physical source lines under a read action, then groups and summarizes them off the EDT. */
    suspend fun generateCanvas(result: CallGraphResult, nodeIds: List<Int>? = null, flow: Flow? = null,
        progress: EnrichmentProgress = EnrichmentProgress.NONE): CanvasData {
        val selected = nodeIds ?: flow?.nodes
        val sources = smartReadAction(project) {
            val lineStarts = hashMapOf<com.intellij.psi.PsiFile, IntArray>()
            result.graph.nodes.filter { it.inProject && it.kind == SymbolKind.FUNCTION && (selected == null || it.id in selected) }
                .mapNotNull { node ->
                    ProgressManager.checkCanceled()
                    val element = result.pointers.getOrNull(node.id)?.element?.takeIf { it.isValid } ?: return@mapNotNull null
                    val file = element.containingFile ?: return@mapNotNull null
                    val virtual = file.virtualFile ?: return@mapNotNull null
                    val offset = element.textRange.startOffset
                    val starts = lineStarts.getOrPut(file) {
                        val positions = mutableListOf(0)
                        file.text.forEachIndexed { index, char -> if (char == '\n') positions += index + 1 }
                        positions.toIntArray()
                    }
                    val found = starts.binarySearch(offset)
                    val startLine = (if (found >= 0) found else -found - 2) + 1
                    val relative = project.basePath?.trimEnd('/')?.let { virtual.path.removePrefix("$it/") } ?: virtual.path
                    val qualified = listOf(node.container, node.name).filter { it.isNotBlank() }.joinToString(".")
                    node.id to CanvasSource(node.id, element.text, virtual.path, relative, qualified, startLine,
                        "$relative::$qualified")
                }.toMap()
        }
        val snapshot = latest?.takeIf { it.graph === result.graph }
        val roles = snapshot?.enrichment?.groupLabels.orEmpty().mapValues { it.value.summary.orEmpty() }
        val graph = if (flow == null) result.graph else result.graph.copy(edges = flow.edges)
        val document = CanvasDocumentBuilder.build(graph, sources, selected, roles)
        if (document.lines.isEmpty()) return CanvasData(document, emptyList())
        return withContext(Dispatchers.IO) { CanvasPipeline(llm()::complete, cache).generate(document, progress) }
    }

    suspend fun enrich(result: CallGraphResult, options: EnrichmentOptions = EnrichmentOptions()): EnrichmentSnapshot =
        withBackgroundProgress(project, "Enriching application graph") {
            val facts = smartReadAction(project) { SymbolFactsExtractor.extract(project, result) }
            val input = EnrichmentInput(project.name, result.graph, facts, result.occurrences)
            withContext(Dispatchers.IO) {
                val pipeline = EnrichmentPipeline(decisions()::evaluate, llm()::complete, cache, options)
                val enrichment = reportRawProgress { reporter ->
                    pipeline.run(input) { phase, done, total ->
                        reporter.text(phase)
                        reporter.details("$done / $total")
                        reporter.fraction(if (total > 0) done.toDouble() / total else null)
                    }
                }
                val report = reportPath
                Files.createDirectories(report.parent)
                Files.writeString(report, EnrichmentReport.toJson(input, enrichment))
                EnrichmentSnapshot(result.graph, input, enrichment, report).also { latest = it }
            }
        }

    /** Splits discovered starts without requiring a full enrichment run. */
    suspend fun splitFlows(result: CallGraphResult, entries: List<dev.insight.entrypoints.EntryPoint>,
        progress: (String) -> Unit = {}): dev.insight.callgraph.ApplicationFlowResult =
        withBackgroundProgress(project, "Splitting application flows") {
            reportRawProgress { reporter ->
                reporter.text("Reading source facts…")
                val facts = smartReadAction(project) { SymbolFactsExtractor.extract(project, result) }
                withContext(Dispatchers.IO) {
                    dev.insight.callgraph.ApplicationFlowSplitter(llm()::complete, cache)
                        .split(result.graph, entries, project.basePath, facts, latest) { message ->
                            reporter.text(message)
                            progress(message)
                        }
                }
            }
        }

    private fun decisions() = ChatSettings.getInstance().createDecisionsClient()

    private fun llm() = configuredLlmClient()
}

/** The LLM client for background analyses. Call on IO: reading the API key from the password safe can block. */
internal fun configuredLlmClient(): LlmClient = ChatSettings.getInstance().createClient()
