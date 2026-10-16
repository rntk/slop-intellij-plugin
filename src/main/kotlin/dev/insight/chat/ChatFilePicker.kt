package dev.insight.chat

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.text.Position

/** Shared composer control for regular, chart and scoped chats. All UI state stays on the EDT. */
internal class ChatFilePicker(project: Project, private val input: JBTextArea) : JPanel(BorderLayout(0, 4)), Disposable {
    private val search = project.service<ChatFileSearch>()
    private val scope = CoroutineScope(search.scope.coroutineContext + SupervisorJob(search.scope.coroutineContext[Job]) + Dispatchers.EDT)
    private val query = JBTextField().apply {
        emptyText.text = "Search project files…"
        toolTipText = "File name or relative path · ↑/↓ to select · Enter to insert · Esc to close"
        accessibleContext.accessibleName = "Search project files"
    }
    private val model = DefaultListModel<String>()
    private val results = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 5
        emptyText.text = "Type a file name or path"
    }
    private val hint = JLabel("↑/↓ to select · Enter to insert · Esc to close")
    private var job: Job? = null
    private var generation = 0
    private var target: Pair<Position, Position>? = null
    private var expected = ""
    private var disposed = false
    private val inputListener = createInputListener()
    private val escape = KeyStroke.getKeyStroke("ESCAPE")
    private val originalEscape = input.getInputMap(JComponent.WHEN_FOCUSED).get(escape)

    init {
        isVisible = false
        add(JPanel(BorderLayout(4, 0)).apply {
            add(query, BorderLayout.CENTER)
            add(JButton("×").apply {
                toolTipText = "Close file search (Esc)"
                accessibleContext.accessibleName = "Close file search"
                addActionListener { close() }
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(JBScrollPane(results).apply { preferredSize = Dimension(JBUI.scale(240), JBUI.scale(110)) }, BorderLayout.CENTER)
        add(hint, BorderLayout.SOUTH)
        query.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { if (isVisible) updateResults() }
        })
        input.document.addDocumentListener(inputListener)
        bind(this, "ESCAPE", "closeFiles", JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT) { close() }
        listOf(query, results).forEach { component ->
            bind(component, "ESCAPE", "closeFiles") { close() }
            bind(component, "ENTER", "insertFile") { insertSelected() }
        }
        bind(query, "DOWN", "nextFile") { moveSelection(1) }
        bind(query, "UP", "previousFile") { moveSelection(-1) }
        results.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = results.locationToIndex(e.point)
                if (SwingUtilities.isLeftMouseButton(e) && index >= 0 && results.getCellBounds(index, index).contains(e.point)) {
                    results.selectedIndex = index
                    insertSelected()
                }
            }
        })
    }

    private fun createInputListener(): DocumentAdapter = object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) {
            if (disposed || isVisible || e.type != DocumentEvent.EventType.INSERT || e.length != 1) return
            val offset = e.offset
            if (e.document.getText(offset, 1) != "@") return
            // Leave email addresses alone. A standalone @ opens the same search as the button.
            if (offset > 0 && !e.document.getText(offset - 1, 1).single().let { it.isWhitespace() || it in "([{\"'" }) return
            val marker = e.document.createPosition(offset)
            SwingUtilities.invokeLater {
                if (!disposed && !isVisible && marker.offset < input.document.length && input.document.getText(marker.offset, 1) == "@") {
                    open(marker.offset, marker.offset + 1)
                }
            }
        }
    }

    fun open(start: Int = input.selectionStart, end: Int = input.selectionEnd) {
        if (disposed) return
        target = input.document.createPosition(start) to input.document.createPosition(end)
        expected = input.document.getText(start, end - start)
        isVisible = true
        bind(input, "ESCAPE", "closeFiles") { close() }
        query.text = ""
        updateResults()
        revalidate()
        query.requestFocusInWindow()
    }

    fun close() {
        generation++
        job?.cancel()
        target = null
        isVisible = false
        restoreEscape()
        revalidate()
        input.requestFocusInWindow()
    }

    private fun updateResults() {
        job?.cancel()
        val request = ++generation
        val text = query.text
        model.clear()
        results.emptyText.text = "Searching…"
        hint.text = "↑/↓ to select · Enter to insert · Esc to close"
        job = scope.launch {
            try {
                delay(75)
                val paths = search.search(text)
                if (request != generation || !isVisible || disposed) return@launch
                model.addAll(paths)
                results.emptyText.text = "No matching project files"
                if (paths.isNotEmpty()) results.selectedIndex = 0
                if (paths.size == 50) hint.text = "Showing top 50 · Type more to narrow · Esc to close"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger<ChatFilePicker>().warn("Project file search failed", e)
                if (request == generation && isVisible && !disposed) results.emptyText.text = "File search unavailable; try again"
            }
        }
    }

    private fun moveSelection(delta: Int) {
        if (model.isEmpty) return
        results.selectedIndex = (results.selectedIndex + delta).coerceIn(0, model.size - 1)
        results.ensureIndexIsVisible(results.selectedIndex)
    }

    private fun insertSelected() {
        val path = results.selectedValue ?: return
        val (from, to) = target ?: return
        val start = from.offset
        val end = to.offset
        // A changed draft must never replace unrelated text using an old selection.
        if (input.document.getText(start, end - start) != expected) { close(); return }
        val suffix = if (end == input.document.length || !input.document.getText(end, 1).single().isWhitespace()) " " else ""
        close()
        input.select(start, end)
        input.replaceSelection(path + suffix)
        input.requestFocusInWindow()
    }

    override fun dispose() {
        disposed = true
        generation++
        scope.cancel()
        input.document.removeDocumentListener(inputListener)
        restoreEscape()
    }

    private fun restoreEscape() {
        val map = input.getInputMap(JComponent.WHEN_FOCUSED)
        if (map.get(escape) != "closeFiles") return
        if (originalEscape == null) map.remove(escape) else map.put(escape, originalEscape)
        input.actionMap.remove("closeFiles")
    }

    private fun bind(component: JComponent, key: String, name: String, condition: Int = JComponent.WHEN_FOCUSED, action: () -> Unit) {
        component.getInputMap(condition).put(KeyStroke.getKeyStroke(key), name)
        component.actionMap.put(name, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = action()
        })
    }
}
