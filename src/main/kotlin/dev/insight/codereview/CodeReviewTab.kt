package dev.insight.codereview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportRawProgress
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.ui.JBUI
import dev.insight.chat.ChatBackend
import dev.insight.chat.ChatMessage
import dev.insight.chat.ChatPanel
import dev.insight.chat.ChatSession
import dev.insight.chat.ChatSettings
import dev.insight.chat.LlmChatBackend
import dev.insight.chat.ToolCallEvent
import dev.insight.chat.ToolInputException
import dev.insight.diff.ChangeSummaries
import dev.insight.diff.ChangeSummarizer
import dev.insight.diff.ChangeSummaryOptions
import dev.insight.diff.ChangeTopicGenerator
import dev.insight.diff.ChangeTopics
import dev.insight.diff.DiffNote
import dev.insight.diff.DiffReviewCanvasFile
import dev.insight.diff.DiffSourceRange
import dev.insight.diff.DiffSymbols
import dev.insight.diff.NoteCanvas
import dev.insight.diff.ReviewCanvasOwner
import dev.insight.diff.ReviewTraceLauncher
import dev.insight.diff.ReviewVocabulary
import dev.insight.diff.SummariesCard
import dev.insight.diff.SummaryNode
import dev.insight.diff.SymbolFile
import dev.insight.diff.TopicsCard
import dev.insight.diff.isSummariesRequest
import dev.insight.diff.isTopicsRequest
import dev.insight.diff.openReviewCanvas
import dev.insight.diff.reviewJson
import dev.insight.diff.seedTopicSummaries
import dev.insight.diff.summariesFocus
import dev.insight.diff.summariesReply
import dev.insight.diff.topicsReply
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Owns one conversation and canvas about the code in scope. File selection never changes this tab's scope. While a
 * /summaries, /topic-summaries or /topics run is in progress, snapshot updates are held and applied when the chat is idle
 * again, so an edit elsewhere does not stop a long run; ordinary chat turns stop on a new revision like Diff Review.
 * [complete] replaces the configured model (tests).
 */
