package dev.insight.codereview

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.GeneratedSourcesFilter
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiManager
import dev.insight.callgraph.CallGraphLanguageSupport
import dev.insight.callgraph.ProjectGraphAnalyzer
import dev.insight.review.ReviewDiskCache
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The project files an entry file depends on, transitively: everything its imports, calls, type uses, inheritance and other
 * references resolve to, through the same PSI relations as the call graph. [files] is in discovery order, the entry first and
 * nearer files before farther ones. Library, excluded, ignored and generated files are not followed; [beyond] counts files
 * found after [CodeLimits.maxFiles] that were not followed, [excluded] the files left out by the exclude patterns.
 */
internal class CodeDependencies(val files: List<VirtualFile>, val beyond: Int, val excluded: Int = 0)

internal class CodeDependencyWalker(private val project: Project) {
    private val supports get() = CallGraphLanguageSupport.EP_NAME.extensionList

    /**
     * Breadth-first from [entry], never following a file that is [excluded]. Each file is resolved
     * in its own smart read action, so indexing and writes only delay it.
     */
    suspend fun walk(entry: VirtualFile, maxFiles: Int, excluded: (VirtualFile) -> Boolean = { false }): CodeDependencies {
        val index = ProjectFileIndex.getInstance(project)
        val changes = ChangeListManager.getInstance(project)
        val types = FileTypeRegistry.getInstance()
        val manager = PsiManager.getInstance(project)
        fun followed(file: VirtualFile) = file.isValid && !file.isDirectory && index.isInContent(file) &&
            !types.isFileIgnored(file) && !changes.isIgnoredFile(file) && "/.idea/" !in file.path && "/${ReviewDiskCache.DIRECTORY}/" !in file.path
        val found = LinkedHashSet<VirtualFile>().apply { add(entry) }
        val beyond = HashSet<String>()
        val left = HashSet<String>()
        val queue = ArrayDeque(listOf(entry))
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val file = queue.removeFirst()
            val targets = smartReadAction(project) {
                if (!file.isValid || file.fileType.isBinary || GeneratedSourcesFilter.isGeneratedSourceByAnyFilter(file, project))
                    return@smartReadAction emptyList()
                val psi = manager.findFile(file) ?: return@smartReadAction emptyList()
                val targets = LinkedHashSet<VirtualFile>()
                ProjectGraphAnalyzer.relations(psi) { _, to, _ ->
                    if (to is PsiDirectory) {
                        // Package imports (Go) resolve to a folder: its source files directly inside.
                        to.files.filter { child -> supports.any { it.supportsFile(child) } }.mapNotNullTo(targets) { it.virtualFile }
                    } else to.containingFile?.virtualFile?.let(targets::add)
                }
                targets.filter { it != file && followed(it) }
            }
            for (target in targets) {
                if (target in found || target.path in beyond || target.path in left) continue
                if (excluded(target)) { left += target.path; continue }
                if (found.size >= maxFiles) { beyond += target.path; continue }
                found += target
                queue += target
            }
        }
        return CodeDependencies(found.toList(), beyond.size, left.size)
    }
}
