package dev.insight.codereview

import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.GeneratedSourcesFilter
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Loads the text files under a scope directory: project content only (excluded folders are skipped by the index, VCS- and
 * IDE-ignored folders are pruned and counted), without binary, too large, generated or minified files. Unchanged files of
 * the previous snapshot are reused without reading them again. Never modifies files.
 */
internal class CodeReviewLoader(private val project: Project) {
    suspend fun load(scope: CodeScope, previous: CodeSnapshot?, limits: CodeLimits = CodeLimits()): CodeSnapshot =
        withContext(Dispatchers.IO) {
            val directory = readAction { findCodeScopeDirectory(project, scope.absolutePath) }
                ?: throw IllegalArgumentException("The folder ${scope.displayName} no longer exists.")
            val found = enumerate(directory)
            val reusable = previous?.takeIf { it.limits.chunk == limits.chunk }?.byPath.orEmpty()
            val files = mutableListOf<CodeFile>()
            val skipped = mutableListOf<SkippedFile>()
            var totalChars = 0L
            var overLimit = 0
            for ((index, entry) in found.files.withIndex()) {
                coroutineContext.ensureActive()
                val (path, file) = entry
                val loaded = if (files.size >= limits.maxFiles) null else when (val result = read(path, file, reusable[path], limits)) {
                    is LoadResult.Skipped -> { skipped += SkippedFile(path, result.reason); continue }
                    is LoadResult.Loaded -> result.file.takeIf { totalChars + it.text.length <= limits.maxTotalChars }
                }
                if (loaded == null) {
                    // Stop at the limits; the rest are counted, and the first of them listed.
                    overLimit = found.files.size - index
                    found.files.subList(index, minOf(found.files.size, index + MAX_LISTED_OVER_LIMIT))
                        .forEach { skipped += SkippedFile(it.first, SkipReason.LIMIT) }
                    break
                }
                totalChars += loaded.text.length
                files += loaded
            }
            CodeSnapshot(scope, files, skipped, found.ignored, overLimit > 0, overLimit, limits)
        }

    private class Found(val files: List<Pair<String, VirtualFile>>, val ignored: Int)

    private sealed interface LoadResult {
        class Loaded(val file: CodeFile) : LoadResult
        class Skipped(val reason: SkipReason) : LoadResult
    }

    /**
     * Collects content files under [directory] in project-relative path order. Each top-level folder is walked in its own
     * read action, so a write action restarts only part of a large tree.
     */
    private suspend fun enumerate(directory: VirtualFile): Found {
        val index = ProjectFileIndex.getInstance(project)
        val changes = ChangeListManager.getInstance(project)
        val types = FileTypeRegistry.getInstance()
        fun ignored(file: VirtualFile) = types.isFileIgnored(file) || changes.isIgnoredFile(file) ||
            file.isDirectory && file.name == PROJECT_STORE_FOLDER
        // A project root that is not content itself still holds content roots.
        val starts = readAction {
            if (index.isInContent(directory)) listOf(directory)
            else ProjectRootManager.getInstance(project).contentRoots.filter { VfsUtilCore.isAncestor(directory, it, false) }
        }
        val files = mutableListOf<VirtualFile>()
        var ignoredCount = 0
        for (start in starts) {
            coroutineContext.ensureActive()
            val (direct, folders, prunedTop) = readAction {
                val direct = mutableListOf<VirtualFile>()
                val folders = mutableListOf<VirtualFile>()
                var pruned = 0
                if (start.isValid) for (child in start.children) {
                    if (!index.isInContent(child)) continue
                    when {
                        ignored(child) -> pruned++
                        child.isDirectory -> folders += child
                        else -> direct += child
                    }
                }
                Triple(direct, folders, pruned)
            }
            files += direct
            ignoredCount += prunedTop
            for (folder in folders) {
                coroutineContext.ensureActive()
                val (part, pruned) = readAction {
                    val part = mutableListOf<VirtualFile>()
                    var pruned = 0
                    if (folder.isValid) index.iterateContentUnderDirectory(folder, { file ->
                        ProgressManager.checkCanceled()
                        if (!file.isDirectory) part += file
                        true
                    }) { file ->
                        val skip = file != folder && ignored(file)
                        if (skip) pruned++
                        !skip
                    }
                    part to pruned
                }
                files += part
                ignoredCount += pruned
            }
        }
        val roots = readAction { codeScopeRoots(project) }
        val sorted = files.distinctBy { it.path }.map { relativePath(roots, it) to it }.sortedBy { it.first }
        return Found(sorted, ignoredCount)
    }

