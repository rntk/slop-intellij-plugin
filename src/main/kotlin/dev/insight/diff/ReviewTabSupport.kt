package dev.insight.diff

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import dev.insight.apilog.ApiLogs
import dev.insight.core.decisionsClient
import java.awt.BorderLayout
import java.time.Duration
import javax.swing.SwingUtilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opens a review's canvas in the IDE editor area. Splits that are not under the editor's center are left alone, and the canvas
 * is closed in them, since a Swing canvas has one parent.
 */
internal fun openReviewCanvas(project: Project, file: DiffReviewCanvasFile, focus: Boolean) {
    val manager = FileEditorManagerEx.getInstanceEx(project)
    for (window in manager.windows) {
        val center = (window.owner.layout as? BorderLayout)?.getLayoutComponent(BorderLayout.CENTER)
        if (center == null) {
            window.owner.add(window.tabbedPane.component, BorderLayout.CENTER)
            window.owner.validate()
        } else if (!SwingUtilities.isDescendingFrom(window.tabbedPane.component, center)) {
            if (window.isFileOpen(file)) window.closeFile(file)
            manager.windows.firstOrNull {
                it.owner === window.owner && SwingUtilities.isDescendingFrom(it.tabbedPane.component, center)
            }?.setAsCurrentWindow(false)
        }
    }
    manager.openFile(file, focus).filterIsInstance<DiffReviewEditor>().firstOrNull()?.attachCanvas()
}

/**
 * Prescoring and searching summaries for one review, with a background progress each. One tracer per review, so its cached
 * judgments serve every word traced in it. Results arrive on the EDT; [scope] should run on it and ends the tasks with the tab.
 */
internal class ReviewTraceLauncher(private val project: Project, private val scope: CoroutineScope) : SummaryTraceLauncher {
    private val summaryTracer by lazy {
        SummaryTracer(decisionsClient("local", requestTimeout = Duration.ofMinutes(5), traffic = ApiLogs.decisions)::evaluate)
    }

    override fun prescore(summaries: ChangeSummaries, words: List<Pair<Int, Int>>,
                          scored: (SummaryContext) -> Unit, done: (Result<Unit>) -> Unit): Job =
        launchSummaryTask("Scoring summary words", done) {
            // Four workers bound the number of words in flight; each word's question batches run in parallel.
            coroutineScope {
                words.chunked(((words.size + 3) / 4).coerceAtLeast(1)).map { chunk ->
                    async {
                        for ((id, word) in chunk) {
                            val context = summaryTracer.score(summaries, id, word)
                            withContext(Dispatchers.EDT) { scored(context) }
                        }
                    }
                }.awaitAll()
            }
            Unit
        }

    override fun search(summaries: ChangeSummaries, id: Int, context: SummaryContext, done: (Result<SummaryTrace>) -> Unit): Job =
        launchSummaryTask("Searching related summaries", done) { summaryTracer.search(summaries, id, context) }

    private fun <T> launchSummaryTask(title: String, done: (Result<T>) -> Unit, task: suspend () -> T): Job = scope.launch {
        val result = try {
            Result.success(withBackgroundProgress(project, title) {
                withContext(Dispatchers.IO) { task() }
            })
        } catch (e: CancellationException) {
            // A replaced word or closed tab must not publish a stale result. Cancelling only the
            // IDE progress task still completes the callback so the card stops showing a pending request.
            currentCoroutineContext().ensureActive()
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
        done(result)
    }
}
