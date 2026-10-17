package dev.insight.diff

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
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
) : JPanel(BorderLayout()), Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    val state = DiffReviewState(paths)
    private var disposed = false
    private val canvas = NoteCanvas(showDiff, { note -> state.removeNote(note.paths, note.symbol); renderCanvas() }, { symbol ->
        LocalFileSystem.getInstance().findFileByPath(symbol.absolutePath)?.let { file ->
            OpenFileDescriptor(project, file, symbol.offset).navigate(true)
        }
    }, removeSource = { range -> state.removeSource(range); renderCanvas() }, project = project)
    private val describe = JButton("Description")
    private val scopeLabel = JLabel()
    val session = object : ChatSession(project, scope, welcome =
        "Ask about ${if (paths == null) "the current changes" else "the selected files' changes"}, or type /description for brief notes on each file.") {
        override fun createBackend(): ChatBackend = object : ChatBackend {
            override suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit): String {
                val (context, total) = withContext(Dispatchers.EDT) { captureContext() to state.snapshot.files.size }
                val client = ChatSettings.getInstance().createClient()
                val question = messages.lastOrNull()?.text.orEmpty()
                val description = isDescriptionRequest(question)
                // Only this turn carries the description instructions; the transcript keeps the command as typed.
                val request = if (description) messages.dropLast(1) + messages.last().copy(text = descriptionRequest(question)) else messages
                // Code, not the model, checks that a description covers every file in scope.
                val coverage: (suspend () -> String?)? = if (!description) null else suspend {
                    context.uncovered().takeIf { it.isNotEmpty() }?.let { coverageNote(it, total) }
                }
                // Reviews accumulate evidence for many rounds; evict in chunks so the request prefix stays cacheable.
                val reply = LlmChatBackend(client::complete, diffReviewTools(context), diffReviewPrompt(project.name),
                    maxContextChars = 160_000, maxToolRounds = 100, context = { context.state(0, INLINE_PATCH_CHARS) },
                    contextLabel = "Current diff snapshot", evictionTarget = 0.7, followUp = coverage)
                    .reply(request, onToolCall)
                val missing = if (description) context.uncovered() else emptyList()
                return if (missing.isEmpty()) reply
                    else "$reply\n\n[No note for ${missing.size} of $total files: ${missing.take(10).joinToString()}${if (missing.size > 10) ", …" else ""}]"
            }
        }
    }
    private val chat = ChatPanel(project, session, title = "Diff Chat", showDashboard = false)

    init {
        minimumSize = Dimension(300, 200)
        chat.input.emptyText.text = "/description or ask about this tab's changes…"
        add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(scopeLabel, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(describe.apply { addActionListener { session.send("/description") } })
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
        Disposer.register(this, session.subscribe { updateControls() })
        updateControls()
    }

    fun invalidate(stop: Boolean, affectedPaths: Set<String>? = null) {
        if (affectedPaths != null && (affectedPaths.isEmpty() ||
            (state.scopePaths != null && state.scopePaths.none { it in affectedPaths } &&
                state.snapshot.files.none { it.absolutePath in affectedPaths }))) return
        state.refreshing = true
        if (stop) session.stop()
        updateControls()
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

    private fun updateControls() {
        describe.isEnabled = !state.refreshing && !session.busy && state.snapshot.files.isNotEmpty()
        val paths = state.scopePaths
        scopeLabel.text = if (paths == null) "Scope: All changes · ${state.snapshot.files.size} files"
            else "Scope: ${paths.size} files · ${state.snapshot.files.size} currently changed"
        scopeLabel.toolTipText = paths?.sorted()?.joinToString("\n") ?: "Follows all current changes, including files included in the global review."
    }

    private fun renderCanvas() { canvas.showNotes(state.notes, state.sources); canvas.highlight(selectedPaths().toSet()) }

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
