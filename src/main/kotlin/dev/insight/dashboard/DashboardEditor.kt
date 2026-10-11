package dev.insight.dashboard

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import java.beans.PropertyChangeListener
import javax.swing.JComponent

class DashboardFile : LightVirtualFile("Insight Dashboard")

@Service(Service.Level.PROJECT)
class DashboardSession {
    val file = DashboardFile()
}

fun openDashboard(project: Project) {
    FileEditorManager.getInstance(project).openFile(project.service<DashboardSession>().file, true)
}

class OpenDashboardAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) { e.project?.let(::openDashboard) }
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null }
    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
}

class DashboardEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is DashboardFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = DashboardEditor(project, file)
    override fun getEditorTypeId() = "insight-dashboard"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

private class DashboardEditor(project: Project, private val dashboardFile: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val panel = DashboardPanel(project)
    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = panel.range
    override fun getName() = "Insight Dashboard"
    override fun getFile(): VirtualFile = dashboardFile
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = false
    override fun isValid() = dashboardFile.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() = Unit
}
