package dev.insight.knowledge.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import dev.insight.agent.codeScopeRoots

/** Maps project-relative paths to virtual files and back. Call inside a read action. */
internal object ProjectPaths {
    /** The file or directory at [path] (`""` is the project root), or null. */
    fun find(project: Project, path: String): VirtualFile? {
        val input = path.trim().replace('\\', '/')
        if (input.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(input) || input.split('/').any { it == ".." }) return null
        val normal = normalizeProjectPath(path)
        return codeScopeRoots(project).firstNotNullOfOrNull { root ->
            (if (normal.isEmpty()) root else root.findFileByRelativePath(normal))?.takeIf { contains(root, it) }
        }
    }

    /** The file at an absolute path under any project root, whatever file system the project lives on. */
    fun findAbsolute(project: Project, absolutePath: String): VirtualFile? =
        codeScopeRoots(project).firstNotNullOfOrNull { root ->
            root.fileSystem.findFileByPath(absolutePath)?.takeIf { contains(root, it) }
        }

    /** Both the visible path and its symlink-resolved target must be inside the same root. */
    internal fun contains(root: VirtualFile, file: VirtualFile): Boolean {
        if (!file.isValid || !VfsUtilCore.isAncestor(root, file, false)) return false
        val canonicalRoot = root.canonicalFile ?: return false
        val canonicalFile = file.canonicalFile ?: return false
        return VfsUtilCore.isAncestor(canonicalRoot, canonicalFile, false)
    }

    fun contains(project: Project, file: VirtualFile): Boolean = codeScopeRoots(project).any { contains(it, file) }

    /** [file]'s path relative to the first project root containing it; the absolute path when it is outside all of them. */
    fun relative(project: Project, file: VirtualFile): String =
        codeScopeRoots(project).firstNotNullOfOrNull { if (contains(it, file)) VfsUtilCore.getRelativePath(file, it) else null } ?: file.path
}
