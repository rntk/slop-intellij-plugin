package dev.insight.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** Native toolbar overflow keeps commands and section toggles reachable in narrow tool windows. */
internal class CollapsibleControls(project: Project, private val key: String, target: JComponent) : JPanel(BorderLayout()) {
    private val preferences = PropertiesComponent.getInstance(project)
    private val actions = DefaultActionGroup()
    private val toolbar = ActionManager.getInstance().createActionToolbar(key, actions, true).apply {
        setTargetComponent(target)
        setLayoutStrategy(ToolbarLayoutStrategy.AUTOLAYOUT_STRATEGY)
    }
    private val rows = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    init {
        add(toolbar.component, BorderLayout.NORTH)
        add(rows, BorderLayout.CENTER)
    }

    fun command(action: AnAction) { actions.add(action) }
    fun separator() { actions.addSeparator() }

    inner class Section internal constructor(id: String, private val title: String,
                                             private val content: JComponent, private val expanded: Boolean) {
        private val property = "$key.$id"
        private var shown = preferences.getBoolean(property, expanded)
        var hint: String = ""
            set(value) {
                if (field != value) {
                    field = value
                    toolbar.updateActionsAsync()
                }
            }
        private val action = object : DumbAwareToggleAction(title) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            init { templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true) }
            override fun isSelected(e: AnActionEvent) = shown
            override fun setSelected(e: AnActionEvent, state: Boolean) { setShown(state) }
            override fun update(e: AnActionEvent) {
                super.update(e)
                val note = hint.takeIf { !shown && it.isNotBlank() }
                e.presentation.text = title + note?.let { " · ${it.take(40)}" }.orEmpty()
                e.presentation.description = "${if (shown) "Hide" else "Show"} $title" + note?.let { " ($it)" }.orEmpty()
            }
        }

        init {
            actions.add(action)
            content.isVisible = shown
        }

        fun reveal() { setShown(true) }

        private fun setShown(value: Boolean) {
            if (shown == value) return
            shown = value
            preferences.setValue(property, shown, expanded)
            content.isVisible = shown
            content.parent?.revalidate()
            content.parent?.repaint()
            toolbar.updateActionsAsync()
        }
    }

    fun section(id: String, title: String, content: JComponent, expanded: Boolean = false): Section {
        val wrapper = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(4)
            add(content, BorderLayout.CENTER)
        }
        rows.add(wrapper)
        return toggle(id, title, wrapper, expanded)
    }

    fun toggle(id: String, title: String, content: JComponent, expanded: Boolean = true): Section =
        Section(id, title, content, expanded)
}
