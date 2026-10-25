package dev.insight.diff

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import java.awt.Component
import javax.swing.JComponent
import javax.swing.JPanel

/** Collects optional instructions before creating a regenerated summary version. */
internal class ResummaryDialog(parent: Component, node: SummaryNode, recursive: Boolean) : DialogWrapper(parent, false) {
    private val text = JBTextArea(6, 60).apply {
        name = "resummary-instructions"
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Optional custom instructions, e.g. focus on error handling"
    }
    val instructions: String get() = text.text.trim()

    private val heading = node.entryLabel
    private val description = if (recursive) "Regenerate this entry and every entry beneath it."
        else "Regenerate this entry using existing child summaries where available."

    init {
        title = if (recursive) "Resummarize all" else "Resummarize"
        setOKButtonText(title)
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(VerticalLayout(JBUI.scale(8))).apply {
        add(JBLabel(heading))
        add(JBLabel(description))
        add(JBLabel("The result opens as a new summary version."))
        add(JBLabel("Custom instructions (optional):"))
        add(JBScrollPane(text))
    }

    override fun getPreferredFocusedComponent(): JComponent = text
}
