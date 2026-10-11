package dev.insight.callgraph

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import kotlinx.coroutines.CoroutineScope

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
class CallGraphResult(val graph: CallGraph, val pointers: List<SmartPsiElementPointer<PsiElement>?>)

@Service(Service.Level.PROJECT)
class CallGraphService(private val project: Project, val scope: CoroutineScope) {
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
