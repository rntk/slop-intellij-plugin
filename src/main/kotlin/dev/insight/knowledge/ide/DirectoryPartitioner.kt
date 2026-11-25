package dev.insight.knowledge.ide

import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import dev.insight.knowledge.KnowledgePartition
import dev.insight.knowledge.KnowledgePartitioner

/**
 * Partitions a review scope by directory: every directory in the scope that holds files directly becomes a partition of those
 * files, split into balanced chunks when it has more than [maxFilesPerPartition] (see [partitionFiles]). Scope entries are
 * project-relative files or directories; a directory is expanded to the text files below it that lie in project content,
 * leaving out build, generated and tooling directories (see [isExcludedPath]), binary and very large files. Entries that do
 * not exist are ignored.
 */
class DirectoryPartitioner(
    private val project: Project,
    private val maxFilesPerPartition: Int = 40,
) : KnowledgePartitioner {
    init {
        require(maxFilesPerPartition >= 1) { "maxFilesPerPartition must be at least 1" }
    }

    override suspend fun partitions(scope: List<String>): List<KnowledgePartition> =
        partitionFiles(readAction { expand(scope) }, maxFilesPerPartition)

    private fun expand(scope: List<String>): List<String> {
        val index = ProjectFileIndex.getInstance(project)
        val files = LinkedHashSet<String>()
        fun keep(file: VirtualFile): Boolean {
            if (file.isDirectory || !index.isInContent(file) || index.isInGeneratedSources(file)) return false
            return !file.fileType.isBinary && file.length <= MAX_FILE_BYTES
        }
        for (entry in scope) {
            val root = ProjectPaths.find(project, entry) ?: continue
            if (!root.isDirectory) {
                val path = ProjectPaths.relative(project, root)
                if (keep(root) && !isExcludedPath(path)) files += path
                continue
            }
            VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>() {
                override fun visitFileEx(file: VirtualFile): Result {
                    ProgressManager.checkCanceled()
                    if (files.size >= MAX_FILES) return SKIP_CHILDREN
                    if (file.isDirectory) {
                        val skip = file != root && (isExcludedPath(file.name, isDirectory = true) || !index.isInContent(file))
                        return if (skip) SKIP_CHILDREN else CONTINUE
                    }
                    if (keep(file)) files += ProjectPaths.relative(project, file)
                    return CONTINUE
                }
            })
        }
        return files.toList()
    }

    private companion object {
        const val MAX_FILES = 20_000
        const val MAX_FILE_BYTES = 2_000_000L
    }
}
