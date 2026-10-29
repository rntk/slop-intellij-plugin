package dev.insight.callgraph

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportRawProgress
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiModificationTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException

data class CallGraphRequest(
    val direction: CallDirection = CallDirection.BOTH,
    val depth: Int = 2,
    val includeLibraries: Boolean = false,
    val maxNodes: Int = 150,
)

/**
 * A graph snapshot plus pointers for navigation. Pointers are indexed by node id and are null
 * for code that was edited during the search.
 */
class CallGraphResult(val graph: CallGraph, val pointers: List<SmartPsiElementPointer<PsiElement>?>,
    val occurrences: List<CallOccurrence> = emptyList())

@Service(Service.Level.PROJECT)
class CallGraphService(private val project: Project, val scope: CoroutineScope) {
    private val log = logger<CallGraphService>()
    /** The last application graph and the PSI modification count it was built at. */
    @Volatile private var latestProject: Pair<Long, CallGraphResult>? = null

    suspend fun buildProject(): CallGraphResult = withBackgroundProgress(project, "Building application graph") {
        val started = System.nanoTime()
        var stage = "Waiting for project indexes"
        log.info("Building application graph for ${project.name}")
        try {
            reportRawProgress { reporter ->
                reporter.text(stage)
                smartReadAction(project) {
                    val stamp = PsiModificationTracker.getInstance(project).modificationCount
                    ProjectGraphAnalyzer.build(project) {
                        stage = it
                        reporter.text(it)
                        log.debug(it)
                    }.also { latestProject = stamp to it }
                }
            }.also {
                log.info("Built application graph: ${it.graph.nodes.size} symbols, ${it.graph.edges.size} edges in ${(System.nanoTime() - started) / 1_000_000} ms")
            }
        } catch (e: Exception) {
            if (e is CancellationException || e is ControlFlowException) {
                log.info("Application graph interrupted at: $stage", e)
            } else {
                log.warn("Application graph failed at: $stage", e)
            }
            throw e
        }
    }

    /** The last application graph while no code changed since it was built, otherwise a fresh one. */
    suspend fun currentProject(): CallGraphResult {
        val latest = latestProject
        val stamp = smartReadAction(project) { PsiModificationTracker.getInstance(project).modificationCount }
        return latest?.takeIf { it.first == stamp }?.second ?: buildProject()
    }

    /**
     * Explores the graph in the background. Each expansion runs in its own read action that waits
     * for indexing, so typing and indexing are not blocked for the whole search.
     */
    suspend fun build(root: SmartPsiElementPointer<PsiElement>, request: CallGraphRequest): CallGraphResult? =
        withBackgroundProgress(project, "Building call graph") {
            val element = smartReadAction(project) { root.element?.takeIf { it.isValid } } ?: return@withBackgroundProgress null
            val exploration = exploreCalls(
                element, request.direction, request.depth, request.maxNodes,
                callers = { smartReadAction(project) { CallGraphAnalyzer.callers(it, request.maxNodes) } },
                callees = { smartReadAction(project) { CallGraphAnalyzer.callees(it, request.includeLibraries) } },
                expandable = { smartReadAction(project) { CallGraphAnalyzer.isInProject(project, it) } },
            )
            smartReadAction(project) {
                val pointers = SmartPointerManager.getInstance(project)
                val nodes = exploration.items.mapIndexed { id, item ->
                    CallGraphAnalyzer.describe(project, item, id, exploration.levels[id])
                }
                CallGraphResult(
                    CallGraph(nodes, exploration.edges, exploration.truncated),
                    exploration.items.map { item -> item.takeIf { it.isValid }?.let(pointers::createSmartPsiElementPointer) },
                )
            }
        }
}
