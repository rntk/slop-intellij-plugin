package dev.insight.codereview

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * The start directory of a code review: a project folder typed or chosen, or the project root. A draft folder is applied
 * explicitly with Load; applying the current folder again refreshes it. Invalid folders are reported through [onError].
 */
internal class CodeScopeControls(
    private val project: Project,
    private val onError: (String) -> Unit,
    private val onScope: (CodeScope) -> Unit,
) : JPanel(BorderLayout(0, JBUI.scale(6))) {
    internal val field = TextFieldWithBrowseButton()
    private var current: CodeScope? = null
    internal val applyButton = JButton("Load").apply { addActionListener { apply() } }
    private val rootButton = JButton("Project root").apply {
        toolTipText = "Review the whole project"
        addActionListener { field.text = ""; apply() }
    }

    init {
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.emptyBottom(8))
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Code Review Folder")
            .withDescription("Choose the project folder whose code to review")
        project.guessProjectDir()?.let { descriptor.withRoots(it) }
        field.addBrowseFolderListener(project, descriptor)
        field.toolTipText = "Project-relative or absolute folder to review; empty for the project root"
        (field.textField as? JBTextField)?.emptyText?.text = "Project root"
        field.textField.addActionListener { apply() }
        field.textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = updateButton()
        })
        add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(JLabel("Folder").apply { labelFor = field }, BorderLayout.WEST)
            add(field, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(JPanel(GridLayout(1, 2, JBUI.scale(8), 0)).apply {
            add(applyButton)
            add(rootButton)
        }, BorderLayout.CENTER)
        updateButton()
    }

    /** Shows the applied scope. */
    fun show(scope: CodeScope) {
        current = scope
        if (!draftIs(scope)) field.text = scope.path
        updateButton()
    }

    /** Validates the draft and applies it; the folder must be project content and not excluded. */
    fun apply() {
        val scope = try {
            ReadAction.compute<CodeScope, RuntimeException> { codeScope(project, field.text) }
        } catch (e: IllegalArgumentException) {
            onError(e.message ?: "Choose a project folder.")
            return
        }
        show(scope)
        onScope(scope)
    }

    private fun draftIs(scope: CodeScope): Boolean {
        val text = field.text.trim().replace('\\', '/').trimEnd('/').removePrefix("./").let { if (it == ".") "" else it }
        return text == scope.path || text == scope.absolutePath.trimEnd('/')
    }

    private fun updateButton() {
        val refresh = current?.let(::draftIs) == true
        applyButton.text = if (refresh) "Refresh" else "Load"
        applyButton.toolTipText = if (refresh) "Reload the files of this folder" else "Review the code in this folder"
    }
}
