package dev.insight.context.actions

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import dev.insight.agent.codeScopeRoots
import com.intellij.platform.ide.progress.withBackgroundProgress
import dev.insight.InsightBundle
import dev.insight.codereview.CodeFile
import dev.insight.codereview.CodeReviewLoader
import dev.insight.codereview.CodeScope
import dev.insight.codereview.CodeSnapshot
import dev.insight.codereview.ChunkOptions
import dev.insight.codereview.chunkSource
import dev.insight.codereview.codeScope
import dev.insight.codereview.codeSummaryReview
import dev.insight.context.ContextKeys
import dev.insight.context.ContextOrigin
import dev.insight.context.ContextRequest
import dev.insight.context.ContextScopeKind
import dev.insight.context.EDITOR_DIFF_SUMMARY
import dev.insight.context.SUMMARY_SOURCE_TEXT
import dev.insight.context.location
import dev.insight.core.describeFailure
import dev.insight.core.runCatchingReported
import dev.insight.diff.ReviewFile
import dev.insight.diff.diffSummaryReview
import dev.insight.review.SummaryKind
import dev.insight.review.ui.ReviewTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Provides a scoped review when the picker does not belong to a review tab. */
internal object ContextSummaryGeneration {
    fun canGenerate(request: ContextRequest): Boolean {
        if (request.context.path == null || request.context.kind == ContextScopeKind.CHANGE) return false
        if (request.context.kind == ContextScopeKind.DIRECTORY) return request.directory() != null
        return request.capturedDiff() != null ||
            EDITOR_DIFF_SUMMARY in request.data ||
            (request.origin != ContextOrigin.IDE_DIFF && !request.context.isPatch && SUMMARY_SOURCE_TEXT in request.data)
    }

    fun generate(request: ContextRequest) {
        val path = request.context.path ?: return
        if (!canGenerate(request)) return
        val project = request.project
        project.service<ContextSummaryPreparation>().prepare(request.context.location()) {
            request.directory()?.let { directory ->
                val location = readAction { codeScope(project, directory) }
                val snapshot = CodeReviewLoader(project).load(location, previous = null)
                if (snapshot.files.isEmpty()) return@prepare report(project, InsightBundle.message("context.summary.no.files"))
                return@prepare openContextSummaries(project, codeSummaryReview(project, snapshot), location.path, SummaryKind.DIRECTORY)
            }
            val diff = request.capturedDiff() ?: request.data[EDITOR_DIFF_SUMMARY]?.let {
                withContext(Dispatchers.Default) { it.reviewFile() }
            }
            if (diff != null) return@prepare openContextSummaries(project, diffSummaryReview(project, diff), diff.path, SummaryKind.FILE)
            val text = request.data[SUMMARY_SOURCE_TEXT]
                ?: return@prepare report(project, InsightBundle.message("context.summary.unavailable", request.context.location()))
            val absolutePath = request.context.absolutePath ?: request.data[ContextKeys.VIRTUAL_FILE]?.path ?: path
            val reviewPath = readAction { reviewPath(project, request, absolutePath) } ?: path.trim('/')
            val file = withContext(Dispatchers.Default) {
                CodeFile(reviewPath, absolutePath, text, chunks = chunkSource(text, ChunkOptions()))
            }
            val snapshot = CodeSnapshot(CodeScope(reviewPath, absolutePath, entry = true), listOf(file))
            openContextSummaries(project, codeSummaryReview(project, snapshot), reviewPath, SummaryKind.FILE)
        }
    }

    /**
     * The file's path relative to the roots [CodeReviewLoader] uses, so review tabs match it; null when it is outside them.
     * [ContextRequest.context]'s path is only relative to the project's base directory.
     */
    private fun reviewPath(project: Project, request: ContextRequest, absolutePath: String): String? {
        val file = request.data[ContextKeys.VIRTUAL_FILE]?.takeIf { it.isValid && !it.isDirectory && it.path == absolutePath }
            ?: LocalFileSystem.getInstance().findFileByPath(absolutePath)
            ?: return null
        return codeScopeRoots(project).firstNotNullOfOrNull { VfsUtilCore.getRelativePath(file, it) }?.takeIf { it.isNotEmpty() }
    }

    private fun ContextRequest.directory(): VirtualFile? = if (context.kind == ContextScopeKind.DIRECTORY)
        data[ContextKeys.VIRTUAL_FILE]?.takeIf { it.isValid && it.isDirectory } else null

    private fun ContextRequest.capturedDiff(): ReviewFile? = data[ContextKeys.REVIEW_FILE]?.takeIf {
        it.path == context.path || it.previousPath == context.path
    }
}

@Service(Service.Level.PROJECT)
internal class ContextSummaryPreparation(private val project: Project, private val scope: CoroutineScope) {
    fun prepare(location: String, action: suspend () -> Unit) {
        scope.launch(Dispatchers.EDT) {
            val title = InsightBundle.message("context.summary.preparing", location)
            runCatchingReported(LOG, title) {
                withBackgroundProgress(project, title) { withContext(Dispatchers.EDT) { action() } }
            }
                .onFailure { report(project, describeFailure(it)) }
        }
    }

    companion object {
        private val LOG = logger<ContextSummaryPreparation>()
    }
}

/** Opens the ordinary Summaries editor and reveals the requested file before filling its missing summary. */
internal fun openContextSummaries(project: Project, tab: ReviewTab<*, *>?, path: String, kind: SummaryKind) {
    val node = tab?.state?.summaries?.tree?.scopeNode(path, kind)
    if (tab == null || node == null) {
        report(project, InsightBundle.message("context.summary.unavailable", path.ifEmpty { "." }))
        return
    }
    if (kind != SummaryKind.FILE || !tab.showInSummaryTree(path)) tab.openSummaries()
    if (tab.state.summaries.text(node.id) == null && !tab.fillSummary(path, kind))
        report(project, InsightBundle.message("context.summary.busy"))
}
