package dev.insight.canvas

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import dev.insight.callgraph.CallGraphService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LOG = logger<CanvasSessionFile>()

/** A project file paired with an optional 1-based target line to scroll to on the canvas. */
internal data class CanvasLinkedFile(val file: VirtualFile, val line: Int? = null)

/** A new canvas session of several [files]: the first as its root, each other one as a card opened from it. */
internal class LinkedFilesCanvas(val file: CanvasSessionFile, val unavailable: List<String>)

/**
 * Builds a new session rooted at the first of [files] that the canvas can show, with every other one as a whole-file card anchored
 * where the root mentions it, or at its top. Files the canvas cannot read are listed in [LinkedFilesCanvas.unavailable]. Off the EDT.
 */
internal suspend fun prepareLinkedFilesCanvas(
    project: Project,
    files: List<VirtualFile>,
    title: String,
    initialLimit: Int = CANVAS_LOAD_CHUNK,
): LinkedFilesCanvas? = prepareLinkedFilesCanvas(project, files.map { CanvasLinkedFile(it) }, title, initialLimit)

/**
 * Builds a new session rooted at the first of [files] that the canvas can show, preserving each file's 1-based target line when
 * present. Files the canvas cannot read are listed in [LinkedFilesCanvas.unavailable]. Off the EDT.
 */
@JvmName("prepareLinkedFilesWithLinesCanvas")
internal suspend fun prepareLinkedFilesCanvas(
    project: Project,
    files: List<CanvasLinkedFile>,
    title: String,
    initialLimit: Int = CANVAS_LOAD_CHUNK,
): LinkedFilesCanvas? {
    val unavailable = mutableListOf<String>()
    var opened: CanvasSessionFile? = null
    var rootEntry: CanvasLinkedFile? = null
    var rest = files
    while (opened == null && rest.isNotEmpty()) {
        val candidate = rest.first()
        rest = rest.drop(1)
        val root = candidate.file
        opened = openCanvasSessionFile(project, root, store = null,
            saved = CanvasSession.new(relativePath(project, root.path), "${root.name} · $title"))
        if (opened == null) unavailable += relativePath(project, root.path)
        else rootEntry = candidate
    }
    val session = opened ?: return null
    val rootLine0 = rootEntry?.line?.let { (it - 1).coerceAtLeast(0) }
    session.initialLine = rootLine0
    if (rootLine0 != null && rootLine0 > 0) {
        val rootCard = session.session.root
        session.session = session.session.copy(
            cards = listOf(rootCard.copy(symbol = rootCard.symbol?.copy(line = rootLine0))) + session.session.cards.drop(1),
        )
    }
    val model = CanvasSessionModel(session.session)
    val controller = CanvasSessionController(project, session.source, session.exploration, session.root, model)
    val pending = mutableListOf<CanvasPendingFile>()
    try {
        val root = controller.loadRoot()
        var openedCount = 0
        for (entry in rest) {
            val file = entry.file
            val line0 = entry.line?.let { (it - 1).coerceAtLeast(0) } ?: 0
            val path = relativePath(project, file.path)
            if (openedCount >= initialLimit.coerceAtLeast(0)) {
                pending += CanvasPendingFile(path, line = line0)
                continue
            }
            val anchor = fileConnectionAnchor(root, file.path, 0, controller::node)
            try {
                controller.openFile(CANVAS_ROOT, anchor.startOffset, anchor.endOffset, file, line = line0)
                openedCount++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is ControlFlowException) throw e
                LOG.info("Linked file $path is not shown on canvas", e)
                unavailable += path
            }
        }
        withContext(Dispatchers.EDT) { session.session = model.session.copy(pendingFiles = pending) }
    } finally {
        controller.dispose()
    }
    return LinkedFilesCanvas(session, unavailable)
}

/** Opens [files] together in a new canvas session tab, reporting files that could not be shown. */
internal fun openFilesOnCanvas(project: Project, files: List<VirtualFile>, title: String) {
    openLinkedFilesOnCanvas(project, files.map { CanvasLinkedFile(it) }, title)
}

/** Opens [files] together in a new canvas session tab at their 1-based target lines, reporting files that could not be shown. */
internal fun openLinkedFilesOnCanvas(project: Project, files: List<CanvasLinkedFile>, title: String) {
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
            openCanvasSessionTab(project, prepared.file)
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