    /** A [CodeFile], reused from [previous] when the file is unchanged, or the reason it is left out. */
    private suspend fun read(path: String, file: VirtualFile, previous: CodeFile?, limits: CodeLimits): LoadResult {
        if (file.fileType.isBinary) return LoadResult.Skipped(SkipReason.BINARY)
        // Byte length bounds the characters of UTF-8 text from above; the decoded length is checked again below.
        if (file.length > limits.maxFileChars) return LoadResult.Skipped(SkipReason.TOO_LARGE)
        val manager = FileDocumentManager.getInstance()
        val (generated, unsaved) = readAction {
            if (!file.isValid) return@readAction null
            GeneratedSourcesFilter.isGeneratedSourceByAnyFilter(file, project) to
                (if (manager.isFileModified(file)) manager.getCachedDocument(file)?.immutableCharSequence?.toString() else null)
        } ?: return LoadResult.Skipped(SkipReason.UNREADABLE)
        if (generated) return LoadResult.Skipped(SkipReason.GENERATED)
        val stamp = file.modificationStamp
        // Unsaved text cannot be reused by disk metadata after edits are discarded.
        val length = if (unsaved == null) file.length else -1L
        val unchanged = previous?.takeIf { it.absolutePath == file.path }
        if (unsaved == null && unchanged != null && unchanged.stamp == stamp && unchanged.length == length)
            return LoadResult.Loaded(unchanged)
        val raw = unsaved ?: try { VfsUtilCore.loadText(file) } catch (_: IOException) { return LoadResult.Skipped(SkipReason.UNREADABLE) }
        val text = StringUtil.convertLineSeparators(raw)
        if (text.length > limits.maxFileChars) return LoadResult.Skipped(SkipReason.TOO_LARGE)
        if (isMinified(text, limits.chunk)) return LoadResult.Skipped(SkipReason.MINIFIED)
        // Same text: keep the parts and update whether the snapshot matches disk metadata.
        if (unchanged != null && unchanged.text == text)
            return LoadResult.Loaded(CodeFile(path, file.path, text, unchanged.fingerprint, unchanged.chunks, stamp, length))
        return LoadResult.Loaded(CodeFile(path, file.path, text, chunks = chunkSource(text, limits.chunk), stamp = stamp, length = length))
    }

    companion object {
        /** Files beyond the limits are counted, and only this many are listed. */
        const val MAX_LISTED_OVER_LIMIT = 1_000
        private const val PROJECT_STORE_FOLDER = ".idea"
    }
}

/** The project directory, then the content roots: project-relative paths are relative to the first root holding a file. */
internal fun codeScopeRoots(project: Project): List<VirtualFile> =
    (listOfNotNull(project.guessProjectDir()) + ProjectRootManager.getInstance(project).contentRoots).distinct()

private fun relativePath(roots: List<VirtualFile>, file: VirtualFile): String =
    roots.firstNotNullOfOrNull { VfsUtilCore.getRelativePath(file, it) } ?: file.path

/** The directory at [absolutePath] on disk or under a project root (tests use an in-memory file system). Call in a read action. */
internal fun findCodeScopeDirectory(project: Project, absolutePath: String): VirtualFile? {
    val path = absolutePath.replace('\\', '/').let { if (it.length > 1) it.trimEnd('/') else it }
    LocalFileSystem.getInstance().findFileByPath(path)?.takeIf { it.isDirectory }?.let { return it }
    return codeScopeRoots(project).firstNotNullOfOrNull { root ->
        when {
            path == root.path -> root
            path.startsWith(root.path.trimEnd('/') + "/") -> root.findFileByRelativePath(path.removePrefix(root.path.trimEnd('/') + "/"))
            else -> null
        }
    }?.takeIf { it.isDirectory }
}

/**
 * The scope of [directory]. Call in a read action. Throws [IllegalArgumentException] with a message for the user when the
 * directory is not a project folder, or is excluded.
 */
internal fun codeScope(project: Project, directory: VirtualFile): CodeScope {
    require(directory.isValid && directory.isDirectory) { "${directory.presentableUrl} is not a folder." }
    val index = ProjectFileIndex.getInstance(project)
    val path = codeScopeRoots(project).firstNotNullOfOrNull { VfsUtilCore.getRelativePath(directory, it) }
    require(path != null) { "${directory.presentableUrl} is not in the project." }
    val name = path.ifEmpty { "The project root" }
    require(!index.isExcluded(directory)) { "$name is excluded from the project." }
    require(index.isInContent(directory) ||
        ProjectRootManager.getInstance(project).contentRoots.any { VfsUtilCore.isAncestor(directory, it, false) }) {
        "$name is not part of the project's content."
    }
    return CodeScope(path, directory.path)
}

/**
 * The scope of a project-relative or absolute [path], "" or "." for the project root. Call in a read action. Throws
 * [IllegalArgumentException] with a message for the user when it is not a project folder.
 */
internal fun codeScope(project: Project, path: String): CodeScope {
    val trimmed = path.trim().replace('\\', '/')
    require("/../" !in "/$trimmed/") { "The folder must not contain '..': $path" }
    val absolute = trimmed.startsWith("/") || Regex("^[A-Za-z]:/").containsMatchIn(trimmed)
    val directory = if (absolute) findCodeScopeDirectory(project, trimmed) else {
        val clean = trimmed.trim('/').removePrefix("./").let { if (it == ".") "" else it }
        codeScopeRoots(project).firstNotNullOfOrNull { if (clean.isEmpty()) it else it.findFileByRelativePath(clean) }
            ?.takeIf { it.isDirectory }
    }
    require(directory != null) { "There is no project folder at '${trimmed.ifEmpty { "." }}'." }
    return codeScope(project, directory)
}