internal class CodeReviewTab(
    private val project: Project,
    paths: Set<String>?,
    private val selectedPaths: () -> List<String>,
    private val openFile: (path: String, line: Int?) -> Unit,
    private val complete: (suspend (CompletionRequest) -> Completion)? = null,
) : JPanel(BorderLayout()), Disposable, ReviewCanvasOwner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    val state = CodeReviewState(paths)
    private var disposed = false
    override val isDisposed: Boolean get() = disposed
    override val canvasTitle: String get() = ReviewVocabulary.CODE.canvasTitle
    var onCanvasSelected: () -> Unit = {}
    override fun canvasSelected() = onCanvasSelected()

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
        openSymbol = { symbol ->
            findSourceFile(project, symbol.path, symbol.absolutePath)?.let { OpenFileDescriptor(project, it, symbol.offset).navigate(true) }
        },
        removeSource = { range -> state.removeSource(range); renderCanvas() },
        showDiffCard = { path -> showSourceBlock(path, null) },
        project = project,
        summarize = { summarize() },
        generateTopics = { if (canSummarize) session.send("/topics") },
        fillTopicSummaries = { if (canSummarize) session.send("/topic-summaries") },
        removeTopics = { state.hideTopics(); renderCanvas() },
        removeSummaries = { state.hideSummaries(); renderCanvas() },
        traceSummaries = ReviewTraceLauncher(project, scope),
        vocabulary = ReviewVocabulary.CODE,
        openNode = { node -> openFile(node.path, node.lines?.first) },
        showNode = { node: SummaryNode -> showSourceBlock(node.path, node.lines) },
    )

    val canvasFile = DiffReviewCanvasFile(this)
    private val summariesButton = JButton("Summaries").apply {
        toolTipText = "Read the code as summaries, from the whole scope down to each file and the parts of large files (/summaries)"
    }
    private val topicsButton = JButton("Topics").apply {
        toolTipText = "Group the files in scope by responsibility, with each file's summary (/topics)"
    }
    private val scopeLabel = JLabel()
    /** Coalesces canvas updates while a run saves summaries or topics. */
    private val canvasTimer = Timer(CANVAS_REFRESH_MS) { if (!disposed) summariesChanged() }.apply { isRepeats = false }

    val session = object : ChatSession(project, scope, welcome =
        "Ask about ${if (paths == null) "the code in scope" else "the selected files and folders"}. Type /summaries to read it " +
            "from the whole scope down to each file, or /topics to group the files by responsibility.") {
        override fun createBackend(): ChatBackend = object : ChatBackend {
            override suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit): String {
                val (context, scopeName) = withContext(Dispatchers.EDT) { captureContext() to promptScope() }
                val question = messages.lastOrNull()?.text.orEmpty()
                val complete = this@CodeReviewTab.complete ?: ChatSettings.getInstance().createClient()::complete
                if (isTopicsRequest(question)) {
                    val requests = withContext(Dispatchers.EDT) { estimateTopicRequests(state.topics) }
                    if (!confirm(requests, "topics")) return cancelledReply(requests)
                    val run = pipeline {
                        withBackgroundProgress(project, "Generating code topics") {
                            reportRawProgress { reporter ->
                                reporter.text("Classifying files in scope…")
                                ChangeTopicGenerator(complete, codeReviewTools(context).single { it.definition.name == READ_CODE_TOOL },
                                    system = CODE_TOPIC_SYSTEM, evidence = codeTopicEvidence(context.snapshot) { state.summaries },
                                    concurrency = CODE_TOPIC_CONCURRENCY)
                                    .run(context.startTopics(), summariesFocus(question), context::assignTopic, onToolCall,
                                        onProgress = { done, total ->
                                            reporter.details("$done of $total files classified")
                                            reporter.fraction(if (total > 0) done.toDouble() / total else null)
                                        })
                            }
                        }
                    }
                    return topicsReply(run).replace("There are no changed files to classify.", "There are no files in scope to classify.")
                }
                if (isSummariesRequest(question) || question.trim() == "/topic-summaries") {
                    val topicSummaries = question.trim() == "/topic-summaries"
                    val evidence = CodeSummaryEvidence(context.snapshot)
                    val requests = withContext(Dispatchers.EDT) {
                        if (topicSummaries) estimateSummaryRequests(seedTopicSummaries(state.topicSummaries, state.summaries),
                            CODE_SUMMARY_OPTIONS, evidence::size, restartComplete = false)
                        else estimateSummaryRequests(state.summaries, CODE_SUMMARY_OPTIONS, evidence::size)
                    }
                    if (!confirm(requests, if (topicSummaries) "topic summaries" else "summaries")) return cancelledReply(requests)
                    // Code walks the tree and batches the requests; the model only writes text, without a chat history or tool loop.
                    val run = pipeline {
                        withBackgroundProgress(project, "Generating code summaries") {
                            reportRawProgress { reporter ->
                                reporter.text("Summarizing the code…")
                                ChangeSummarizer(complete, null, CODE_SUMMARY_OPTIONS, evidence)
                                    .run(if (topicSummaries) context.startTopicSummaries() else context.startSummaries(), summariesFocus(question),
                                        if (topicSummaries) context::summarizeTopics else context::summarize, onToolCall,
                                        onProgress = { done, total ->
                                            reporter.details("$done of $total summaries")
                                            reporter.fraction(if (total > 0) done.toDouble() / total else null)
                                        })
                            }
                        }
                    }
                    val reply = summariesReply(run, empty = "There are no files in scope to summarize.")
                    return if (topicSummaries) reply.replace("They show their names in the Summaries card.",
                        "Topics without summaries show file names in Hierarchy. Click Fill summaries to continue.") else reply
                }
                // Reviews accumulate evidence for many rounds; evict in chunks so the request prefix stays cacheable.
                return LlmChatBackend(complete, codeReviewTools(context), codeReviewPrompt(project.name, scopeName),
                    maxContextChars = 160_000, maxToolRounds = 100, context = { context.overview() },
                    contextLabel = "Current code scope", evictionTarget = 0.7)
                    .reply(messages, onToolCall)
            }
        }
    }
    private val chat = ChatPanel(project, session, title = "Code Chat", showDashboard = false)

    init {
        minimumSize = Dimension(300, 200)
        chat.input.emptyText.text = "/summaries, /topics or ask about the code in scope…"
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
                        addActionListener { state.clearNotes(); renderCanvas() }
                    }, BorderLayout.WEST)
                }, BorderLayout.SOUTH)
            }, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(chat, BorderLayout.CENTER)
        Disposer.register(this, chat)
        Disposer.register(this, canvas)
        Disposer.register(this, session.subscribe {
            if (!session.busy) applyHeld()
            updateControls()
            summariesChanged()
        })
        updateControls()
    }

    fun openCanvas(focus: Boolean = false) {
        if (disposed) return
        openReviewCanvas(project, canvasFile, focus)
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
        if (state.revision != previousRevision) session.stop()
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

    /** Shows up to [CODE_SOURCE_LINES] lines of a file from the start of [lines] (a part) or from its head. */
    private fun showSourceBlock(path: String, lines: IntRange?) {
        val file = state.snapshot.byPath[path] ?: return
        val range = sourceBlockRange(file, lines) ?: return
        val block = try { state.showSource(state.revision, range) } catch (_: ToolInputException) { return }
        renderCanvas()
        showCanvas { canvas.focusSource(block.range) }
    }

    private fun summariesCard() = if (state.summariesVisible) SummariesCard(state.summaries, session.busy, canSummarize) else null

    private fun topicsCard() = if (state.topicsVisible) TopicsCard(state.topics, state.summaries, canSummarize, state.topicSummaries) else null

    /** The topics card shows the file summaries too, so it follows every summary and chat update. */
    private fun summariesChanged() { canvas.updateSummaries(summariesCard()); canvas.updateTopics(topicsCard()) }

    /** Saves during a run redraw the cards at most every [CANVAS_REFRESH_MS]. */
    private fun scheduleCanvasRefresh() { if (!canvasTimer.isRunning) canvasTimer.start() }

    private fun promptScope(): String {
        val name = state.snapshot.scope.path.ifEmpty { "the project root" }
        return state.scopePaths?.let { "${it.size} selected paths of $name" } ?: name
    }

    private fun updateControls() {
        topicsButton.isEnabled = !state.refreshing && !session.busy && state.snapshot.files.isNotEmpty()
        summariesButton.isEnabled = !state.refreshing && state.snapshot.files.isNotEmpty()
        val snapshot = state.snapshot
        val paths = state.scopePaths
        val files = "${snapshot.files.size} ${if (snapshot.files.size == 1) "file" else "files"}"
        scopeLabel.text = if (paths == null) "Scope: ${snapshot.scope.displayName} · $files" else "Scope: ${paths.size} selected · $files"
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

    private fun renderCanvas() {
        canvas.showNotes(state.notes, state.sources, summaries = summariesCard(), topics = topicsCard())
        canvas.highlight(selectedPaths().toSet())
    }

    /** Captured once per turn; subsequent calls cannot silently mix evidence from two snapshots. */
    internal fun captureContext(): CodeReviewContext {
        if (disposed) throw ToolInputException("This review tab is closed")
        state.check(state.revision)
        val capturedRevision = state.revision
        val captured = state.snapshot
        val selected = selectedPaths().filter { it in captured.byPath }
        val live = this@CodeReviewTab.state
        fun current() {
            if (disposed) throw ToolInputException("This review tab is closed")
            live.check(capturedRevision)
        }
        fun file(path: String) = captured.byPath[path] ?: throw ToolInputException("$path is not a file in this review's scope.")
        fun symbols() = DiffSymbols(project, captured.files.map { SymbolFile(it.path, it.absolutePath, it.text) },
            stale = { path -> "$path changed since the code snapshot. Wait for the refresh, then retry or use a file note without symbol." },
            missing = { path -> "$path is not a file in this review's scope." },
            findTool = "find_code_symbols")
        return object : CodeReviewContext {
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
                val (result, stamp) = withContext(Dispatchers.Default) { readAction {
                    symbols().find(paths, query, offset) to PsiModificationTracker.getInstance(project).modificationCount
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
                    symbols().resolve(notes) to PsiModificationTracker.getInstance(project).modificationCount
                } }
                return withContext(Dispatchers.EDT) {
                    current()
                    if (notes.any { it.symbol != null } && stamp != PsiModificationTracker.getInstance(project).modificationCount)
                        throw ToolInputException("Source changed while resolving notes. Retry with current source.")
                    val added = live.addNotes(capturedRevision, resolved)
                    renderCanvas()
                    "Added ${added.size} note${if (added.size == 1) "" else "s"} to the canvas."
                }
            }

            override suspend fun summaries(offset: Int, path: String?): String = withContext(Dispatchers.EDT) {
                current()
                codeSummariesJson(live.summaries, offset, path)
            }

            override suspend fun startSummaries(): ChangeSummaries = withContext(Dispatchers.EDT) {
                current()
                // An incomplete tree is continued, so an edit only re-summarizes the changed files and the folders above them.
                live.startSummaries(capturedRevision).also { renderCanvas() }
            }

            override suspend fun summarize(entries: Map<Int, String>): ChangeSummaries = withContext(Dispatchers.EDT) {
                current()
                live.summarize(capturedRevision, entries).also { scheduleCanvasRefresh() }
            }

            override suspend fun startTopics(): ChangeTopics = withContext(Dispatchers.EDT) {
                current()
                live.startTopics().also { canvas.updateTopics(topicsCard()) }
            }

            override suspend fun assignTopic(path: String, label: String): ChangeTopics = withContext(Dispatchers.EDT) {
                current()
                live.assignTopic(capturedRevision, path, label).also { scheduleCanvasRefresh() }
            }

            override suspend fun startTopicSummaries(): ChangeSummaries = withContext(Dispatchers.EDT) {
                current()
                live.startTopicSummaries(capturedRevision).also { summariesChanged() }
            }

            override suspend fun summarizeTopics(entries: Map<Int, String>): ChangeSummaries = withContext(Dispatchers.EDT) {
                current()
                live.summarizeTopics(capturedRevision, entries).also { scheduleCanvasRefresh() }
            }
        }
    }

    override fun dispose() {
        disposed = true
        onCanvasSelected = {}
        canvasTimer.stop()
        FileEditorManager.getInstance(project).closeFile(canvasFile)
        canvasFile.setValid(false)
        session.discard()
        scope.cancel()
        holding = false
        pendingSnapshot = null
        state.update(CodeSnapshot.empty())
        state.clearNotes()
        state.refreshing = true
        canvas.showNotes(emptyList())
        canvas.removeAll()
        removeAll()
    }

    internal companion object {
        const val CANVAS_REFRESH_MS = 250
    }
}

