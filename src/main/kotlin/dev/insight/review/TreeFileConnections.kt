package dev.insight.review

import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiModificationTracker
import dev.insight.agent.findSourceFile
import dev.insight.callgraph.CallGraphLanguageSupport
import dev.insight.callgraph.ProjectGraphAnalyzer
import dev.insight.graph.RelationKind

internal data class TreeFileConnections(val incoming: Set<String>, val outgoing: Set<String>)

/** Direct file links in one review scope, resolved against the IDE's current source. */
internal class TreeFileConnectionIndex(private val project: Project, private val sources: List<ReviewSource>) {
    private data class Snapshot(val stamp: Long, val links: Map<String, TreeFileConnections>)

    @Volatile
    private var snapshot: Snapshot? = null

    suspend fun connections(path: String): TreeFileConnections? = constrainedReadAction(
        ReadConstraint.inSmartMode(project),
        ReadConstraint.withDocumentsCommitted(project),
    ) {
        val stamp = PsiModificationTracker.getInstance(project).modificationCount
        val current = snapshot?.takeIf { it.stamp == stamp } ?: Snapshot(stamp, build()).also { snapshot = it }
        current.links[path]
    }

    private fun build(): Map<String, TreeFileConnections> {
        val manager = PsiManager.getInstance(project)
        val files = sources.mapNotNull { source ->
            ProgressManager.checkCanceled()
            val file = findSourceFile(project, source.path, source.absolutePath)
            if (file == null || file.fileType.isBinary) null else manager.findFile(file)?.let { source.path to it }
        }.toMap()
        val paths = files.entries.associate { (path, file) -> file.virtualFile to path }
        val supports = CallGraphLanguageSupport.EP_NAME.extensionList
        val packageMembers = files.entries.filter { (_, file) -> supports.any { it.supportsFile(file) } }
            .groupBy({ (_, file) -> file.virtualFile.parent }, { (path, _) -> path })
        val incoming = mutableMapOf<String, MutableSet<String>>()
        val outgoing = mutableMapOf<String, MutableSet<String>>()
        for ((from, file) in files) {
            fun connect(to: String) {
                if (from == to) return
                if (outgoing.getOrPut(from, ::linkedSetOf).add(to)) incoming.getOrPut(to, ::linkedSetOf).add(from)
            }
            ProjectGraphAnalyzer.relations(file) { _, target, kind ->
                if (kind == RelationKind.CONTAINS) return@relations
                if (target is PsiDirectory) {
                    // A package import can resolve to a directory rather than a declaration.
                    if (kind == RelationKind.IMPORT) packageMembers[target.virtualFile]?.forEach(::connect)
                } else {
                    target.containingFile?.virtualFile?.let(paths::get)?.let(::connect)
                }
            }
        }
        return files.keys.associateWith { TreeFileConnections(incoming[it].orEmpty(), outgoing[it].orEmpty()) }
    }
}
