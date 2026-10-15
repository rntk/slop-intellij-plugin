package dev.insight.callgraph

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.wm.StatusBar
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
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.ui.components.JBTextField
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
import dev.insight.entrypoints.EntryPointsPanel
import dev.insight.entrypoints.EntryPointService
import com.intellij.util.ui.JBUI
import dev.insight.ui.CollapsibleControls
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.ActionEvent
import javax.swing.*
import javax.swing.event.DocumentEvent

private const val TOOL_WINDOW_ID = "Application Graph"

/** Shared by editor actions and the call graph's semantic exploration entry point. */
fun openFlowChart(project: Project, pointer: SmartPsiElementPointer<PsiElement>) {
    val window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
    window.activate {
        val content = window.contentManager.contents.firstOrNull { it.component is FlowChartPanel } ?: return@activate
        window.contentManager.setSelectedContent(content)
        (content.component as FlowChartPanel).addToChart(pointer)
    }
}

/** Tools menu: opens the application graph and highlights the local changes and their callers and callees. */
class ShowChangesInGraphAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        window.activate {
            val content = window.contentManager.contents.firstOrNull { it.component is ProjectGraphPanel } ?: return@activate
            window.contentManager.setSelectedContent(content)
            (content.component as ProjectGraphPanel).showChanges()
        }
    }
}

/**
 * An editor or structure view action on the symbol at the caret, the symbol referenced at the caret, or the selected declaration.
 * Inside a parameter or local variable, it acts on the declaration around it.
 */
abstract class GraphSymbolAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && target(e)?.let { inContent(project, it) } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val element = target(e) ?: return
        perform(e, project, SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element))
    }

    protected abstract fun perform(e: AnActionEvent, project: Project, pointer: SmartPsiElementPointer<PsiElement>)

    private fun target(e: AnActionEvent): PsiElement? {
        e.getData(CommonDataKeys.PSI_ELEMENT)?.let(::declaration)?.let { return it }
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return null
        val offset = e.getData(CommonDataKeys.CARET)?.offset ?: return null
        return declaration(file.findElementAt(offset) ?: return null)
    }

    /** The innermost declaration around [element] that the application graph can show. */
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

/** Adds the symbol to the dedicated Flow Chart panel in the Application Graph tool window. */
class AddToFlowChartAction : GraphSymbolAction() {
    override fun perform(e: AnActionEvent, project: Project, pointer: SmartPsiElementPointer<PsiElement>) = openFlowChart(project, pointer)
}

/**
 * Opens the symbol and what it calls side by side, as the application graph's node menu does, without the tool window.
 * Reuses the last application graph while the code is unchanged, otherwise builds one in the background.
 */
class OpenSideBySideAction : GraphSymbolAction() {
    override fun perform(e: AnActionEvent, project: Project, pointer: SmartPsiElementPointer<PsiElement>) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val service = project.service<CallGraphService>()
        service.scope.launch(Dispatchers.EDT) {
            val built = service.currentProject()
            val found = readAction {
                val element = pointer.element ?: return@readAction null
                val path = element.containingFile?.virtualFile?.path
                val node = built.graph.nodes.firstOrNull { it.filePath == path && built.pointers.getOrNull(it.id)?.element == element }
                    ?: return@readAction null
                val tree = built.graph.hierarchy()
                tree.sourceCallChain(node.id) to tree.nodes[node.id].name
            }
            if (found == null) {
                val text = "This symbol is not in the application graph."
                if (editor != null && !editor.isDisposed) HintManager.getInstance().showInformationHint(editor, text)
                else StatusBar.Info.set(text, project)
                return@launch
            }
            openSourceCallChain(project, built, found.first, found.second)
        }
    }
}

class ProjectGraphToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ProjectGraphPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "Application graph", false)
        content.setDisposer(panel)
        val chart = FlowChartPanel(project,
            activateChart = { toolWindow.contentManager.contents.firstOrNull { it.component is FlowChartPanel }?.let { toolWindow.contentManager.setSelectedContent(it) } },
            showInGraph = panel::showInGraph, refresh = { panel.rebuild() }, enrich = panel::enrich,
            openReport = panel::openReport, building = { panel.isBuilding })
        panel.flowPanel = chart
        panel.activateGraph = { toolWindow.contentManager.setSelectedContent(content) }
        val chartContent = ContentFactory.getInstance().createContent(chart, "Flow Chart", false)
        chartContent.setDisposer(chart)
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.addContent(chartContent)
        val reading = CanvasPanel(project,
            activateCanvas = { toolWindow.contentManager.contents.firstOrNull { it.component is CanvasPanel }?.let { toolWindow.contentManager.setSelectedContent(it) } },
            refresh = { panel.rebuild() }, enrich = panel::enrich)
        panel.canvasPanel = reading
        val canvasContent = ContentFactory.getInstance().createContent(reading, "Canvas", false)
        canvasContent.setDisposer(reading)
        toolWindow.contentManager.addContent(canvasContent)
        val entryPoints = EntryPointsPanel(project) { panel.latestResult }
        val entryContent = ContentFactory.getInstance().createContent(entryPoints, "Entry Points", false)
        entryContent.setDisposer(entryPoints)
        toolWindow.contentManager.addContent(entryContent)
        val flows = ApplicationFlowsPanel(project, latest = { panel.latestResult }, building = { panel.isBuilding },
            refresh = { panel.rebuild() }, showInGraph = panel::showInGraph,
            addToChart = { chart.addToChart(FlowStart(it)) }, openInCanvas = reading::openFunction)
        panel.flowsPanel = flows
        val flowsContent = ContentFactory.getInstance().createContent(flows, "Flows", false)
        flowsContent.setDisposer(flows)
        toolWindow.contentManager.addContent(flowsContent)
        panel.rebuild()
    }
}

internal class ProjectGraphPanel(private val project: Project) : JPanel(BorderLayout(0, JBUI.scale(8))), Disposable {
    private val service = project.service<CallGraphService>()
    private val entryPoints = project.service<EntryPointService>()
    private var entryPointJob: Job? = null
    private var result: CallGraphResult? = null
    val latestResult: CallGraphResult? get() = result
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
    val isBuilding: Boolean get() = job?.isActive == true
    private var disposed = false
    private val status = JLabel("Building application graph…")
    private val activeFile = JLabel()
    private val selection = JLabel(HINT)
    private val canvas = CallGraphCanvas(onSelect = ::describe, onOpen = ::activate, popup = ::menu, menuOnClick = true)
    private val scroll = JBScrollPane(canvas)
    lateinit var flowPanel: FlowChartPanel
    lateinit var canvasPanel: CanvasPanel
    lateinit var flowsPanel: ApplicationFlowsPanel
    var activateGraph: () -> Unit = {}
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
    private val flowChoice = flowChoiceControl()
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

    private val diffService = project.service<DiffService>()
    /** The last change set mapped onto the graph; ignored by [render] once the graph is rebuilt. */
    private var diff: DiffResult? = null
    private var diffJob: Job? = null
    private val changesMode = JComboBox(ChangesMode.entries.toTypedArray()).apply {
        selectedItem = ChangesMode.OFF
        toolTipText = "Highlight: emphasize changed symbols and their callers and callees · Changed only: show just those"
    }
    private val changesBase = JBTextField(8).apply {
        emptyText.text = "HEAD"
        toolTipText = if (DiffBaseProvider.available) "Revision to compare the working tree with (branch, tag or commit); empty for local changes vs HEAD. Enter to apply."
            else "Comparing with another revision needs the Git plugin; local changes are compared with HEAD."
        isEnabled = DiffBaseProvider.available
    }
    private val changesHops = JComboBox(arrayOf("1", "2", "3", "All")).apply {
        selectedItem = "All"
        toolTipText = "Caller and callee hops kept around changed symbols"
    }
    private val changesStatus = JLabel()
    private lateinit var searchSection: CollapsibleControls.Section
    private lateinit var changesSection: CollapsibleControls.Section
    /** Suppresses [changesModeChanged] while the mode is switched programmatically. */
    private var updatingChanges = false
    /** Recomputes after the IDE's change list updates, for example after a commit or an external edit. */
    private val changesTimer = Timer(1000) { if (changesMode.selectedItem != ChangesMode.OFF && changesBase.text.isBlank()) refreshChanges() }
        .apply { isRepeats = false }

