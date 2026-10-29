package dev.insight.codereview

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.util.ui.JBUI
import dev.insight.agent.ChatBackend
import dev.insight.agent.ChatMessage
import dev.insight.chat.ChatPanel
import dev.insight.chat.ChatSession
import dev.insight.settings.ChatSettings
import dev.insight.agent.ChatTool
import dev.insight.chat.LlmChatBackend
import dev.insight.chat.SourcePoints
import dev.insight.agent.ToolCallEvent
import dev.insight.agent.ToolInputException
import dev.insight.agent.TraceFile
import dev.insight.agent.projectContextTools
import dev.insight.agent.tracedFileCompletion
import dev.insight.core.withCountProgress
import dev.insight.llm.CompletionFn
import dev.insight.review.ChatFocus
import dev.insight.review.ReviewCommand
import dev.insight.review.reviewProgressTitle
import dev.insight.review.ReviewDiskCache
import dev.insight.review.ReviewNote
import dev.insight.review.CapturedReviewContext
import dev.insight.review.ReviewContextChange
import dev.insight.review.ReviewSummarizer
import dev.insight.review.ReviewSymbols
import dev.insight.review.ReviewTopicGenerator
import dev.insight.review.ReviewVocabulary
import dev.insight.review.SummaryNode
import dev.insight.review.SymbolFile
import dev.insight.review.TOPIC_SUMMARIES_MISSING
import dev.insight.review.chatFocus
import dev.insight.agent.findSourceFile
import dev.insight.review.current
import dev.insight.review.seedTopicSummaries
import dev.insight.review.summariesFocus
import dev.insight.review.summariesReply
import dev.insight.review.topicsReply
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Timer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.insight.review.ui.CanvasState
import dev.insight.review.ui.FileStructure
import dev.insight.review.ui.FileViewCard
import dev.insight.review.ui.NOTES_CHANGED
import dev.insight.review.ui.NoteCanvas
import dev.insight.review.ui.ReviewContextSummaryLauncher
import dev.insight.review.ui.ReviewTab
import dev.insight.review.ui.SYMBOLS_CHANGED
import dev.insight.review.ui.SourceCanvasActions
import dev.insight.review.ui.SummaryCanvasActions
import dev.insight.review.ui.TopicCanvasActions
import dev.insight.review.ui.openHighlightedLines
import dev.insight.review.ui.readPsiThen

private val LOG = logger<CodeReviewTab>()

/**
 * Owns one conversation and canvas about the code in scope. File selection never changes this tab's scope. While a
 * /summaries, /topic-summaries or /topics run is in progress, snapshot updates are held and applied when the chat is idle
 * again, so an edit elsewhere does not stop a long run; ordinary chat turns stop on a new revision like Diff Review.
 * [complete] replaces the configured model (tests).
 */
