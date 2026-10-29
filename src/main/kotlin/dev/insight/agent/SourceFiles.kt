package dev.insight.agent

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/** The project directory, then the content roots: project-relative paths are relative to the first root holding a file. */
internal fun codeScopeRoots(project: Project): List<VirtualFile> =
    (listOfNotNull(project.guessProjectDir()) + ProjectRootManager.getInstance(project).contentRoots).distinct()

/** The file of a code review path: on disk, or under a project root (tests use an in-memory file system). */
internal fun findSourceFile(project: Project, path: String, absolutePath: String?): VirtualFile? =
    absolutePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }?.takeIf { it.isValid && !it.isDirectory }
        ?: codeScopeRoots(project).firstNotNullOfOrNull { it.findFileByRelativePath(path) }?.takeIf { it.isValid && !it.isDirectory }