    init {
        border = JBUI.Borders.empty(8)
        for (control in listOf(colorBy, edgeFilter)) control.addActionListener { lensChanged() }
        flowChoice.addActionListener {
            if (updatingLens) return@addActionListener
            lensChanged()
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
        changesMode.addActionListener {
            changesSection.hint = changesMode.selectedItem.takeIf { it != ChangesMode.OFF }?.toString().orEmpty()
            changesModeChanged()
        }
        changesBase.addActionListener { refreshChanges() }
        changesHops.addActionListener { refreshChanges() }
        add(JPanel(BorderLayout(0, 4)).apply {
            add(CollapsibleControls(project, "insight.graph.controls").apply {
                section("view", "View", toolbar())
                section("filters", "Filters", JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    add(JLabel("Colour:")); add(colorBy)
                    add(hideTests); add(hideUtilities)
                    add(JLabel("Calls:")); add(edgeFilter)
                    add(JLabel("Flow:")); add(flowChoice); add(previousStep); add(nextStep)
                })
                searchSection = section("search", "Search", JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    add(JLabel("Search:")); add(search); add(searchMode)
                    add(previousMatch); add(nextMatch); add(matchCount)
                })
                changesSection = section("changes", "Changes", changesRow(), expanded = false)
            }, BorderLayout.NORTH)
            add(activeFile, BorderLayout.CENTER)
            add(JPanel(BorderLayout(0, 4)).apply {
                add(status, BorderLayout.NORTH)
                add(changesStatus, BorderLayout.SOUTH)
            }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, 4)).apply {
            add(selection, BorderLayout.NORTH)
            add(CollapsibleControls(project, "insight.graph.controls").apply {
                section("legend", "Legend", JPanel(GridLayout(0, 1, 0, 4)).apply {
                    add(JLabel("Click: node menu / Side by side · Enter: expand/collapse a group, open a symbol · drag/arrows to pan · scroll to zoom"))
                    add(JLabel("Filled: current file · outlined: dependencies/users · thick: many relationships · dashed: imports · dotted: references"))
                    add(JLabel("Stripe: colour-by · ▶ Start: entry point (groups contain starting points) · numbered: flow step · red dashed: error path"))
                    add(JLabel("Sections view: one titled column per file, folder, domain, layer, phase or importance · hover a title for details"))
                    add(JLabel("Changes: Δ / amber stripe: changed symbol (files show +added −removed) · faint: not a caller or callee of a change"))
                }, expanded = false)
            }, BorderLayout.CENTER)
        }, BorderLayout.SOUTH)
        project.messageBus.connect(this).apply {
            subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) { highlight(event.newFile); canvas.revealFile() }
            })
            subscribe(PsiModificationTracker.TOPIC, PsiModificationTracker.Listener { SwingUtilities.invokeLater { rebuild(600) } })
            subscribe(ChangeListListener.TOPIC, object : ChangeListListener {
                override fun changeListUpdateDone() { SwingUtilities.invokeLater { if (!disposed) changesTimer.restart() } }
            })
        }
        refreshLens()
        highlight()
        entryPointJob = service.scope.launch(Dispatchers.EDT) {
            entryPoints.entryPoints.collect { if (!disposed) render(null) }
        }
    }

    private fun toolbar() = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        add(JLabel("Detail:"))
        add(detail.apply { addActionListener { resetDetail() } })
        add(JLabel("View:"))
        add(viewChoice.apply {
            addActionListener {
                sectionBy.isEnabled = selectedItem == GraphView.SECTIONS
                updateFlowControls()
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
        add(zoomControl({ canvas.zoom }, { canvas.zoom = it }, canvas))
    }

    private fun changesRow() = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        add(JLabel("Changes:"))
        add(changesMode)
        add(JLabel("vs"))
        add(changesBase)
        add(JLabel("Hops:"))
        add(changesHops)
        add(JButton("Refresh changes").apply {
            toolTipText = "Compare again and map the changed lines onto the graph"
            addActionListener { refreshChanges() }
        })
    }

    /** Turns on highlighting of the local changes, as the Tools menu action does. */
    fun showChanges() {
        changesSection.reveal()
        if (changesMode.selectedItem == ChangesMode.OFF) changesMode.selectedItem = ChangesMode.HIGHLIGHT
        else refreshChanges()
    }

    private fun changesModeChanged() {
        if (updatingChanges) return
        if (changesMode.selectedItem == ChangesMode.OFF) {
            diffJob?.cancel()
            changesStatus.text = ""
            rerender()
        } else if (diff?.graph?.let { it === result?.graph } == true) rerender()
        else refreshChanges()
    }

    /** The current diff while it belongs to the shown graph and the changes are on. */
    private fun activeDiff(): DiffResult? =
        diff?.takeIf { changesMode.selectedItem != ChangesMode.OFF && it.graph != null && it.graph === hierarchy?.graph }

    private val changedOnly: Boolean
        get() = changesMode.selectedItem == ChangesMode.CHANGED_ONLY && activeDiff()?.changed?.isNotEmpty() == true

    /** Shows a subset of the graph (search filter or changed only), where groups do not expand in place. */
    private val onlyView: Boolean get() = filtering || changedOnly

    /** Maps the VCS changes onto the current graph in the background; does nothing while the changes are off. */
    private fun refreshChanges() {
        if (disposed || changesMode.selectedItem == ChangesMode.OFF) return
        val built = result ?: return
        diffJob?.cancel()
        changesStatus.text = "Comparing…"
        val request = DiffRequest(changesBase.text.trim().takeIf { it.isNotEmpty() }, (changesHops.selectedItem as String).toIntOrNull())
        diffJob = service.scope.launch(Dispatchers.EDT) {
            try {
                val computed = diffService.compute(built, request)
                if (disposed || result !== built) return@launch
                diff = computed
                changesStatus.text = when {
                    computed.files.isEmpty() -> "No changes vs ${computed.base}"
                    else -> "${computed.files.size} file(s) vs ${computed.base}: +${computed.added} −${computed.removed} · " +
                        if (computed.changed.isEmpty()) "no changed symbols, showing the full graph"
                        else "${computed.changed.size} changed symbol(s), ${computed.neighborhood.size} with callers and callees"
                }
                rerender()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                thisLogger().info("Mapping changes failed", e)
                diff = null
                changesStatus.text = "Changes unavailable: ${e.message ?: e.javaClass.simpleName}"
                rerender()
            }
        }
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
        searchSection.hint = if (filtering) "filtering · ${matchCount.text}" else matchCount.text
        previousMatch.isEnabled = matches.isNotEmpty()
        nextMatch.isEnabled = matches.isNotEmpty()
    }

    /** Leaves the filter, keeping the query highlighted, and shows [source] expanded in the full graph. */
    fun showInGraph(source: Int) {
        activateGraph()
        viewChoice.selectedItem = GraphView.GRAPH
        val tree = hierarchy ?: return
        updatingSearch = true
        try { searchMode.selectedItem = SearchMode.HIGHLIGHT } finally { updatingSearch = false }
        updatingChanges = true
        try { if (changesMode.selectedItem == ChangesMode.CHANGED_ONLY) changesMode.selectedItem = ChangesMode.HIGHLIGHT } finally { updatingChanges = false }
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
        val changes = activeDiff()
        val filter = found.toHashSet().takeIf { filtering }
        val affected = changes?.takeIf { changedOnly }?.let { changedOnlyNodes(tree, it) }
        val shown = tree.view(expanded, excluded, if (filter != null && affected != null) filter.intersect(affected) else filter ?: affected)
        view = shown
        // Matches hidden by the lens cannot be shown.
        matches = found.filter { shown.shownId(it) != null }
        val sections = if (viewChoice.selectedItem != GraphView.SECTIONS) null else lens(null) {
            SectionLens.sections(tree, shown, sectionBy.selectedItem as SectionBy, snapshot, project.name, options)
        }
        canvas.show(shown.graph, shown.weights, sections)
        val decorations = snapshot?.let { lens(GraphDecorations.NONE) { EnrichmentLens.decorate(it, tree.graph, shown, options) } } ?: GraphDecorations.NONE
        val withChanges = changes?.let { lens(decorations) { decorateDiff(decorations, shown, it) } } ?: decorations
        canvas.decorate(EntryPointLens.decorate(withChanges, tree.graph, entryPoints.entryPoints.value, project.basePath, shown::shownId))
        highlight()
        markMatches()
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
            val inside = if (node.hidden > 0) " · ${node.hidden} inside, Enter to ${if (onlyView) "show in full graph" else "expand"}" else ""
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

    fun enrich() {
        val built = result ?: return
        enrichJob?.cancel()
        enrichButton.isEnabled = false
        flowPanel.setBusy(true)
        canvasPanel.setBusy(true)
        enrichJob = service.scope.launch(Dispatchers.EDT) {
            try {
                val snapshot = enrichment.enrich(built)
                val stats = snapshot.enrichment.stats
                status.text = "Enriched ${snapshot.enrichment.nodes.size} symbols, ${snapshot.enrichment.edges.size} calls, " +
                    "${snapshot.enrichment.flows.size} flows, ${snapshot.enrichment.domains.size} areas in ${stats.durationMillis / 1000}s " +
                    "(${stats.cacheHits} cached, ${stats.failures} failed)"
                reportButton.isEnabled = true
                refreshLens()
                flowPanel.refreshLens()
                canvasPanel.refreshLens()
                rerender()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                thisLogger().warn("Enrichment failed", e)
                status.text = "Enrichment failed: ${e.message ?: e.javaClass.simpleName}"
                flowPanel.setStatus(status.text)
                canvasPanel.setStatus(status.text)
            } finally {
                if (!disposed) { enrichButton.isEnabled = true; flowPanel.setBusy(false); canvasPanel.setBusy(false) }
            }
        }
    }

    fun openReport() {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(enrichment.reportPath) ?: return
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    /** Double-click or Enter: groups expand or collapse in place; other symbols open in the editor. */
    private fun activate(id: Int) {
        val tree = hierarchy ?: return
        val source = view?.sources?.getOrNull(id) ?: return
        when {
            !tree.isGroup(source) -> navigate(source)
            onlyView -> showInGraph(source)
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
            if (onlyView) add(item("Show in full graph") { showInGraph(source) })
            else if (tree.isGroup(source)) {
                if (source in open) add(item("Collapse") { setExpanded(open - source, source) })
                else add(item("Expand") { setExpanded(open + source, source) })
                add(item("Expand all inside") { setExpanded(open + tree.groupsWithin(source), source) })
            }
            tree.parent[source].takeIf { it >= 0 && !onlyView }?.let { parent ->
                add(item("Collapse into ${tree.nodes[parent].name}") { setExpanded(open - parent, parent) })
            }
            if (result?.pointers?.getOrNull(source) != null) add(item("Open source") { navigate(source) })
            if (tree.graph.nodes.getOrNull(source)?.kind == SymbolKind.FUNCTION) add(item("Add to flow chart") { flowPanel.addToChart(FlowStart(source)) })
            if (tree.graph.nodes.getOrNull(source)?.kind == SymbolKind.FUNCTION) add(item("Open in canvas") { canvasPanel.openFunction(source) })
        }.takeIf { it.componentCount > 0 }
    }

    fun rebuild(debounce: Long = 0) {
        if (disposed) return
        job?.cancel()
        job = service.scope.launch(Dispatchers.EDT) {
            delay(debounce)
            status.text = "Building application graph…"
            flowPanel.setStatus(status.text)
            canvasPanel.setBuilding()
            try {
                val built = service.buildProject()
                val tree = built.graph.hierarchy()
                result = built
                hierarchy = tree
                if (expandedKeys == null) expandedKeys = tree.expansion(detail.selectedItem as GraphDetail).mapTo(hashSetOf(), tree::key)
                render(null)
                refreshChanges()
                flowPanel.showResult(built)
                canvasPanel.showResult(built)
                flowsPanel.graphChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                thisLogger().warn("Application graph failed", e)
                status.text = "Could not build graph. Use Refresh to retry."
                flowPanel.setStatus(status.text)
                canvasPanel.setStatus(status.text)
            }
        }
    }

    override fun dispose() { disposed = true; searchTimer.stop(); changesTimer.stop(); job?.cancel(); enrichJob?.cancel(); diffJob?.cancel(); entryPointJob?.cancel() }

    private enum class SearchMode(val label: String) {
        HIGHLIGHT("Highlight"), FILTER("Filter");
        override fun toString() = label
    }

    private enum class ChangesMode(val label: String) {
        OFF("Off"), HIGHLIGHT("Highlight"), CHANGED_ONLY("Changed only");
        override fun toString() = label
    }

    private companion object {
        const val HINT = "Click a node for Side by side. Enter expands a group or opens a symbol's source."
    }
}
