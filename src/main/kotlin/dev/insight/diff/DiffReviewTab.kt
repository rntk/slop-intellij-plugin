package dev.insight.diff

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.util.progress.reportRawProgress
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.ui.JBUI
import dev.insight.callgraph.ExplainChartResult
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
    /** Pinned to every turn of this tab's chat; see [ChatFocus]. */
    val focus: ChatFocus? = null,
    /** Opens a new tab whose chat is focused on a summary entry. */
    private val toChat: (ChatFocus) -> Unit = {},
) : JPanel(BorderLayout()), Disposable, ReviewCanvasOwner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    val state = DiffReviewState(paths, ReviewDiskCache.forProject(project))
    private var disposed = false
    private val sourcePoints = SourcePoints(project, "Diff chat")
    private val fullFileSnapshots = java.util.IdentityHashMap<ReviewFile, com.intellij.openapi.vfs.VirtualFile>()
    override val isDisposed: Boolean get() = disposed
    override val canvasTitle: String get() = ReviewVocabulary.DIFF.canvasTitle
    var onCanvasSelected: () -> Unit = {}
    override fun canvasSelected() = onCanvasSelected()
    override val canvas: NoteCanvas = NoteCanvas(
        showDiff,
        { note -> state.removeNote(note.paths, note.symbol); renderCanvas() },
        project = project,
        reviewed = state.reviewed,
        sourceActions = SourceCanvasActions(
            openSymbol = { symbol ->
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
        ),
        summaryActions = SummaryCanvasActions(
            summarize = { summarize() },
            removeSummaries = { state.hideSummaries(); renderCanvas() },
            traceSummaries = ReviewTraceLauncher(project, scope),
            markReviewed = state::setReviewed,
            reviewSummaries = ReviewSummaryLinkLauncher(project, scope),
            contextSummaries = ReviewContextSummaryLauncher(project, scope) {
                val context = captureContext()
                val files = state.snapshot.files
                val client = ChatSettings.getInstance().createClient()
                val complete = tracedFileCompletion(files.map { TraceFile(it.path, patch = it.patch, before = it.before, after = it.after) }, client::complete)
                ChangeSummarizer(complete, diffReviewTools(context).single { it.definition.name == "read_diff_files" })
            },
            deleteOriginalSummaries = { state.clearSummaries(); summariesChanged() },
            // The scored lines are those of the snapshot file, so its diff opens rather than the current one.
            openLines = { node, lines -> (node.file as? ReviewFile)?.let { showDiffLines(project, it, lines) } },
            openWholeFile = { file ->
                WriteIntentReadAction.run {
                    val snapshot = fullFileSnapshots.getOrPut(file) { createFullFileSnapshot(file) }
                    FileEditorManager.getInstance(project).openFile(snapshot, true)
                }
            },
            openDirectoryDiff = { files -> showDirectoryDiff(project, files) },
            explainNode = { node -> explain(node) },
            chatNode = { node, summaries -> toChat(chatFocus(node, summaries)) },
        ),
        topicActions = TopicCanvasActions(
            fillTopicSummaries = { if (canSummarize) session.send("/topic-summaries") },
            generateTopics = { if (canSummarize) session.send("/topics") },
            removeTopics = { state.hideTopics(); renderCanvas() },
            saveTopicSummaries = { updated, ids -> state.replaceTopicSummaries(updated, ids); summariesChanged() },
        ),
        metricActions = MetricCardActions(
            removeWords = { state.hideWords(); renderCanvas() },
            removeDiffOverview = { state.hideDiffOverview(); renderCanvas() },
            removeSemantic = { state.hideSemantic(); renderCanvas() },
        ),
    )

    val canvasFile = DiffReviewCanvasFile(this)
    private val summariesButton = JButton("Summaries").apply {
        toolTipText = "Read the change as summaries, from the whole change down to each file (/summaries)"
    }
    private val topicsButton = JButton("Topics").apply {
        toolTipText = "Read every diff as one document, grouped by topic, with each file's summary beside it (/topics)"
    }
    private val wordsButton = JButton("Words").apply {
        toolTipText = "Chart the words of the removed and added lines: removed on the left, added on the right"
    }
    private val diffOverviewButton = JButton("Diff overview").apply {
        toolTipText = "Compare deleted and added line counts with compact word lists filling opposite corners"
    }
    private val semanticButton = JButton("Metrics").apply {
        toolTipText = "Count code metrics from both sides of every changed file: totals, function complexity before and after, and added or removed constructs"
    }
    private val scopeLabel = JLabel()
    private val explanations = ExplainResults()
    val session = object : ChatSession(project, scope, onClear = sourcePoints::clear, welcome = if (focus != null)
        "Ask about ${focus.label}. Its summaries and diff are in this chat's context; other project files are read when a " +
            "question needs them." else
        "Ask about ${if (paths == null) "the current changes" else "the selected files' changes"}, or type /summaries to read the change from the whole down to each file. " +
            "Use /topics to read every diff as one document grouped by topic. " +
            "Use Explain on a summary, or /explain with a path, to learn how a change works and why.") {
        override fun replyProgressTitle(text: String): String = when {
            isTopicsRequest(text) -> "Generating change topics"
            isExplainRequest(text) -> "Explaining the change"
            isSummariesRequest(text) || text == "/topic-summaries" -> "Generating change summaries"
            else -> "Insight change review chat"
        }

        override fun createBackend(): ChatBackend = object : ChatBackend {
            override suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit): String {
                val (context, snapshot, turnFocus) = withContext(Dispatchers.EDT) {
                    Triple(captureContext(), state.snapshot,
                        focus?.current(state.snapshot.files.mapTo(hashSetOf()) { it.path }, state.summaries, state.topicSummaries))
                }
                val files = snapshot.files
                val question = messages.lastOrNull()?.text.orEmpty()
                val client = ChatSettings.getInstance().createClient()
                val complete = tracedFileCompletion(files.map { TraceFile(it.path, patch = it.patch, before = it.before, after = it.after) }, client::complete)
                if (isTopicsRequest(question)) {
                    val run = reportRawProgress { reporter ->
                        reporter.text("Classifying changed files…")
                        ChangeTopicGenerator(complete, diffReviewTools(context).single { it.definition.name == "read_diff_files" })
                            .run(context.startTopics(), summariesFocus(question), context::assignTopic, onToolCall,
                                onProgress = { done, total ->
                                    reporter.details("$done of $total files classified")
                                    reporter.fraction(if (total > 0) done.toDouble() / total else null)
                                })
                    }
                    return topicsReply(run)
                }
                if (isSummariesRequest(question) || question.trim() == "/topic-summaries") {
                    val topicSummaries = question.trim() == "/topic-summaries"
                    // Code walks the tree and batches the requests; the model only writes text, without a chat history or tool loop.
                    val run = reportRawProgress { reporter ->
                        reporter.text("Summarizing the change…")
                        ChangeSummarizer(complete, diffReviewTools(context).single { it.definition.name == "read_diff_files" })
                            .run(if (topicSummaries) context.startTopicSummaries() else context.startSummaries(), summariesFocus(question),
                                if (topicSummaries) context::summarizeTopics else context::summarize, onToolCall,
                                onProgress = { done, total ->
                                    reporter.details("$done of $total summaries")
                                    reporter.fraction(if (total > 0) done.toDouble() / total else null)
                                })
                    }
                    return if (topicSummaries) summariesReply(run, missing = TOPIC_SUMMARIES_MISSING) else summariesReply(run)
                }
                val explain = if (isExplainRequest(question)) withContext(Dispatchers.EDT) { takeExplain(question, files.map { it.path }) } else null
                if (explain != null && explain.files.isEmpty()) return "There are no changed files to explain."
                val chart = explainChartFor(project, explain, messages) { paths -> files.filter { it.path in paths }.map { it.absolutePath } }
                if (explain != null) withContext(Dispatchers.EDT) { explanations.chartPrepared(chart) }
                // Only this turn carries the command's instructions; the transcript keeps the command as typed.
                val request = when {
                    explain != null -> messages.dropLast(1) + messages.last().copy(text = explainRequest(explain, diff = true,
                        (chart as? ExplainChartResult.Ready)?.chart?.functions, (chart as? ExplainChartResult.Unavailable)?.reason))
                    else -> messages
                }
                val chartTools = (chart as? ExplainChartResult.Ready)?.chart?.tools.orEmpty()
                // Reviews accumulate evidence for many rounds; evict in chunks so the request prefix stays cacheable.
                return LlmChatBackend(client::complete,
                    chatTools(context) + chartTools,
                    diffReviewPrompt() + (if (chartTools.isEmpty()) "" else "\n\n" + explainChartPrompt()) + focusPrompt(files, turnFocus),
                    maxContextChars = 160_000, maxToolRounds = 100,
                    context = if (turnFocus == null) { { context.state(0, INLINE_PATCH_CHARS) } }
                        else { { withContext(Dispatchers.EDT) { focusState(snapshot, turnFocus) } } },
                    contextLabel = if (turnFocus == null) "Current diff snapshot" else "Current focus snapshot",
                    evictionTarget = 0.7)
                    .reply(request, onToolCall)
            }
        }
    }
    /** Shown by the panel's Chat page, so it is not part of this tab's controls. */
    internal val chat = ChatPanel(project, session, title = "Diff Chat", showDashboard = false, showFileTrace = true)

    init {
        minimumSize = Dimension(300, 200)
        chat.input.emptyText.text = "/summaries, /topics, /explain or ask about this tab's changes…"
        add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(scopeLabel, BorderLayout.NORTH)
            add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                add(JButton("Open review").apply {
                    toolTipText = "Open this review's tabs in the IDE editor area"
                    addActionListener { openCanvas(true) }
                }, BorderLayout.NORTH)
                add(JPanel(GridLayout(1, 2, JBUI.scale(4), 0)).apply {
                    add(topicsButton.apply { addActionListener { openTopics() } })
                    add(summariesButton.apply { addActionListener { openSummaries() } })
                }, BorderLayout.CENTER)
                add(JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                    add(JButton("Clear tabs").apply {
                        addActionListener { state.clearNotes(); renderCanvas() }
                    }, BorderLayout.WEST)
                    add(JPanel(GridLayout(1, 3, JBUI.scale(4), 0)).apply {
                        add(wordsButton.apply { addActionListener { openWords() } })
                        add(diffOverviewButton.apply { addActionListener { openDiffOverview() } })
                        add(semanticButton.apply { addActionListener { openSemantic() } })
                    }, BorderLayout.EAST)
                }, BorderLayout.SOUTH)
            }, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        Disposer.register(this, chat)
        Disposer.register(this, canvas)
        Disposer.register(this, sourcePoints)
        Disposer.register(this, session.subscribe {
            updateControls()
            summariesChanged()
            if (explanations.update(session.messages, session.busy)) {
                renderCanvas()
                if (explanations.result != null) showCanvas { canvas.focusExplanation() }
            }
        })
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
        if (state.revision != previousRevision) { session.stop(); sourcePoints.clear(); fullFileSnapshots.clear() }
        renderCanvas()
        updateControls()
        return previousNotes - state.notes.size
    }

    fun highlight(paths: Set<String>) { canvas.highlight(paths) }

    private val canSummarize: Boolean get() = !disposed && !state.refreshing && !session.busy && !canvas.topicSummariesRunning &&
        state.snapshot.files.isNotEmpty()

    private fun summarize() { if (canSummarize) session.send("/summaries") }

    /** The target of the /explain command an Explain link sent, until its turn takes it; see [takeExplain]. */
    private var pendingExplain: Pair<String, ExplainTarget>? = null

    /** Asks what to focus on, then sends /explain for a summary entry; its files and lines need no parsing of the command. */
    private fun explain(node: SummaryNode) {
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

    /** Counted from the snapshot by code, so the chart needs no chat turn. */
    fun openWords() {
        state.showWords()
        renderCanvas()
        showCanvas { canvas.focusWords() }
    }

    fun openDiffOverview() {
        state.showDiffOverview()
        renderCanvas()
        showCanvas { canvas.focusDiffOverview() }
    }

    /** Counting parses both sides of every file, so it runs in a background read action and fills the card when done. */
    fun openSemantic() {
        state.showSemantic()
        renderCanvas()
        showCanvas { canvas.focusSemantic() }
    }

    private var semanticJob: Job? = null
    private var semanticCounting: List<ReviewFile>? = null

    private fun countSemantic() {
        val files = state.snapshot.files
        if (!state.semanticVisible || !state.semanticPending || semanticCounting === files) return
        semanticJob?.cancel()
        semanticCounting = files
        semanticJob = scope.launch {
            val result = try {
                SemanticResult.Ready(withContext(Dispatchers.Default) { smartReadAction(project) { semanticChanges(project, files) } })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                SemanticResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            if (semanticCounting === files) semanticCounting = null
            if (!disposed && state.setSemantic(files, result)) renderCanvas()
        }
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

    /** The focus of a To Chat on the files and folders [entries] of this tab's changes; null when they reach no file. */
    fun focusOn(entries: List<String>): ChatFocus? = chatFocus(entries, state.snapshot.files.map { it.path }, state.summaries)

    /**
     * The pinned focus for a turn on [files]: each file's patch; empty without a focus. A patch is not split by line, so a
     * focus on part of a file pins the whole file's diff and says so.
     */
    private fun focusPrompt(files: List<ReviewFile>, focus: ChatFocus?): String {
        focus ?: return ""
        val byPath = files.associateBy { it.path }
        val whole = focus.lines?.takeIf { focus.files.isNotEmpty() }?.let {
            "\n\nThe file's diff covers the whole file; the focus is lines ${it.first}-${it.last} of it."
        }
        return "\n\n" + chatFocusPrompt(focus, "change", "read_diff_files") { path, _ -> byPath[path]?.patch } + whole.orEmpty()
    }

    /**
     * The per-turn state of a focused chat: only the focused files, with their notes and source blocks. Their patches are
     * already pinned by [focusPrompt], so none are repeated; the rest of the diff is left to the tools.
     */
    private fun focusState(snapshot: ReviewSnapshot, focus: ChatFocus): String {
        val paths = focus.files.toSet()
        return diffStateResult(snapshot.copy(files = snapshot.files.filter { it.path in paths }), state.scopePaths,
            selectedPaths().filter { it in paths }, state.notes, 0, 0, state.sources.filter { it.range.path in paths })
    }

    private fun updateControls() {
        topicsButton.isEnabled = !state.refreshing && !session.busy && state.snapshot.files.isNotEmpty()
        summariesButton.isEnabled = !state.refreshing && state.snapshot.files.isNotEmpty()
        wordsButton.isEnabled = summariesButton.isEnabled
        diffOverviewButton.isEnabled = wordsButton.isEnabled
        semanticButton.isEnabled = wordsButton.isEnabled
        val paths = state.scopePaths
        scopeLabel.text = if (paths == null) "Scope: All changes · ${state.snapshot.files.size} files"
            else "Scope: ${paths.size} files · ${state.snapshot.files.size} currently changed"
        scopeLabel.text += " · ${state.snapshot.comparison.summary}"
        focus?.let { scopeLabel.text += " · Focus: ${it.label}" }
        scopeLabel.toolTipText = state.snapshot.comparison.summary + "\n" + state.snapshot.comparison.request.repository.orEmpty() + "\n" +
            (paths?.sorted()?.joinToString("\n") ?: "Follows all files in the selected diff.")
    }

    private fun renderCanvas() {
        canvas.showNotes(CanvasState(state.notes, state.sources, state.diffs, summariesCard(), topicsCard(),
            words = if (state.wordsVisible) state.words else null, wordFiles = state.snapshot.files,
            diffOverview = if (state.diffOverviewVisible) state.words else null,
            semantic = if (state.semanticVisible) state.semantic else null, explanation = explanations.result))
        countSemantic()
        canvas.highlight(selectedPaths().toSet())
    }

    /** Snapshot tools stay scoped; current project files remain readable for supporting context. */
    internal fun chatTools(context: DiffReviewContext): List<ChatTool> =
        diffChatTools(context) + projectContextTools(project)

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
        fun file(path: String) = snapshot.files.firstOrNull { it.path == path } ?: throw ToolInputException(
            "$path has no diff in this snapshot. Use read_file or point_to_code for current project context.")
        return object : DiffReviewContext {
            override suspend fun pointToCode(point: SourcePoint): String = withContext(Dispatchers.EDT) {
                current()
                val sourceFile = snapshot.files.firstOrNull { it.path == point.path }
                    ?: return@withContext showProjectSource(project, sourcePoints, point)
                val source = when (point.side) {
                    "before" -> sourceFile.before
                    "after" -> sourceFile.after
                    else -> throw ToolInputException("side must be before or after")
                } ?: throw ToolInputException("The ${point.side} source is unavailable for ${point.path}.")
                sourcePoints.show(point, source)
            }

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
                    "Added ${added.size} note${if (added.size == 1) "" else "s"}." +
                        changeFlow(live.notes).summary().let { if (it.isEmpty()) "" else " $it" }
                }
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
        fullFileSnapshots.clear()
        onCanvasSelected = {}
        FileEditorManager.getInstance(project).closeFile(canvasFile)
        canvasFile.setValid(false)
        session.discard()
        scope.cancel()
        state.update(ReviewSnapshot(emptyList(), emptyList()))
        state.clearNotes()
        state.refreshing = true
        canvas.showNotes(CanvasState(emptyList()))
        canvas.removeAll()
        removeAll()
    }

    private companion object {
        // Small diffs arrive with the question, so an answer often needs no read calls at all.
        const val INLINE_PATCH_CHARS = 24_000
    }
}
