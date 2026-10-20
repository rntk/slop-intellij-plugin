package dev.insight.diff

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportRawProgress
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.ui.JBUI
import dev.insight.chat.*
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Owns one conversation and canvas. File selection never changes this tab's scope. */
internal class DiffReviewTab(
    private val project: Project,
    paths: Set<String>?,
    private val selectedPaths: () -> List<String>,
    showDiff: (String) -> Unit,
) : JPanel(BorderLayout()), Disposable, ReviewCanvasOwner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    val state = DiffReviewState(paths, dev.insight.diff.ReviewDiskCache.forProject(project))
    private var disposed = false
    override val isDisposed: Boolean get() = disposed
    override val canvasTitle: String get() = ReviewVocabulary.DIFF.canvasTitle
    var onCanvasSelected: () -> Unit = {}
    override fun canvasSelected() = onCanvasSelected()
    override val canvas: NoteCanvas = NoteCanvas(
        showDiff,
        { note -> state.removeNote(note.paths, note.symbol); renderCanvas() },
        { symbol ->
            LocalFileSystem.getInstance().findFileByPath(symbol.absolutePath)?.let { file ->
                OpenFileDescriptor(project, file, symbol.offset).navigate(true)
            }
        },
        removeSource = { range -> state.removeSource(range); renderCanvas() },
        showDiffCard = { path ->
            if (state.showDiff(path) != null) {
                renderCanvas()
                canvas.focusDiff(path)
            }
        },
        removeDiff = { path -> state.removeDiff(path); renderCanvas() },
        project = project,
        removeFlows = { state.removeFlows(); renderCanvas() },
        summarize = { summarize() },
        fillTopicSummaries = { if (canSummarize) session.send("/topic-summaries") },
        generateTopics = { if (canSummarize) session.send("/topics") },
        removeTopics = { state.hideTopics(); renderCanvas() },
        removeSummaries = { state.hideSummaries(); renderCanvas() },
        traceSummaries = ReviewTraceLauncher(project, scope),
    )

    val canvasFile = DiffReviewCanvasFile(this)
    private val describe = JButton("Description")
    private val flowsButton = JButton("Flows").apply {
        toolTipText = "Map the change: functions and calls from source, grouped and labelled by the assistant (/flows)"
    }
    private val summariesButton = JButton("Summaries").apply {
        toolTipText = "Read the change as summaries, from the whole change down to each file (/summaries)"
    }
    private val topicsButton = JButton("Topics").apply {
        toolTipText = "Read every diff as one document, grouped by topic, with each file's summary beside it (/topics)"
    }
    private val scopeLabel = JLabel()
    val session = object : ChatSession(project, scope, welcome =
        "Ask about ${if (paths == null) "the current changes" else "the selected files' changes"}, type /description for brief notes on each file, " +
            "/flows to map the change from an overview down to the code, or /summaries to read it from the whole change down to each file. Use /topics to read every diff as one document grouped by topic.") {
        override fun createBackend(): ChatBackend = object : ChatBackend {
            override suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit): String {
                val (context, total) = withContext(Dispatchers.EDT) { captureContext() to state.snapshot.files.size }
                val question = messages.lastOrNull()?.text.orEmpty()
                val client = ChatSettings.getInstance().createClient()
                if (isTopicsRequest(question)) {
                    val run = withBackgroundProgress(project, "Generating change topics") {
                        reportRawProgress { reporter ->
                            reporter.text("Classifying changed files…")
                            ChangeTopicGenerator(client::complete, diffReviewTools(context).single { it.definition.name == "read_diff_files" })
                                .run(context.startTopics(), summariesFocus(question), context::assignTopic, onToolCall,
                                    onProgress = { done, total ->
                                        reporter.details("$done of $total files classified")
                                        reporter.fraction(if (total > 0) done.toDouble() / total else null)
                                    })
                        }
                    }
                    return topicsReply(run)
                }
                if (isSummariesRequest(question) || question.trim() == "/topic-summaries") {
                    val topicSummaries = question.trim() == "/topic-summaries"
                    // Code walks the tree and batches the requests; the model only writes text, without a chat history or tool loop.
                    val run = withBackgroundProgress(project, "Generating change summaries") {
                        reportRawProgress { reporter ->
                            reporter.text("Summarizing the change…")
                            ChangeSummarizer(client::complete, diffReviewTools(context).single { it.definition.name == "read_diff_files" })
                                .run(if (topicSummaries) context.startTopicSummaries() else context.startSummaries(), summariesFocus(question),
                                    if (topicSummaries) context::summarizeTopics else context::summarize, onToolCall,
                                    onProgress = { done, total ->
                                        reporter.details("$done of $total summaries")
                                        reporter.fraction(if (total > 0) done.toDouble() / total else null)
                                    })
                        }
                    }
                    return if (topicSummaries) summariesReply(run).replace("They show their names in the Summaries card.",
                        "Topics without summaries show file names in Hierarchy. Click Fill summaries to continue.") else summariesReply(run)
                }
                val description = isDescriptionRequest(question)
                val flows = isFlowsRequest(question)
                // The graph is built and drawn before the model runs, so the structure never depends on a reply.
                val graph = if (flows) context.buildFlows() else null
                // Only this turn carries the command's instructions; the transcript keeps the command as typed.
                val request = when {
                    description -> messages.dropLast(1) + messages.last().copy(text = descriptionRequest(question))
                    graph != null -> messages.dropLast(1) + messages.last().copy(text = flowsRequest(question, graph))
                    else -> messages
                }
                // Code, not the model, checks that a description covers every file in scope and that flows interpret every change.
                val coverage: (suspend () -> String?)? = when {
                    description -> suspend { context.uncovered().takeIf { it.isNotEmpty() }?.let { coverageNote(it, total) } }
                    flows -> suspend { context.flowsUncovered()?.takeIf { !it.complete }?.let(::flowsCoverageNote) }
                    else -> null
                }
                // Reviews accumulate evidence for many rounds; evict in chunks so the request prefix stays cacheable.
                val reply = LlmChatBackend(client::complete, diffReviewTools(context) + changeFlowTools(context) + changeSummaryTools(context), diffReviewPrompt(project.name),
                    maxContextChars = 160_000, maxToolRounds = 100, context = { context.state(0, INLINE_PATCH_CHARS) },
                    contextLabel = "Current diff snapshot", evictionTarget = 0.7, followUp = coverage)
                    .reply(request, onToolCall)
                if (flows) {
                    val missing = context.flowsUncovered() ?: return reply
                    return if (missing.complete) reply else "$reply\n\n[Not interpreted: ${missing.nodes.size} functions, " +
                        "${missing.clusters.size} clusters without a theme. They are shown with structural titles.]"
                }
                val missing = if (description) context.uncovered() else emptyList()
                return if (missing.isEmpty()) reply
                    else "$reply\n\n[No note for ${missing.size} of $total files: ${missing.take(10).joinToString()}${if (missing.size > 10) ", …" else ""}]"
            }
        }
    }
    private val chat = ChatPanel(project, session, title = "Diff Chat", showDashboard = false)

    init {
        minimumSize = Dimension(300, 200)
        chat.input.emptyText.text = "/description, /flows, /summaries, /topics or ask about this tab's changes…"
        add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(scopeLabel, BorderLayout.NORTH)
            add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                add(JButton("Open review").apply {
                    toolTipText = "Open this review's tabs in the IDE editor area"
                    addActionListener { openCanvas(true) }
                }, BorderLayout.NORTH)
                add(JPanel(GridLayout(2, 2, JBUI.scale(4), JBUI.scale(4))).apply {
                    add(describe.apply { addActionListener { session.send("/description") } })
                    add(flowsButton.apply { addActionListener { session.send("/flows") } })
                    add(topicsButton.apply { addActionListener { openTopics() } })
                    add(summariesButton.apply { addActionListener { openSummaries() } })
                }, BorderLayout.CENTER)
                add(JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                    add(JButton("Clear tabs").apply {
                        addActionListener { state.clearNotes(); renderCanvas() }
                    }, BorderLayout.WEST)
                }, BorderLayout.SOUTH)
            }, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(chat, BorderLayout.CENTER)
        Disposer.register(this, chat)
        Disposer.register(this, canvas)
        Disposer.register(this, session.subscribe { updateControls(); summariesChanged() })
        updateControls()
    }

    fun openCanvas(focus: Boolean = false) {
        if (disposed) return
        openReviewCanvas(project, canvasFile, focus)
    }

    fun invalidate(stop: Boolean, affectedPaths: Set<String>? = null) {
        if (affectedPaths != null && (affectedPaths.isEmpty() ||
            (state.scopePaths != null && state.scopePaths.none { it in affectedPaths } &&
                state.snapshot.files.none { it.absolutePath in affectedPaths }))) return
        state.refreshing = true
        if (stop) session.stop()
        updateControls()
        summariesChanged()
    }

    /** Updates even hidden tabs, retaining only notes whose scoped evidence is unchanged. */
    fun update(snapshot: ReviewSnapshot): Int {
        val previousRevision = state.revision
        val previousNotes = state.notes.size
        state.update(snapshot)
        if (state.revision != previousRevision) session.stop()
        renderCanvas()
        updateControls()
        return previousNotes - state.notes.size
    }

    fun highlight(paths: Set<String>) { canvas.highlight(paths) }

    private val canSummarize: Boolean get() = !disposed && !state.refreshing && !session.busy && state.snapshot.files.isNotEmpty()

    private fun summarize() { if (canSummarize) session.send("/summaries") }

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
    private fun showCanvas(focus: () -> Unit) {
        openCanvas(true)
        SwingUtilities.invokeLater { if (!disposed) focus() }
    }

    private fun summariesCard() = if (state.summariesVisible) SummariesCard(state.summaries, session.busy, canSummarize) else null

    private fun topicsCard() = if (state.topicsVisible) TopicsCard(state.topics, state.summaries, canSummarize, state.topicSummaries) else null

    /** The topics document shows the file summaries too, so it follows every summary and chat update. */
    private fun summariesChanged() { canvas.updateSummaries(summariesCard()); canvas.updateTopics(topicsCard()) }

    private fun updateControls() {
        describe.isEnabled = !state.refreshing && !session.busy && state.snapshot.files.isNotEmpty()
        flowsButton.isEnabled = describe.isEnabled
        topicsButton.isEnabled = describe.isEnabled
        summariesButton.isEnabled = !state.refreshing && state.snapshot.files.isNotEmpty()
        val paths = state.scopePaths
        scopeLabel.text = if (paths == null) "Scope: All changes · ${state.snapshot.files.size} files"
            else "Scope: ${paths.size} files · ${state.snapshot.files.size} currently changed"
        scopeLabel.text += " · ${state.snapshot.comparison.summary}"
        scopeLabel.toolTipText = state.snapshot.comparison.summary + "\n" + state.snapshot.comparison.request.repository.orEmpty() + "\n" +
            (paths?.sorted()?.joinToString("\n") ?: "Follows all files in the selected diff.")
    }

    private fun renderCanvas() { canvas.showNotes(state.notes, state.sources, state.diffs, state.flows, summariesCard(), topicsCard()); canvas.highlight(selectedPaths().toSet()) }

    /** Captured once per turn; subsequent calls cannot silently mix evidence from two diffs. */
    internal fun captureContext(): DiffReviewContext {
        if (disposed) throw ToolInputException("This review tab is closed")
        state.check(state.revision)
        val capturedRevision = state.revision
        val snapshot = state.snapshot
        val selected = selectedPaths().filter { path -> snapshot.files.any { it.path == path } }
        val live = this@DiffReviewTab.state
        fun current() {
            if (disposed) throw ToolInputException("This review tab is closed")
            live.check(capturedRevision)
        }
        fun file(path: String) = snapshot.files.firstOrNull { it.path == path } ?: throw ToolInputException("$path is not a file included in this diff")
        return object : DiffReviewContext {
            override suspend fun state(offset: Int, patchBudget: Int): String = withContext(Dispatchers.EDT) {
                current()
                diffStateResult(snapshot, live.scopePaths, selected, live.notes, offset, patchBudget, live.sources)
            }

            override suspend fun read(paths: List<String>, offset: Int): String = withContext(Dispatchers.EDT) {
                current()
                val files = paths.distinct().map(::file)
                if (offset > 0 && files.size != 1) throw ToolInputException("offset requires exactly one path")
                if (offset > 0 && files.single().patch.length < offset) throw ToolInputException("Offset is outside this patch")
                diffReadResult(files, offset)
            }

            override suspend fun symbols(paths: List<String>, query: String, offset: Int): String {
                withContext(Dispatchers.EDT) { current(); paths.forEach(::file) }
                val (result, stamp) = withContext(Dispatchers.Default) { readAction {
                    DiffSymbols(project, snapshot).find(paths, query, offset) to PsiModificationTracker.getInstance(project).modificationCount
                } }
                withContext(Dispatchers.EDT) {
                    current()
                    if (stamp != PsiModificationTracker.getInstance(project).modificationCount)
                        throw ToolInputException("Source changed during symbol discovery. Retry with current source.")
                }
                return result
            }

            override suspend fun showSource(range: DiffSourceRange): String = withContext(Dispatchers.EDT) {
                current()
                val block = live.showSource(capturedRevision, range)
                renderCanvas()
                reviewJson.toJson(mapOf("shown" to block.range, "characters" to block.text.length))
            }

            override suspend fun addNotes(notes: List<DiffNote>): String {
                withContext(Dispatchers.EDT) { current(); notes.flatMap { it.paths }.forEach(::file) }
                val (resolved, stamp) = withContext(Dispatchers.Default) { readAction {
                    DiffSymbols(project, snapshot).resolve(notes) to PsiModificationTracker.getInstance(project).modificationCount
                } }
                return withContext(Dispatchers.EDT) {
                    current()
                    if (notes.any { it.symbol != null } && stamp != PsiModificationTracker.getInstance(project).modificationCount)
                        throw ToolInputException("Source changed while resolving notes. Retry with current source.")
                    val added = live.addNotes(capturedRevision, resolved)
                    renderCanvas()
                    val missing = live.uncovered()
                    "Added ${added.size} note${if (added.size == 1) "" else "s"}. " + (if (missing.isEmpty()) "Every file now has a note."
                        else "${missing.size} files still have no note: ${missing.take(20).joinToString()}${if (missing.size > 20) ", …" else ""}.") +
                        changeFlow(live.notes).summary().let { if (it.isEmpty()) "" else " $it" }
                }
            }

            override suspend fun uncovered(): List<String> = withContext(Dispatchers.EDT) {
                if (disposed || live.refreshing || live.revision != capturedRevision) emptyList() else live.uncovered()
            }

            override suspend fun buildFlows(): String {
                withContext(Dispatchers.EDT) { current() }
                // References and resolution need indexes; the stamp proves the graph read one consistent state of the source.
                val (graph, stamp) = withContext(Dispatchers.Default) { smartReadAction(project) {
                    ChangeFlowBuilder(project, snapshot).build() to PsiModificationTracker.getInstance(project).modificationCount
                } }
                return withContext(Dispatchers.EDT) {
                    current()
                    if (stamp != PsiModificationTracker.getInstance(project).modificationCount)
                        throw ToolInputException("Source changed during flow analysis. Run /flows again.")
                    val flows = live.setFlows(capturedRevision, graph)
                    renderCanvas()
                    changeFlowsJson(flows, 0)
                }
            }

            private fun currentFlows(): ChangeFlows {
                current()
                return live.flows ?: throw ToolInputException("There is no change-flow graph. Ask the user to run /flows.")
            }

            override suspend fun flows(offset: Int): String = withContext(Dispatchers.EDT) { changeFlowsJson(currentFlows(), offset) }

            override suspend fun readFlowNodes(ids: List<Int>): String = withContext(Dispatchers.EDT) { changeNodesJson(currentFlows(), ids) }

            override suspend fun annotateFlows(update: FlowAnnotationUpdate): String = withContext(Dispatchers.EDT) {
                current()
                val flows = live.annotateFlows(capturedRevision, update)
                renderCanvas()
                val missing = flows.uncovered()
                "Annotated ${update.nodes.size} nodes" + (if (update.clusters.isEmpty()) "" else ", ${update.clusters.size} clusters") +
                    (update.themes?.let { ", ${it.size} themes" } ?: "") + ". " +
                    if (missing.complete) "Every change is interpreted." else listOfNotNull(
                        missing.nodes.takeIf { it.isNotEmpty() }?.let { "Still missing nodes: ${it.take(30).joinToString()}${if (it.size > 30) ", …" else ""}" },
                        missing.clusters.takeIf { it.isNotEmpty() }?.let { "Clusters without a theme: ${it.joinToString()}" },
                    ).joinToString(". ") + "."
            }

            override suspend fun flowsUncovered(): FlowCoverage? = withContext(Dispatchers.EDT) {
                if (disposed || live.refreshing || live.revision != capturedRevision) null else live.flows?.uncovered()
            }

            override suspend fun startTopicSummaries(): ChangeSummaries = withContext(Dispatchers.EDT) {
                current()
                live.startTopicSummaries(capturedRevision).also { summariesChanged() }
            }

            override suspend fun summarizeTopics(entries: Map<Int, String>): ChangeSummaries = withContext(Dispatchers.EDT) {
                current()
                live.summarizeTopics(capturedRevision, entries).also { summariesChanged() }
            }

            override suspend fun startTopics(): ChangeTopics = withContext(Dispatchers.EDT) {
                current()
                live.startTopics().also { canvas.updateTopics(topicsCard()) }
            }

            override suspend fun assignTopic(path: String, label: String): ChangeTopics = withContext(Dispatchers.EDT) {
                current()
                live.assignTopic(capturedRevision, path, label).also { canvas.updateTopics(topicsCard()) }
            }

            override suspend fun startSummaries(): ChangeSummaries = withContext(Dispatchers.EDT) {
                current()
                // An incomplete tree is continued, so an edit only re-summarizes the changed files and the folders above them.
                if (live.summaries.complete) live.clearSummaries()
                live.showSummaries()
                renderCanvas()
                live.summaries
            }

            override suspend fun summaries(offset: Int): String = withContext(Dispatchers.EDT) {
                current()
                changeSummariesJson(live.summaries, offset)
            }

            override suspend fun summarize(entries: Map<Int, String>): ChangeSummaries = withContext(Dispatchers.EDT) {
                current()
                live.summarize(capturedRevision, entries).also { summariesChanged() }
            }
        }
    }

    override fun dispose() {
        disposed = true
        onCanvasSelected = {}
        FileEditorManager.getInstance(project).closeFile(canvasFile)
        canvasFile.setValid(false)
        session.discard()
        scope.cancel()
        state.update(ReviewSnapshot(emptyList(), emptyList()))
        state.clearNotes()
        state.refreshing = true
        canvas.showNotes(emptyList())
        canvas.removeAll()
        removeAll()
    }

    private companion object {
        // Small diffs arrive with the question, so a description often needs no read calls at all.
        const val INLINE_PATCH_CHARS = 24_000
    }
}
