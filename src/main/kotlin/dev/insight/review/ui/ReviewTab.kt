package dev.insight.review.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiModificationTracker
import dev.insight.callgraph.ExplainChartResult
import dev.insight.agent.ChatConversation
import dev.insight.agent.ChatMessage
import dev.insight.agent.ChatTool
import dev.insight.agent.ToolInputException
import dev.insight.review.ChatFocus
import dev.insight.review.CanvasTabKey
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
import javax.swing.JComponent
import javax.swing.JPanel
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
) : JPanel(BorderLayout()), Disposable, ReviewCanvasOwner {
    init { state.showSummaries() }

    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    protected var disposed = false
        private set
    override val isDisposed: Boolean get() = disposed
    override val canvasTitle: String = vocabulary.canvasTitle
    var onCanvasSelected: () -> Unit = {}
    override fun canvasSelected() = onCanvasSelected()
    abstract override val canvas: NoteCanvas
    abstract val session: ChatConversation
    /** Shown by the panel's Chat page, so it is not part of the tab's controls. */
    abstract val chat: JComponent
    /** Focused when the panel's Chat page is selected. */
    abstract val chatInput: JComponent
    private var reviewName = ""
    private val artifactFiles = mutableMapOf<CanvasTabKey, ReviewCanvasFile>()
    private var knownPages = emptySet<CanvasTabKey>()
    val canvasFile = artifactFile(CanvasTabKey.Summaries)
    protected val explanations = ExplainResults()

    /** The views of this tab, in the order of its [viewSelector]; the first is the one shown initially. */
    protected abstract fun viewOptions(): List<ReviewViewOption>
    /** Opens the page of [key], generating its content where it has none yet. */
    protected open fun openView(key: CanvasTabKey) {
        when (key) {
            CanvasTabKey.Summaries -> openSummaries()
            CanvasTabKey.Topics -> openTopics()
            else -> {}
        }
    }
    val viewSelector: ReviewViewSelector by lazy { ReviewViewSelector(viewOptions(), ::openView) }

    private fun artifactFile(key: CanvasTabKey): ReviewCanvasFile = artifactFiles.getOrPut(key) {
        ReviewCanvasFile(object : ReviewCanvasOwner {
            override val canvas get() = this@ReviewTab.canvas.editorPage(key)
            override val isDisposed get() = disposed
            override val canvasTitle get() = this@ReviewTab.canvasTitle
            override fun canvasSelected() {
                this@ReviewTab.canvas.selectPage(key)
                this@ReviewTab.viewSelector.show(key)
                this@ReviewTab.canvasSelected()
            }
        })
    }

    fun rename(name: String) {
        reviewName = name
        updateArtifactFiles()
    }

    private fun updateArtifactFiles() {
        val manager = FileEditorManager.getInstance(project)
        artifactFiles.entries.removeIf { (key, file) ->
            if (key in canvas.pageKeys) {
                val name = listOf(reviewName, canvas.pageTitle(key)).filter { it.isNotEmpty() }.joinToString(" · ")
                if (file.name != "$canvasTitle: $name") {
                    file.renameReview(name)
                    manager.updateFilePresentation(file)
                }
                false
            } else {
                manager.closeFile(file)
                if (file !== canvasFile) file.setValid(false)
                file !== canvasFile
            }
        }
    }

    private fun openArtifact(key: CanvasTabKey, focus: Boolean = true) {
        if (disposed || key !in canvas.pageKeys) return
        val file = artifactFile(key)
        updateArtifactFiles()
        openReviewCanvas(project, file, focus)
    }

    protected abstract fun renderCanvas()
    /** Empties the state's snapshot when the tab is disposed. */
    protected abstract fun clearSnapshot()

    /** Follows the chat: [before] runs first on every change, then the controls, cards and explanation are updated. */
    protected fun followSession(before: () -> Unit = {}, updateControls: () -> Unit): Disposable {
        canvas.onPageFocused = { viewSelector.show(it); openArtifact(it) }
        canvas.onPagesChanged = {
            val added = canvas.pageKeys - knownPages - CanvasTabKey.Summaries
            knownPages = canvas.pageKeys.toSet()
            updateArtifactFiles()
            added.forEach { openArtifact(it, focus = false) }
        }
        return session.subscribe {
            before()
            updateControls()
            summariesChanged()
            if (explanations.update(session.messages, session.busy)) {
                renderCanvas()
                if (explanations.result != null) showCanvas { canvas.focusExplanation() }
            }
        }
    }

    fun openCanvas(focus: Boolean = false) {
        if (disposed) return
        openArtifact(canvas.selectedKey ?: CanvasTabKey.Summaries, focus)
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

    /** Focusing a page opens its regular IDE editor tab. */
    protected fun showCanvas(focus: () -> Unit) {
        if (!disposed) focus()
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
        canvas.onPageFocused = {}
        canvas.onPagesChanged = {}
        artifactFiles.values.forEach {
            FileEditorManager.getInstance(project).closeFile(it)
            it.setValid(false)
        }
        artifactFiles.clear()
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