/** Runs estimated above this many model requests ask before they start. */
internal const val CONFIRM_REQUESTS = 500

/**
 * About how many model requests a summary run of [summaries] sends: missing leaves batched like [ChangeSummarizer] (by
 * count, by [size] and alone when cut), then the missing branches wave by wave in batches of [ChangeSummaryOptions.foldersPerRequest].
 * A branch with a single child reuses its summary without a request. A complete tree starts over when [restartComplete].
 */
internal fun estimateSummaryRequests(
    summaries: ChangeSummaries,
    options: ChangeSummaryOptions,
    size: (SummaryNode) -> Int,
    restartComplete: Boolean = true,
): Int {
    val start = if (restartComplete && summaries.complete) ChangeSummaries(summaries.tree) else summaries
    val tree = start.tree
    val missing = start.missing().toHashSet()
    if (missing.isEmpty()) return 0
    var requests = 0
    var batch = 0
    var chars = 0
    // Wave of a missing node: 0 when its children are all written, else one after its latest missing child.
    val wave = HashMap<Int, Int>()
    for (node in tree.nodes.asReversed()) {
        if (node.id !in missing) continue
        wave[node.id] = node.children.filter { it in missing }.maxOfOrNull { wave.getValue(it) + 1 } ?: 0
        if (node.children.isNotEmpty()) continue
        val chars1 = size(node)
        if (chars1 > options.patchChars) { requests++; continue }
        if (batch > 0 && (batch >= options.filesPerRequest || chars + chars1 > options.batchChars)) { requests++; batch = 0; chars = 0 }
        batch++; chars += chars1
    }
    if (batch > 0) requests++
    val branches = tree.nodes.filter { it.id in missing && it.children.size > 1 }.groupingBy { wave.getValue(it.id) }.eachCount()
    branches.values.forEach { requests += (it + options.foldersPerRequest - 1) / options.foldersPerRequest }
    return requests
}

