package dev.insight.callgraph

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
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.testFramework.LightVirtualFile
import dev.insight.graph.CallGraphResult
import dev.insight.graph.SymbolKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SchematicFile(val result: CallGraphResult, val root: Int) : LightVirtualFile("Schematic: ${result.graph.nodes[root].name}") {
    internal var exploration: ProgressiveSchematic? = null
}

fun openSchematic(project: Project, result: CallGraphResult, root: Int) {
    FileEditorManager.getInstance(project).openFile(SchematicFile(result, root), true)
}

/** Opens the schematic of the selected file. */
class OpenSchematicAction : AnAction() {
    private val log = logger<OpenSchematicAction>()
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
        val service = project.service<CallGraphService>()
        fun showNotice(text: String, type: NotificationType = NotificationType.WARNING) {
            if (project.isDisposed) return
            NotificationGroupManager.getInstance().getNotificationGroup("Insight Schematic")
                .createNotification("Schematic: ${file.name}", text, type).notify(project)
        }
        service.scope.launch(Dispatchers.EDT) {
            try {
                log.info("Opening schematic for ${file.path}")
                val exploration = ProgressiveSchematic.open(project, file)
                val built = exploration?.result
                val node = built?.graph?.nodes?.firstOrNull { it.kind == SymbolKind.FILE && it.filePath == file.path }
                if (node == null) {
                    log.warn("Schematic source is unavailable or unsupported: ${file.path}")
                    showNotice("This file cannot be shown. Check that its language plugin is enabled and the file is included in project content.")
                } else {
                    FileEditorManager.getInstance(project).openFile(SchematicFile(built, node.id).apply {
                        this.exploration = exploration
                    }, true)
                    log.info("Opened schematic for ${file.path}")
                }
            } catch (e: CancellationException) {
                log.info("Schematic cancelled for ${file.path}", e)
                showNotice("Schematic cancelled. Choose Schematic to retry.")
                throw e
            } catch (e: Throwable) {
                if (e is ProcessCanceledException) {
                    log.info("Schematic cancelled for ${file.path}", e)
                    showNotice("Schematic cancelled. Choose Schematic to retry.")
                    throw e
                }
                if (e is ControlFlowException) throw e
                log.warn("Unable to open schematic for ${file.path}", e)
                showNotice("Unable to open schematic: ${e.message ?: e.javaClass.simpleName}. See the IDE log for details.",
                    NotificationType.ERROR)
            }
        }
    }
}
