package dev.insight.callgraph

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import dev.insight.enrichment.ColorBy
import dev.insight.enrichment.Domain
import dev.insight.enrichment.EdgeFilter
import dev.insight.enrichment.EnrichmentLens
import dev.insight.enrichment.EnrichmentService
import dev.insight.enrichment.EnrichmentSnapshot
import dev.insight.enrichment.Flow
import dev.insight.enrichment.FlowChartLens
import dev.insight.enrichment.SymbolFacts
import dev.insight.enrichment.SymbolFactsExtractor
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
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.pom.Navigatable
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.ActionEvent
import javax.swing.*
import javax.swing.event.DocumentEvent

private const val TOOL_WINDOW_ID = "Application Graph"

/**
 * Adds the symbol at the caret, the symbol referenced at the caret, or the selected declaration to the flow chart of the
 * Application Graph tool window. Inside a parameter or local variable, the declaration around it is added.
 */
class AddToFlowChartAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && target(e)?.let { inContent(project, it) } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val element = target(e) ?: return
        val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element)
        val window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        window.activate {
            window.contentManager.contents.firstNotNullOfOrNull { it.component as? ProjectGraphPanel }?.addToChart(pointer)
        }
    }

    private fun target(e: AnActionEvent): PsiElement? {
        e.getData(CommonDataKeys.PSI_ELEMENT)?.let(::declaration)?.let { return it }
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return null
        val offset = e.getData(CommonDataKeys.CARET)?.offset ?: return null
        return declaration(file.findElementAt(offset) ?: return null)
    }

    /** The innermost declaration around [element] that the flow chart can show. */
    private fun declaration(element: PsiElement): PsiElement? {
        var current: PsiElement? = element.navigationElement
        while (current != null && current !is PsiFile) {
            if (ProjectGraphAnalyzer.kind(current) in CHART_KINDS) return current
            current = current.parent
        }
        return null
    }

    /** The application graph covers project content only. */
    private fun inContent(project: Project, element: PsiElement): Boolean =
        element.containingFile?.virtualFile?.let { ProjectFileIndex.getInstance(project).isInContent(it) } == true

    private companion object {
        val CHART_KINDS = setOf(SymbolKind.FUNCTION, SymbolKind.CLASS, SymbolKind.PROPERTY, SymbolKind.SYMBOL)
    }
}

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
        toolTipText = "Graph: free layered layout · Sections: titled columns grouped by file, folder or enrichment judgment · " +
            "Flow chart: functions and flows added to the chart, explored through their calls and callers"
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
    private val canvas = CallGraphCanvas(onSelect = ::describe, onOpen = ::activate, popup = ::menu, menuOnClick = true)
    private val flowCanvas: FlowChartCanvas = FlowChartCanvas(onSelect = ::describeStep, onOpen = { navigate(it) }, popup = ::flowMenu, onChart = ::loadSteps,
        emptyText = "Nothing on the chart yet. Right-click a function in the Graph view and choose Add to flow chart, or pick a flow above.")
    private val scroll = JBScrollPane(canvas)
    /** Reads call sites and docs of steps that appeared on the flow chart. */
    private var flowJob: Job? = null
    /** Calls for the flow chart, made from [result], the latest enrichment and the colouring; see [renderFlow]. */
    private var flowLens: FlowLensFor? = null
    /** Call sites and current source facts of flow chart steps, read once per build. */
    private val callSites = HashMap<Int, List<CallSite>>()
    private val stepFacts = HashMap<Int, SymbolFacts>()
    private val stepsRead = hashSetOf<Int>()
    /** A symbol sent to the flow chart from the editor, waiting for a finished graph build; see [addToChart]. */
    private var pendingChart: SmartPsiElementPointer<PsiElement>? = null
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

    private val search = SearchTextField(false).apply { textEditor.columns = 24 }
    private val searchMode = JComboBox(SearchMode.entries.toTypedArray()).apply {
        selectedItem = SearchMode.HIGHLIGHT
        toolTipText = "Highlight: mark matches in the graph · Filter: show only the matching nodes"
    }
    private val previousMatch = JButton("Previous").apply { isEnabled = false }
    private val nextMatch = JButton("Next").apply { isEnabled = false }
    private val matchCount = JLabel()
    /** Hierarchy ids matching the query, best first, without those hidden by the lens; set by [render]. */
    private var matches: List<Int> = emptyList()
    /** The match last stepped to, as a hierarchy id. */
    private var currentMatch: Int? = null
    /** Suppresses [searchChanged] while the search controls are updated programmatically. */
    private var updatingSearch = false
    /** Redraws once typing pauses, as large graphs take a moment to lay out. */
    private val searchTimer = Timer(250) { searchChanged() }.apply { isRepeats = false }

    init {
        border = JBUI.Borders.empty(8)
        flowChoice.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean): Component =
                super.getListCellRendererComponent(list, value, index, selected, focused).also {
                    (it as JComponent).toolTipText = (value as? FlowChoice)?.flow?.description
                }
        }
        for (control in listOf(colorBy, edgeFilter)) control.addActionListener { lensChanged() }
        flowChoice.addActionListener {
            if (updatingLens) return@addActionListener
            lensChanged()
            if (flowView) selectedFlow()?.let(::addFlow)
        }
        for (control in listOf(hideTests, hideUtilities)) control.addActionListener { lensChanged() }
        previousStep.apply { toolTipText = "Previous step of the flow"; addActionListener { stepFlow(-1) } }
        nextStep.apply { toolTipText = "Next step of the flow"; addActionListener { stepFlow(1) } }
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { if (!updatingSearch) searchTimer.restart() }
        })
        search.textEditor.apply {
            toolTipText = "Fuzzy search by name: camel humps (\"PGT\"), word starts and subsequences · Enter: next · Shift+Enter: previous"
            for ((stroke, delta) in listOf("ENTER" to 1, "shift ENTER" to -1)) {
                inputMap.put(KeyStroke.getKeyStroke(stroke), stroke)
                actionMap.put(stroke, object : AbstractAction() {
                    override fun actionPerformed(e: ActionEvent?) = stepMatch(delta)
                })
            }
        }
        searchMode.addActionListener { searchChanged() }
        previousMatch.apply { toolTipText = "Previous match (Shift+Enter)"; addActionListener { stepMatch(-1) } }
        nextMatch.apply { toolTipText = "Next match (Enter)"; addActionListener { stepMatch(1) } }
        add(JPanel(BorderLayout(0, 4)).apply {
            add(JPanel(BorderLayout(0, 4)).apply {
                add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    add(JLabel("Search:"))
                    add(search)
                    add(searchMode)
                    add(previousMatch)
                    add(nextMatch)
                    add(matchCount)
                }, BorderLayout.SOUTH)
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
        add(scroll, BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, 4)).apply {
            add(selection, BorderLayout.NORTH)
            add(JPanel(GridLayout(0, 1, 0, 4)).apply {
                add(JLabel("Click: node menu / Side by side · Enter: expand/collapse a group, open a symbol · drag/arrows to pan · scroll to zoom"))
                add(JLabel("Filled: current file · outlined: dependencies/users · thick: many relationships · dashed: imports · dotted: references"))
                add(JLabel("Stripe: colour-by · ▶ entry point · numbered: flow step · red dashed: error path"))
                add(JLabel("Sections view: one titled column per file, folder, domain, layer, phase or importance · hover a title for details"))
                add(JLabel("Flow chart: Add to flow chart in the graph, or pick a flow · pills above a step show callers, below it calls · right-click for more · Ctrl+wheel to zoom"))
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
                val shown = if (flowView) flowCanvas else canvas
                if (scroll.viewport.view !== shown) scroll.setViewportView(shown)
                updateFlowControls()
                rerender()
                if (flowView && flowCanvas.board.starts.isEmpty()) selectedFlow()?.let(::addFlow)
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
                if (!updating) (if (flowView) flowCanvas::zoom else canvas::zoom).set(selectedItem.toString().removeSuffix("%").toDouble() / 100)
            }
            for (target in listOf(canvas, flowCanvas)) target.addPropertyChangeListener("zoom") { event ->
                updating = true
                try { model.setSelectedItem("${kotlin.math.round(event.newValue as Double * 100).toInt()}%") }
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

    private val flowView: Boolean get() = viewChoice.selectedItem == GraphView.FLOW

    private fun updateFlowControls() {
        val flow = selectedFlow()
        flowChoice.toolTipText = flow?.description
        previousStep.isEnabled = if (flowView) flowCanvas.chart != null else flow != null && enrichment.latest != null
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
        if (flowView) {
            flowCanvas.step(delta)?.let { navigate(it, focus = false) }
            return
        }
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
        } else render(target, expanded + tree.ancestors(target))
    }

    private val filtering: Boolean get() = searchMode.selectedItem == SearchMode.FILTER && search.text.isNotBlank()

    /** A new query or mode: matches are recomputed by [render], starting over from the first one. */
    private fun searchChanged() {
        if (updatingSearch) return
        searchTimer.stop()
        currentMatch = null
        rerender()
    }

    /**
     * Selects the previous or next match, wrapping around, and zooms the canvas to it. Starts after the selection when
     * it is a match; groups folding the match are expanded first.
     */
    private fun stepMatch(delta: Int) {
        val tree = hierarchy ?: return
        if (searchTimer.isRunning) searchChanged()
        if (matches.isEmpty()) return
        val current = canvas.selected?.let { view?.sources?.getOrNull(it) }?.let(matches::indexOf)?.takeIf { it >= 0 }
            ?: currentMatch?.let(matches::indexOf) ?: -1
        val target = matches[if (current < 0) (if (delta > 0) 0 else matches.lastIndex) else Math.floorMod(current + delta, matches.size)]
        currentMatch = target
        if (view?.shownId(target)?.let { view?.sources?.getOrNull(it) } == target) markMatches()
        else render(target, expanded + tree.ancestors(target))
        val id = view?.shownId(target) ?: return
        canvas.select(id)
        SwingUtilities.invokeLater { if (!disposed) canvas.zoomTo(id) }
    }

    /** Marks the matches on the canvas (folded ones on the group holding them) and updates the search controls. */
    private fun markMatches() {
        val shown = view ?: return
        val active = search.text.isNotBlank()
        canvas.markMatches(if (active) matches.mapNotNullTo(hashSetOf(), shown::shownId) else null, currentMatch?.let(shown::shownId))
        val index = currentMatch?.let(matches::indexOf) ?: -1
        matchCount.text = when {
            !active -> ""
            matches.isEmpty() -> "No matches"
            index >= 0 -> "${index + 1} of ${matches.size}"
            else -> if (matches.size == 1) "1 match" else "${matches.size} matches"
        }
        previousMatch.isEnabled = matches.isNotEmpty()
        nextMatch.isEnabled = matches.isNotEmpty()
    }

    /** Leaves the filter, keeping the query highlighted, and shows [source] expanded in the full graph. */
    private fun showInGraph(source: Int) {
        val tree = hierarchy ?: return
        updatingSearch = true
        try { searchMode.selectedItem = SearchMode.HIGHLIGHT } finally { updatingSearch = false }
        setExpanded(expanded + tree.ancestors(source) + source, source)
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
        val found = tree.search(search.text)
        val shown = tree.view(expanded, excluded, found.toHashSet().takeIf { filtering })
        view = shown
        // Matches hidden by the lens cannot be shown.
        matches = found.filter { shown.shownId(it) != null }
        val sections = if (viewChoice.selectedItem != GraphView.SECTIONS) null else lens(null) {
            SectionLens.sections(tree, shown, sectionBy.selectedItem as SectionBy, snapshot, project.name, options)
        }
        canvas.show(shown.graph, shown.weights, sections)
        canvas.decorate(snapshot?.let { lens(GraphDecorations.NONE) { EnrichmentLens.decorate(it, tree.graph, shown, options) } } ?: GraphDecorations.NONE)
        highlight()
        markMatches()
        val target = focus?.let(shown::shownId)
        target?.let(canvas::select)
        SwingUtilities.invokeLater { if (!disposed) target?.let(canvas::reveal) ?: canvas.revealFile() }
        val base = tree.graph
        val files = base.nodes.count { it.kind == SymbolKind.FILE }
        status.text = "${shown.graph.nodes.size} nodes shown · ${shown.graph.edges.size} relationships · " +
            "${base.nodes.size - files} symbols in $files files"
        if (flowView) renderFlow()
    }

    private fun describe(id: Int?) {
        val graph = view?.graph
        val node = id?.let { graph?.nodes?.getOrNull(it) }
        selection.text = if (graph != null && node != null) {
            val outgoing = graph.edges.filter { it.caller == node.id && it.kind != RelationKind.CONTAINS }.groupingBy { it.kind }.eachCount()
            val incoming = graph.edges.count { it.callee == node.id && it.kind != RelationKind.CONTAINS }
            val inside = if (node.hidden > 0) " · ${node.hidden} inside, Enter to ${if (filtering) "show in full graph" else "expand"}" else ""
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

    /**
     * Draws the flow chart with the calls of the current graph, described by the latest enrichment. The calls are
     * prepared once per graph, enrichment and colouring; call sites and docs are read by [loadSteps].
     */
    private fun renderFlow() {
        val built = result
        if (built == null) {
            flowCanvas.show(null, "Building application graph…")
            return
        }
        val snapshot = enrichment.latest
        val made = flowLens?.takeIf { it.graph === built.graph && it.snapshot === snapshot && it.colorBy == options.colorBy }
            ?: lens<FlowLensFor?>(null) { FlowLensFor(built.graph, snapshot, options.colorBy, FlowChartLens(snapshot, built.graph, options.colorBy)) }
        flowLens = made
        if (made == null) flowCanvas.show(null, "Could not draw the flow chart.")
        else lens(Unit) { flowCanvas.show(made.lens.source(callSites, stepFacts)) }
        updateFlowControls()
    }

    /**
     * Reads, in the background, the call sites (the order of calls and the conditions around them) and current docs of
     * steps that are new on [chart], then draws it again. One read runs at a time; the next picks up what is still missing.
     */
    private fun loadSteps(chart: FlowChart?) {
        updateFlowControls()
        val built = result ?: return
        val lens = flowLens?.lens ?: return
        if (chart == null || flowJob?.isActive == true) return
        val missing = chart.steps.map { it.node }.filter { it !in stepsRead }
        if (missing.isEmpty()) return
        flowJob = service.scope.launch(Dispatchers.EDT) {
            val (sites, facts) = try {
                smartReadAction(project) {
                    val callees = missing.flatMap { id -> lens.calls[id].orEmpty().map { it.callee } }
                    CallSiteAnalyzer.collect(built, missing, callees) to SymbolFactsExtractor.extract(project, built, only = missing.toSet())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Without call sites the calls keep the graph's order and have no branches.
                thisLogger().warn("Reading call sites failed", e)
                emptyMap<Int, List<CallSite>>() to emptyMap()
            }
            if (result !== built || disposed) return@launch
            stepsRead += missing
            callSites += sites
            stepFacts += facts
            flowJob = null
            if (flowView) renderFlow()
        }
    }

    /** Places [start] on the flow chart, switching to it. */
    private fun addToChart(start: FlowStart) {
        // Added before switching, so switching does not also add the chosen flow to an empty chart.
        flowCanvas.add(start)
        if (!flowView) viewChoice.selectedItem = GraphView.FLOW
        flowCanvas.requestFocusInWindow()
        SwingUtilities.invokeLater { if (!disposed) flowCanvas.selectNode(start.node) }
    }

    /**
     * Places the symbol [pointer] points to on the flow chart. While the graph is (re)building, for example right after
     * the symbol was written, it is placed once the build finishes.
     */
    fun addToChart(pointer: SmartPsiElementPointer<PsiElement>) {
        pendingChart = pointer
        placePending()
    }

    private fun placePending() {
        val pointer = pendingChart ?: return
        val built = result
        if (built == null || job?.isActive == true) return
        pendingChart = null
        val element = pointer.element ?: return
        val path = element.containingFile?.virtualFile?.path
        val node = built.graph.nodes.firstOrNull { it.filePath == path && built.pointers.getOrNull(it.id)?.element == element }
        if (node == null) {
            status.text = "${(element as? PsiNamedElement)?.name ?: "This symbol"} is not in the application graph."
            return
        }
        addToChart(FlowStart(node.id))
    }

    private fun addFlow(flow: Flow) {
        flowLens?.lens?.start(flow.id)?.let(::addToChart)
    }

    private fun describeStep(source: Int?) {
        val node = source?.let { result?.graph?.nodes?.getOrNull(it) }
        selection.text = if (node == null) FLOW_HINT
            else listOfNotNull("${node.kind.name.lowercase()} ${node.name}", node.location.ifEmpty { null }, annotationSummary(source))
                .joinToString(" · ")
    }

    /** The menu of a step or reference to [source], or of the chart itself for null. */
    private fun flowMenu(source: Int?): JPopupMenu? = JPopupMenu().apply {
        fun item(text: String, action: () -> Unit) = add(JMenuItem(text).apply { addActionListener { action() } })
        val board = flowCanvas.board
        val step = source?.let { flowCanvas.chart?.step(it) }
        if (source != null) {
            if (result?.pointers?.getOrNull(source) != null) item("Open source") { navigate(source) }
            item("Show in graph") {
                val tree = hierarchy ?: return@item
                viewChoice.selectedItem = GraphView.GRAPH
                render(source, expanded + tree.ancestors(source))
            }
        }
        if (step != null && (step.calls > 0 || step.callers > 0)) {
            addSeparator()
            if (step.calls > 0) item(if (step.node in board.openCalls) "Hide calls" else "Show calls (${step.calls})") { flowCanvas.toggleCalls(step.node) }
            if (step.callers > 0) item(if (step.node in board.openCallers) "Hide callers" else "Show callers (${step.callers})") { flowCanvas.toggleCallers(step.node) }
        }
        if (source != null) {
            addSeparator()
            if (board.starts.any { it.node == source }) item("Remove column") { flowCanvas.removeColumn(source) }
            else item("Open in new column") { flowCanvas.add(FlowStart(source)) }
        }
        if (board.starts.isNotEmpty()) item("Clear chart") { flowCanvas.clear() }
    }.takeIf { it.componentCount > 0 }

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
            filtering -> showInGraph(source)
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
        val built = result ?: return null
        val open = expanded
        fun item(text: String, action: () -> Unit) = JMenuItem(text).apply { addActionListener { action() } }
        return JPopupMenu().apply {
            add(item("Side by side") {
                openSourceCallChain(project, built, tree.sourceCallChain(source), tree.nodes[source].name)
            })
            if (filtering) add(item("Show in full graph") { showInGraph(source) })
            else if (tree.isGroup(source)) {
                if (source in open) add(item("Collapse") { setExpanded(open - source, source) })
                else add(item("Expand") { setExpanded(open + source, source) })
                add(item("Expand all inside") { setExpanded(open + tree.groupsWithin(source), source) })
            }
            tree.parent[source].takeIf { it >= 0 && !filtering }?.let { parent ->
                add(item("Collapse into ${tree.nodes[parent].name}") { setExpanded(open - parent, parent) })
            }
            if (result?.pointers?.getOrNull(source) != null) add(item("Open source") { navigate(source) })
            if (tree.graph.nodes.getOrNull(source)?.kind == SymbolKind.FUNCTION) add(item("Add to flow chart") { addToChart(FlowStart(source)) })
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
                result?.let { previous -> moveFlowChart(previous.graph, built.graph) }
                result = built
                hierarchy = tree
                if (expandedKeys == null) expandedKeys = tree.expansion(detail.selectedItem as GraphDetail).mapTo(hashSetOf(), tree::key)
                render(null)
                // This job is still active here; place a symbol sent from the editor once it has finished.
                SwingUtilities.invokeLater { if (!disposed) placePending() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                thisLogger().warn("Application graph failed", e)
                status.text = "Could not build graph. Use Refresh to retry."
            }
        }
    }

    /** Keeps the flow chart across a rebuild: its functions are matched by [stableKey]; call sites and docs are read again. */
    private fun moveFlowChart(previous: CallGraph, next: CallGraph) {
        flowJob?.cancel()
        flowJob = null
        callSites.clear()
        stepFacts.clear()
        stepsRead.clear()
        val ids = HashMap<String, Int>()
        next.nodes.forEach { ids.putIfAbsent(stableKey(it), it.id) }
        flowCanvas.remap { id -> previous.nodes.getOrNull(id)?.let { ids[stableKey(it)] } }
    }

    override fun dispose() { disposed = true; searchTimer.stop(); job?.cancel(); enrichJob?.cancel(); flowJob?.cancel() }

    /** A flow list entry; null stands for "(none)". */
    private class FlowChoice(val flow: Flow?) {
        override fun toString() = flow?.name ?: "(none)"
    }

    /** The [lens] made for [graph], [snapshot] and [colorBy]. */
    private class FlowLensFor(val graph: CallGraph, val snapshot: EnrichmentSnapshot?, val colorBy: ColorBy, val lens: FlowChartLens)

    private enum class SearchMode(val label: String) {
        HIGHLIGHT("Highlight"), FILTER("Filter");
        override fun toString() = label
    }

    private companion object {
        const val HINT = "Click a node for Side by side. Enter expands a group or opens a symbol's source."
        const val FLOW_HINT = "Click a step to select it; double-click or Enter opens its source. Up/Down move between steps; " +
            "Left toggles callers; Right or Space toggles calls; Delete removes a column."
    }
}
