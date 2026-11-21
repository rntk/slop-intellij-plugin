package dev.insight.canvas

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import dev.insight.callgraph.CallGraphService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LOG = logger<CanvasSessionFile>()

/** A new canvas session of several [files]: the first as its root, each other one as a card opened from it. */
internal class LinkedFilesCanvas(val file: CanvasSessionFile, val unavailable: List<String>)

/**
 * Builds a new session rooted at the first of [files] that the canvas can show, with every other one as a whole-file card anchored
 * where the root mentions it, or at its top. Files the canvas cannot read are listed in [LinkedFilesCanvas.unavailable]. Off the EDT.
 */
internal suspend fun prepareLinkedFilesCanvas(project: Project, files: List<VirtualFile>, title: String): LinkedFilesCanvas? {
    val unavailable = mutableListOf<String>()
    var opened: CanvasSessionFile? = null
    var rest = files
    while (opened == null && rest.isNotEmpty()) {
        val root = rest.first()
        rest = rest.drop(1)
        opened = openCanvasSessionFile(project, root, store = null,
            saved = CanvasSession.new(relativePath(project, root.path), "${root.name} · $title"))
        if (opened == null) unavailable += relativePath(project, root.path)
    }
    val session = opened ?: return null
    val model = CanvasSessionModel(session.session)
    val controller = CanvasSessionController(project, session.source, session.exploration, session.root, model)
    try {
        val root = controller.loadRoot()
        for (file in rest) {
            val path = relativePath(project, file.path)
            val anchor = fileConnectionAnchor(root, file.path, 0, controller::node)
            try {
                controller.openFile(CANVAS_ROOT, anchor.startOffset, anchor.endOffset, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is ControlFlowException) throw e
                LOG.info("Linked file $path is not shown on canvas", e)
                unavailable += path
            }
        }
        withContext(Dispatchers.EDT) { session.session = model.session }
    } finally {
        controller.dispose()
    }
    return LinkedFilesCanvas(session, unavailable)
}

/** Opens [files] together in a new canvas session tab, reporting files that could not be shown. */
internal fun openFilesOnCanvas(project: Project, files: List<VirtualFile>, title: String) {
    if (files.isEmpty()) return
    fun notify(text: String, type: NotificationType) {
        if (project.isDisposed) return
        NotificationGroupManager.getInstance().getNotificationGroup("Insight Canvas Session")
            .createNotification("Canvas session: $title", text, type).notify(project)
    }
    project.service<CallGraphService>().scope.launch(Dispatchers.EDT) {
        try {
            val prepared = withBackgroundProgress(project, "Opening ${files.size} files on canvas") {
                withContext(Dispatchers.Default) { prepareLinkedFilesCanvas(project, files, title) }
            }
            if (prepared == null) {
                notify("None of these files can be shown. Check that their language plugins are enabled.", NotificationType.WARNING)
                return@launch
            }
            FileEditorManager.getInstance(project).openFile(prepared.file, true)
            if (prepared.unavailable.isNotEmpty()) {
                notify("Not shown: ${prepared.unavailable.joinToString(", ")}", NotificationType.INFORMATION)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (e is ControlFlowException) throw e
            LOG.warn("Unable to open linked files on canvas", e)
            notify("Unable to open these files on canvas: ${e.message ?: e.javaClass.simpleName}", NotificationType.ERROR)
        }
    }
}
