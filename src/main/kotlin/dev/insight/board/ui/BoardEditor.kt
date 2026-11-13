package dev.insight.board.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.insight.agent.ChatMessage
import dev.insight.agent.ChatProfile
import dev.insight.agent.ChatRole
import dev.insight.agent.findSourceFile
import dev.insight.agent.projectContextTools
import dev.insight.board.BOARD_CHAT_TOOLS
import dev.insight.board.BoardAnchor
import dev.insight.board.BoardChange
import dev.insight.board.BoardEditException
import dev.insight.board.BoardModel
import dev.insight.board.BoardOrigin
import dev.insight.board.BoardSaveState
import dev.insight.board.BoardSide
import dev.insight.board.BoardSources
import dev.insight.board.BoardStore
import dev.insight.board.boardAskDraft
import dev.insight.board.boardChatContext
import dev.insight.board.boardChatSystemPrompt
import dev.insight.board.boardMarkdown
import dev.insight.board.boardStatusText
import dev.insight.board.boardToolRegistry
import dev.insight.chat.ChatBackendFactory
import dev.insight.chat.ChatSession
import dev.insight.chat.LlmChatBackend
import dev.insight.settings.ChatSettings
import dev.insight.settings.configuredChatBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.SwingUtilities

/*
 * The explainer board as an editor tab: the rendered board with its toolbar, and beside it the chat whose tools build
 * the board. The board and the chat's questions and answers save to the project's board store shortly after each change
 * and when the tab closes.
 */

private val LOG = logger<BoardEditor>()

/**
 * An explainer board in an editor tab: its [model], the [sources] its tools and links resolve against, what it explains
 * ([origin]) and the [chat] restored from its saved file. [saved] is whether the board already has a file in [store].
 */
class BoardFile(
    val project: Project,
    val model: BoardModel,
    val sources: BoardSources,
    val origin: BoardOrigin?,
    val chat: List<ChatMessage> = emptyList(),
    val store: BoardStore? = BoardStore.of(project),
    val saved: Boolean = false,
) : LightVirtualFile(tabName(model.board.title)) {
    init {
        isWritable = false
    }

    /** Follows the board's title in the tab. */
    internal fun retitle(title: String) {
        isWritable = true
        try { rename(this, tabName(title)) } finally { isWritable = false }
    }
}

private fun tabName(title: String) = "Board: $title"

class BoardEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is BoardFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = BoardEditor(project, file as BoardFile)
    override fun getEditorTypeId() = "insight-board"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/**
 * The board with a toolbar (title, fit, zoom, Markdown export, chat toggle and status) and its chat in a splitter. The
 * chat's tools edit the model on the EDT; links open the code in the IDE.
 */
private class BoardEditor(private val project: Project, private val file: BoardFile) : UserDataHolderBase(), FileEditor {
    private val model = file.model
    private val sources = file.sources
    private val store = file.store
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    private var disposed = false
    private var dirty = false
    private var saveJob: Job? = null
    private var saveState = when {
        store == null -> BoardSaveState.NO_STORE
        file.saved -> BoardSaveState.SAVED
        else -> BoardSaveState.NEW
    }
    private var notice: String? = null

    private val session = object : ChatSession(project, scope, ChatBackendFactory { _ -> backend() }, welcome(), {}, file.chat) {
        override fun replyProgressTitle(text: String) = "Composing the explainer board"
    }
    private var chatMessages = conversationSize()
    private val chat = BoardChatPanel(session)

    private val board = BoardPanel(model, object : BoardPanelActions {
        override fun navigate(anchor: BoardAnchor) = this@BoardEditor.navigate(anchor)
        override fun askAbout(blockId: String) = this@BoardEditor.askAbout(blockId)
        override fun remove(blockId: String) {
            try {
                model.remove(blockId)
            } catch (e: BoardEditException) {
                LOG.debug("Block $blockId was already removed", e)
            }
        }
    })

