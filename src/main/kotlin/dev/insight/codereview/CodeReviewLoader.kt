package dev.insight.codereview

import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
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
import dev.insight.review.ReviewDiskCache
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private val LOG = logger<CodeReviewLoader>()

/**
 * Loads the text files under a scope directory, or an entry file and the project files it depends on: project content only (excluded folders are skipped by the index, VCS- and
 * IDE-ignored folders and those matching the scope's exclude patterns are pruned and counted), without binary, too large,
 * generated or minified files. Unchanged files of
 * the previous snapshot are reused without reading them again. Never modifies files.
 */
internal class CodeReviewLoader(private val project: Project) {
    suspend fun load(scope: CodeScope, previous: CodeSnapshot?, limits: CodeLimits = CodeLimits(),
        progress: (String, Int, Int) -> Unit = { _, _, _ -> }): CodeSnapshot =
        withContext(Dispatchers.IO) {
            progress(if (scope.entry) "Following entry-file dependencies…" else "Finding files in scope…", 0, 0)
            val start = readAction { findCodeScopeFile(project, scope.absolutePath, directory = !scope.entry) }
                ?: throw IllegalArgumentException("The ${if (scope.entry) "file" else "folder"} ${scope.path.ifEmpty { "Project root" }} no longer exists.")
            val found = if (scope.entry) dependencies(start, limits, scope.exclusions) else enumerate(start, scope.exclusions)
            val reusable = previous?.takeIf { it.limits.chunk == limits.chunk }?.byPath.orEmpty()
            val files = mutableListOf<CodeFile>()
            val skipped = mutableListOf<SkippedFile>()
            var totalChars = 0L
            var overLimit = 0
            for ((index, entry) in found.files.withIndex()) {
                currentCoroutineContext().ensureActive()
                val (path, file) = entry
                progress("Reading $path", index, found.files.size)
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
            overLimit += found.beyond
            progress("Code snapshot loaded", found.files.size, found.files.size)
            // Dependencies are read nearest first, so the limits cut the farthest; the snapshot is in path order.
            CodeSnapshot(scope, files.sortedBy { it.path }, skipped.sortedBy { it.path }, found.ignored, overLimit > 0, overLimit, limits,
                found.excluded)
        }

    /** [files] in the order they are read; [beyond] counts files that were found but not listed, [excluded] those excluded. */
    private class Found(val files: List<Pair<String, VirtualFile>>, val ignored: Int, val beyond: Int = 0, val excluded: Int = 0)

    /** The entry file and the project files it depends on, nearest first; excluded files are not followed. */
    private suspend fun dependencies(entry: VirtualFile, limits: CodeLimits, exclusions: CodeExclusions): Found {
        val roots = readAction { codeScopeRoots(project) }
        val walked = CodeDependencyWalker(project).walk(entry, limits.maxFiles) { exclusions.excludes(relativePath(roots, it)) }
        return Found(walked.files.map { relativePath(roots, it) to it }, 0, walked.beyond, walked.excluded)
    }

    private sealed interface LoadResult {
        class Loaded(val file: CodeFile) : LoadResult
        class Skipped(val reason: SkipReason) : LoadResult
    }

    /**
     * Collects content files under [directory] in project-relative path order. Each top-level folder is walked in its own
     * read action, so a write action restarts only part of a large tree. Excluded folders are pruned without walking them.
     */
    private suspend fun enumerate(directory: VirtualFile, exclusions: CodeExclusions): Found {
        val index = ProjectFileIndex.getInstance(project)
        val changes = ChangeListManager.getInstance(project)
        val types = FileTypeRegistry.getInstance()
        val roots = readAction { codeScopeRoots(project) }
        fun ignored(file: VirtualFile) = types.isFileIgnored(file) || changes.isIgnoredFile(file) ||
            file.isDirectory && file.name in setOf(PROJECT_STORE_FOLDER, ReviewDiskCache.DIRECTORY)
        fun excluded(file: VirtualFile) = !exclusions.isEmpty && exclusions.matches(relativePath(roots, file), file.isDirectory)
        // A project root that is not content itself still holds content roots.
        val starts = readAction {
            if (index.isInContent(directory)) listOf(directory)
            else ProjectRootManager.getInstance(project).contentRoots.filter { VfsUtilCore.isAncestor(directory, it, false) }
        }
        val files = mutableListOf<VirtualFile>()
        var ignoredCount = 0
        var excludedCount = 0
        for (start in starts) {
            currentCoroutineContext().ensureActive()
            if (start != directory && readAction { excluded(start) }) { excludedCount++; continue }
            val (direct, folders, prunedTop) = readAction {
                val direct = mutableListOf<VirtualFile>()
                val folders = mutableListOf<VirtualFile>()
                var pruned = 0
                if (start.isValid) for (child in start.children) {
                    if (!index.isInContent(child)) continue
                    when {
                        ignored(child) -> pruned++
                        excluded(child) -> excludedCount++
                        child.isDirectory -> folders += child
                        else -> direct += child
                    }
                }
                Triple(direct, folders, pruned)
            }
            files += direct
            ignoredCount += prunedTop
            for (folder in folders) {
                currentCoroutineContext().ensureActive()
                val (part, pruned, left) = readAction {
                    val part = mutableListOf<VirtualFile>()
                    var pruned = 0
                    var left = 0
                    if (folder.isValid) index.iterateContentUnderDirectory(folder, { file ->
                        ProgressManager.checkCanceled()
                        if (!file.isDirectory) part += file
                        true
                    }) { file ->
                        when {
                            file == folder -> true
                            ignored(file) -> { pruned++; false }
                            excluded(file) -> { left++; false }
                            else -> true
                        }
                    }
                    Triple(part, pruned, left)
                }
                files += part
                ignoredCount += pruned
                excludedCount += left
            }
        }
        val sorted = files.distinctBy { it.path }.map { relativePath(roots, it) to it }.sortedBy { it.first }
        return Found(sorted, ignoredCount, excluded = excludedCount)
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
        val raw = unsaved ?: try { VfsUtilCore.loadText(file) } catch (e: IOException) { LOG.warn("Could not read ${file.path}", e); return LoadResult.Skipped(SkipReason.UNREADABLE) }
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

/**
 * The folder ([directory]) or file at [absolutePath] on disk or under a project root (tests use an in-memory file system).
 * Call in a read action.
 */
internal fun findCodeScopeFile(project: Project, absolutePath: String, directory: Boolean): VirtualFile? {
    val path = absolutePath.replace('\\', '/').let { if (it.length > 1) it.trimEnd('/') else it }
    LocalFileSystem.getInstance().findFileByPath(path)?.takeIf { it.isDirectory == directory }?.let { return it }
    return codeScopeRoots(project).firstNotNullOfOrNull { root ->
        when {
            path == root.path -> root
            path.startsWith(root.path.trimEnd('/') + "/") -> root.findFileByRelativePath(path.removePrefix(root.path.trimEnd('/') + "/"))
            else -> null
        }
    }?.takeIf { it.isDirectory == directory }
}

/**
 * The scope of [file]: a folder, or an entry file whose dependencies are reviewed. Call in a read action. Throws
 * [IllegalArgumentException] with a message for the user when it is not a project folder or a project source file, or is
 * excluded or ignored.
 */
internal fun codeScope(project: Project, file: VirtualFile): CodeScope {
    require(file.isValid) { "${file.presentableUrl} no longer exists." }
    val index = ProjectFileIndex.getInstance(project)
    val path = codeScopeRoots(project).firstNotNullOfOrNull { VfsUtilCore.getRelativePath(file, it) }
    require(path != null) { "${file.presentableUrl} is not in the project." }
    val name = path.ifEmpty { "The project root" }
    require(ReviewDiskCache.DIRECTORY !in path.split('/')) { "$name contains generated review cache data." }
    require(!index.isExcluded(file)) { "$name is excluded from the project." }
    if (!file.isDirectory) {
        require(index.isInContent(file)) { "$name is not part of the project's content." }
        require(!FileTypeRegistry.getInstance().isFileIgnored(file) && !ChangeListManager.getInstance(project).isIgnoredFile(file)) {
            "$name is ignored."
        }
        require(!file.fileType.isBinary) { "$name is a binary file." }
        return CodeScope(path, file.path, entry = true)
    }
    require(index.isInContent(file) ||
        ProjectRootManager.getInstance(project).contentRoots.any { VfsUtilCore.isAncestor(file, it, false) }) {
        "$name is not part of the project's content."
    }
    return CodeScope(path, file.path)
}

/**
 * The scope of a project-relative or absolute [path] of a folder or an entry file, "" or "." for the project root. Call in a
 * read action. Throws [IllegalArgumentException] with a message for the user when it is not a project folder or file.
 */
internal fun codeScope(project: Project, path: String): CodeScope {
    val trimmed = path.trim().replace('\\', '/')
    require("/../" !in "/$trimmed/") { "The path must not contain '..': $path" }
    val absolute = trimmed.startsWith("/") || Regex("^[A-Za-z]:/").containsMatchIn(trimmed)
    val file = if (absolute) {
        findCodeScopeFile(project, trimmed, directory = true) ?: findCodeScopeFile(project, trimmed, directory = false)
    } else {
        val clean = trimmed.trim('/').removePrefix("./").let { if (it == ".") "" else it }
        codeScopeRoots(project).firstNotNullOfOrNull { if (clean.isEmpty()) it else it.findFileByRelativePath(clean) }
    }
    require(file != null) { "There is no project folder or file at '${trimmed.ifEmpty { "." }}'." }
    return codeScope(project, file)
}
