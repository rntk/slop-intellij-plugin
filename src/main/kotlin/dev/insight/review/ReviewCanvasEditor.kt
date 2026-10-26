package dev.insight.review

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

/** What the canvas editor needs of the review tab that owns the canvas: a diff review or a code review. */
internal interface ReviewCanvasOwner {
    val canvas: JComponent
    val isDisposed: Boolean
    /** The editor's name and the prefix of its tab: "$canvasTitle: name". */
    val canvasTitle: String
    /** The canvas editor was selected. */
    fun canvasSelected()
}

/** The review owns its canvas; closing this editor only detaches the view. */
class ReviewCanvasFile internal constructor(internal val review: ReviewCanvasOwner) : LightVirtualFile(review.canvasTitle) {
    init {
        isWritable = false
        putUserData(FileEditorManagerKeys.FORBID_PREVIEW_TAB, true)
        // A Swing canvas has one parent. It can be moved between editor groups, but not duplicated.
        putUserData(FileEditorManagerKeys.FORBID_TAB_SPLIT, true)
    }

    internal fun renameReview(name: String) {
        isWritable = true
        try { rename(this, "${review.canvasTitle}: $name") } finally { isWritable = false }
    }
}

class ReviewCanvasEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is ReviewCanvasFile && !file.review.isDisposed
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = ReviewCanvasEditor(file as ReviewCanvasFile)
    override fun getEditorTypeId() = "insight-diff-review"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

internal class ReviewCanvasEditor(private val file: ReviewCanvasFile) : UserDataHolderBase(), FileEditor {
    private val panel = JPanel(BorderLayout())

    init { attachCanvas() }

    internal fun attachCanvas() {
        val canvas = file.review.canvas
        canvas.isFocusable = true
        if (canvas.parent !== panel) {
            val previous = canvas.parent
            previous?.remove(canvas)
            previous?.revalidate()
            previous?.repaint()
            panel.add(canvas, BorderLayout.CENTER)
            panel.revalidate()
            panel.repaint()
        }
    }

    override fun selectNotify() {
        attachCanvas()
        file.review.canvasSelected()
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = file.review.canvas
    override fun getName() = file.review.canvasTitle
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = false
    override fun isValid() = file.isValid && !file.review.isDisposed
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() { panel.removeAll() }
}
