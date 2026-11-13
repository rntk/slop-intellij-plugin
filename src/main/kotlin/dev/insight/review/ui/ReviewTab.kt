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
import dev.insight.agent.ChatRole
import dev.insight.agent.ChatTool
import dev.insight.agent.ToolInputException
import dev.insight.review.ChatFocus
import dev.insight.review.CanvasTabKey
import dev.insight.review.ExplainDialog
import dev.insight.review.ExplainResults
import dev.insight.review.ExplainTarget
import dev.insight.review.ReviewCanvasFile
import dev.insight.review.ReviewCanvasOwner
import dev.insight.review.ReviewPipeline
import dev.insight.review.ReviewPipelineQueue
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
import dev.insight.review.isDone
import dev.insight.review.isExplainRequest
import dev.insight.review.openReviewCanvas
import dev.insight.review.textOf
import dev.insight.review.typedExplainTarget
import dev.insight.ui.EdgeTab
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.ScrollPaneConstants
import javax.swing.Timer
import javax.swing.JComponent
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
 *
 * On the hub (see [ReviewHubScreen]) the tab shows its scope above a [tileGrid]: the source tile, which opens the Start page,
 * one tile per view of [viewOptions], and the tile that opens the chat. The tiles' stats follow the tab through
 * [scheduleTileRefresh].
 */
