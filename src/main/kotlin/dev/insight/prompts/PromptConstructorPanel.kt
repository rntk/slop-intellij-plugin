package dev.insight.prompts

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import javax.swing.AbstractAction
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.Timer
import javax.swing.event.DocumentEvent

private const val WINDOW = "Prompt Constructor"

class PromptConstructorToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = PromptConstructorPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

class OpenPromptConstructorAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { ToolWindowManager.getInstance(it).getToolWindow(WINDOW)?.activate(null) }
    }
}

internal class PromptConstructorPanel(private val project: Project) : JPanel(BorderLayout(0, 8)), Disposable {
    private val state = project.service<PromptConstructorState>().state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    private var root = Path.of(state.libraryPath?.takeIf { it.isNotBlank() }
        ?: Path.of(project.basePath ?: System.getProperty("user.home"), ".insight", "prompt-library").toString())
    private var items = emptyList<PromptItem>()
    private val search = JBTextField().apply { emptyText.text = "Search titles, tags and content" }
    private val category = ComboBox(arrayOf("All", "Prompts", "Appendices"))
    private val model = DefaultListModel<PromptItem>()
    private val library = JBList(model).apply { selectionMode = ListSelectionModel.SINGLE_SELECTION }
    private val itemText = JBTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val editor = JBTextArea(state.draft.orEmpty()).apply { lineWrap = true; wrapStyleWord = true }
    private val preview = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        isEditable = false
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
    }
    private val tabs = JBTabbedPane()
    private val counts = JLabel()
    private val status = JLabel(" ")
    private val libraryLocation = JLabel()
    private val previewTimer = Timer(250) { updatePreview() }.apply { isRepeats = false }
    private val operationButtons = mutableListOf<JButton>()

    init {
        border = JBUI.Borders.empty(10)
        add(JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JLabel(WINDOW))
                add(button("Choose library…") { chooseLibrary() })
                add(button("Refresh") { reload() })
                add(button("Add starter templates") { seed() })
            }, BorderLayout.NORTH)
            add(libraryLocation, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        val left = JPanel(BorderLayout(0, 8)).apply {
            add(JPanel(BorderLayout(4, 0)).apply { add(search); add(category, BorderLayout.EAST) }, BorderLayout.NORTH)
            add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(library), JBScrollPane(itemText)).apply {
                resizeWeight = 0.5
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(button("Append") { selected()?.let { editor.text = appendPrompt(editor.text, it.content) } })
                add(button("Insert at cursor") { selected()?.let { editor.replaceSelection(it.content); editor.requestFocusInWindow() } })
                add(button("Replace") { selected()?.let { if (discardDraft()) editor.text = it.content } })
                add(button("Delete…") { deleteSelected() })
            }, BorderLayout.SOUTH)
        }
        tabs.addTab("Edit", JBScrollPane(editor))
        tabs.addTab("Preview", JBScrollPane(preview))
        tabs.addChangeListener { updatePreview() }
        val right = JPanel(BorderLayout(0, 8)).apply {
            add(counts, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(button("Copy prompt") { copy() })
                add(button("Save to library…") { save() })
                add(button("Export Markdown…") { export() })
                add(button("Clear") { if (discardDraft()) editor.text = "" })
            }, BorderLayout.SOUTH)
        }
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right).apply { resizeWeight = 0.35 }, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        search.document.addDocumentListener(change { filter() })
        category.addActionListener { filter() }
        library.addListSelectionListener {
            itemText.text = selected()?.let { "${it.title}\n${it.description}\n${it.tags}\n\n${it.content}" }.orEmpty()
            itemText.caretPosition = 0
        }
        editor.document.addDocumentListener(change {
            state.draft = editor.text
            updateCounts()
            previewTimer.restart()
        })
        for (key in listOf("ctrl ENTER", "meta ENTER")) {
            editor.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), "copyPrompt")
        }
        editor.actionMap.put("copyPrompt", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = copy()
        })
        updateCounts()
        reload()
    }

    private fun button(label: String, action: () -> Unit) = JButton(label).apply {
        addActionListener { action() }
        operationButtons += this
    }

    private fun selected() = library.selectedValue
    private fun filter() {
        val selected = selected()
        model.clear()
        items.filter { it.matches(search.text.trim()) && when (category.selectedIndex) {
            1 -> it.kind == PromptKind.PROMPT
            2 -> it.kind == PromptKind.APPENDIX
            else -> true
        } }.forEach(model::addElement)
        if (selected != null) library.setSelectedValue(selected, true)
    }

    private fun chooseLibrary() {
        FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Choose Prompt Library")
            .withDescription("Choose a folder containing prompts/ and appendices/"), project, null)?.let {
            root = Path.of(it.path)
            state.libraryPath = root.toString()
            reload()
        }
    }

    private fun reload() {
        libraryLocation.text = root.toString()
        libraryLocation.toolTipText = "Library folder containing prompts/ and appendices/"
        val repository = PromptLibrary(root)
        work("Loading library…", { repository.list() }) {
            items = it
            filter()
            status.text = "${items.size} library items · Ctrl/⌘+Enter to copy"
        }
    }

    private fun save() {
        val kind = ComboBox(PromptKind.entries.toTypedArray()).apply { selectedItem = selected()?.kind ?: PromptKind.PROMPT }
        val name = JBTextField(selected()?.filename ?: "my-prompt.md", 28)
        val form = JPanel(BorderLayout(8, 8)).apply {
            add(kind, BorderLayout.NORTH)
            add(name, BorderLayout.CENTER)
        }
        val dialog = object : DialogWrapper(project) {
            init { title = "Save to Prompt Library"; setOKButtonText("Save"); init() }
            override fun createCenterPanel(): JComponent = form
        }
        if (!dialog.showAndGet()) return
        val filename = try { PromptLibrary.safeFilename(name.text) } catch (e: IllegalArgumentException) {
            status.text = e.message; return
        }
        val type = kind.selectedItem as PromptKind
        val overwrite = items.any { it.kind == type && it.filename == filename }
        if (overwrite && Messages.showYesNoDialog(project, "Replace ${type.folder}/$filename with the workbench text?",
                "Overwrite Library Item", Messages.getQuestionIcon()) != Messages.YES) return
        val content = editor.text
        val repository = PromptLibrary(root)
        work("Saving…", { repository.save(type, filename, content, overwrite) }) { reload() }
    }

    private fun deleteSelected() {
        val item = selected() ?: return
        if (Messages.showYesNoDialog(project, "Delete ${item.kind.folder}/${item.filename}?", "Delete Library Item", Messages.getQuestionIcon()) != Messages.YES) return
        val repository = PromptLibrary(root)
        work("Deleting…", { repository.delete(item) }) { reload() }
    }

    private fun export() {
        val file = FileChooserFactory.getInstance().createSaveFileDialog(
            FileSaverDescriptor("Export Prompt", "Save the workbench as Markdown", "md"), project).save("prompt.md") ?: return
        val content = editor.text
        work("Exporting…", { Files.writeString(file.file.toPath(), content) }) { status.text = "Exported ${it.fileName}" }
    }

    private fun copy() {
        CopyPasteManager.getInstance().setContents(StringSelection(editor.text))
        status.text = "Prompt copied"
    }

    private fun discardDraft() = editor.text.isBlank() || Messages.showYesNoDialog(
        project, "Discard the current workbench text?", "Replace Draft", Messages.getQuestionIcon()) == Messages.YES

    private fun seed() {
        val repository = PromptLibrary(root)
        work("Adding starter templates…", {
            for ((kind, filename, text) in listOf(
                Triple(PromptKind.PROMPT, "code-review.md", "# Code Review\n\nReview the supplied code for correctness, security and maintainability. Cite file paths and lines for each finding."),
                Triple(PromptKind.APPENDIX, "concise.md", "### Output\n\nKeep the response concise. Prioritize actionable findings and include a brief explanation of each."),
                Triple(PromptKind.APPENDIX, "json-output.md", "### Output format\n\nReturn valid JSON only, without Markdown fences or introductory text."),
            )) {
                try { repository.save(kind, filename, text) } catch (_: FileAlreadyExistsException) { /* Preserve user files. */ }
            }
        }) { reload() }
    }

    private fun updateCounts() {
        val text = editor.text
        val words = Regex("\\S+").findAll(text).count()
        counts.text = "$words words · ${text.length} characters · ~${(text.length + 3) / 4} tokens (estimate)"
    }

    private fun updatePreview() {
        if (tabs.selectedIndex != 1) return
        preview.text = promptPreview(editor.text)
        preview.caretPosition = 0
    }

    private fun <T> work(message: String, task: () -> T, done: (T) -> Unit) {
        status.text = message
        operationButtons.forEach { it.isEnabled = false }
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { task() }
                operationButtons.forEach { it.isEnabled = true }
                done(result)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                operationButtons.forEach { it.isEnabled = true }
                status.text = if (e is FileAlreadyExistsException) "That filename already exists. Save with a new name." else "Could not complete operation: ${e.message}"
                status.toolTipText = status.text
            }
        }
    }

    override fun dispose() { previewTimer.stop(); scope.cancel() }
}

private fun change(action: () -> Unit) = object : DocumentAdapter() {
    override fun textChanged(e: DocumentEvent) = action()
}
