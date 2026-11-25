package dev.insight.knowledge.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import dev.insight.agent.codeScopeRoots

/** Maps project-relative paths to virtual files and back. Call inside a read action. */
internal object ProjectPaths {
    /** The file or directory at [path] (`""` is the project root), or null. */
    fun find(project: Project, path: String): VirtualFile? {
        val normal = normalizeProjectPath(path)
        return codeScopeRoots(project).firstNotNullOfOrNull { root ->
            (if (normal.isEmpty()) root else root.findFileByRelativePath(normal))?.takeIf { it.isValid }
        }
    }

    /** The file at an absolute path under any project root, whatever file system the project lives on. */
    fun findAbsolute(project: Project, absolutePath: String): VirtualFile? =
        codeScopeRoots(project).firstNotNullOfOrNull { it.fileSystem.findFileByPath(absolutePath) }?.takeIf { it.isValid }

    /** [file]'s path relative to the first project root containing it; the absolute path when it is outside all of them. */
    fun relative(project: Project, file: VirtualFile): String =
        codeScopeRoots(project).firstNotNullOfOrNull { VfsUtilCore.getRelativePath(file, it) } ?: file.path
}
