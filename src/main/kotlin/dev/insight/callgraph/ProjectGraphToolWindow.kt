package dev.insight.callgraph

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import dev.insight.enrichment.ColorBy
import dev.insight.enrichment.Domain
import dev.insight.enrichment.EdgeFilter
import dev.insight.enrichment.EnrichmentLens
import dev.insight.enrichment.EnrichmentService
import dev.insight.enrichment.Flow
import dev.insight.enrichment.LensOptions
import dev.insight.enrichment.SectionBy
import dev.insight.enrichment.SectionLens
import com.intellij.openapi.diagnostic.ControlFlowException
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
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridLayout
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
    private val viewChoice = JComboBox(GraphView.entries.toTypedArray()).apply {
        selectedItem = GraphView.GRAPH
        toolTipText = "Graph: free layered layout · Sections: titled columns grouped by file, folder or enrichment judgment"
    }
    private val sectionBy = JComboBox(SectionBy.entries.toTypedArray()).apply {
        selectedItem = SectionBy.FILE
        isEnabled = false
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean): Component =
                super.getListCellRendererComponent(list, value, index, selected, focused).also {
                    if (index >= 0 && (value as? SectionBy)?.enriched == true && enrichment.latest == null) text = "$value (needs Enrich)"
                }
        }
    }
    private var job: Job? = null
    private var disposed = false
    private val status = JLabel("Building application graph…")
    private val activeFile = JLabel()
    private val selection = JLabel(HINT)
    private val canvas = CallGraphCanvas(onSelect = ::describe, onOpen = ::activate, popup = ::menu)
    private val enrichment = project.service<EnrichmentService>()
    private var enrichJob: Job? = null
    private val enrichButton = JButton("Enrich")
    private val reportButton = JButton("Report")

    private var options = LensOptions()
    /** Suppresses [lensChanged] while the controls are updated programmatically. */
    private var updatingLens = false
    private val colorBy = JComboBox(ColorBy.entries.toTypedArray()).apply { selectedItem = options.colorBy }
    private val hideTests = JCheckBox("Hide tests")
    private val hideUtilities = JCheckBox("Hide utilities")
    private val edgeFilter = JComboBox(EdgeFilter.entries.toTypedArray()).apply { selectedItem = options.edges }
    private val flowChoice = object : JComboBox<FlowChoice>() {
        override fun getPreferredSize() = super.getPreferredSize().also { it.width = it.width.coerceIn(JBUI.scale(120), JBUI.scale(280)) }
    }
    private val previousStep = JButton("◀")
    private val nextStep = JButton("▶")

    init {
        border = JBUI.Borders.empty(8)
        flowChoice.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean): Component =
                super.getListCellRendererComponent(list, value, index, selected, focused).also {
                    (it as JComponent).toolTipText = (value as? FlowChoice)?.flow?.description
                }
        }
        for (control in listOf(colorBy, edgeFilter, flowChoice)) control.addActionListener { lensChanged() }
        for (control in listOf(hideTests, hideUtilities)) control.addActionListener { lensChanged() }
        previousStep.apply { toolTipText = "Previous step of the flow"; addActionListener { stepFlow(-1) } }
        nextStep.apply { toolTipText = "Next step of the flow"; addActionListener { stepFlow(1) } }
        add(JPanel(BorderLayout(0, 4)).apply {
            add(JPanel(BorderLayout(0, 4)).apply {
                add(toolbar(), BorderLayout.NORTH)
                add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    add(JLabel("Colour:"))
                    add(colorBy)
                    add(hideTests)
                    add(hideUtilities)
                    add(JLabel("Calls:"))
                    add(edgeFilter)
                    add(JLabel("Flow:"))
                    add(flowChoice)
                    add(previousStep)
                    add(nextStep)
                }, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(activeFile, BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(JBScrollPane(canvas), BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, 4)).apply {
            add(selection, BorderLayout.NORTH)
            add(JPanel(GridLayout(0, 1, 0, 4)).apply {
                add(JLabel("Double-click or Enter: expand/collapse a group (+N), open a symbol · right-click for more · drag/arrows to pan · scroll to zoom"))
                add(JLabel("Filled: current file · outlined: dependencies/users · thick: many relationships · dashed: imports · dotted: references"))
                add(JLabel("Stripe: colour-by · ▶ entry point · numbered: flow step · red dashed: error path"))
                add(JLabel("Sections view: one titled column per file, folder, domain, layer, phase or importance · hover a title for details"))
            }, BorderLayout.CENTER)
        }, BorderLayout.SOUTH)
        project.messageBus.connect(this).apply {
            subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) { highlight(event.newFile); canvas.revealFile() }
            })
            subscribe(PsiModificationTracker.TOPIC, PsiModificationTracker.Listener { SwingUtilities.invokeLater { rebuild(600) } })
        }
        refreshLens()
        highlight()
        rebuild()
    }

    private fun toolbar() = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        add(JLabel("Detail:"))
        add(detail.apply { addActionListener { resetDetail() } })
        add(JLabel("View:"))
        add(viewChoice.apply {
            addActionListener {
                sectionBy.isEnabled = selectedItem == GraphView.SECTIONS
                rerender()
            }
        })
        add(JLabel("by"))
        add(sectionBy.apply { addActionListener { rerender() } })
        add(JButton("Refresh").apply { addActionListener { rebuild() } })
        add(JButton("Current file").apply { addActionListener { showCurrentFile() } })
        add(enrichButton.apply {
            toolTipText = "Classify symbols, calls and flows with the decisions API and the LLM. Results are cached by content."
            addActionListener { enrich() }
        })
        add(reportButton.apply {
            isEnabled = enrichment.latest != null
            addActionListener { openReport() }
        })
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
    }

    /** Reads the lens controls into [options] and redraws. */
    private fun lensChanged() {
        if (updatingLens) return
        options = LensOptions(colorBy.selectedItem as ColorBy, hideTests.isSelected, hideUtilities.isSelected,
            edgeFilter.selectedItem as EdgeFilter, selectedFlow()?.id)
        updateFlowControls()
        rerender()
    }

    private fun selectedFlow(): Flow? = (flowChoice.selectedItem as? FlowChoice)?.flow

    private fun updateFlowControls() {
        val flow = selectedFlow()
        flowChoice.toolTipText = flow?.description
        previousStep.isEnabled = flow != null && enrichment.latest != null
        nextStep.isEnabled = previousStep.isEnabled
    }

    /** Fills the flow list from the latest enrichment, keeping the chosen flow if it still exists, and enables the lens row accordingly. */
    private fun refreshLens() {
        val snapshot = enrichment.latest
        updatingLens = true
        try {
            val choices = listOf(FlowChoice(null)) + snapshot?.enrichment?.flows.orEmpty().map(::FlowChoice)
            flowChoice.model = DefaultComboBoxModel(choices.toTypedArray())
            flowChoice.selectedItem = choices.firstOrNull { it.flow != null && it.flow.id == options.flowId } ?: choices.first()
            options = options.copy(flowId = selectedFlow()?.id)
        } finally { updatingLens = false }
        for (control in listOf<JComponent>(colorBy, hideTests, hideUtilities, edgeFilter, flowChoice)) control.isEnabled = snapshot != null
        updateFlowControls()
    }

    /** Redraws with the current options, keeping the selected symbol. */
    private fun rerender() {
        if (hierarchy != null) render(canvas.selected?.let { view?.sources?.getOrNull(it) })
    }

    /** Runs a lens computation; a failure is logged and replaced by [fallback] so the plain graph still shows. */
    private fun <T> lens(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        if (e is ControlFlowException || e is CancellationException || e is VirtualMachineError) throw e
        thisLogger().warn("Enrichment lens failed", e)
        fallback
    }

    /**
     * Moves the selection to the previous or next node of the chosen flow, starting at the first (or last) node when
     * the selection is not in the flow. Groups folding the node are expanded first; the source opens without taking focus.
     */
    private fun stepFlow(delta: Int) {
        val tree = hierarchy ?: return
        val flow = selectedFlow() ?: return
        val snapshot = enrichment.latest ?: return
        val nodes = lens(emptyList()) { EnrichmentLens.flowSteps(snapshot, tree.graph, flow.id) }
        if (nodes.isEmpty()) return
        val current = canvas.selected?.let { view?.sources?.getOrNull(it) }?.let(nodes::indexOf) ?: -1
        val target = nodes[if (current < 0) (if (delta > 0) 0 else nodes.lastIndex) else (current + delta).coerceIn(nodes.indices)]
        val shownNow = view?.shownId(target)?.takeIf { view?.sources?.getOrNull(it) == target }
        navigate(target, focus = false)
        if (shownNow != null) {
            canvas.select(shownNow)
            SwingUtilities.invokeLater { if (!disposed) canvas.reveal(shownNow) }
        } else {
            val parents = generateSequence(tree.parent[target]) { tree.parent[it] }.takeWhile { it >= 0 }.toSet()
            render(target, expanded + parents)
        }
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
        val snapshot = enrichment.latest
        val excluded = snapshot?.let { lens(emptySet()) { EnrichmentLens.hiddenSymbols(it, tree.graph, options) } }.orEmpty()
        val shown = tree.view(expanded, excluded)
        view = shown
        val sections = if (viewChoice.selectedItem != GraphView.SECTIONS) null else lens(null) {
            SectionLens.sections(tree, shown, sectionBy.selectedItem as SectionBy, snapshot, project.name, options)
        }
        canvas.show(shown.graph, shown.weights, sections)
        canvas.decorate(snapshot?.let { lens(GraphDecorations.NONE) { EnrichmentLens.decorate(it, tree.graph, shown, options) } } ?: GraphDecorations.NONE)
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
                outgoing.entries.joinToString { "${it.value} ${it.key.name.lowercase()}" } + inside +
                (view?.sources?.getOrNull(node.id)?.let(::annotationSummary)?.let { " · $it" } ?: "")
        } else HINT
    }

    /** Model judgments for a node of the analysed graph, while the enrichment still matches it. */
    private fun annotationSummary(source: Int): String? {
        val snapshot = enrichment.latest?.takeIf { it.graph === result?.graph } ?: return null
        val facts = snapshot.input.facts[source]
        if (facts?.kind == SymbolKind.FILE) return snapshot.enrichment.groupLabels[facts.path]?.label
        val annotation = snapshot.enrichment.nodes[source] ?: return null
        val concerns = annotation.concerns.filterValues { it >= 0.5 }.entries.sortedByDescending { it.value }.joinToString("/") { it.key }
        val flows = snapshot.enrichment.flows.count { source in it.nodes }
        return listOfNotNull(
            annotation.layer?.choice, annotation.phase?.choice, annotation.domain?.choice?.takeIf { it != Domain.NONE },
            concerns.takeIf { it.isNotEmpty() }, annotation.importance?.let { "importance %.1f".format(it.score) },
            flows.takeIf { it > 0 }?.let { "in $it flow(s)" },
        ).joinToString(" · ")
    }

    private fun enrich() {
        val built = result ?: return
        enrichJob?.cancel()
        enrichButton.isEnabled = false
        enrichJob = service.scope.launch(Dispatchers.EDT) {
            try {
                val snapshot = enrichment.enrich(built)
                val stats = snapshot.enrichment.stats
                status.text = "Enriched ${snapshot.enrichment.nodes.size} symbols, ${snapshot.enrichment.edges.size} calls, " +
                    "${snapshot.enrichment.flows.size} flows, ${snapshot.enrichment.domains.size} areas in ${stats.durationMillis / 1000}s " +
                    "(${stats.cacheHits} cached, ${stats.failures} failed)"
                reportButton.isEnabled = true
                refreshLens()
                rerender()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                thisLogger().warn("Enrichment failed", e)
                status.text = "Enrichment failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                if (!disposed) enrichButton.isEnabled = true
            }
        }
    }

    private fun openReport() {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(enrichment.reportPath) ?: return
        FileEditorManager.getInstance(project).openFile(file, true)
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

    private fun navigate(source: Int, focus: Boolean = true) {
        (result?.pointers?.getOrNull(source)?.element?.navigationElement as? Navigatable)
            ?.takeIf { it.canNavigate() }?.navigate(focus)
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

    override fun dispose() { disposed = true; job?.cancel(); enrichJob?.cancel() }

    /** A flow list entry; null stands for "(none)". */
    private class FlowChoice(val flow: Flow?) {
        override fun toString() = flow?.name ?: "(none)"
    }

    private companion object {
        const val HINT = "Double-click a group to expand it, or a symbol to open its source."
    }
}
