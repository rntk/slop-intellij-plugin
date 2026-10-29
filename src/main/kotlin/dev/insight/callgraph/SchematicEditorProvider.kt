package dev.insight.callgraph

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.beans.PropertyChangeListener
import javax.swing.JComponent

class SchematicEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is SchematicFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = SchematicEditor(project, file as SchematicFile)
    override fun getEditorTypeId() = "insight-schematic"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

private class SchematicEditor(project: Project, private val file: SchematicFile) : UserDataHolderBase(), FileEditor {
    private val panel = SchematicPanel(project, file)
    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = panel.canvas
    override fun getName() = "Schematic"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = false
    override fun isValid() = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() = panel.dispose()
}
