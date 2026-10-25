package dev.insight.diff

import com.intellij.codeInsight.highlighting.HighlightManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportRawProgress
import com.intellij.util.ui.JBUI
import dev.insight.chat.ChatPanel
import dev.insight.chat.ChatSettings
import java.awt.BorderLayout
import java.time.Duration
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane
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

/** Opens [file] with [lines] centered and highlighted like search results, until Escape or an edit. */
internal fun openHighlightedLines(project: Project, file: VirtualFile, lines: List<Int>) {
    val first = lines.minOrNull() ?: return
    val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file, first - 1, 0), true) ?: return
    highlightLines(project, editor, lines)
    editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
}

/** Highlights each run of consecutive 1-based [lines] of [editor]; lines past its end are skipped. */
internal fun highlightLines(project: Project, editor: Editor, lines: List<Int>) {
    val document = editor.document
    val manager = HighlightManager.getInstance(project)
    val sorted = lines.filter { it in 1..document.lineCount }.distinct().sorted()
    var start = 0
    for (index in sorted.indices) {
        if (index + 1 < sorted.size && sorted[index + 1] == sorted[index] + 1) continue
        manager.addRangeHighlight(editor, document.getLineStartOffset(sorted[start] - 1), document.getLineEndOffset(sorted[index] - 1),
            EditorColors.SEARCH_RESULT_ATTRIBUTES, true, null)
        start = index + 1
    }
}

/**
 * Prescoring and searching summaries for one review, with a background progress each. One tracer per review, so its cached
 * judgments serve every word traced in it. Results arrive on the EDT; [scope] should run on it and ends the tasks with the tab.
 */
internal class ReviewTraceLauncher(private val project: Project, private val scope: CoroutineScope) : SummaryTraceLauncher {
    private val summaryTracer by lazy {
        SummaryTracer(ChatSettings.getInstance().createDecisionsClient(requestTimeout = Duration.ofMinutes(5), maxRetries = 2)::evaluate,
            cache = ReviewDiskCache.forProject(project))
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

    override fun searchLines(summaries: ChangeSummaries, id: Int, context: SummaryContext, done: (Result<LineTrace>) -> Unit): Job =
        launchSummaryTask("Searching related lines", done) { summaryTracer.searchLines(summaries, id, context) }

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

/** Resolves semantic review links off the EDT, using the review's existing Decisions configuration. */
internal class ReviewSummaryLinkLauncher(private val project: Project, private val scope: CoroutineScope) : SummaryReviewLauncher {
    private val linker by lazy {
        SummaryReviewLinker(ChatSettings.getInstance().createDecisionsClient(requestTimeout = Duration.ofMinutes(5), maxRetries = 2)::evaluate,
            ReviewDiskCache.forProject(project))
    }

    override fun launch(summaries: ChangeSummaries, reviewed: Set<String>, done: (Result<ReviewDimming>) -> Unit): Job = scope.launch {
        val result = try {
            Result.success(withBackgroundProgress(project, "Linking reviewed summaries", cancellable = true) {
                withContext(Dispatchers.IO) {
                    reportRawProgress { reporter ->
                        reporter.text("Linking reviewed summaries…")
                        linker.dimming(summaries, reviewed) { count, total ->
                            reporter.details("$count of $total summaries")
                            reporter.fraction(if (total > 0) count.toDouble() / total else null)
                        }
                    }
                }
            })
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
        done(result)
    }
}

/** Uses the same evidence and prompts as an ordinary run, writing only the requested version's nodes. */
internal class ReviewContextSummaryLauncher(
    private val project: Project,
    private val scope: CoroutineScope,
    private val create: suspend () -> ChangeSummarizer,
) : ContextSummaryLauncher {
    override fun launch(start: ChangeSummaries, request: ContextSummaryRequest,
                        updated: (ChangeSummaries) -> Unit, done: (Result<ChangeSummaryRun>) -> Unit): Job {
        return scope.launch {
            val result = try {
                // Capture the review's evidence on the EDT, before starting background work.
                val summarizer = create()
                Result.success(withBackgroundProgress(project, if (request.regenerate) "Resummarizing" else "Filling summaries", cancellable = true) {
                    withContext(Dispatchers.IO) {
                        reportRawProgress { reporter ->
                            var current = start
                            summarizer.run(start, "", save = { entries ->
                                current = current.summarize(entries, reuseSingleChildren = false)
                                withContext(Dispatchers.EDT) { updated(current) }
                                current
                            }, onProgress = { count, total ->
                                reporter.text(if (request.regenerate) "Resummarizing summaries…" else "Filling missing summaries…")
                                reporter.details("$count of $total summaries")
                                reporter.fraction(if (total > 0) count.toDouble() / total else null)
                            }, nodeIds = request.nodeIds, queryContext = request.query,
                                instructions = request.instructions, reuseSingleChildren = !request.regenerate)
                        }
                    }
                })
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(e)
            }
            done(result)
        }
    }
}

/**
 * A review tool window's two pages: Main holds the scope, files and review tab controls, and Chat gives the selected review
 * tab's chat the full height. A Swing chat has one parent, so it moves here whenever its review tab is selected.
 */
internal class ReviewSidePages(main: JComponent) : JTabbedPane() {
    private val chatTitle = JLabel().apply { border = JBUI.Borders.empty(4, 12, 0, 12) }
    private val chatHost = JPanel(BorderLayout())
    private var chat: ChatPanel? = null

    init {
        addTab("Main", main)
        addTab("Chat", JPanel(BorderLayout()).apply {
            add(chatTitle, BorderLayout.NORTH)
            add(chatHost, BorderLayout.CENTER)
        })
        setToolTipTextAt(CHAT, "The selected review tab's conversation")
        addChangeListener { if (selectedIndex == CHAT) chat?.input?.requestFocusInWindow() }
    }

    /** Shows [chat], the conversation of the review tab named [name]; null leaves the page empty. */
    fun showChat(chat: ChatPanel?, name: String) {
        chatTitle.text = if (chat == null) "" else "Review tab: $name"
        if (this.chat === chat) return
        this.chat = chat
        chatHost.removeAll()
        chat?.let { chatHost.add(it, BorderLayout.CENTER) }
        chatHost.revalidate()
        chatHost.repaint()
    }

    private companion object {
        const val CHAT = 1
    }
}
