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
import dev.insight.graph.RelationKind
import dev.insight.graph.hierarchy
import dev.insight.enrichment.liftCalls
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
    private val openedWhole = hashSetOf<Int>()

    suspend fun findUsages(id: Int, limit: Int = 100): SchematicUsageSearch = mutex.withLock {
        val found = smartReadAction(project) {
            checkSources()
            findSchematicUsages(project, result, id, limit).let { it to fileStamps(it.result) }
        }
        result = found.first.result
        stamps = found.second
        found.first
    }

    private fun checkSources() {
        check(stamps.all { (file, stamp) -> file.isValid && fileStamp(file) == stamp }) {
            "Source changed. Reopen the schematic to explore the updated code."
        }
    }

    /** Resolves the scope [id]; [whole] also resolves the functions folded inside it, as a reader of all its lines needs. */
    suspend fun expand(id: Int, whole: Boolean = false) = mutex.withLock {
        if (id in openedWhole || !whole && id in opened) return@withLock
        val next = smartReadAction(project) {
            checkSources()
            val element = result.pointers.getOrNull(id)?.element?.takeIf { it.isValid }
                ?: error("Source is no longer available. Reopen the schematic.")
            val branch = ProjectGraphAnalyzer.buildScope(project, element, foldFunctions = !whole)
            val merged = mergeSchematicResults(result, branch) { snapshot, node ->
                val target = snapshot.pointers.getOrNull(node.id)?.element
                Triple(node.filePath, target?.textOffset, node.kind) to node.unresolved
            }
            merged to fileStamps(merged)
        }
        result = next.first
        stamps = next.second
        opened += id
        if (whole) openedWhole += id
    }

    /** Adds a complete file while keeping existing symbol ids stable. */
    suspend fun fileSchematic(file: VirtualFile): Schematic? {
        val id = mutex.withLock {
            smartReadAction(project) {
                checkSources()
                val psi = PsiManager.getInstance(project).findFile(file) ?: return@smartReadAction null
                fun fileId(snapshot: CallGraphResult) = snapshot.graph.nodes.firstOrNull { node ->
                    node.kind == dev.insight.graph.SymbolKind.FILE && snapshot.pointers.getOrNull(node.id)?.element == psi
                }?.id
                fileId(result)?.let { return@smartReadAction it }
                val branch = ProjectGraphAnalyzer.buildScope(project, psi, declarationsOnly = true)
                val merged = mergeSchematicResults(result, branch) { snapshot, node ->
                    val target = snapshot.pointers.getOrNull(node.id)?.element
                    Triple(node.filePath, target?.textOffset, node.kind) to node.unresolved
                }
                result = merged
                stamps = fileStamps(merged)
                fileId(merged)
            }
        } ?: return null
        return schematic(id, whole = true)
    }

    @Volatile private var analysis: SchematicAnalysis? = null

    /**
     * Expands [id], then builds its schematic from the expanded graph as a schematic level does; null when its source
     * is gone. [whole] reads all of its lines, with [Schematic.mentions]. Call off the EDT.
     */
    suspend fun schematic(id: Int, whole: Boolean = false): Schematic? {
        expand(id, whole)
        val snapshot = result
        val facts = analysis?.takeIf { it.result === snapshot } ?: SchematicAnalysis(snapshot).also { analysis = it }
        val hierarchy = facts.hierarchy
        val calls = facts.calls
        return smartReadAction(project) { SchematicBuilder.build(snapshot, hierarchy, calls, id, whole) }
    }

    companion object {
        /** Attaches indexed outward exploration to an existing graph. Read action only. */
        fun fromResult(project: Project, result: CallGraphResult) = ProgressiveSchematic(project, result, fileStamps(result))

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

/** The graph facts a schematic is built from, computed once per graph snapshot. */
internal class SchematicAnalysis(val result: CallGraphResult) {
    val calls by lazy { liftCalls(result.graph).edges.filter { it.kind == RelationKind.CALL }.groupBy { it.caller } }
    val hierarchy by lazy { result.graph.hierarchy() }
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
