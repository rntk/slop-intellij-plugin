package dev.insight.canvas

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.testFramework.LightVirtualFile
import dev.insight.agent.ChatBackend
import dev.insight.agent.projectContextTools
import dev.insight.callgraph.CallGraphService
import dev.insight.callgraph.ProgressiveSchematic
import dev.insight.chat.LlmChatBackend
import dev.insight.graph.SymbolKind
import dev.insight.llm.CompletionFn
import dev.insight.settings.ChatSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The ports a canvas session talks to: where sessions are saved ([store], null when the project has none), the model
 * that writes summaries ([complete], created per summary) and each card's chat backend. Tests pass fakes.
 */
internal class CanvasSessionServices(
    val store: CanvasSessionStore?,
    val complete: () -> CompletionFn,
    val summaryTools: () -> List<dev.insight.agent.ChatTool> = { emptyList() },
    val chatBackend: (CanvasSessionModel, String) -> ChatBackend,
) {
    companion object {
        fun forProject(project: Project) = CanvasSessionServices(
            CanvasSessionStore.forProject(project),
            { ChatSettings.getInstance().createClient()::complete },
            { projectContextTools(project) },
        ) { model, cardId ->
            LlmChatBackend(ChatSettings.getInstance().createClient()::complete, projectContextTools(project), canvasChatPrompt,
                maxContextChars = 64_000, maxToolRounds = 30, context = { canvasChainContext(model.session, cardId) },
                contextLabel = "Canvas chain")
        }
    }
}

/** A canvas session of [source] in an editor tab: its graph [exploration] with FILE symbol [root], and the [session] shown. */
internal class CanvasSessionFile(
    val source: VirtualFile,
    val rootPath: String,
    val exploration: ProgressiveSchematic,
    val root: Int,
    session: CanvasSession,
) : LightVirtualFile("Canvas: ${source.name}") {
    @Volatile var session: CanvasSession = session
}

/** Opens a canvas session of the selected file: the most recent saved one, else a new one. */
class OpenCanvasSessionAction : AnAction() {
    private val log = logger<OpenCanvasSessionAction>()
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = project != null && file != null && !file.isDirectory &&
            ProjectFileIndex.getInstance(project).isInContent(file)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val manager = FileEditorManager.getInstance(project)
        manager.openFiles.filterIsInstance<CanvasSessionFile>().firstOrNull { it.source == file }?.let {
            manager.openFile(it, true)
            return
        }
        fun showNotice(text: String, type: NotificationType = NotificationType.WARNING) {
            if (project.isDisposed) return
            NotificationGroupManager.getInstance().getNotificationGroup("Insight Canvas Session")
                .createNotification("Canvas session: ${file.name}", text, type).notify(project)
        }
        project.service<CallGraphService>().scope.launch(Dispatchers.EDT) {
            try {
                log.info("Opening canvas session for ${file.path}")
                val opened = withBackgroundProgress(project, "Opening canvas session: ${file.name}") { openCanvasSessionFile(project, file) }
                if (opened == null) {
                    log.warn("Canvas session source is unavailable or unsupported: ${file.path}")
                    showNotice("This file cannot be shown. Check that its language plugin is enabled and the file is included in project content.")
                } else {
                    manager.openFile(opened, true)
                    log.info("Opened canvas session ${opened.session.id} for ${file.path}")
                }
            } catch (e: CancellationException) {
                log.info("Canvas session cancelled for ${file.path}", e)
                showNotice("Canvas session cancelled. Choose Canvas Session to retry.")
                throw e
            } catch (e: Throwable) {
                if (e is ProcessCanceledException) {
                    log.info("Canvas session cancelled for ${file.path}", e)
                    showNotice("Canvas session cancelled. Choose Canvas Session to retry.")
                    throw e
                }
                if (e is ControlFlowException) throw e
                log.warn("Unable to open canvas session for ${file.path}", e)
                showNotice("Unable to open canvas session: ${e.message ?: e.javaClass.simpleName}. See the IDE log for details.",
                    NotificationType.ERROR)
            }
        }
    }
}

/**
 * The canvas session of [file]: its graph, and the most recent saved session of it from [store] or a new one; null
 * when the file cannot be shown. Off the EDT.
 */
internal suspend fun openCanvasSessionFile(project: Project, file: VirtualFile,
    store: CanvasSessionStore? = CanvasSessionStore.forProject(project), saved: CanvasSession? = null): CanvasSessionFile? {
    val exploration = ProgressiveSchematic.open(project, file) ?: return null
    val root = exploration.result.graph.nodes.firstOrNull { it.kind == SymbolKind.FILE && it.filePath == file.path } ?: return null
    val rootPath = relativePath(project, file.path)
    val session = saved ?: withContext(Dispatchers.IO) {
        store?.list(rootPath)?.firstNotNullOfOrNull { store.load(it.id) }
    } ?: CanvasSession.new(rootPath)
    return CanvasSessionFile(file, rootPath, exploration, root.id, session)
}

/** Called on the EDT after loading a saved session; reuse its file's tab when it is already open. */
internal suspend fun showSavedCanvasSession(project: Project, source: VirtualFile, session: CanvasSession): Boolean {
    val manager = FileEditorManager.getInstance(project)
    val existing = manager.openFiles.filterIsInstance<CanvasSessionFile>().firstOrNull { it.source == source }
    if (existing != null) {
        manager.openFile(existing, true)
        manager.getEditors(existing).filterIsInstance<CanvasSessionEditor>().forEach { it.selectSession(session) }
        return true
    }
    val opened = withContext(Dispatchers.Default) { openCanvasSessionFile(project, source, saved = session) } ?: return false
    manager.openFile(opened, true)
    return true
}

class CanvasSessionEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is CanvasSessionFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        CanvasSessionEditor(project, file as CanvasSessionFile, CanvasSessionServices.forProject(project))
    override fun getEditorTypeId() = "insight-canvas-session"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/** Shows one session of the file at a time; switching sessions replaces the panel. */
private class CanvasSessionEditor(private val project: Project, private val file: CanvasSessionFile,
    private val services: CanvasSessionServices) : UserDataHolderBase(), FileEditor {
    private val holder = JPanel(BorderLayout())
    private var panel = create(file.session)
    private var disposed = false

    fun selectSession(session: CanvasSession) {
        if (panel.model.session.id != session.id) switchTo(session)
    }

    private fun create(session: CanvasSession) = CanvasSessionPanel(project, file, session, services, ::switchTo).also {
        holder.add(it, BorderLayout.CENTER)
    }

    private fun switchTo(session: CanvasSession) {
        if (disposed) return
        panel.dispose()
        holder.remove(panel)
        file.session = session
        panel = create(session)
        holder.revalidate()
        holder.repaint()
    }

    override fun getComponent(): JComponent = holder
    override fun getPreferredFocusedComponent(): JComponent = panel.canvas
    override fun getName() = "Canvas Session"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = false
    override fun isValid() = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() {
        disposed = true
        panel.dispose()
    }
}
