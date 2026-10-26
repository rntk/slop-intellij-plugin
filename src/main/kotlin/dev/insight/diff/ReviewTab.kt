package dev.insight.diff

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.platform.util.progress.reportRawProgress
import com.intellij.psi.util.PsiModificationTracker
import dev.insight.callgraph.ExplainChartResult
import dev.insight.chat.ChatMessage
import dev.insight.chat.ChatPanel
import dev.insight.chat.ChatSession
import dev.insight.chat.ChatTool
import dev.insight.chat.SourcePoints
import dev.insight.chat.ToolInputException
import dev.insight.review.ChatFocus
import dev.insight.review.ExplainDialog
import dev.insight.review.ExplainResults
import dev.insight.review.ExplainTarget
import dev.insight.review.ReviewCanvasFile
import dev.insight.review.ReviewCanvasOwner
import dev.insight.review.ReviewSource
import dev.insight.review.ReviewTabState
import dev.insight.review.ReviewVocabulary
import dev.insight.review.SummaryNode
import dev.insight.review.chatFocus
import dev.insight.review.explainChartFor
import dev.insight.review.explainChartPrompt
import dev.insight.review.explainCommand
import dev.insight.review.explainRequest
import dev.insight.review.explainTarget
import dev.insight.review.isExplainRequest
import dev.insight.review.openReviewCanvas
import dev.insight.review.textOf
import dev.insight.review.typedExplainTarget
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/**
 * What the Diff Review and Code Review tabs share: one conversation and canvas over a [state], the summaries and topics
 * cards, Explain and To Chat. A subclass creates the [canvas] and [session], lays out its controls and renders the canvas.
 */