internal abstract class ReviewTab<S : ReviewSource, T : ReviewTabState<S>>(
    protected val project: Project,
    val state: T,
    /** Pinned to every turn of this tab's chat; see [ChatFocus]. */
    val focus: ChatFocus?,
    private val vocabulary: ReviewVocabulary,
) : JPanel(BorderLayout()), Disposable, ReviewCanvasOwner {
    init { state.showSummaries() }

    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    protected var disposed = false
        private set
    override val isDisposed: Boolean get() = disposed
    override val canvasTitle: String = vocabulary.canvasTitle
    var onCanvasSelected: () -> Unit = {}
    /** The button floating on each of this tab's view pages that leads back to the hub; see [ReviewHubScreen.button]. */
    var hubButton: ((Disposable) -> EdgeTab)? = null
    /** What the source tile does: open the panel's Start page. */
    var onOpenSource: () -> Unit = {}
    /** What the chat tile does: select this tab and show its chat in the tool window. */
    var onOpenChat: () -> Unit = {}
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

    /** The views of this tab, in the order of their tiles; the first is the one selected initially. */
    protected abstract fun viewOptions(): List<ReviewViewOption>
    /** Opens the page of [key]; summaries, topics and grouping are generated only from their page's buttons or a [schedule]. */
    protected open fun openView(key: CanvasTabKey) {
        when (key) {
            CanvasTabKey.Summaries -> openSummaries()
            CanvasTabKey.Topics -> openTopics()
            CanvasTabKey.Grouping -> openGrouping()
            else -> {}
        }
    }
    /** The stats of the source tile, read from values in memory like those of the views; see [ReviewViewOption.stats]. */
    protected open fun sourceStats(): List<ReviewTileStat> = emptyList()

    /** Built on first use, after the subclass has initialized what [viewOptions] reads. */
    private val views: List<ReviewViewOption> by lazy { viewOptions() }

    /** The source tile, a tile per view, and the chat tile; the selected one follows the view page shown in the editor. */
    val tileGrid: ReviewTileGrid by lazy {
        ReviewTileGrid(listOf(ReviewTile(SOURCE_TILE, vocabulary.sourceTitle, vocabulary.sourceHint) { onOpenSource() }) +
            views.map { option ->
                ReviewTile(viewTileId(option.key), option.label, option.tooltip) {
                    tileGrid.setSelected(viewTileId(option.key))
                    openView(option.key)
                }
            } +
            ReviewTile(CHAT_TILE, "Open chat", vocabulary.chatHint) { onOpenChat() },
        ).apply { setSelected(viewTileId(views.first().key)) }
    }

    /** Selects the tile of the view on [key]'s page; pages that are not views leave the selection as it is. */
    private fun showView(key: CanvasTabKey) {
        if (views.any { it.key == key }) tileGrid.setSelected(viewTileId(key))
    }

    /**
     * The tab's controls on the hub: [scopeLabel] above the tiles, and below them a small button that empties the canvas
     * with [clear].
     */
    protected fun tileArea(scopeLabel: JComponent, clear: () -> Unit): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        border = JBUI.Borders.empty(8, 4, 4, 4)
        add(scopeLabel, BorderLayout.NORTH)
        add(JBScrollPane(tileGrid, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
        }, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(JButton("Clear tabs").apply {
                font = JBFont.small()
                toolTipText = "Remove this tab's notes, cards and views from its canvas; summaries and topics are kept"
                addActionListener { clear() }
            })
        }, BorderLayout.SOUTH)
    }

    /** View tiles open only while the tab has files and is not refreshing; the reason shows on the disabled tiles. */
    protected fun updateViewTiles() {
        val enabled = !state.refreshing && state.snapshotFiles.isNotEmpty()
        val reason = if (state.refreshing) "Loading the files; the views open once they are loaded" else vocabulary.noFiles
        views.forEach { tileGrid.setTileEnabled(viewTileId(it.key), enabled, reason.takeUnless { enabled }) }
        scheduleTileRefresh()
    }

    /** Coalesces the tile stats updates of a burst of changes, such as a run saving summaries. */
    private val tileTimer = Timer(TILE_REFRESH_MS) { refreshTiles() }.apply { isRepeats = false }

    /** Updates the tiles' stats shortly, once for every call within [TILE_REFRESH_MS]. */
    protected fun scheduleTileRefresh() {
        if (!disposed) tileTimer.restart()
    }

    /** Sets every tile's stats now; they read values in memory only (EDT). Internal for tests. */
    internal fun refreshTiles() {
        if (disposed) return
        val grid = tileGrid
        grid.setStats(SOURCE_TILE, sourceStats())
        views.forEach { grid.setStats(viewTileId(it.key), it.stats()) }
        // Questions and answers only: the welcome, notices and tool steps are not counted.
        grid.setStats(CHAT_TILE, chatStats(session.messages.count { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }, session.busy))
    }

    private fun artifactFile(key: CanvasTabKey): ReviewCanvasFile = artifactFiles.getOrPut(key) {
        ReviewCanvasFile(object : ReviewCanvasOwner {
            override val canvas get() = this@ReviewTab.canvas.editorPage(key)
            override val isDisposed get() = disposed
            override val canvasTitle get() = this@ReviewTab.canvasTitle
            override fun floatingButton(parent: Disposable) = this@ReviewTab.hubButton?.invoke(parent)
            override fun canvasSelected() {
                this@ReviewTab.canvas.selectPage(key)
                this@ReviewTab.showView(key)
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
        canvas.onPageFocused = { showView(it); openArtifact(it) }
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
            scheduleTileRefresh()
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

    /** Puts the summaries card on the canvas and shows it; the summaries are generated from the card or a [schedule]. */
    fun openSummaries() {
        state.showSummaries()
        renderCanvas()
        showCanvas { canvas.focusSummaries() }
    }

    /**
     * Opens the summaries in the Tree view on the route to [path], a relative or absolute file path; false when the file is not in
     * this tab's scope. Like [openSummaries], it generates nothing.
     */
    fun showInSummaryTree(path: String): Boolean {
        if (disposed || state.snapshotFiles.none { it.path == path || it.absolutePath == path }) return false
        state.showSummaries()
        renderCanvas()
        showCanvas { canvas.showSummariesInTree(path) }
        return true
    }

    fun openTopics() {
        state.showTopics()
        renderCanvas()
        showCanvas { canvas.focusTopics() }
    }

    fun openGrouping() {
        state.showGrouping()
        renderCanvas()
        showCanvas { canvas.focusGrouping() }
    }

    private val pipelines = ReviewPipelineQueue()
    private var pipelineCheck = false
    /** The scheduled pipeline whose chat turn runs now, if any. */
    private var runningPipeline: ReviewPipeline? = null

    /** The pipelines still waiting to run; see [schedule]. */
    val scheduledPipelines: Set<ReviewPipeline> get() = pipelines.scheduled

    /**
     * Runs [scheduled] one after another through this tab's chat, each once the chat and canvas are idle and the source is
     * loaded. Pipelines with nothing left to generate are skipped rather than regenerated.
     */
    fun schedule(scheduled: Collection<ReviewPipeline>) {
        if (disposed) return
        pipelines.schedule(scheduled)
        runScheduled()
    }

    /** Starts the next scheduled pipeline when the tab is ready; deferred, so it never sends from within a chat notification. */
    protected fun runScheduled() {
        if (pipelineCheck || disposed || pipelines.scheduled.isEmpty()) return
        pipelineCheck = true
        SwingUtilities.invokeLater {
            pipelineCheck = false
            if (disposed) return@invokeLater
            if (!session.busy) runningPipeline = null
            pipelines.next(canSummarize) { state.isDone(it) }?.let {
                runningPipeline = it
                session.send(it.command.command)
            }
        }
    }

    /** Before a source refresh stops the chat: the scheduled pipeline it interrupts runs again, continuing on the new source. */
    protected fun requeueRunningPipeline() {
        val running = runningPipeline?.takeIf { session.busy } ?: return
        runningPipeline = null
        pipelines.schedule(listOf(running))
    }

    /** Focusing a page opens its regular IDE editor tab. */
    protected fun showCanvas(focus: () -> Unit) {
        if (!disposed) focus()
    }

    protected fun summariesCard() = if (state.summariesVisible) SummariesCard(state.summaries, session.busy, canSummarize) else null

    protected fun topicsCard() = if (state.topicsVisible) TopicsCard(state.topics, state.summaries, canSummarize, state.topicSummaries) else null

    protected fun groupingCard() = if (state.groupingVisible) TopicsCard(state.grouping, state.summaries, canSummarize, state.groupingSummaries) else null

    /** The topics and grouping cards show the file summaries too, so they follow every summary and chat update. */
    protected open fun summariesChanged() {
        canvas.updateSummaries(summariesCard())
        canvas.updateTopics(topicsCard())
        canvas.updateGrouping(groupingCard())
        runScheduled()
        scheduleTileRefresh()
    }

    /** The focus of a To Chat on the files and folders [entries] of this tab's scope; null when they reach no file. */
    fun focusOn(entries: List<String>): ChatFocus? = chatFocus(entries, state.snapshotFiles.map { it.path }, state.summaries)

    override fun dispose() {
        disposed = true
        pipelines.clear()
        onCanvasSelected = {}
        hubButton = null
        onOpenSource = {}
        onOpenChat = {}
        tileTimer.stop()
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
