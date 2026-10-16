package dev.insight.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ide.ActivityTracker

/** Panel-owned command state, shared by its toolbar and overflow presentations. */
internal class PanelCommand(text: String, description: String = text, private val perform: () -> Unit) :
    DumbAwareAction(text, description, null) {
    var text: String = text
        set(value) { if (field != value) { field = value; ActivityTracker.getInstance().inc() } }
    var isEnabled: Boolean = true
        set(value) { if (field != value) { field = value; ActivityTracker.getInstance().inc() } }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    init { templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true) }
    override fun update(e: AnActionEvent) {
        e.presentation.text = text
        e.presentation.isEnabled = isEnabled
    }
    override fun actionPerformed(e: AnActionEvent) { if (isEnabled) perform() }
}