/** One request per file that a /topics run classifies: the missing files, or every file when the topics are complete. */
internal fun estimateTopicRequests(topics: ChangeTopics): Int =
    if (topics.complete) topics.files.size else topics.files.count { it.path !in topics.labels }

/**
 * The lines a source block shows for [lines] (a part) or a whole file: from its first line, at most [CODE_SOURCE_LINES]
 * lines and [CODE_SOURCE_CHARS] characters. Null when the file is empty or its first line alone is too long.
 */
internal fun sourceBlockRange(file: CodeFile, lines: IntRange?): DiffSourceRange? {
    if (file.lineCount == 0) return null
    val start = (lines?.first ?: 1).coerceIn(1, file.lineCount)
    var end = minOf(lines?.last ?: file.lineCount, start + CODE_SOURCE_LINES - 1, file.lineCount).coerceAtLeast(start)
    if (file.lines(start..start).length > CODE_SOURCE_CHARS) return null
    if (file.lines(start..end).length > CODE_SOURCE_CHARS) {
        // The longest prefix that fits: the first line fits, the full range does not.
        var low = start
        var high = end
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (file.lines(start..middle).length <= CODE_SOURCE_CHARS) low = middle else high = middle - 1
        }
        end = low
    }
    return DiffSourceRange(file.path, start, end, CODE_SOURCE_SIDE)
}

/** The file of a code review path: on disk, or under a project root (tests use an in-memory file system). */
internal fun findSourceFile(project: Project, path: String, absolutePath: String?): VirtualFile? =
    absolutePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }?.takeIf { it.isValid && !it.isDirectory }
        ?: codeScopeRoots(project).firstNotNullOfOrNull { it.findFileByRelativePath(path) }?.takeIf { it.isValid && !it.isDirectory }