internal class CodeReviewTab(
    project: Project,
    paths: Set<String>?,
    private val selectedPaths: () -> List<String>,
    private val openFile: (path: String, line: Int?) -> Unit,
    private val complete: CompletionFn? = null,
    focus: ChatFocus? = null,
    /** Opens a new tab whose chat is focused on a summary entry. */
    private val toChat: (ChatFocus) -> Unit = {},
) : ReviewTab<CodeFile, CodeReviewState>(project, CodeReviewState(paths, ReviewDiskCache.forProject(project)), focus,
    ReviewVocabulary.CODE) {
    private val sourcePoints = SourcePoints(project, "Code chat")

    /** Pipeline runs in progress (EDT). Updates are held from the start of a run until the chat is idle again. */
    private var pipelineRuns = 0
    private var holding = false
    /** The latest snapshot that arrived while [holding]; applied once the run is over. */
    internal var pendingSnapshot: CodeSnapshot? = null
        private set
    /**
     * Asks before a run of more than [CONFIRM_REQUESTS] requests; false cancels it. Called on the EDT with the estimated
     * request count and what the run writes. Unit tests skip the dialog.
     */
    internal var confirmLargeRun: (Int, String) -> Boolean = { requests, what ->
        ApplicationManager.getApplication().isUnitTestMode || Messages.showYesNoDialog(project,
            "Writing $what for ${state.snapshot.scope.displayName} needs about $requests model requests. Continue?",
            "Large Code Review Run", "Continue", "Cancel", Messages.getQuestionIcon()) == Messages.YES
    }

    override val canvas: NoteCanvas = NoteCanvas(
        open = { path -> openFile(path, null) },
        remove = { note -> state.removeNote(note.paths, note.symbol); renderCanvas() },
        project = project,
        reviewed = state.reviewed,
        vocabulary = ReviewVocabulary.CODE,
        sourceActions = SourceCanvasActions(
            openSymbol = { symbol ->
                findSourceFile(project, symbol.path, symbol.absolutePath)?.let { OpenFileDescriptor(project, it, symbol.offset).navigate(true) }
            },
            removeSource = { range -> state.removeSource(range); renderCanvas() },
            showDiffCard = { path -> showSourceBlock(path, null) },
            openFileAt = { path, line -> openFile(path, line) },
            showFileLines = { path, lines -> showSourceBlock(path, lines) },
            removeFileView = { closeFileView() },
        ),
        summaryActions = SummaryCanvasActions(
            summarize = { summarize() },
            removeSummaries = { state.hideSummaries(); renderCanvas() },
            markReviewed = state::setReviewed,
            contextSummaries = ReviewContextSummaryLauncher(project, scope) {
                val snapshot = state.snapshot
                val complete = tracedFileCompletion(snapshot.files.map { TraceFile(it.path, source = it.text) },
                    this.complete ?: ChatSettings.getInstance().createClient()::complete)
                ReviewSummarizer(complete, null, CODE_SUMMARY_OPTIONS, CodeSummaryEvidence(snapshot))
            },
            deleteOriginalSummaries = { state.clearSummaries(); summariesChanged() },
            openNode = { node -> openFile(node.path, node.lines?.first) },
            showNode = { node: SummaryNode -> showSourceBlock(node.path, node.lines) },
            openLines = { node, lines ->
                findSourceFile(project, node.path, node.file?.absolutePath)?.let { openHighlightedLines(project, it, lines.after) }
            },
            explainNode = { node -> explain(node) },
            chatNode = { node, summaries -> toChat(chatFocus(node, summaries)) },
        ),
        topicActions = TopicCanvasActions(
            generateTopics = { if (canSummarize) session.send("/topics") },
            fillTopicSummaries = { if (canSummarize) session.send("/topic-summaries") },
            removeTopics = { state.hideTopics(); renderCanvas() },
            saveTopicSummaries = { updated, ids -> state.replaceTopicSummaries(updated, ids); summariesChanged() },
        ),
    )

    /** The file shown in File view, its structure once analyzed or why it could not be, and the analysis in progress. */
    private var fileViewPath: String? = null
    private var fileStructure: FileStructure? = null
    private var fileViewError: String? = null
    private var fileViewJob: Job? = null
    private var fileViewTarget: CodeFile? = null
    private var fileViewRevision = -1

    private val summariesButton = JButton("Summaries").apply {
        toolTipText = "Read every source file as one document, with file paths and summaries (/summaries)"
    }
    private val topicsButton = JButton("Topics").apply {
        toolTipText = "Read every source file as one document, grouped by topic, with each file's summary (/topics)"
    }
    private val scopeLabel = JLabel()
    /** Coalesces canvas updates while a run saves summaries or topics. */
    private val canvasTimer = Timer(CANVAS_REFRESH_MS) { if (!disposed) summariesChanged() }.apply { isRepeats = false }

    override val session = object : ChatSession(project, scope, backendFactory = { _ -> createBackend() },
        onClear = sourcePoints::clear, welcome = if (focus != null)
        "Ask about ${focus.label}. Its summaries and source are in this chat's context; other project files are read when a " +
            "question needs them." else
        "Ask about ${if (paths == null) "the code in scope" else "the selected files and folders"}. Type /summaries to read it " +
            "from the whole scope down to each file, or /topics to group the files by responsibility. " +
            "Use Explain on a summary, or /explain with a path, to learn how the code works and why.") {
        override fun replyProgressTitle(text: String): String = reviewProgressTitle(text, "code")
    }

    private fun createBackend(): ChatBackend = object : ChatBackend {
        override suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit): String {
            val (context, scopeName, turnFocus) = withContext(Dispatchers.EDT) {
                Triple(captureContext(), codePromptScope(state.snapshot.scope, state.scopePaths),
                    focus?.current(state.snapshot.byPath.keys, state.summaries, state.topicSummaries))
            }
            val question = messages.lastOrNull()?.text.orEmpty()
            val rawComplete = this@CodeReviewTab.complete ?: ChatSettings.getInstance().createClient()::complete
            val complete = tracedFileCompletion(context.snapshot.files.map { TraceFile(it.path, source = it.text) },
                rawComplete)
            val command = ReviewCommand.parse(question)
            if (command == ReviewCommand.TOPICS) {
                val requests = withContext(Dispatchers.EDT) { estimateTopicRequests(state.topics) }
                if (!confirm(requests, "topics")) return cancelledReply(requests)
                val run = pipeline {
                    withCountProgress("Classifying files in scope…", "files classified") { onProgress ->
                        ReviewTopicGenerator(complete, codeReviewTools(context).single { it.definition.name == READ_CODE_TOOL },
                            system = CODE_TOPIC_SYSTEM, evidence = codeTopicEvidence(context.snapshot) { state.summaries },
                            concurrency = CODE_TOPIC_CONCURRENCY)
                            .run(context.startTopics(), summariesFocus(question), context::assignTopic, onToolCall, onProgress)
                    }
                }
                return topicsReply(run, empty = "There are no files in scope to classify.")
            }
            if (command == ReviewCommand.SUMMARIES || command == ReviewCommand.TOPIC_SUMMARIES) {
                val topicSummaries = command == ReviewCommand.TOPIC_SUMMARIES
                val evidence = CodeSummaryEvidence(context.snapshot)
                val requests = withContext(Dispatchers.EDT) {
                    if (topicSummaries) estimateSummaryRequests(seedTopicSummaries(state.topicSummaries, state.summaries),
                        CODE_SUMMARY_OPTIONS, evidence::size, restartComplete = false)
                    else estimateSummaryRequests(state.summaries, CODE_SUMMARY_OPTIONS, evidence::size)
                }
                if (!confirm(requests, if (topicSummaries) "topic summaries" else "summaries")) return cancelledReply(requests)
                // Code walks the tree and batches the requests; the model only writes text, without a chat history or tool loop.
                val run = pipeline {
                    withCountProgress("Summarizing the code…", "summaries") { onProgress ->
                        ReviewSummarizer(complete, null, CODE_SUMMARY_OPTIONS, evidence)
                            .run(if (topicSummaries) context.startTopicSummaries() else context.startSummaries(), summariesFocus(question),
                                if (topicSummaries) context::summarizeTopics else context::summarize, onToolCall, onProgress)
                    }
                }
                val empty = "There are no files in scope to summarize."
                return if (topicSummaries) summariesReply(run, empty, TOPIC_SUMMARIES_MISSING) else summariesReply(run, empty)
            }
            val turn = chatTurn(messages, context.snapshot.files, diff = false) ?: return "There are no files in scope to explain."
            // Reviews accumulate evidence for many rounds; evict in chunks so the request prefix stays cacheable.
            return LlmChatBackend(rawComplete,
                chatTools(context) + turn.chartTools,
                turn.prompt(codeReviewPrompt(scopeName)) + codeFocusPrompt(context.snapshot, turnFocus),
                maxContextChars = 160_000, maxToolRounds = 100,
                context = if (turnFocus == null) { { context.overview() } }
                    else { { withContext(Dispatchers.EDT) { focusOverview(context.snapshot, turnFocus) } } },
                contextLabel = if (turnFocus == null) "Current code scope" else "Current focus", evictionTarget = 0.7)
                .reply(turn.request, onToolCall)
        }
    }

    override val chat = ChatPanel(project, session, title = "Code Chat", showDashboard = false, showFileTrace = true)
    override val chatInput get() = chat.input

    init {
        minimumSize = Dimension(300, 200)
        chat.input.emptyText.text = "/summaries, /topics, /explain or ask about the code in scope…"
        add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(scopeLabel, BorderLayout.NORTH)
            add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                add(JButton("Open review").apply {
                    toolTipText = "Open this review's tabs in the IDE editor area"
                    addActionListener { openCanvas(true) }
                }, BorderLayout.NORTH)
                add(JPanel(GridLayout(1, 2, JBUI.scale(4), JBUI.scale(4))).apply {
                    add(summariesButton.apply { addActionListener { openSummaries() } })
                    add(topicsButton.apply { addActionListener { openTopics() } })
                }, BorderLayout.CENTER)
                add(JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                    add(JButton("Clear tabs").apply {
                        addActionListener { state.clearNotes(); closeFileView() }
                    }, BorderLayout.WEST)
                }, BorderLayout.SOUTH)
            }, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        Disposer.register(this, chat)
        Disposer.register(this, canvas)
        Disposer.register(this, sourcePoints)
        Disposer.register(this, followSession(before = { if (!session.busy) applyHeld() }, updateControls = ::updateControls))
        updateControls()
    }

    /** Blocks the tools until the next update; [stop] (a new scope or an explicit refresh) also ends a held run. */
    fun invalidate(stop: Boolean) {
        if (stop) {
            session.stop()
            holding = false
            pendingSnapshot = null
        } else if (holding) return
        state.refreshing = true
        updateControls()
        summariesChanged()
    }

    /** Updates even hidden tabs, retaining only notes whose evidence is unchanged. Returns the number of notes removed. */
    fun update(snapshot: CodeSnapshot): Int {
        if (holding) {
            pendingSnapshot = snapshot
            updateControls()
            return 0
        }
        pendingSnapshot = null
        val previousRevision = state.revision
        val previousNotes = state.notes.size
        state.update(snapshot)
        if (state.revision != previousRevision) { session.stop(); sourcePoints.clear() }
        // The viewed file closes when it leaves the scope; an edit to it, or to code it reaches, is followed again.
        if (fileViewPath != null && fileViewPath !in state.snapshot.byPath) closeFileView()
        else if (fileViewPath != null && state.revision != previousRevision) { fileStructure = null; analyzeFileView() }
        renderCanvas()
        updateControls()
        return previousNotes - state.notes.size
    }

    /** A pipeline run starts holding snapshot updates (EDT). */
    internal fun pipelineStarted() {
        pipelineRuns++
        holding = true
    }

    /** A pipeline run ended (EDT). Held updates apply once the chat is idle. */
    internal fun pipelineEnded() {
        pipelineRuns = (pipelineRuns - 1).coerceAtLeast(0)
        canvasTimer.stop()
        if (!disposed) summariesChanged()
        if (!session.busy) applyHeld()
    }

    private fun applyHeld() {
        if (pipelineRuns > 0 || !holding) return
        holding = false
        pendingSnapshot?.let { update(it) }
    }

    private suspend fun <T> pipeline(run: suspend () -> T): T {
        withContext(Dispatchers.EDT) { pipelineStarted() }
        try {
            return run()
        } finally {
            withContext(NonCancellable + Dispatchers.EDT) { pipelineEnded() }
        }
    }

    private suspend fun confirm(requests: Int, what: String): Boolean =
        requests <= CONFIRM_REQUESTS || withContext(Dispatchers.EDT) { !disposed && confirmLargeRun(requests, what) }

    private fun cancelledReply(requests: Int) =
        "Cancelled: this run needs about $requests model requests. Choose a smaller folder in the Code Review panel, or start it again to confirm."

    /** Shows up to [CODE_SOURCE_LINES] lines of a file from the start of [lines] (a part) or from its head. */
    private fun showSourceBlock(path: String, lines: IntRange?) {
        val file = state.snapshot.byPath[path] ?: return
        val range = sourceBlockRange(file, lines) ?: return
        val block = try { state.showSource(state.revision, range) } catch (e: ToolInputException) { LOG.debug("Source range not available", e); return }
        renderCanvas()
        showCanvas { canvas.focusSource(block.range) }
    }

    override fun summariesChanged() {
        super.summariesChanged()
        canvas.updateFileView(fileViewCard())
    }

    /** File view reads the topics and summaries of the files [fileStructure] reaches, so it follows them like the topics card. */
    private fun fileViewCard(): FileViewCard? {
        val file = fileViewPath?.let { state.snapshot.byPath[it] } ?: return null
        val structure = fileStructure?.takeIf { it.path == file.path && it.fingerprint == file.fingerprint }
        return FileViewCard(file, structure, fileViewError, state.topics, state.summaries, canSummarize)
    }

    /**
     * Shows [path] in File view: each block beside its neighboring topics, with summaries on hover. False when
     * the file is not in this tab's scope.
     */
    fun openFileView(path: String): Boolean {
        if (disposed || path !in state.snapshot.byPath) return false
        if (path != fileViewPath) {
            fileViewPath = path
            fileStructure = null
            fileViewError = null
        }
        analyzeFileView()
        renderCanvas()
        showCanvas { canvas.focusFileView() }
        return true
    }

    private fun closeFileView() {
        fileViewJob?.cancel()
        fileViewJob = null
        fileViewPath = null
        fileStructure = null
        fileViewError = null
        renderCanvas()
    }

    /** Resolves the viewed file's calls in the background, unless its current text is already analyzed or being analyzed. */
    private fun analyzeFileView() {
        val file = fileViewPath?.let { state.snapshot.byPath[it] } ?: return
        if (fileStructure?.let { it.path == file.path && it.fingerprint == file.fingerprint } == true) return
        if (fileViewJob?.isActive == true && fileViewTarget == file && fileViewRevision == state.revision) return
        fileViewJob?.cancel()
        fileViewError = null
        fileViewTarget = file
        val revision = state.revision
        fileViewRevision = revision
        val snapshot = state.snapshot
        fileViewJob = scope.launch {
            val result = try {
                Result.success(withBackgroundProgress(project, "Following the calls of ${file.path.substringAfterLast('/')}") {
                    smartReadAction(project) { CodeFileStructureBuilder(project, snapshot).build(file) }
                })
            } catch (e: CancellationException) {
                // Propagate tab/job cancellation; progress-only cancellation must clear the file card's pending state.
                currentCoroutineContext().ensureActive()
                Result.failure(e)
            } catch (e: Exception) {
                LOG.warn("Could not follow calls in ${file.path}", e)
                Result.failure(e)
            }
            if (disposed || fileViewPath != file.path || state.revision != revision) return@launch
            fileStructure = result.getOrNull()
            fileViewError = result.exceptionOrNull()?.let {
                if (it is CancellationException) "Following calls cancelled. Reopen File view to retry."
                else "Could not follow the calls: ${it.message ?: it.javaClass.simpleName}"
            }
            canvas.updateFileView(fileViewCard())
        }
    }

    /** Saves during a run redraw the cards at most every [CANVAS_REFRESH_MS]. */
    private fun scheduleCanvasRefresh() { if (!canvasTimer.isRunning) canvasTimer.start() }

    /**
     * The per-turn overview of a focused chat: only the focused files, with their notes and source blocks. Their source and
     * summaries are already pinned by [codeFocusPrompt]; the rest of the scope is left to the tools.
     */
    private fun focusOverview(snapshot: CodeSnapshot, focus: ChatFocus): String {
        val paths = focus.files.toSet()
        return codeOverview(snapshot.copy(files = snapshot.files.filter { it.path in paths }), state.scopePaths,
            selectedPaths().filter { it in paths }, state.notes.filter { note -> note.paths.any { it in paths } },
            state.sources.filter { it.range.path in paths }, summaries = null)
    }

    private fun updateControls() {
        topicsButton.isEnabled = !state.refreshing && !session.busy && state.snapshot.files.isNotEmpty()
        summariesButton.isEnabled = !state.refreshing && state.snapshot.files.isNotEmpty()
        val snapshot = state.snapshot
        val paths = state.scopePaths
        val files = "${snapshot.files.size} ${if (snapshot.files.size == 1) "file" else "files"}"
        scopeLabel.text = if (paths == null) "Scope: ${snapshot.scope.displayName} · $files" else "Scope: ${paths.size} selected · $files"
        focus?.let { scopeLabel.text += " · Focus: ${it.label}" }
        if (pendingSnapshot != null) scopeLabel.text += " · update waits for the run"
        scopeLabel.toolTipText = buildString {
            append(snapshot.scope.absolutePath.ifEmpty { snapshot.scope.displayName })
            append("\n$files · ${snapshot.totalLines} lines")
            if (snapshot.chunkedFiles > 0) append(" · ${snapshot.chunkedFiles} large files in ${snapshot.partCount} parts")
            append("\n")
            append(paths?.sorted()?.let { list ->
                list.take(50).joinToString("\n") { it.ifEmpty { "." } } + if (list.size > 50) "\n… ${list.size - 50} more" else ""
            } ?: "Follows every file in the scope.")
            if (pendingSnapshot != null) append("\nFiles changed during the run; the tab updates when it ends.")
        }
    }

    override fun renderCanvas() {
        canvas.showNotes(CanvasState(state.notes, state.sources, summaries = summariesCard(), topics = topicsCard(), fileView = fileViewCard(),
            explanation = explanations.result))
        canvas.highlight(selectedPaths().toSet())
    }

    /** Snapshot tools stay scoped; current project files remain readable for supporting context. */
    internal fun chatTools(context: CodeReviewContext): List<ChatTool> =
        codeReviewTools(context) + projectContextTools(project)

    /** Captured once per turn; subsequent calls cannot silently mix evidence from two snapshots. */
    internal fun captureContext(): CodeReviewContext {
        val capturedRevision = state.revision
        val captured = state.snapshot
        val selected = selectedPaths().filter { it in captured.byPath }
        val live = this@CodeReviewTab.state
        fun file(path: String) = captured.byPath[path] ?: throw ToolInputException(
            "$path has no source in this snapshot. Use read_file or point_to_code for current project context.")
        fun symbols() = ReviewSymbols(project, captured.files.map { SymbolFile(it.path, it.absolutePath, it.text) },
            stale = { path -> "$path changed since the code snapshot. Wait for the refresh, then retry or use a file note without symbol." },
            missing = { path -> "$path is not a file in this review's scope." },
            findTool = "find_code_symbols")
        return object : CapturedReviewContext<CodeFile>(live, capturedRevision, { disposed }, { change ->
            when (change) {
                ReviewContextChange.SOURCE, ReviewContextChange.SUMMARIES_STARTED -> renderCanvas()
                ReviewContextChange.TOPICS_STARTED -> canvas.updateTopics(topicsCard())
                ReviewContextChange.TOPIC_SUMMARIES_STARTED -> summariesChanged()
                ReviewContextChange.SUMMARIES_SAVED, ReviewContextChange.TOPIC_ASSIGNED -> scheduleCanvasRefresh()
            }
        }), CodeReviewContext {
            override suspend fun pointToCode(point: dev.insight.agent.SourcePoint): String = withContext(Dispatchers.EDT) {
                current()
                if (point.side != null) throw ToolInputException("Code review has no diff sides")
                val sourceFile = captured.byPath[point.path]
                if (sourceFile == null) dev.insight.chat.showProjectSource(project, sourcePoints, point)
                else sourcePoints.show(point, sourceFile.text)
            }

            override val snapshot: CodeSnapshot get() = captured

            override suspend fun overview(): String = withContext(Dispatchers.EDT) {
                current()
                codeOverview(captured, live.scopePaths, selected, live.notes, live.sources, live.summaries)
            }

            override suspend fun listFiles(directory: String, glob: String?, offset: Int): String {
                withContext(Dispatchers.EDT) { current() }
                return withContext(Dispatchers.Default) { codeFilesResult(captured, directory, glob, offset) }
            }

            override suspend fun read(ranges: List<CodeRange>): String {
                withContext(Dispatchers.EDT) { current() }
                return withContext(Dispatchers.Default) { codeReadResult(captured, ranges) }
            }

            override suspend fun search(query: String, regex: Boolean, directory: String, glob: String?, offset: Int): String {
                withContext(Dispatchers.EDT) { current() }
                return withContext(Dispatchers.Default) {
                    val job = currentCoroutineContext().job
                    codeSearchResult(captured, query, regex, directory, glob, offset) { job.ensureActive() }
                }
            }

            override suspend fun symbols(paths: List<String>, query: String, offset: Int): String {
                withContext(Dispatchers.EDT) { current(); paths.forEach(::file) }
                return readPsiThen(project, ::current, SYMBOLS_CHANGED, { symbols().find(paths, query, offset) }) { it }
            }

            override suspend fun addNotes(notes: List<ReviewNote>): String {
                withContext(Dispatchers.EDT) { current(); notes.flatMap { it.paths }.forEach(::file) }
                return readPsiThen(project, ::current, NOTES_CHANGED.takeIf { notes.any { it.symbol != null } },
                    { symbols().resolve(notes) }) { resolved ->
                    val added = live.addNotes(capturedRevision, resolved)
                    renderCanvas()
                    "Added ${added.size} note${if (added.size == 1) "" else "s"} to the canvas."
                }
            }

            override suspend fun summaries(offset: Int, path: String?): String = withContext(Dispatchers.EDT) {
                current()
                codeSummariesJson(live.summaries, offset, path)
            }
        }
    }

    override fun clearSnapshot() = state.update(CodeSnapshot.empty())

    override fun dispose() {
        canvasTimer.stop()
        holding = false
        pendingSnapshot = null
        super.dispose()
    }

    internal companion object {
        const val CANVAS_REFRESH_MS = 250
    }
}

/** Runs estimated above this many model requests ask before they start. */
internal const val CONFIRM_REQUESTS = 500
