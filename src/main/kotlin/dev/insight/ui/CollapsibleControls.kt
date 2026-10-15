package dev.insight.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JToggleButton

/** A single compact strip keeps hidden controls discoverable without rebuilding their state. */
internal class CollapsibleControls(project: Project, private val key: String) : JPanel(BorderLayout()) {
    private val preferences = PropertiesComponent.getInstance(project)
    private val strip = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
    private val rows = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    init {
        add(strip, BorderLayout.NORTH)
        add(rows, BorderLayout.CENTER)
    }

    /** A disclosure button and the content it shows or hides. */
    inner class Section internal constructor(private val id: String, private val title: String,
                                             private val content: JComponent, private val expanded: Boolean) {
        private val property = "$key.$id"
        private val button = JToggleButton(title, preferences.getBoolean(property, expanded)).apply {
            margin = JBUI.insets(2, 6)
        }

        /** Active state shown on the button while the section is collapsed, so hidden settings are not silent. */
        var hint: String = ""
            set(value) { if (field != value) { field = value; update() } }

        init {
            button.addActionListener {
                preferences.setValue(property, button.isSelected, expanded)
                update()
            }
            strip.add(button)
            update()
        }

        /** Expands the section (remembered like a click) when it is collapsed. */
        fun reveal() {
            if (!button.isSelected) button.doClick()
        }

        private fun update() {
            val shown = button.isSelected
            content.isVisible = shown
            val note = hint.takeIf { !shown && it.isNotBlank() }
            button.text = "${if (shown) "▾" else "▸"} $title" + note?.let { " · ${it.take(40)}" }.orEmpty()
            button.toolTipText = "${if (shown) "Hide" else "Show"} $title" + note?.let { " ($it)" }.orEmpty()
            button.accessibleContext.accessibleName = button.toolTipText
            content.parent?.revalidate()
            content.parent?.repaint()
        }
    }

    fun section(id: String, title: String, content: JComponent, expanded: Boolean = true): Section {
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
