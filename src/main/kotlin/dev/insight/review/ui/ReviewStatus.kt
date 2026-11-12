package dev.insight.review.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/** Review metadata stays available on hover without taking a row from the document. */
internal fun installReviewStatus(component: JComponent, project: Project?, text: String) {
    component.toolTipText = listOfNotNull(component.toolTipText, text).distinct().joinToString(" · ")
    if (project == null) return
    component.addMouseListener(object : MouseAdapter() {
        override fun mouseEntered(e: MouseEvent) {
            if (!project.isDisposed) StatusBar.Info.set(text, project)
        }

        override fun mouseExited(e: MouseEvent) {
            if (!project.isDisposed) StatusBar.Info.set("", project)
        }
    })
}
