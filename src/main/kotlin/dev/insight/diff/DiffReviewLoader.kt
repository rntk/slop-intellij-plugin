package dev.insight.diff

import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.changes.BinaryContentRevision
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.VirtualFile
import dev.insight.callgraph.DiffService
import kotlinx.coroutines.ensureActive
import java.nio.file.Path
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext

/** Runs on IO. Inclusion of untracked files is review-local and never changes the VCS index. */
internal class DiffReviewLoader(private val project: Project) {
    suspend fun load(includedUntracked: Set<String>, request: ReviewDiffRequest = ReviewDiffRequest()): ReviewSnapshot {
        val selected = request.validated()
        val manager = ChangeListManager.getInstance(project)
        val loaded = if (selected.mode == ReviewDiffMode.LOCAL) null else {
            ReviewDiffProvider.EP_NAME.extensionList.firstNotNullOfOrNull { it.changes(project, selected) }
                ?: throw VcsException("The selected Git repository is unavailable. Enable the Git plugin and choose a repository.")
        }
        val comparison = loaded?.comparison ?: ReviewComparison()
        val changes = loaded?.changes?.toList() ?: manager.allChanges.toList()
        val tracked = changes.mapNotNull { (it.afterRevision ?: it.beforeRevision)?.file?.path }.toSet()
        val untracked = if (!selected.mode.usesWorkingTree) emptyList() else manager.unversionedFilesPaths.mapNotNull { path ->
            path.virtualFile?.takeIf { !it.isDirectory && it.path !in tracked && "/${ReviewDiskCache.DIRECTORY}/" !in it.path &&
                (selected.repository == null || ProjectLevelVcsManager.getInstance(project).getVcsRootFor(it)?.path == selected.repository) }
        }.distinctBy { it.path }
        val files = changes.mapNotNull { change ->
            currentCoroutineContext().ensureActive()
            val before = change.beforeRevision
            val after = change.afterRevision
            if ((after ?: before)?.file?.isDirectory == true) return@mapNotNull null
            val path = (after ?: before)?.file?.path ?: return@mapNotNull null
            val previous = before?.file?.path?.takeIf { it != path }
            val status = when { before == null -> "added"; after == null -> "deleted"; previous != null -> "renamed"; else -> "modified" }
            val old = if (before == null) "" else revisionText(before)
            val fresh = if (after == null) "" else if (selected.mode.usesWorkingTree)
                workingText(after.file.virtualFile) { revisionText(after) } else revisionText(after)
            val virtual = after?.file?.virtualFile
            file(path, previous, status, old, fresh,
                "${before?.revisionNumber?.asString()}:${after?.revisionNumber?.asString()}" +
                    if (selected.mode.usesWorkingTree) ":${diskVersion(virtual)}" else "", comparison)
        }.toMutableList()
        for (virtual in untracked.filter { it.path in includedUntracked }) {
            currentCoroutineContext().ensureActive()
            files += file(virtual.path, null, "untracked", "", workingText(virtual) { StringUtil.convertLineSeparators(String(virtual.contentsToByteArray(), virtual.charset)) },
                diskVersion(virtual), comparison)
        }
        return ReviewSnapshot(files.sortedBy { it.path }, untracked.map { UntrackedFile(relative(it.path), it.path) }.sortedBy { it.path }, comparison)
    }

    private fun relative(path: String): String {
        val base = project.basePath ?: return path
        return if (Path.of(path).startsWith(Path.of(base))) Path.of(base).relativize(Path.of(path)).toString().replace('\\', '/') else path
    }

    private fun revisionText(revision: ContentRevision): String? {
        if (revision is BinaryContentRevision || revision.file.fileType.isBinary) return null
        return try { revision.content?.let(StringUtil::convertLineSeparators) }
        catch (_: VcsException) { null }
    }

    /**
     * Stands in for unreadable content. The disk time and length survive a restart, unlike the session-local modification
     * stamp; an unsaved edit is not on disk yet, so only then the document's stamp is added.
     */
    private suspend fun diskVersion(file: VirtualFile?): String = if (file == null) "" else readAction {
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        "${file.timeStamp}:${file.length}" +
            if (document != null && FileDocumentManager.getInstance().isDocumentUnsaved(document)) ":unsaved:${document.modificationStamp}" else ""
    }

    private suspend fun workingText(file: VirtualFile?, fallback: () -> String?): String? {
        if (file != null) {
            if (file.fileType.isBinary || file.length > MAX_TEXT) return null
            readAction { FileDocumentManager.getInstance().getCachedDocument(file)?.immutableCharSequence?.toString() }
                ?.let { return StringUtil.convertLineSeparators(it) }
        }
        return try { fallback() } catch (_: IOException) { null }
    }

    private fun file(path: String, previous: String?, status: String, before: String?, after: String?, unavailableVersion: String, comparison: ReviewComparison): ReviewFile {
        val readable = before != null && after != null && before.length <= MAX_TEXT && after.length <= MAX_TEXT
        val patch = if (readable) reviewPatch(before, after, DiffService.lineChanges(before, after))
            else "[Diff content unavailable: binary, unreadable, or over the text size limit ($MAX_TEXT bytes/characters per side).]"
        val relativePath = relative(path)
        val previousPath = previous?.let(::relative)
        // Project-relative paths and stable versions only, so the key, and every result saved under it, survives a restart.
        return ReviewFile(relativePath, path, previousPath, status, patch,
            reviewFingerprint(relativePath, previousPath.orEmpty(), status, before ?: "[unavailable]", after ?: "[unavailable]", if (readable) "" else unavailableVersion),
            before.takeIf { readable }, after.takeIf { readable }, comparison.beforeLabel, comparison.afterLabel)
    }

    companion object { private const val MAX_TEXT = 1_000_000 }
}