internal abstract class ReviewTab<S : ReviewSource, T : ReviewTabState<S>>(
    protected val project: Project,
    val state: T,
    /** Pinned to every turn of this tab's chat; see [ChatFocus]. */
    val focus: ChatFocus?,
    vocabulary: ReviewVocabulary,
    sourcePointsTitle: String,
) : JPanel(BorderLayout()), Disposable, ReviewCanvasOwner {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    protected var disposed = false
        private set
    protected val sourcePoints = SourcePoints(project, sourcePointsTitle)
    override val isDisposed: Boolean get() = disposed
    override val canvasTitle: String = vocabulary.canvasTitle
    var onCanvasSelected: () -> Unit = {}
    override fun canvasSelected() = onCanvasSelected()
    abstract override val canvas: NoteCanvas
    abstract val session: ChatSession
    /** Shown by the panel's Chat page, so it is not part of the tab's controls. */
    abstract val chat: ChatPanel
    val canvasFile = ReviewCanvasFile(this)
    protected val explanations = ExplainResults()

    protected abstract fun renderCanvas()
    /** Empties the state's snapshot when the tab is disposed. */
    protected abstract fun clearSnapshot()

    /** Follows the chat: [before] runs first on every change, then the controls, cards and explanation are updated. */
    protected fun followSession(before: () -> Unit = {}, updateControls: () -> Unit) = session.subscribe {
        before()
        updateControls()
        summariesChanged()
        if (explanations.update(session.messages, session.busy)) {
            renderCanvas()
            if (explanations.result != null) showCanvas { canvas.focusExplanation() }
        }
    }

    fun openCanvas(focus: Boolean = false) {
        if (disposed) return
        openReviewCanvas(project, canvasFile, focus)
    }

    fun highlight(paths: Set<String>) { canvas.highlight(paths) }

    protected val canSummarize: Boolean get() = !disposed && !state.refreshing && !session.busy && !canvas.topicSummariesRunning &&
        state.snapshotFiles.isNotEmpty()

    protected fun summarize() { if (canSummarize) session.send("/summaries") }

    /** The target of the /explain command an Explain link sent, until its turn takes it; see [takeExplain]. */
    private var pendingExplain: Pair<String, ExplainTarget>? = null

    /** Asks what to focus on, then sends /explain for a summary entry; its files and lines need no parsing of the command. */
    protected fun explain(node: SummaryNode) {
        if (!canSummarize) return
        val dialog = ExplainDialog(project, node.location, state.summaries.textOf(node))
        if (!dialog.showAndGet() || !canSummarize) return
        val target = explainTarget(node, state.summaries, dialog)
        val command = explainCommand(node.location, target.instructions)
        pendingExplain = command to target
        session.send(command)
    }

    /** The pending link target when [question] is its command, else the typed one; limited to the [paths] still in scope (EDT). */
    private fun takeExplain(question: String, paths: List<String>): ExplainTarget {
        val pending = pendingExplain?.takeIf { it.first == question.trim() }?.second
        pendingExplain = null
        val inScope = paths.toSet()
        return pending?.let { it.copy(files = it.files.filter(inScope::contains)) } ?: typedExplainTarget(question, state.summaries, paths)
    }

    /** The request and chart tools of a chat turn, with the /explain instructions when [messages] end with one. */
    protected class ChatTurn(val request: List<ChatMessage>, val chartTools: List<ChatTool>) {
        /** [prompt] with the chart instructions when the turn has chart tools. */
        fun prompt(prompt: String) = prompt + if (chartTools.isEmpty()) "" else "\n\n" + explainChartPrompt()
    }

    /** Prepares a chat turn on [files]; null for an /explain whose target has no files left. */
    protected suspend fun chatTurn(messages: List<ChatMessage>, files: List<S>, diff: Boolean): ChatTurn? {
        val question = messages.lastOrNull()?.text.orEmpty()
        val explain = if (isExplainRequest(question)) withContext(Dispatchers.EDT) { takeExplain(question, files.map { it.path }) } else null
        if (explain != null && explain.files.isEmpty()) return null
        val chart = explainChartFor(project, explain, messages) { paths -> files.filter { it.path in paths }.map { it.absolutePath } }
        if (explain != null) withContext(Dispatchers.EDT) { explanations.chartPrepared(chart) }
        // Only this turn carries the command's instructions; the transcript keeps the command as typed.
        val request = if (explain == null) messages else messages.dropLast(1) + messages.last().copy(text = explainRequest(explain,
            diff, (chart as? ExplainChartResult.Ready)?.chart?.functions, (chart as? ExplainChartResult.Unavailable)?.reason))
        return ChatTurn(request, (chart as? ExplainChartResult.Ready)?.chart?.tools.orEmpty())
    }

    /** Puts the summaries card on the canvas, shows the canvas and starts /summaries if the tree is incomplete and the chat is idle. */
    fun openSummaries() {
        state.showSummaries()
        renderCanvas()
        showCanvas { canvas.focusSummaries() }
        if (!state.summaries.complete) summarize()
    }

    fun openTopics() {
        state.showTopics()
        renderCanvas()
        showCanvas { canvas.focusTopics() }
        if (!state.topics.complete && canSummarize) session.send("/topics")
    }

    /** Opens the canvas editor and centers a card once the canvas has its size; a newly opened editor lays it out later. */
    protected fun showCanvas(focus: () -> Unit) {
        openCanvas(true)
        SwingUtilities.invokeLater { if (!disposed) focus() }
    }

    protected fun summariesCard() = if (state.summariesVisible) SummariesCard(state.summaries, session.busy, canSummarize) else null

    protected fun topicsCard() = if (state.topicsVisible) TopicsCard(state.topics, state.summaries, canSummarize, state.topicSummaries) else null

    /** The topics card shows the file summaries too, so it follows every summary and chat update. */
    protected open fun summariesChanged() {
        canvas.updateSummaries(summariesCard())
        canvas.updateTopics(topicsCard())
    }

    /** The focus of a To Chat on the files and folders [entries] of this tab's scope; null when they reach no file. */
    fun focusOn(entries: List<String>): ChatFocus? = chatFocus(entries, state.snapshotFiles.map { it.path }, state.summaries)

    override fun dispose() {
        disposed = true
        onCanvasSelected = {}
        FileEditorManager.getInstance(project).closeFile(canvasFile)
        canvasFile.setValid(false)
        session.discard()
        scope.cancel()
        clearSnapshot()
        state.clearNotes()
        state.refreshing = true
        canvas.showNotes(CanvasState(emptyList()))
        canvas.removeAll()
        removeAll()
    }
}

/** Runs a pipeline that reports "[done] of [total] [unit]" under [text] as it goes. */
internal suspend fun <R> withCountProgress(text: String, unit: String,
                                           run: suspend (onProgress: suspend (Int, Int) -> Unit) -> R): R = reportRawProgress { reporter ->
    reporter.text(text)
    run { done, total ->
        reporter.details("$done of $total $unit")
        reporter.fraction(if (total > 0) done.toDouble() / total else null)
    }
}

/**
 * Reads PSI with [read] in a background read action, then runs [apply] on the EDT after [current] checks the tab. With
 * [changed], an edit to PSI in between fails the call with that message, as the result may no longer match the source.
 */
internal suspend fun <V, R> readPsiThen(project: Project, current: () -> Unit, changed: String?, read: () -> V, apply: (V) -> R): R {
    fun stamp() = PsiModificationTracker.getInstance(project).modificationCount
    val (value, before) = withContext(Dispatchers.Default) { readAction { read() to stamp() } }
    return withContext(Dispatchers.EDT) {
        current()
        if (changed != null && before != stamp()) throw ToolInputException(changed)
        apply(value)
    }
}

internal const val SYMBOLS_CHANGED = "Source changed during symbol discovery. Retry with current source."
internal const val NOTES_CHANGED = "Source changed while resolving notes. Retry with current source."
