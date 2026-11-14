package dev.insight.codereview

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

private val LOG = logger<CodeScopeControls>()

/**
 * The scope of a code review: a project folder typed or chosen, the project root, or an entry file whose dependencies make up
 * the scope, and the patterns of files and folders to exclude from it. A draft is applied explicitly with Load; applying the
 * current one again refreshes it. Invalid paths and patterns are reported through [onError].
 */
internal class CodeScopeControls(
    private val project: Project,
    private val onError: (String) -> Unit,
    private val onScope: (CodeScope) -> Unit,
) : JPanel(BorderLayout(0, JBUI.scale(6))) {
    internal val field = TextFieldWithBrowseButton()
    internal val excludeField = JBTextField()
    private var current: CodeScope? = null
    internal val applyButton = JButton("Load").apply { addActionListener { apply() } }
    private val rootButton = JButton("Project root").apply {
        toolTipText = "Review the whole project"
        addActionListener { field.text = ""; apply() }
    }

    init {
        val descriptor = FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor()
            .withTitle("Code Review Scope")
            .withDescription("Choose a project folder to review, or an entry file to review with the files it depends on")
        project.guessProjectDir()?.let { descriptor.withRoots(it) }
        field.addBrowseFolderListener(project, descriptor)
        field.toolTipText = "Project-relative or absolute folder to review, or an entry file to review with the project files it depends on " +
            "(imports, calls and references, transitively); empty for the project root"
        (field.textField as? JBTextField)?.emptyText?.text = "Project root"
        excludeField.emptyText.text = "Nothing excluded, e.g. *.js, test/, src/legacy, web/**/*.min.css"
        excludeField.toolTipText = "<html>Files and folders to leave out, separated by commas:<br>" +
            "a name such as <code>*.js</code>, <code>test</code> or <code>generated/</code> matches at any depth;<br>" +
            "a path such as <code>src/legacy</code> or <code>web/**/*.js</code> matches from the project root or the folder;<br>" +
            "a trailing <code>/</code> matches folders only, <code>**</code> spans folders. An excluded folder excludes everything in it.</html>"
        for (text in listOf(field.textField, excludeField)) {
            text.addActionListener { apply() }
            text.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = updateButton()
            })
        }
        // Labels in one column so both fields start at the same x; the buttons line up with the fields at their natural width.
        val rows = listOf(JLabel("Folder or file") to field, JLabel("Exclude") to excludeField)
        add(JPanel(GridBagLayout()).apply {
            rows.forEachIndexed { index, (label, input) ->
                label.labelFor = input
                add(label, GridBagConstraints().apply {
                    gridx = 0; gridy = index
                    anchor = GridBagConstraints.WEST
                    insets = JBUI.insets(0, 0, 6, 8)
                })
                add(input, GridBagConstraints().apply {
                    gridx = 1; gridy = index; weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    insets = JBUI.insetsBottom(6)
                })
            }
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(applyButton)
                add(Box.createHorizontalStrut(JBUI.scale(8)))
                add(rootButton)
            }, GridBagConstraints().apply {
                gridx = 1; gridy = rows.size; weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                insets = JBUI.insetsTop(4)
            })
        }, BorderLayout.NORTH)
        updateButton()
    }

    /** Shows the applied scope. */
    fun show(scope: CodeScope) {
        current = scope
        if (!pathIs(scope)) field.text = scope.path
        if (draftExcludes() != scope.excludes) excludeField.text = CodeExclusions.format(scope.excludes)
        updateButton()
    }

    /** Validates the draft and applies it; the folder or file must be project content and not excluded. */
    fun apply() {
        val scope = try {
            val excludes = CodeExclusions.parse(excludeField.text)
            ReadAction.compute<CodeScope, RuntimeException> { codeScope(project, field.text) }.copy(excludes = excludes)
        } catch (e: IllegalArgumentException) {
            LOG.debug("Invalid code review scope", e)
            onError(e.message ?: "Choose a project folder or file.")
            return
        }
        show(scope)
        onScope(scope)
    }

    private fun pathIs(scope: CodeScope): Boolean {
        val text = field.text.trim().replace('\\', '/').trimEnd('/').removePrefix("./").let { if (it == ".") "" else it }
        return text == scope.path || text == scope.absolutePath.trimEnd('/')
    }

    /** The draft patterns; null while they are invalid. */
    private fun draftExcludes(): List<String>? = runCatching { CodeExclusions.parse(excludeField.text) }
        .onFailure { LOG.debug("Exclude draft is invalid", it) }.getOrNull()

    private fun draftIs(scope: CodeScope): Boolean = pathIs(scope) && draftExcludes() == scope.excludes

    private fun updateButton() {
        val refresh = current?.let(::draftIs) == true
        applyButton.text = if (refresh) "Refresh" else "Load"
        applyButton.toolTipText = when {
            refresh && current?.entry == true -> "Find the dependencies of this file again and reload them"
            refresh -> "Reload the files of this folder"
            else -> "Review the code in this folder, or this file and its dependencies, without the excluded files"
        }
    }
}
