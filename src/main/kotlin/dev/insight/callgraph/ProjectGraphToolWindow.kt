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
    private var hierarchy: GraphHierarchy? = null
    /** Expanded groups by [GraphHierarchy.key], so they survive rebuilds after edits; null until the first build. */
    private var expandedKeys: Set<String>? = null
    private var view: DetailGraph? = null
    private val detail = JComboBox(GraphDetail.entries.toTypedArray()).apply { selectedItem = GraphDetail.OVERVIEW }
    private var job: Job? = null
    private var disposed = false
    private val status = JLabel("Building application graph…")
    private val activeFile = JLabel()
    private val selection = JLabel(HINT)
    private val canvas = CallGraphCanvas(onSelect = ::describe, onOpen = ::activate, popup = ::menu)

    init {
        border = JBUI.Borders.empty(8)
        add(JPanel(BorderLayout(0, 4)).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(JLabel("Detail:"))
                add(detail.apply { addActionListener { resetDetail() } })
                add(JButton("Refresh").apply { addActionListener { rebuild() } })
                add(JButton("Current file").apply { addActionListener { showCurrentFile() } })
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
            add(JLabel("Double-click or Enter: expand/collapse a group (+N), open a symbol · right-click for more · drag/arrows to pan · scroll to zoom"), BorderLayout.CENTER)
            add(JLabel("Filled: current file · outlined: dependencies/users · thick: many relationships · dashed: imports · dotted: references"), BorderLayout.SOUTH)
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

    private fun currentFile(): VirtualFile? = FileEditorManager.getInstance(project).selectedEditor?.file

    private fun highlight(file: VirtualFile? = currentFile()) {
        view?.let { canvas.highlight(it.highlightFile(file?.path)) }
        activeFile.text = file?.let { "Current file: ${it.name}" } ?: "Open a source file to highlight its symbols."
    }

    private val expanded: Set<Int> get() = hierarchy?.ids(expandedKeys.orEmpty()).orEmpty()

    private fun setExpanded(ids: Set<Int>, focus: Int? = null) = render(focus, ids)

    /** Starts over from the chosen level of detail; the analysis is reused. */
    private fun resetDetail() {
        val tree = hierarchy ?: return
        setExpanded(tree.expansion(detail.selectedItem as GraphDetail))
    }

    /** Expands the groups around the current file so its own node is shown. */
    private fun showCurrentFile() {
        val tree = hierarchy
        if (tree != null) render(null, expanded + tree.ancestors(currentFile()?.path)) else highlight()
    }

    /** Derives the displayed graph from the last analysis and keeps [focus] (a hierarchy id) selected and in view. */
    private fun render(focus: Int?, ids: Set<Int>? = null) {
        val tree = hierarchy ?: return
        if (ids != null) expandedKeys = ids.mapTo(hashSetOf(), tree::key)
        val shown = tree.view(expanded)
        view = shown
        canvas.show(shown.graph, shown.weights)
        highlight()
        val target = focus?.let(shown::shownId)
        target?.let(canvas::select)
        SwingUtilities.invokeLater { if (!disposed) target?.let(canvas::reveal) ?: canvas.revealFile() }
        val base = tree.graph
        val files = base.nodes.count { it.kind == SymbolKind.FILE }
        status.text = "${shown.graph.nodes.size} nodes shown · ${shown.graph.edges.size} relationships · " +
            "${base.nodes.size - files} symbols in $files files"
    }

    private fun describe(id: Int?) {
        val graph = view?.graph
        val node = id?.let { graph?.nodes?.getOrNull(it) }
        selection.text = if (graph != null && node != null) {
            val outgoing = graph.edges.filter { it.caller == node.id && it.kind != RelationKind.CONTAINS }.groupingBy { it.kind }.eachCount()
            val incoming = graph.edges.count { it.callee == node.id && it.kind != RelationKind.CONTAINS }
            val inside = if (node.hidden > 0) " · ${node.hidden} inside, double-click to expand" else ""
            "${node.kind.name.lowercase()} ${node.name} · $incoming incoming · " +
                outgoing.entries.joinToString { "${it.value} ${it.key.name.lowercase()}" } + inside
        } else HINT
    }

    /** Double-click or Enter: groups expand or collapse in place; other symbols open in the editor. */
    private fun activate(id: Int) {
        val tree = hierarchy ?: return
        val source = view?.sources?.getOrNull(id) ?: return
        when {
            !tree.isGroup(source) -> navigate(source)
            source in expanded -> setExpanded(expanded - source, source)
            else -> setExpanded(expanded + source, source)
        }
    }

    private fun navigate(source: Int) {
        (result?.pointers?.getOrNull(source)?.element?.navigationElement as? Navigatable)
            ?.takeIf { it.canNavigate() }?.navigate(true)
    }

    private fun menu(id: Int): JPopupMenu? {
        val tree = hierarchy ?: return null
        val source = view?.sources?.getOrNull(id) ?: return null
        val open = expanded
        fun item(text: String, action: () -> Unit) = JMenuItem(text).apply { addActionListener { action() } }
        return JPopupMenu().apply {
            if (tree.isGroup(source)) {
                if (source in open) add(item("Collapse") { setExpanded(open - source, source) })
                else add(item("Expand") { setExpanded(open + source, source) })
                add(item("Expand all inside") { setExpanded(open + tree.groupsWithin(source), source) })
            }
            tree.parent[source].takeIf { it >= 0 }?.let { parent ->
                add(item("Collapse into ${tree.nodes[parent].name}") { setExpanded(open - parent, parent) })
            }
            if (result?.pointers?.getOrNull(source) != null) add(item("Open source") { navigate(source) })
        }.takeIf { it.componentCount > 0 }
    }

    private fun rebuild(debounce: Long = 0) {
        if (disposed) return
        job?.cancel()
        job = service.scope.launch(Dispatchers.EDT) {
            delay(debounce)
            status.text = "Building application graph…"
            try {
                val built = service.buildProject()
                val tree = built.graph.hierarchy()
                result = built
                hierarchy = tree
                if (expandedKeys == null) expandedKeys = tree.expansion(detail.selectedItem as GraphDetail).mapTo(hashSetOf(), tree::key)
                render(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                thisLogger().warn("Application graph failed", e)
                status.text = "Could not build graph. Use Refresh to retry."
            }
        }
    }

    override fun dispose() { disposed = true; job?.cancel() }

    private companion object {
        const val HINT = "Double-click a group to expand it, or a symbol to open its source."
    }
}
