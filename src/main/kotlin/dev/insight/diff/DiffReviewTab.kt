package dev.insight.diff

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.ui.JBUI
import dev.insight.chat.*
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane

/** Owns one conversation and canvas. File selection never changes this tab's scope. */
internal class DiffReviewTab(
    private val project: Project,
    paths: Set<String>?,
    private val selectedPaths: () -> List<String>,
    showDiff: (String) -> Unit,
    /** Called when this tab's summaries or chat state change. */
    private val summariesChanged: () -> Unit = {},
) : JPanel(BorderLayout()), Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    val state = DiffReviewState(paths)
    private var disposed = false
    private val canvas: NoteCanvas = NoteCanvas(
        showDiff,
        { note -> state.removeNote(note.paths, note.symbol); renderCanvas() },
        { symbol ->
            LocalFileSystem.getInstance().findFileByPath(symbol.absolutePath)?.let { file ->
                OpenFileDescriptor(project, file, symbol.offset).navigate(true)
            }
        },
        removeSource = { range -> state.removeSource(range); renderCanvas() },
        showDiffCard = { path ->
            val alreadyShown = state.diffs.any { it.path == path }
            if (state.showDiff(path) != null) {
                renderCanvas()
                if (alreadyShown) canvas.focusDiff(path)
            }
        },
        removeDiff = { path -> state.removeDiff(path); renderCanvas() },
        project = project,
        removeFlows = { state.removeFlows(); renderCanvas() },
    )
    private val describe = JButton("Description")
    private val flowsButton = JButton("Flows").apply {
        toolTipText = "Map the change: functions and calls from source, grouped and labelled by the assistant (/flows)"
    }
    private val scopeLabel = JLabel()
    val session = object : ChatSession(project, scope, welcome =
        "Ask about ${if (paths == null) "the current changes" else "the selected files' changes"}, type /description for brief notes on each file, " +
            "/flows to map the change from an overview down to the code, or /summaries for the Summaries tree.") {
        override fun createBackend(): ChatBackend = object : ChatBackend {
            override suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit): String {
                val (context, total) = withContext(Dispatchers.EDT) { captureContext() to state.snapshot.files.size }
                val question = messages.lastOrNull()?.text.orEmpty()
                val description = isDescriptionRequest(question)
                val flows = isFlowsRequest(question)
                val summaries = isSummariesRequest(question)
                // The graph is built and drawn before the model runs, so the structure never depends on a reply.
                val graph = if (flows) context.buildFlows() else null
                val tree = if (summaries) context.startSummaries() else null
                val client = ChatSettings.getInstance().createClient()
                // Only this turn carries the command's instructions; the transcript keeps the command as typed.
                val request = when {
                    description -> messages.dropLast(1) + messages.last().copy(text = descriptionRequest(question))
                    graph != null -> messages.dropLast(1) + messages.last().copy(text = flowsRequest(question, graph))
                    tree != null -> messages.dropLast(1) + messages.last().copy(text = summariesRequest(question, tree))
                    else -> messages
                }
                // Code, not the model, checks that a description covers every file in scope, that flows interpret every change
                // and that summaries reach the root.
                val coverage: (suspend () -> String?)? = when {
                    description -> suspend { context.uncovered().takeIf { it.isNotEmpty() }?.let { coverageNote(it, total) } }
                    flows -> suspend { context.flowsUncovered()?.takeIf { !it.complete }?.let(::flowsCoverageNote) }
                    summaries -> suspend { context.currentSummaries()?.takeIf { !it.complete }?.let(::summariesCoverageNote) }
                    else -> null
                }
                // Reviews accumulate evidence for many rounds; evict in chunks so the request prefix stays cacheable.
                val reply = LlmChatBackend(client::complete, diffReviewTools(context) + changeFlowTools(context) + changeSummaryTools(context), diffReviewPrompt(project.name),
                    maxContextChars = 160_000, maxToolRounds = 100, context = { context.state(0, INLINE_PATCH_CHARS) },
                    contextLabel = "Current diff snapshot", evictionTarget = 0.7, followUp = coverage)
                    .reply(request, onToolCall)
                if (summaries) {
                    val current = context.currentSummaries() ?: return reply
                    return if (current.complete) reply else "$reply\n\n[No summary for ${current.missing().size} of ${current.total} tree nodes. " +
                        "They show their names in the Summaries tab.]"
                }
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
        chat.input.emptyText.text = "/description, /flows, /summaries or ask about this tab's changes…"
        add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(scopeLabel, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(describe.apply { addActionListener { session.send("/description") } })
                add(flowsButton.apply { addActionListener { session.send("/flows") } })
                add(JButton("Clear canvas").apply { addActionListener { state.clearNotes(); renderCanvas() } })
                add(JButton("−").apply { toolTipText = "Zoom out"; addActionListener { canvas.zoomAt(canvas.zoom / 1.2) } })
                add(JButton("100%").apply {
                    toolTipText = "Reset canvas view"
                    addActionListener { canvas.resetView() }
                    canvas.onZoomChanged = { text = "${(it * 100).toInt()}%" }
                })
                add(JButton("+").apply { toolTipText = "Zoom in"; addActionListener { canvas.zoomAt(canvas.zoom * 1.2) } })
                add(JButton("Fit").apply { toolTipText = "Fit all cards"; addActionListener { canvas.fitView() } })
            }, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(JSplitPane(JSplitPane.VERTICAL_SPLIT, canvas, chat).apply {
            resizeWeight = 0.65; setDividerLocation(430)
        }, BorderLayout.CENTER)
        Disposer.register(this, chat)
        Disposer.register(this, canvas)
        Disposer.register(this, session.subscribe { updateControls(); summariesChanged() })
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
        if (state.revision != previousRevision) session.stop()
        renderCanvas()
        updateControls()
        summariesChanged()
        return previousNotes - state.notes.size
    }

    fun highlight(paths: Set<String>) { canvas.highlight(paths) }

    val canSummarize: Boolean get() = !disposed && !state.refreshing && !session.busy && state.snapshot.files.isNotEmpty()
    private var autoSummarized = -1

    fun summarize() { if (canSummarize) session.send("/summaries") }

    /** Opening the Summaries tab starts a missing tree once per revision; a failed or stopped run waits for the button. */
    fun summarizeOnOpen() {
        if (!canSummarize || state.summaries.complete || autoSummarized == state.revision) return
        autoSummarized = state.revision
        summarize()
    }

    private fun updateControls() {
        describe.isEnabled = !state.refreshing && !session.busy && state.snapshot.files.isNotEmpty()
        flowsButton.isEnabled = describe.isEnabled
        val paths = state.scopePaths
        scopeLabel.text = if (paths == null) "Scope: All changes · ${state.snapshot.files.size} files"
            else "Scope: ${paths.size} files · ${state.snapshot.files.size} currently changed"
        scopeLabel.text += " · ${state.snapshot.comparison.summary}"
        scopeLabel.toolTipText = state.snapshot.comparison.summary + "\n" + state.snapshot.comparison.request.repository.orEmpty() + "\n" +
            (paths?.sorted()?.joinToString("\n") ?: "Follows all files in the selected diff.")
    }

    private fun renderCanvas() { canvas.showNotes(state.notes, state.sources, state.diffs, state.flows); canvas.highlight(selectedPaths().toSet()) }

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

            override suspend fun startSummaries(): String = withContext(Dispatchers.EDT) {
                current()
                // An incomplete tree is continued, so an edit only re-summarizes the changed files and the folders above them.
                if (live.summaries.complete) { live.clearSummaries(); summariesChanged() }
                changeSummariesJson(live.summaries, 0)
            }

            override suspend fun summaries(offset: Int): String = withContext(Dispatchers.EDT) {
                current()
                changeSummariesJson(live.summaries, offset)
            }

            override suspend fun summarize(entries: Map<Int, String>): String = withContext(Dispatchers.EDT) {
                current()
                val next = live.summarize(capturedRevision, entries)
                summariesChanged()
                summarizeResult(next, entries.size)
            }

            override suspend fun currentSummaries(): ChangeSummaries? = withContext(Dispatchers.EDT) {
                if (disposed || live.refreshing || live.revision != capturedRevision) null else live.summaries
            }
        }
    }

    override fun dispose() {
        disposed = true
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
