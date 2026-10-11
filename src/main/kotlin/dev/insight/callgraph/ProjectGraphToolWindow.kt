package dev.insight.callgraph

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.pom.Navigatable
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*

class ProjectGraphToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ProjectGraphPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "Application graph", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

private class ProjectGraphPanel(private val project: Project) : JPanel(BorderLayout(0, JBUI.scale(8))), Disposable {
    private val service = project.service<CallGraphService>()
    private var result: CallGraphResult? = null
    private var view: DetailGraph? = null
    private val detail = JComboBox(GraphDetail.entries.toTypedArray()).apply { selectedItem = GraphDetail.SYMBOL }
    private var job: Job? = null
    private var disposed = false
    private val status = JLabel("Building application graph…")
    private val activeFile = JLabel()
    private val selection = JLabel("Double-click a node to open its source.")
    private val canvas = CallGraphCanvas(onSelect = { id ->
        val graph = view?.graph
        val node = id?.let { graph?.nodes?.getOrNull(it) }
        selection.text = if (graph != null && node != null) {
            val selected = node
            val outgoing = graph.edges.filter { it.caller == selected.id }.groupingBy { it.kind }.eachCount()
            val incoming = graph.edges.count { it.callee == selected.id }
            "${selected.kind.name.lowercase()} ${selected.name} · $incoming incoming · " +
                outgoing.entries.joinToString { "${it.value} ${it.key.name.lowercase()}" }
        } else "Double-click a node to open its source."
    }, onOpen = { id ->
        val source = view?.sources?.getOrNull(id)
        (source?.let { result?.pointers?.getOrNull(it) }?.element?.navigationElement as? Navigatable)
            ?.takeIf { it.canNavigate() }?.navigate(true)
    })

    init {
        border = JBUI.Borders.empty(8)
        add(JPanel(BorderLayout(0, 4)).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(JLabel("Detail:"))
                add(detail.apply { addActionListener { showDetail() } })
                add(JButton("Refresh").apply { addActionListener { rebuild() } })
                add(JButton("Current file").apply { addActionListener { highlight(); canvas.revealFile() } })
                add(JComboBox(arrayOf("25%", "50%", "75%", "100%", "150%")).apply {
                    selectedItem = "100%"
                    var updating = false
                    addActionListener {
                        if (!updating) canvas.zoom = selectedItem.toString().removeSuffix("%").toDouble() / 100
                    }
                    canvas.addPropertyChangeListener("zoom") {
                        updating = true
                        try { model.setSelectedItem("${kotlin.math.round(canvas.zoom * 100).toInt()}%") }
                        finally { updating = false }
                    }
                })
            }, BorderLayout.NORTH)
            add(activeFile, BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(JBScrollPane(canvas), BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, 4)).apply {
            add(selection, BorderLayout.NORTH)
            add(JLabel("Drag to pan · arrows to pan (Shift: faster) · scroll to zoom"), BorderLayout.CENTER)
            add(JLabel("Filled: current file · outlined: dependencies/users · dashed: imports · dotted: references"), BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        project.messageBus.connect(this).apply {
            subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) { highlight(event.newFile); canvas.revealFile() }
            })
            subscribe(PsiModificationTracker.TOPIC, PsiModificationTracker.Listener { SwingUtilities.invokeLater { rebuild(600) } })
        }
        highlight()
        rebuild()
    }

    private fun highlight(file: VirtualFile? = FileEditorManager.getInstance(project).selectedEditor?.file) {
        canvas.highlightFile(file?.path)
        activeFile.text = file?.let { "Current file: ${it.name}" } ?: "Open a source file to highlight its symbols."
    }

    /** Switching detail reuses the last analysis; only the displayed graph is derived again. */
    private fun showDetail() {
        val built = result ?: return
        val level = detail.selectedItem as GraphDetail
        val shown = built.graph.atDetail(level)
        view = shown
        canvas.show(shown.graph)
        highlight()
        SwingUtilities.invokeLater { if (!disposed) canvas.revealFile() }
        val nodes = if (level == GraphDetail.FILE) "files" else "symbols"
        status.text = "${shown.graph.nodes.size} $nodes · ${shown.graph.edges.size} relationships · project source"
    }

    private fun rebuild(debounce: Long = 0) {
        if (disposed) return
        job?.cancel()
        job = service.scope.launch(Dispatchers.EDT) {
            delay(debounce)
            status.text = "Building application graph…"
            try {
                val built = service.buildProject()
                result = built
                showDetail()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                thisLogger().warn("Application graph failed", e)
                status.text = "Could not build graph. Use Refresh to retry."
            }
        }
    }

    override fun dispose() { disposed = true; job?.cancel() }
}
