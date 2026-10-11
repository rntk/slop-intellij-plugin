package dev.insight.enrichment

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportRawProgress
import dev.insight.callgraph.CallGraph
import dev.insight.callgraph.CallGraphResult
import dev.insight.chat.ChatSettings
import dev.insight.core.Endpoints
import dev.insight.core.decisionsClient
import dev.insight.llm.LlamaCppClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

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

    suspend fun enrich(result: CallGraphResult, options: EnrichmentOptions = EnrichmentOptions()): EnrichmentSnapshot =
        withBackgroundProgress(project, "Enriching application graph") {
            val facts = smartReadAction(project) { SymbolFactsExtractor.extract(project, result) }
            val input = EnrichmentInput(project.name, result.graph, facts)
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

    // Hosts are hardcoded for now (see Endpoints). The local decisions server accepts any bearer token.
    private fun decisions() = decisionsClient("local", requestTimeout = Duration.ofSeconds(120), maxRetries = 4)

    /** Runs on IO: reading the API key from the password safe can block. */
    private fun llm(): LlamaCppClient {
        val settings = ChatSettings.getInstance()
        return LlamaCppClient(
            baseUrl = Endpoints.LLM_BASE_URL,
            model = settings.state.model.orEmpty(),
            apiKey = settings.apiKey,
            requestTimeout = Duration.ofSeconds(settings.state.requestTimeoutSeconds.coerceIn(60, 3600).toLong()),
        )
    }
}
