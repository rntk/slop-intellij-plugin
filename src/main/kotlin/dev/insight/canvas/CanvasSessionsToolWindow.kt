package dev.insight.canvas

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import dev.insight.callgraph.CallGraphService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.Timer
import javax.swing.event.DocumentEvent

internal const val CANVAS_SESSIONS_WINDOW = "Canvas Sessions"

class CanvasSessionsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CanvasSessionsPanel(project)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false).apply {
            setDisposer(panel)
            preferredFocusableComponent = panel.search.textEditor
        })
    }
}

/** Project-wide saved sessions, with disk reads off the EDT and filename-only fuzzy filtering. */
internal class CanvasSessionsPanel(private val project: Project) : JPanel(BorderLayout(JBUI.scale(4), JBUI.scale(4))), Disposable {
    private val store = CanvasSessionStore.forProject(project)
    private val scope = project.service<CallGraphService>().scope.let {
        CoroutineScope(it.coroutineContext + SupervisorJob(it.coroutineContext[Job]))
    }
    internal val search = SearchTextField(false).apply {
        textEditor.emptyText.text = "Filter by file name"
        toolTipText = "Fuzzy search by root file name"
    }
    private val listModel = DefaultListModel<CanvasSessionEntry>()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = SimpleListCellRenderer.create<CanvasSessionEntry> { label, entry, _ ->
            label.text = "${entry.rootPath} · ${canvasSessionLabel(entry)}"
        }
    }
    private val status = JLabel()
    private val open = JButton("Open session").apply {
        isEnabled = false
        addActionListener { openSelected() }
    }
    private var entries = emptyList<CanvasSessionEntry>()
    private var refreshing = false
    private var opening = false
    private val refreshTimer = Timer(5_000) { if (isShowing) refresh() }

    init {
        border = JBUI.Borders.empty(8)
        add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(search, BorderLayout.CENTER)
            add(JButton("Refresh").apply { addActionListener { refresh() } }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(status, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { add(open) }, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = filter()
        })
        search.textEditor.addActionListener { openSelected() }
        list.addListSelectionListener { open.isEnabled = list.selectedValue != null && !opening }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && javax.swing.SwingUtilities.isLeftMouseButton(e) &&
                    list.locationToIndex(e.point).let { it >= 0 && list.getCellBounds(it, it).contains(e.point) }) openSelected()
            }
        })
        list.inputMap.put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "openSession")
        list.actionMap.put("openSession", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = openSelected()
        })
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                if (isShowing) {
                    refresh()
                    refreshTimer.start()
                } else refreshTimer.stop()
            }
        }
        refresh()
    }

    private fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch(Dispatchers.EDT) {
            try {
                val saved = withContext(Dispatchers.IO) { store?.list().orEmpty() }
                if (saved != entries) {
                    entries = saved
                    filter()
                } else if (entries.isEmpty()) filter()
            } finally {
                refreshing = false
            }
        }
    }

    private fun filter() {
        val selected = list.selectedValue?.id
        val filtered = filterCanvasSessions(entries, search.text)
        listModel.clear()
        filtered.forEach(listModel::addElement)
        val index = filtered.indexOfFirst { it.id == selected }
        if (index >= 0) list.selectedIndex = index
        else if (filtered.isNotEmpty()) list.selectedIndex = 0
        list.emptyText.text = if (entries.isEmpty()) "No saved canvas sessions" else "No matching file names"
        status.text = "${filtered.size} of ${entries.size} sessions"
    }

    private fun openSelected() {
        val entry = list.selectedValue ?: return
        if (opening) return
        opening = true
        open.isEnabled = false
        scope.launch(Dispatchers.EDT) {
            try {
                val session = withContext(Dispatchers.IO) { store?.load(entry.id) }
                if (session == null) {
                    status.text = "Could not load session. Refresh the list and try again."
                    return@launch
                }
                val source = withContext(Dispatchers.IO) {
                    val path = Path.of(session.rootPath)
                    val absolute = if (path.isAbsolute) path else project.basePath?.let { Path.of(it).resolve(path) }
                    absolute?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
                }
                status.text = when {
                    source == null || source.isDirectory -> "Source file is no longer available: ${session.rootPath}"
                    !showSavedCanvasSession(project, source, session) -> "This file cannot be shown. Check its language plugin."
                    else -> "Opened ${session.title}"
                }
            } catch (e: Throwable) {
                if (e is CancellationException || e is ControlFlowException) throw e
                logger<CanvasSessionsPanel>().warn("Could not open canvas session ${entry.id}", e)
                status.text = "Could not open session. See the IDE log."
            } finally {
                opening = false
                open.isEnabled = list.selectedValue != null
            }
        }
    }

    override fun dispose() {
        refreshTimer.stop()
        scope.cancel()
    }
}
