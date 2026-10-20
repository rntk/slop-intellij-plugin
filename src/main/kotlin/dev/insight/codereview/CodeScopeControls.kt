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
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.emptyBottom(8))
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
        add(JPanel(GridLayout(2, 1, 0, JBUI.scale(6))).apply {
            add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                add(JLabel("Folder or file").apply { labelFor = field }, BorderLayout.WEST)
                add(field, BorderLayout.CENTER)
            })
            add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                add(JLabel("Exclude").apply { labelFor = excludeField }, BorderLayout.WEST)
                add(excludeField, BorderLayout.CENTER)
            })
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
    private fun draftExcludes(): List<String>? = runCatching { CodeExclusions.parse(excludeField.text) }.getOrNull()

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
