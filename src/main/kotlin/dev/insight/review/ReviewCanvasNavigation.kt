package dev.insight.review

import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import javax.swing.SwingUtilities

/**
 * Opens a review's canvas in the IDE editor area. Splits that are not under the editor's center are left alone, and the canvas
 * is closed in them, since a Swing canvas has one parent.
 */
internal fun openReviewCanvas(project: Project, file: ReviewCanvasFile, focus: Boolean) {
    val manager = FileEditorManagerEx.getInstanceEx(project)
    for (window in manager.windows) {
        val center = (window.owner.layout as? BorderLayout)?.getLayoutComponent(BorderLayout.CENTER)
        if (center == null) {
            window.owner.add(window.tabbedPane.component, BorderLayout.CENTER)
            window.owner.validate()
        } else if (!SwingUtilities.isDescendingFrom(window.tabbedPane.component, center)) {
            if (window.isFileOpen(file)) window.closeFile(file)
            manager.windows.firstOrNull {
                it.owner === window.owner && SwingUtilities.isDescendingFrom(it.tabbedPane.component, center)
            }?.setAsCurrentWindow(false)
        }
    }
    manager.openFile(file, focus).filterIsInstance<ReviewCanvasEditor>().firstOrNull()?.attachCanvas()
}

