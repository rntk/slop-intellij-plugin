package dev.insight.diff

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.ui.JBUI
import dev.insight.chat.*
import dev.insight.core.withCountProgress
import dev.insight.llm.CompletionFn
import dev.insight.review.ChatFocus
import dev.insight.review.ReviewCommand
import dev.insight.review.reviewProgressTitle
import dev.insight.review.ReviewDiskCache
import dev.insight.review.ReviewCodeLocation
import dev.insight.review.ReviewCodeNavigation
import dev.insight.review.ReviewNote
import dev.insight.review.CapturedReviewContext
import dev.insight.review.ReviewContextChange
import dev.insight.review.ReviewVocabulary
import dev.insight.review.TOPIC_SUMMARIES_MISSING
import dev.insight.review.chatFocus
import dev.insight.review.current
import dev.insight.review.summariesFocus
import dev.insight.review.summariesReply
import dev.insight.review.topicsReply
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import kotlinx.coroutines.*

private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance("dev.insight.diff.DiffReviewTab")

/** Owns one conversation and canvas. File selection never changes this tab's scope. [complete] replaces the configured model (tests). */
internal class DiffReviewTab(
    project: Project,
    paths: Set<String>?,
    private val selectedPaths: () -> List<String>,
    showDiff: (String) -> Unit,
    private val complete: CompletionFn? = null,
    focus: ChatFocus? = null,
    /** Opens a new tab whose chat is focused on a summary entry. */
    private val toChat: (ChatFocus) -> Unit = {},
) : ReviewTab<ReviewFile, DiffReviewState>(project, DiffReviewState(paths, ReviewDiskCache.forProject(project)), focus,
    ReviewVocabulary.DIFF, "Diff chat") {
    private val fullFileSnapshots = java.util.IdentityHashMap<ReviewFile, com.intellij.openapi.vfs.VirtualFile>()
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
            markReviewed = state::setReviewed,
            contextSummaries = ReviewContextSummaryLauncher(project, scope) {
                val context = captureContext()
                val files = state.snapshot.files
                val rawComplete = this@DiffReviewTab.complete ?: ChatSettings.getInstance().createClient()::complete
                val complete = tracedFileCompletion(files.map { TraceFile(it.path, patch = it.patch, before = it.before, after = it.after) }, rawComplete)
                diffSummarizer(complete, diffReviewTools(context).single { it.definition.name == "read_diff_files" })
            },
            deleteOriginalSummaries = { state.clearSummaries(); summariesChanged() },
            // The cited lines are those of the snapshot file, so its diff opens rather than the current one.
            openLines = { node, lines -> (node.file as? ReviewFile)?.let { showDiffLines(project, it, lines) } },
            openWholeFile = { file ->
                WriteIntentReadAction.run {
                    val snapshot = fullFileSnapshots.getOrPut(file) { createFullFileSnapshot(file) }
                    FileEditorManager.getInstance(project).openFile(snapshot, true)
                        .filterIsInstance<TextEditor>().forEach { opened ->
                            val editor = opened.editor as? EditorEx ?: return@forEach
                            ReviewCodeNavigation.install(editor, project) { line, column ->
                                ReviewCodeLocation(file.path, file.fullFileText.orEmpty(), line + 1, column, file.absolutePath)
                            }
                        }
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
    override val session = object : ChatSession(project, scope, onClear = sourcePoints::clear, welcome = if (focus != null)
        "Ask about ${focus.label}. Its summaries and diff are in this chat's context; other project files are read when a " +
            "question needs them." else
        "Ask about ${if (paths == null) "the current changes" else "the selected files' changes"}, or type /summaries to read the change from the whole down to each file. " +
            "Use /topics to read every diff as one document grouped by topic. " +
            "Use Explain on a summary, or /explain with a path, to learn how a change works and why.") {
        override fun replyProgressTitle(text: String): String = reviewProgressTitle(text, "change")

        override fun createBackend(): ChatBackend = object : ChatBackend {
            override suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit): String {
                val (context, snapshot, turnFocus) = withContext(Dispatchers.EDT) {
                    Triple(captureContext(), state.snapshot,
                        focus?.current(state.snapshot.files.mapTo(hashSetOf()) { it.path }, state.summaries, state.topicSummaries))
                }
                val files = snapshot.files
                val question = messages.lastOrNull()?.text.orEmpty()
                val rawComplete = this@DiffReviewTab.complete ?: ChatSettings.getInstance().createClient()::complete
                val complete = tracedFileCompletion(files.map { TraceFile(it.path, patch = it.patch, before = it.before, after = it.after) }, rawComplete)
                val command = ReviewCommand.parse(question)
                if (command == ReviewCommand.TOPICS) {
                    val run = withCountProgress("Classifying changed files…", "files classified") { onProgress ->
                        diffTopicGenerator(complete, diffReviewTools(context).single { it.definition.name == "read_diff_files" })
                            .run(context.startTopics(), summariesFocus(question), context::assignTopic, onToolCall, onProgress)
                    }
                    return topicsReply(run)
                }
                if (command == ReviewCommand.SUMMARIES || command == ReviewCommand.TOPIC_SUMMARIES) {
                    val topicSummaries = command == ReviewCommand.TOPIC_SUMMARIES
                    // Code walks the tree and batches the requests; the model only writes text, without a chat history or tool loop.
                    val run = withCountProgress("Summarizing the change…", "summaries") { onProgress ->
                        diffSummarizer(complete, diffReviewTools(context).single { it.definition.name == "read_diff_files" })
                            .run(if (topicSummaries) context.startTopicSummaries() else context.startSummaries(), summariesFocus(question),
                                if (topicSummaries) context::summarizeTopics else context::summarize, onToolCall, onProgress)
                    }
                    return if (topicSummaries) summariesReply(run, missing = TOPIC_SUMMARIES_MISSING) else summariesReply(run)
                }
                val turn = chatTurn(messages, files, diff = true) ?: return "There are no changed files to explain."
                // Reviews accumulate evidence for many rounds; evict in chunks so the request prefix stays cacheable.
                return LlmChatBackend(rawComplete,
                    chatTools(context) + turn.chartTools,
                    turn.prompt(diffReviewPrompt()) + diffFocusPrompt(files, turnFocus),
                    maxContextChars = 160_000, maxToolRounds = 100,
                    context = if (turnFocus == null) { { context.state(0, INLINE_PATCH_CHARS) } }
                        else { { withContext(Dispatchers.EDT) { focusState(snapshot, turnFocus) } } },
                    contextLabel = if (turnFocus == null) "Current diff snapshot" else "Current focus snapshot",
                    evictionTarget = 0.7)
                    .reply(turn.request, onToolCall)
            }
        }
    }
    override val chat = ChatPanel(project, session, title = "Diff Chat", showDashboard = false, showFileTrace = true)

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
        Disposer.register(this, followSession(updateControls = ::updateControls))
        updateControls()
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
                if (e is com.intellij.openapi.diagnostic.ControlFlowException || e is VirtualMachineError) throw e
                LOG.warn("Semantic change analysis failed", e)
                SemanticResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            if (semanticCounting === files) semanticCounting = null
            if (!disposed && state.setSemantic(files, result)) renderCanvas()
        }
    }

    /**
     * The per-turn state of a focused chat: only the focused files, with their notes and source blocks. Their patches are
     * already pinned by [diffFocusPrompt], so none are repeated; the rest of the diff is left to the tools.
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

    override fun renderCanvas() {
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
        val capturedRevision = state.revision
        val snapshot = state.snapshot
        val selected = selectedPaths().filter { path -> snapshot.files.any { it.path == path } }
        val live = this@DiffReviewTab.state
        fun file(path: String) = snapshot.files.firstOrNull { it.path == path } ?: throw ToolInputException(
            "$path has no diff in this snapshot. Use read_file or point_to_code for current project context.")
        return object : CapturedReviewContext<ReviewFile>(live, capturedRevision, { disposed }, { change ->
            when (change) {
                ReviewContextChange.SOURCE, ReviewContextChange.SUMMARIES_STARTED -> renderCanvas()
                ReviewContextChange.TOPICS_STARTED, ReviewContextChange.TOPIC_ASSIGNED -> canvas.updateTopics(topicsCard())
                ReviewContextChange.TOPIC_SUMMARIES_STARTED, ReviewContextChange.SUMMARIES_SAVED -> summariesChanged()
            }
        }), DiffReviewContext {
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
                return readPsiThen(project, ::current, SYMBOLS_CHANGED, { diffSymbols(project, snapshot).find(paths, query, offset) }) { it }
            }

            override suspend fun addNotes(notes: List<ReviewNote>): String {
                withContext(Dispatchers.EDT) { current(); notes.flatMap { it.paths }.forEach(::file) }
                return readPsiThen(project, ::current, NOTES_CHANGED.takeIf { notes.any { it.symbol != null } },
                    { diffSymbols(project, snapshot).resolve(notes) }) { resolved ->
                    val added = live.addNotes(capturedRevision, resolved)
                    renderCanvas()
                    "Added ${added.size} note${if (added.size == 1) "" else "s"}." +
                        changeFlow(live.notes).summary().let { if (it.isEmpty()) "" else " $it" }
                }
            }

            override suspend fun summaries(offset: Int): String = withContext(Dispatchers.EDT) {
                current()
                changeSummariesJson(live.summaries, offset)
            }
        }
    }

    override fun clearSnapshot() = state.update(ReviewSnapshot(emptyList(), emptyList()))

    override fun dispose() {
        fullFileSnapshots.clear()
        super.dispose()
    }

    private companion object {
        // Small diffs arrive with the question, so an answer often needs no read calls at all.
        const val INLINE_PATCH_CHARS = 24_000
    }
}
