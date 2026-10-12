package dev.insight.callgraph

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.service
import dev.insight.enrichment.ColorBy
import dev.insight.enrichment.EnrichmentService
import dev.insight.enrichment.EnrichmentSnapshot
import dev.insight.enrichment.Flow
import dev.insight.enrichment.FlowChartLens
import dev.insight.enrichment.SymbolFacts
import dev.insight.enrichment.SymbolFactsExtractor
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.pom.Navigatable
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.ActionEvent
import javax.swing.*
import javax.swing.event.DocumentEvent

/** Owns chart state and controls; the application analysis and enrichment are shared with the graph panel. */
internal class FlowChartPanel(
    private val project: Project,
    private val activateChart: () -> Unit,
    private val showInGraph: (Int) -> Unit,
    private val refresh: () -> Unit,
    private val enrich: () -> Unit,
    private val openReport: () -> Unit,
    private val building: () -> Boolean,
) : JPanel(BorderLayout(0, JBUI.scale(8))), Disposable {
    private val service = project.service<CallGraphService>()
    private val enrichment = project.service<EnrichmentService>()
    private var result: CallGraphResult? = null
    private var disposed = false
    private var enriching = false
    private val status = JLabel("Building application graph…")
    private val selection = JLabel(FLOW_HINT)
    internal val flowCanvas = FlowChartCanvas(::describeStep, { navigate(it) }, ::flowMenu, ::loadSteps,
        "Add a function from the graph, editor or Structure view, or choose an enriched flow above.")
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
    private val colorBy = JComboBox(ColorBy.entries.toTypedArray()).apply { selectedItem = ColorBy.DOMAIN }
    private val flowChoice = flowChoiceControl()
    private val addFlowButton = JButton("Add flow")
    private val previousStep = JButton("◀").apply { toolTipText = "Previous chart step" }
    private val nextStep = JButton("▶").apply { toolTipText = "Next chart step" }
    private val callsButton = JButton("Show calls")
    private val callersButton = JButton("Show callers")
    private val removeButton = JButton("Remove column")
    private val clearButton = JButton("Clear chart")
    private val enrichButton = JButton("Enrich")
    private val reportButton = JButton("Report")
    private var updatingLens = false
    private val search = SearchTextField(false).apply {
        textEditor.columns = 24
        textEditor.toolTipText = "Fuzzy search of visible steps · Enter: next match · Shift+Enter: previous match"
    }
    private val previousMatch = JButton("Previous")
    private val nextMatch = JButton("Next")
    private val matchCount = JLabel()
    private var matches: List<Int> = emptyList()

    init {
        border = JBUI.Borders.empty(8)
        fun row(vararg controls: Component) = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { controls.forEach(::add) }
        add(JPanel(GridLayout(0, 1, 0, 4)).apply {
            add(row(JButton("Refresh").apply { addActionListener { refresh() } },
                enrichButton.apply { addActionListener { enrich() } },
                reportButton.apply { addActionListener { openReport() } },
                JLabel("Colour:"), colorBy, JLabel("Flow:"), flowChoice,
                addFlowButton.apply { addActionListener { selectedFlow()?.let(::addFlow) } },
                previousStep, nextStep, zoomControl({ flowCanvas.zoom }, { flowCanvas.zoom = it }, flowCanvas)))
            add(row(callsButton, callersButton, removeButton, clearButton))
            add(row(JLabel("Search chart:"), search, previousMatch, nextMatch, matchCount))
            add(status)
        }, BorderLayout.NORTH)
        add(JBScrollPane(flowCanvas), BorderLayout.CENTER)
        add(selection, BorderLayout.SOUTH)
        colorBy.addActionListener { if (!updatingLens) renderFlow() }
        flowChoice.addActionListener {
            if (!updatingLens) { selectedFlow()?.let(::addFlow); updateFlowControls() }
        }
        previousStep.addActionListener { flowCanvas.step(-1)?.let { navigate(it, false) } }
        nextStep.addActionListener { flowCanvas.step(1)?.let { navigate(it, false) } }
        callsButton.addActionListener { flowCanvas.selectedNode?.let(flowCanvas::toggleCalls) }
        callersButton.addActionListener { flowCanvas.selectedNode?.let(flowCanvas::toggleCallers) }
        removeButton.addActionListener { flowCanvas.selectedNode?.let(flowCanvas::removeColumn) }
        clearButton.addActionListener { flowCanvas.clear() }
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { updateSearch() }
        })
        previousMatch.addActionListener { stepMatch(-1) }
        nextMatch.addActionListener { stepMatch(1) }
        for ((stroke, delta) in listOf("ENTER" to 1, "shift ENTER" to -1)) {
            search.textEditor.inputMap.put(KeyStroke.getKeyStroke(stroke), stroke)
            search.textEditor.actionMap.put(stroke, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) { stepMatch(delta) }
            })
        }
        enrichButton.isEnabled = false
        refreshLens()
        updateFlowControls()
    }

    fun showResult(built: CallGraphResult) {
        result?.let { moveFlowChart(it.graph, built.graph) }
        result = built
        enrichButton.isEnabled = !enriching
        refreshLens()
        SwingUtilities.invokeLater { if (!disposed) placePending() }
    }

    fun refreshLens() {
        val id = selectedFlow()?.id
        updatingLens = true
        try {
            val choices = listOf(FlowChoice(null)) + enrichment.latest?.enrichment?.flows.orEmpty().map(::FlowChoice)
            flowChoice.model = DefaultComboBoxModel(choices.toTypedArray())
            flowChoice.selectedItem = choices.firstOrNull { it.flow?.id == id } ?: choices.first()
            colorBy.isEnabled = enrichment.latest != null
            flowChoice.isEnabled = enrichment.latest != null
            reportButton.isEnabled = enrichment.latest != null
        } finally { updatingLens = false }
        renderFlow()
    }

    fun setBusy(busy: Boolean) {
        enriching = busy
        enrichButton.isEnabled = !busy && result != null
    }

    fun setStatus(message: String) { status.text = message }

    private fun selectedFlow() = (flowChoice.selectedItem as? FlowChoice)?.flow

    private fun updateFlowControls() {
        val node = flowCanvas.selectedNode
        val step = node?.let { flowCanvas.chart?.step(it) }
        addFlowButton.isEnabled = result != null && selectedFlow() != null
        flowChoice.toolTipText = selectedFlow()?.description
        previousStep.isEnabled = flowCanvas.chart != null
        nextStep.isEnabled = previousStep.isEnabled
        callsButton.isEnabled = step != null && step.calls > 0
        callersButton.isEnabled = step != null && step.callers > 0
        callsButton.text = if (node in flowCanvas.board.openCalls) "Hide calls" else "Show calls"
        callersButton.text = if (node in flowCanvas.board.openCallers) "Hide callers" else "Show callers"
        removeButton.isEnabled = flowCanvas.board.starts.any { it.node == node }
        clearButton.isEnabled = flowCanvas.board.starts.isNotEmpty()
        status.text = if (result == null) "Building application graph…"
            else flowCanvas.chart?.let { "${it.lanes.size} columns · ${it.steps.size} steps" } ?: "Add a function or flow to start a chart."
        updateSearch()
    }

    private fun updateSearch() {
        val nodes = result?.graph?.nodes.orEmpty()
        matches = fuzzyNameSearch(search.text, flowCanvas.chart?.steps.orEmpty().map { it.node }) { nodes[it].name }
        flowCanvas.markMatches(matches.toSet().takeIf { search.text.isNotBlank() })
        val index = matches.indexOf(flowCanvas.selectedNode)
        matchCount.text = when {
            search.text.isBlank() -> ""
            matches.isEmpty() -> "No matches"
            index >= 0 -> "${index + 1} of ${matches.size}"
            else -> "${matches.size} matches"
        }
        previousMatch.isEnabled = matches.isNotEmpty()
        nextMatch.isEnabled = matches.isNotEmpty()
    }

    private fun stepMatch(delta: Int) {
        if (matches.isEmpty()) return
        val index = matches.indexOf(flowCanvas.selectedNode)
        val next = if (index < 0) (if (delta > 0) 0 else matches.lastIndex) else Math.floorMod(index + delta, matches.size)
        flowCanvas.selectNode(matches[next])
    }

    private fun <T> lens(fallback: T, block: () -> T): T = try { block() } catch (e: Throwable) {
        if (e is ControlFlowException || e is CancellationException || e is VirtualMachineError) throw e
        thisLogger().warn("Flow chart lens failed", e)
        fallback
    }

    private fun navigate(source: Int, focus: Boolean = true) {
        (result?.pointers?.getOrNull(source)?.element?.navigationElement as? Navigatable)
            ?.takeIf { it.canNavigate() }?.navigate(focus)
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
        val colour = colorBy.selectedItem as ColorBy
        val made = flowLens?.takeIf { it.graph === built.graph && it.snapshot === snapshot && it.colorBy == colour }
            ?: lens<FlowLensFor?>(null) { FlowLensFor(built.graph, snapshot, colour, FlowChartLens(snapshot, built.graph, colour)) }
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
            renderFlow()
        }
    }

    /** Places [start] on the flow chart, switching to it. */
    fun addToChart(start: FlowStart) {
        flowCanvas.add(start)
        activateChart()
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
        if (built == null || building()) return
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
            else listOfNotNull("${node.kind.name.lowercase()} ${node.name}", node.location.ifEmpty { null }, flowLens?.lens?.card(source)?.details)
                .joinToString(" · ")
        updateFlowControls()
    }

    /** The menu of a step or reference to [source], or of the chart itself for null. */
    private fun flowMenu(source: Int?): JPopupMenu? = JPopupMenu().apply {
        fun item(text: String, action: () -> Unit) = add(JMenuItem(text).apply { addActionListener { action() } })
        val board = flowCanvas.board
        val step = source?.let { flowCanvas.chart?.step(it) }
        if (source != null) {
            if (result?.pointers?.getOrNull(source) != null) item("Open source") { navigate(source) }
            item("Show in graph") {
                showInGraph(source)
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

    override fun dispose() { disposed = true; flowJob?.cancel() }
    private class FlowLensFor(val graph: CallGraph, val snapshot: EnrichmentSnapshot?, val colorBy: ColorBy, val lens: FlowChartLens)
    private companion object {
        const val FLOW_HINT = "Click to select · Enter opens source · Up/Down step · Left toggles callers · Right/Space toggles calls · Delete removes column"
    }
}
