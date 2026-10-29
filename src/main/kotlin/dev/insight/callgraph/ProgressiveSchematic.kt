package dev.insight.callgraph

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import dev.insight.graph.CallGraph
import dev.insight.graph.CallGraphNode
import dev.insight.graph.CallGraphResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One exploration, with stable symbol ids and only explicitly opened scopes resolved. */
internal class ProgressiveSchematic private constructor(
    private val project: Project,
    initial: CallGraphResult,
    private var stamps: Map<PsiFile, FileStamp>,
) {
    @Volatile var result: CallGraphResult = initial
        private set
    private val mutex = Mutex()
    private val opened = hashSetOf<Int>()

    suspend fun expand(id: Int) = mutex.withLock {
        if (id in opened) return@withLock
        val next = smartReadAction(project) {
            check(stamps.all { (file, stamp) -> file.isValid && fileStamp(file) == stamp }) {
                "Source changed. Reopen the schematic to explore the updated code."
            }
            val element = result.pointers.getOrNull(id)?.element?.takeIf { it.isValid }
                ?: error("Source is no longer available. Reopen the schematic.")
            val merged = mergeSchematicResults(result, ProjectGraphAnalyzer.buildScope(project, element)) { snapshot, node ->
                val target = snapshot.pointers.getOrNull(node.id)?.element
                Triple(node.filePath, target?.textOffset, node.kind) to node.unresolved
            }
            merged to fileStamps(merged)
        }
        result = next.first
        stamps = next.second
        opened += id
    }

    companion object {
        suspend fun open(project: Project, file: VirtualFile): ProgressiveSchematic? = smartReadAction(project) {
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@smartReadAction null
            val initial = ProjectGraphAnalyzer.buildScope(project, psi, declarationsOnly = true)
            if (initial.graph.nodes.isEmpty()) return@smartReadAction null
            ProgressiveSchematic(project, initial, fileStamps(initial))
        }

        // Boundary declarations have already contributed names and offsets, so their files count as read too.
        private fun fileStamps(result: CallGraphResult): Map<PsiFile, FileStamp> = result.pointers
            .mapNotNull { it?.element?.containingFile }.distinct().associateWith(::fileStamp)

        private fun fileStamp(file: PsiFile) = FileStamp(file.modificationStamp,
            PsiDocumentManager.getInstance(file.project).getDocument(file)?.modificationStamp)
    }

    private data class FileStamp(val psi: Long, val document: Long?)
}

/** Appends discoveries without renumbering cards, breadcrumbs or chat references already on screen. */
internal fun <K> mergeSchematicResults(previous: CallGraphResult, branch: CallGraphResult,
    key: (CallGraphResult, CallGraphNode) -> K): CallGraphResult {
    val nodes = previous.graph.nodes.toMutableList()
    val pointers = previous.pointers.toMutableList()
    val ids = nodes.associate { key(previous, it) to it.id }.toMutableMap()
    val mapped = branch.graph.nodes.associate { node ->
        node.id to ids.getOrPut(key(branch, node)) {
            nodes.size.also { id ->
                nodes += node.copy(id = id)
                pointers += branch.pointers.getOrNull(node.id)
            }
        }
    }
    fun id(value: Int) = mapped.getValue(value)
    val edges = previous.graph.edges + branch.graph.edges.map { it.copy(caller = id(it.caller), callee = id(it.callee)) }
    val occurrences = previous.occurrences + branch.occurrences.map { call ->
        call.copy(owner = id(call.owner), scope = id(call.scope), targets = call.targets.map(::id),
            unresolvedTarget = call.unresolvedTarget?.let(::id), receiverTargets = call.receiverTargets.map(::id),
            bindings = call.bindings.map(::id), heldBy = call.heldBy.map(::id), choices = call.choices.map(::id))
    }
    return CallGraphResult(CallGraph(nodes, edges.toSet(), false, projectGraph = true), pointers,
        occurrences.distinctBy { Triple(it.filePath, it.startOffset, it.operation) })
}
