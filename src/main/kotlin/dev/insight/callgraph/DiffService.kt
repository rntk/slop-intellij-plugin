package dev.insight.callgraph

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.BinaryContentRevision
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.psi.PsiDocumentManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import dev.insight.enrichment.liftCalls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Changes of the working tree against a named revision ("main", a commit hash). Provided by version control
 * integrations that are optional plugins, such as Git (see `META-INF/diff-git.xml`).
 */
interface DiffBaseProvider {
    /** The changes against [ref], or null when this provider does not manage the project. Throws [VcsException] for an unknown ref. */
    fun changes(project: Project, ref: String): Collection<Change>?

    companion object {
        val EP_NAME = ExtensionPointName.create<DiffBaseProvider>("dev.insight.diffBaseProvider")
        val available: Boolean get() = EP_NAME.extensionList.isNotEmpty()
    }
}

/**
 * @property base revision to compare the working tree with; null for the local changes against HEAD.
 * @property maxHops caller and callee hops kept around changed symbols; null for unlimited.
 * @property contextLines lines around each change that also count as changed, like git's default unified context.
 */
data class DiffRequest(val base: String? = null, val maxHops: Int? = null, val contextLines: Int = 3)

/**
 * Maps VCS changes onto the application graph, like the Python reference flow (`slop-code-graph/diff.py`) but with
 * the IDE's change list and line comparison instead of parsing `git diff` output.
 */
@Service(Service.Level.PROJECT)
class DiffService(private val project: Project, val scope: CoroutineScope) {

    suspend fun compute(built: CallGraphResult, request: DiffRequest = DiffRequest()): DiffResult =
        withBackgroundProgress(project, "Mapping changes onto the application graph") {
            val base = request.base?.trim()?.takeIf { it.isNotEmpty() }
            val changes = withContext(Dispatchers.IO) { changes(base) }
            val files = withContext(Dispatchers.IO) {
                changes.mapNotNull { change ->
                    coroutineContext.ensureActive()
                    fileDiff(change, request.contextLines)
                }
            }
            val paths = files.filter { it.status != FileDiff.Status.DELETED }.mapTo(hashSetOf()) { it.path }
            val ranges = smartReadAction(project) { nodeRanges(built, paths) }
            val tree = built.graph.hierarchy()
            val changed = keepInnermost(mapChangesToNodes(files, ranges), tree.parent)
            val neighborhood = pruneToChangedNeighborhood(liftCalls(built.graph), changed, request.maxHops, request.maxHops)
            DiffResult(base ?: "HEAD", files.sortedBy { it.path }, changed, neighborhood, built.graph)
        }

    private fun changes(base: String?): Collection<Change> {
        if (base == null) return ChangeListManager.getInstance(project).allChanges
        val providers = DiffBaseProvider.EP_NAME.extensionList
        if (providers.isEmpty()) throw VcsException("Comparing with a revision needs the Git plugin")
        return providers.firstNotNullOfOrNull { it.changes(project, base) }
            ?: throw VcsException("No Git repository in this project")
    }

    /** Null when the change has no file on either side. Reads revision content, so call it off the EDT and outside a read action. */
    private suspend fun fileDiff(change: Change, contextLines: Int): FileDiff? {
        val before = change.beforeRevision
        val after = change.afterRevision
        return when {
            after == null && before != null -> deletedFileDiff(before.file.path, text(before)?.let(::lineCount) ?: 0)
            after == null -> null
            before == null -> {
                val text = currentText(after)
                if (text == null) FileDiff(after.file.path, null, FileDiff.Status.ADDED, 0, 0, emptyList())
                else addedFileDiff(after.file.path, lineCount(text))
            }
            else -> {
                val oldText = text(before)
                val newText = currentText(after)
                val oldPath = before.file.path.takeIf { it != after.file.path }
                if (oldText == null || newText == null) {
                    // Binary or unreadable: listed without lines, so it never maps onto a symbol.
                    FileDiff(after.file.path, oldPath, if (oldPath != null) FileDiff.Status.RENAMED else FileDiff.Status.MODIFIED, 0, 0, emptyList())
                } else fileDiffOf(after.file.path, oldPath, lineChanges(oldText, newText), lineCount(newText), contextLines)
            }
        }
    }

    /** The open document's text for a local file, so unsaved edits count, otherwise the revision's content. */
    private suspend fun currentText(revision: ContentRevision): String? {
        if (isBinary(revision)) return null
        val file = revision.file.virtualFile
        if (file != null && file.isValid) {
            readAction { FileDocumentManager.getInstance().getCachedDocument(file)?.text }?.let { return it }
        }
        return text(revision)
    }

    private fun isBinary(revision: ContentRevision) = revision is BinaryContentRevision || revision.file.fileType.isBinary

    private fun text(revision: ContentRevision): String? {
        if (isBinary(revision)) return null
        return try {
            revision.content?.let(StringUtil::convertLineSeparators)
        } catch (e: VcsException) {
            thisLogger().info("Cannot read ${revision.file.path} at ${revision.revisionNumber}", e)
            null
        }
    }

    /** Symbols in [paths] with their 1-based inclusive lines. Locals are left out: calls are attributed to their owners. */
    private fun nodeRanges(built: CallGraphResult, paths: Set<String>): Map<Int, Pair<String, IntRange>> {
        if (paths.isEmpty()) return emptyMap()
        val documents = PsiDocumentManager.getInstance(project)
        val ranges = HashMap<Int, Pair<String, IntRange>>()
        for (node in built.graph.nodes) {
            val path = node.filePath ?: continue
            if (path !in paths || node.kind == SymbolKind.VARIABLE || node.kind == SymbolKind.PARAMETER) continue
            val element = built.pointers.getOrNull(node.id)?.element ?: continue
            val file = element.containingFile ?: continue
            val document = documents.getDocument(file) ?: continue
            val range = element.textRange ?: continue
            val length = document.textLength
            val start = range.startOffset.coerceIn(0, length)
            val end = (range.endOffset - 1).coerceIn(start, maxOf(length, start))
            if (length == 0) { ranges[node.id] = path to 1..1; continue }
            ranges[node.id] = path to (document.getLineNumber(start) + 1)..(document.getLineNumber(minOf(end, length)) + 1)
        }
        return ranges
    }

    companion object {
        /** Changed regions between two texts, from the IDE's line comparison. Too large files count as entirely changed. */
        fun lineChanges(before: CharSequence, after: CharSequence): List<LineChange> = try {
            ComparisonManager.getInstance()
                .compareLines(before, after, ComparisonPolicy.DEFAULT, DumbProgressIndicator.INSTANCE)
                .map { LineChange(it.startLine1, it.endLine1, it.startLine2, it.endLine2) }
        } catch (e: Throwable) {
            if (e is ControlFlowException || e is CancellationException || e is VirtualMachineError) throw e
            com.intellij.openapi.diagnostic.Logger.getInstance(DiffService::class.java).warn("Line comparison failed; treating the file as entirely changed", e)
            listOf(LineChange(0, lineCount(before), 0, lineCount(after)))
        }
    }
}