    private val title = JBLabel(model.board.title).apply {
        font = font.deriveFont(Font.BOLD)
        toolTipText = "Double-click to rename the board"
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) rename()
            }
        })
    }
    private val status = JBLabel()
    private val showChat = JToggleButton("Chat", true).apply { toolTipText = "Show or hide the board's chat" }
    private val splitter = JBSplitter(false, 0.7f).apply {
        firstComponent = JBScrollPane(board).apply { border = JBUI.Borders.empty() }
        secondComponent = chat
    }
    private val panel = JPanel(BorderLayout()).apply {
        add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                add(title)
                add(button("Fit", "Zoom so the whole board fits") { board.zoomToFit() })
                add(button("−", "Zoom out (Ctrl+wheel)") { board.zoom /= ZOOM_STEP })
                add(button("+", "Zoom in (Ctrl+wheel)") { board.zoom *= ZOOM_STEP })
                add(button("Export Markdown", "Copy the board as Markdown to the clipboard") { exportMarkdown() })
                add(showChat)
            }, BorderLayout.WEST)
            add(status, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
    }

    private val modelSubscription = model.subscribe { change ->
        when (change) {
            is BoardChange.TitleChanged, BoardChange.Reset -> retitle()
            // Follow the chat as it builds: show each new block once the panel has laid it out.
            is BoardChange.BlockAdded -> SwingUtilities.invokeLater { if (!disposed) board.focus(change.block.id) }
            else -> {}
        }
        changed()
    }
    private val chatSubscription = session.subscribe {
        val size = conversationSize()
        if (size != chatMessages) {
            chatMessages = size
            changed()
        }
    }

    init {
        showChat.addActionListener { setChatVisible(showChat.isSelected) }
        updateStatus()
    }

    private fun button(text: String, tooltip: String, action: () -> Unit) = JButton(text).apply {
        toolTipText = tooltip
        addActionListener { action() }
    }

    private fun welcome(): String {
        val subject = file.origin?.title?.takeIf { it.isNotBlank() }?.let { " of $it" }.orEmpty()
        val evidence = if (sources.hasComparison) "source, diff and call-chain blocks" else "source and call-chain blocks"
        return "This is an explainer board$subject. Ask what to explain, and I compose a page here: headings, prose and " +
            "callouts around $evidence. The IDE reads the code those blocks show, so it is never written by the model; " +
            "prose is marked as interpretation. Use Ask on a block to question it, and click anchors to open the code. " +
            "Boards are saved in the project and can be reopened from the review's Explainer board tile."
    }

    /** One reply's backend: fresh board tools (their add allowance is per reply), project reading tools and the outline. */
    private fun backend(): LlmChatBackend {
        val registry = boardToolRegistry(model, sources, edit = { change -> withContext(Dispatchers.EDT) { change() } })
        return LlmChatBackend(ChatSettings.getInstance().createClient()::complete,
            projectContextTools(project) + registry.select(*BOARD_CHAT_TOOLS.toTypedArray()),
            boardChatSystemPrompt(sources.hasComparison), configuredChatBudget(ChatProfile.ASSISTANT),
            context = { withContext(Dispatchers.EDT) { boardChatContext(model) } },
            contextLabel = "Current board outline",
            registry = registry)
    }

    private fun conversationSize() = session.messages.count { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }

    private fun setChatVisible(visible: Boolean) {
        showChat.isSelected = visible
        splitter.secondComponent = if (visible) chat else null
        splitter.revalidate()
        splitter.repaint()
    }

    private fun askAbout(blockId: String) {
        val block = model.block(blockId) ?: return
        setChatVisible(true)
        chat.draft(boardAskDraft(block))
    }

    private fun rename() {
        val next = Messages.showInputDialog(project, "Board title:", "Rename Board", null, model.board.title, null) ?: return
        try {
            model.setTitle(next)
        } catch (e: BoardEditException) {
            Messages.showErrorDialog(project, e.message, "Rename Board")
        }
    }

    private fun retitle() {
        val current = model.board.title
        title.text = current
        if (file.name != tabName(current)) {
            file.retitle(current)
            FileEditorManager.getInstance(project).updateFilePresentation(file)
        }
    }

    private fun exportMarkdown() {
        CopyPasteManager.getInstance().setContents(StringSelection(boardMarkdown(model.board)))
        notice = "Markdown copied"
        updateStatus()
    }

    private fun updateStatus() {
        status.text = boardStatusText(model.board.blocks.size, saveState) + notice?.let { " · $it" }.orEmpty()
    }

    // --- saving ---

    private fun changed() {
        if (disposed) return
        notice = null
        if (store != null) {
            dirty = true
            saveState = BoardSaveState.UNSAVED
            saveJob?.cancel()
            saveJob = scope.launch {
                delay(SAVE_DELAY_MS)
                save()
            }
        }
        updateStatus()
    }

    private suspend fun save() {
        val store = store ?: return
        if (!dirty) return
        val snapshot = model.board
        val messages = session.messages
        dirty = false
        saveState = BoardSaveState.SAVING
        updateStatus()
        val written = withContext(Dispatchers.IO) { store.save(snapshot, messages) }
        if (disposed) return
        saveState = when {
            dirty -> BoardSaveState.UNSAVED
            written -> BoardSaveState.SAVED
            else -> BoardSaveState.FAILED
        }
        updateStatus()
    }

    // --- navigation ---

    /**
     * Opens [anchor]'s file at its first line. The IDE has only current files, so a before-side anchor opens the current
     * file with a notice, and a comparison's after side that no longer matches the file opens with a notice too.
     */
    private fun navigate(anchor: BoardAnchor) {
        scope.launch {
            try {
                val target = readAction {
                    val absolute = project.basePath?.let { "$it/${anchor.path}" }
                    findSourceFile(project, anchor.path, absolute)
                }
                if (target == null) {
                    notify("${anchor.path} is not in the project, so it cannot be opened.", NotificationType.WARNING)
                    return@launch
                }
                when {
                    anchor.side == BoardSide.BEFORE -> notify("Lines ${anchor.first}-${anchor.last} are on the before side of the " +
                        "comparison; the current ${target.name} opened instead, where they may differ.", NotificationType.INFORMATION)
                    sources.hasComparison && drifted(target, anchor) -> notify("${target.name} changed since the comparison; " +
                        "lines ${anchor.first}-${anchor.last} may have moved.", NotificationType.INFORMATION)
                }
                if (disposed || project.isDisposed) return@launch
                WriteIntentReadAction.run { OpenFileDescriptor(project, target, anchor.first - 1, 0).navigate(true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Could not open ${anchor.path}", e)
                notify("Could not open ${anchor.path}: ${e.message ?: e.javaClass.simpleName}", NotificationType.WARNING)
            }
        }
    }

    /** Whether the current text of [target] differs from the compared after side at [anchor]. */
    private suspend fun drifted(target: VirtualFile, anchor: BoardAnchor): Boolean {
        val expected = try {
            withContext(Dispatchers.Default) { sources.read(anchor) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.debug("Could not read ${anchor.path} from the board's sources", e)
            return false
        }
        val actual = readAction { FileDocumentManager.getInstance().getDocument(target)?.lines(expected.anchor.first, expected.anchor.last) }
        return actual != expected.text.replace("\r\n", "\n")
    }

    private fun Document.lines(first: Int, last: Int): String? {
        if (first < 1 || last > lineCount) return null
        return getText(com.intellij.openapi.util.TextRange(getLineStartOffset(first - 1), getLineEndOffset(last - 1)))
    }

    private fun notify(text: String, type: NotificationType) {
        if (project.isDisposed) return
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("Explainer board", text, type).notify(project)
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = chat.input
    override fun getName() = "Board"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = false
    override fun isValid() = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() {
        if (disposed) return
        disposed = true
        saveJob?.cancel()
        // The last change is written now; one board file is small, so the EDT may write it while the tab closes.
        if (dirty) store?.save(model.board, session.messages)
        modelSubscription.dispose()
        chatSubscription.dispose()
        session.discard()
        scope.cancel()
        board.dispose()
        chat.dispose()
    }

    private companion object {
        const val SAVE_DELAY_MS = 1_000L
        const val ZOOM_STEP = 1.2
    }
}

/** The notification group of board messages, declared in plugin.xml. */
internal const val NOTIFICATION_GROUP = "Insight Board"
