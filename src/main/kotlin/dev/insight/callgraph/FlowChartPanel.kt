package dev.insight.callgraph

import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import dev.insight.agent.ToolInputException
import dev.insight.chat.ChartChatContext
import dev.insight.chat.ChatPanel
import dev.insight.chat.ChatSession
import dev.insight.core.describeFailure
import dev.insight.core.runCatchingReported
import dev.insight.enrichment.ColorBy
import dev.insight.enrichment.EnrichmentService
import dev.insight.enrichment.EnrichmentSnapshot
import dev.insight.enrichment.Flow
import dev.insight.enrichment.FlowChartLens
import dev.insight.enrichment.SymbolFacts
import dev.insight.enrichment.SymbolFactsExtractor
import dev.insight.entrypoints.EntryPointLens
import dev.insight.entrypoints.EntryPointService
import dev.insight.graph.CallGraph
import dev.insight.graph.CallGraphEdge
import dev.insight.graph.CallGraphResult
import dev.insight.graph.CallSite
import dev.insight.graph.FlowChart
import dev.insight.graph.FlowRegion
import dev.insight.graph.FlowRegionEvidence
import dev.insight.graph.FlowRegionPlan
import dev.insight.graph.FlowStart
import dev.insight.graph.GraphDecorations
import dev.insight.graph.Section
import dev.insight.graph.SourceSite
import dev.insight.graph.flowRegionCandidates
import dev.insight.graph.fuzzyNameSearch
import dev.insight.graph.stableKey
import dev.insight.ui.CollapsibleControls
import dev.insight.ui.PanelCommand
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import kotlinx.coroutines.*

/** Owns chart state and controls; coordinates with the other graph panels through [APPLICATION_GRAPH_TOPIC]. */
internal class FlowChartPanel(
    private val project: Project,
) : JPanel(BorderLayout(0, JBUI.scale(8))), Disposable {
    private val service = project.service<CallGraphService>()
    private val enrichment = project.service<EnrichmentService>()
    private val entryPoints = project.service<EntryPointService>()
    private var entryPointJob: Job? = null
    private var result: CallGraphResult? = null
    private var disposed = false
    private var enriching = false
    /** Set while the application graph is rebuilding; mirrors the graph panel's build job via [APPLICATION_GRAPH_TOPIC]. */
    private var rebuilding = false
    private val status = JLabel("Building application graph…")
    private val selection = JLabel(FLOW_HINT)
    internal val flowCanvas = FlowChartCanvas(::describeStep, { navigate(it) }, ::flowMenu, ::loadSteps,
        "Add a function from the graph, editor or Structure view, or choose an enriched flow above.", ::describeRegion, ::openScopedChat)
    /** Reads call sites and docs of steps that appeared on the flow chart. */
    private var flowJob: Job? = null
    private var summaryJob: Job? = null
    private val regionPlans = HashMap<String, List<FlowRegionPlan>>()
    private val compact = JCheckBox("Compact subflows", true)
    private val summarizeButton = JButton("Summarize subflows")
    private val collapseButton = JButton("Collapse subflows")
    /** Progress and outcome of summarizing; shown beside the status so it stays visible with Subflows collapsed. */
    private val summaryNotice = JLabel()
    /** Calls for the flow chart, made from [result], the latest enrichment and the colouring; see [renderFlow]. */
    private var flowLens: FlowLensFor? = null
    /** Call sites, exits and current source facts of flow chart steps, read once per build. */
    private val callSites = HashMap<Int, List<SourceSite>>()
    private val stepFacts = HashMap<Int, SymbolFacts>()
    private val stepsRead = hashSetOf<Int>()
    /** A symbol sent to the flow chart from the editor, waiting for a finished graph build; see [addToChart]. */
    private var pendingChart: SmartPsiElementPointer<PsiElement>? = null
    private val colorBy = ComboBox(ColorBy.entries.toTypedArray()).apply { selectedItem = ColorBy.DOMAIN }
    private val flowChoice = flowChoiceControl()
    private val addFlowButton = JButton("Add flow")
    private val previousStep = JButton("◀").apply { toolTipText = "Previous chart step" }
    private val nextStep = JButton("▶").apply { toolTipText = "Next chart step" }
    private val callsButton = JButton("Show calls")
    private val callersButton = JButton("Show callers")
    private val removeButton = JButton("Remove column")
    private val clearButton = JButton("Clear chart")
    private val enrichAction = PanelCommand("Enrich") { project.publishGraphEvent { enrichRequested() } }
    private val reportAction = PanelCommand("Report") { project.publishGraphEvent { reportRequested() } }
    private var updatingLens = false
    private val search = SearchTextField(false).apply {
        textEditor.columns = 24
        textEditor.toolTipText = "Fuzzy search of visible steps · Enter: next match · Shift+Enter: previous match"
    }
    private val previousMatch = JButton("Previous")
    private val nextMatch = JButton("Next")
    private val matchCount = JLabel()
    private data class ChartMatch(val node: Int? = null, val region: String? = null, val name: String)
    private var matches: List<ChartMatch> = emptyList()
    private var chatRevision = 0
    private val chatScope = CoroutineScope(service.scope.coroutineContext + SupervisorJob(service.scope.coroutineContext[Job]))
    /** The chat sessions of this chart; a restore point only undoes the latest chat change and never across a rebuild. */
    private val restoreGroup = ChartRestorePoint.Group { chatRevision }

    /** What this chart's chat sessions see; see [FlowChartChatContext]. */
    private val host = object : FlowChartHost {
        override val project get() = this@FlowChartPanel.project
        override val canvas get() = flowCanvas
        override val restoreGroup get() = this@FlowChartPanel.restoreGroup
        override val disposed get() = this@FlowChartPanel.disposed
        override val building get() = this@FlowChartPanel.rebuilding
        override val result get() = this@FlowChartPanel.result
        override val revision get() = chatRevision
        override fun lens(graph: CallGraph) = currentLens(graph)
        override val stepsRead: Set<Int> get() = this@FlowChartPanel.stepsRead
        override val callSites: Map<Int, List<SourceSite>> get() = this@FlowChartPanel.callSites
        override fun storeSteps(built: CallGraphResult, nodes: List<Int>, sites: Map<Int, List<SourceSite>>,
                                facts: Map<Int, SymbolFacts>) {
            if (result !== built || disposed) return
            rememberSteps(nodes, sites, facts)
            if (flowCanvas.chart?.steps.orEmpty().any { it.node in nodes }) renderFlow()
        }
        override suspend fun playTour(name: String, beforeChange: () -> Unit) = this@FlowChartPanel.playTour(name, beforeChange)
    }

    private fun currentLens(graph: CallGraph): FlowChartLens? = flowLens?.takeIf { it.graph === graph }?.lens

    private fun rememberSteps(nodes: List<Int>, sites: Map<Int, List<SourceSite>>, facts: Map<Int, SymbolFacts>) {
        stepsRead += nodes
        callSites += sites
        stepFacts += facts
    }

    private val tours = project.service<ChartTourStore>()

    /**
     * Resolves saved tour [name] against the current graph on the EDT, reads its snippets again in a background read
     * action, then shows it as a story with a notice of what was skipped; a rebuild in between fails instead.
     */
    internal suspend fun playTour(name: String, beforeChange: () -> Unit = {}): String {
        val (built, play) = withContext(Dispatchers.EDT) {
            val tour = tours.find(name) ?: throw ToolInputException("No saved tour named \"$name\"; list_tours shows the saved tours")
            val built = host.built()
            built to resolveTour(tour, built.graph, groundingEffects(currentLens(built.graph)))
        }
        val read = if (play.beats.none { it.scene.snippets.isNotEmpty() }) play
            else smartReadAction(project) { recaptureTourSnippets(play) { declarationSource(project, built, it) } }
        return withContext(Dispatchers.EDT) {
            if (host.built() !== built) throw ToolInputException("The graph was rebuilt while the tour was loading; play it again")
            playChartTour(built.graph, flowCanvas, chatRevision, read, groundingEffects(currentLens(built.graph)), beforeChange) { notice ->
                tourNotice = flowCanvas.story?.beats to notice
                updateStoryBar()
            }
        }
    }

    internal fun saveTour(name: String): SavedTour? {
        val story = flowCanvas.story ?: return null
        return savedTour(name.trim().take(SavedTour.MAX_NAME), story).also {
            tours.put(it)
            status.text = "Saved tour \"${it.name}\"; replay it from Tours."
        }
    }

    private fun askSaveTour() {
        val story = flowCanvas.story ?: return
        val name = Messages.showInputDialog(this, "Tour name:", "Save Tour", null, story.title.take(SavedTour.MAX_NAME), null)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (tours.find(name) != null && Messages.showYesNoDialog(this,
                "Replace the saved tour \"$name\"?", "Save Tour", null) != Messages.YES) return
        saveTour(name)
    }

    /** Saved tours: play, rename, delete and export each. */
    internal fun toursMenu(): JPopupMenu = JPopupMenu().apply {
        fun item(text: String, action: () -> Unit) = JMenuItem(text).apply { addActionListener { action() } }
        val saved = tours.tours
        if (saved.isEmpty()) add(JMenuItem("No saved tours; save a story with Save tour on its bar").apply { isEnabled = false })
        saved.forEach { tour ->
            add(JMenu("${tour.name} · ${tour.beats.size} beats").apply {
                add(item("Play") {
                    chatScope.launch(Dispatchers.EDT) {
                        try {
                            withBackgroundProgress(project, "Loading chart tour") { playTour(tour.name) }
                        } catch (e: CancellationException) {
                            ensureActive()
                            if (!disposed) status.text = "Tour loading cancelled. Choose Play to retry."
                            throw e
                        } catch (e: ToolInputException) { status.text = e.message }
                    }
                })
                add(item("Rename…") {
                    Messages.showInputDialog(this@FlowChartPanel, "New name:", "Rename Tour", null, tour.name, null)?.let { name ->
                        if (!tours.rename(tour.name, name)) status.text = "Could not rename: the name is empty or already used."
                    }
                })
                add(item("Delete") {
                    if (Messages.showYesNoDialog(this@FlowChartPanel, "Delete the saved tour \"${tour.name}\"?", "Delete Tour", null) == Messages.YES)
                        tours.delete(tour.name)
                })
                addSeparator()
                add(item("Copy as Markdown") {
                    CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(tourMarkdown(tour, project.basePath)))
                    status.text = "Copied tour \"${tour.name}\" as Markdown."
                })
                add(item("Export as Markdown…") {
                    val target = FileChooserFactory.getInstance().createSaveFileDialog(
                        FileSaverDescriptor("Export Tour", "Save the tour as Markdown", "md"), project)
                        .save(tour.name.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifEmpty { "tour" } + ".md") ?: return@item
                    val text = tourMarkdown(tour, project.basePath)
                    chatScope.launch(Dispatchers.EDT) {
                        runCatchingReported(thisLogger(), "Could not export chart tour") {
                            withBackgroundProgress(project, "Exporting chart tour", cancellable = false) {
                                withContext(Dispatchers.IO) { java.nio.file.Files.writeString(target.file.toPath(), text) }
                            }
                        }.onFailure {
                            if (!disposed) status.text = "Could not export tour: ${describeFailure(it)}"
                        }
                    }
                })
            })
        }
    }

    private val toursAction = object : DumbAwareAction("Tours", "Play, rename, delete or export saved tours", null) {
        init { templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true) }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            val anchor = e.inputEvent?.component ?: flowCanvas
            toursMenu().show(anchor, 0, anchor.height)
        }
    }

    internal val chatContext: ChartChatContext = FlowChartChatContext(host)

    /** Whether chart tools can act now: a finished graph that is not rebuilding (EDT). */
    internal val chartReady: Boolean get() = !disposed && result != null && !rebuilding

    /**
     * A chart session of its own for a review's /explain turn, and the functions declared in the [paths] (absolute) at the
     * current revision as JSON. Selects the chart tab; the result's Open Flow Chart action shows its window (EDT).
     */
    internal fun explainSession(paths: Set<String>): Pair<ChartChatContext, String> {
        val nodes = result?.graph?.nodes.orEmpty().filter { it.inProject && it.filePath in paths }
        val functions = nodes.take(MAX_EXPLAIN_FUNCTIONS).map { mapOf("id" to it.id, "name" to it.name,
            "kind" to it.kind.name.lowercase(), "container" to it.container, "location" to it.location) }
        project.activateGraphTab(this)
        return FlowChartChatContext(host) to Gson().toJson(mapOf("revision" to chatRevision, "functions" to functions,
            "omitted" to (nodes.size - functions.size)))
    }
    private val chatPanel = ChatPanel(project,
        ChatSession(project, chatScope, ChartChatBackendFactory(project, chatContext)), chartMode = true)
    private val chatTabs = JBTabbedPane().apply { addTab("Chart", chatPanel) }
    private var chatSection: CollapsibleControls.Section
    private var searchSection: CollapsibleControls.Section
    private val scopedChats = mutableMapOf<ChatPanel, CoroutineScope>()

    /** Every invocation gets an independent history and a fixed source anchor. */
    private fun openScopedChat(target: FlowChatTarget) {
        val built = result ?: return
        if (rebuilding || disposed) return
        val revision = chatRevision
        val sourceStamp = PsiModificationTracker.getInstance(project).modificationCount
        val ids = (target.nodes + listOfNotNull(target.owner)).toSet()
        val fragment = flowCanvas.chart?.steps.orEmpty().filter { it.node in ids }.map { step -> mapOf(
            "node_id" to step.node, "step_number" to step.number, "label" to step.card.title,
            "description" to step.card.description, "details" to step.card.details,
            "calls_open" to (step.node in flowCanvas.board.openCalls),
            "callers_open" to (step.node in flowCanvas.board.openCallers),
        ) }
        chatScope.launch(Dispatchers.EDT) {
            val captured = try {
                runCatchingReported(thisLogger(), "Could not capture chart chat context") {
                    withBackgroundProgress(project, "Opening scoped chart chat") {
                        smartReadAction(project) {
                            if (PsiModificationTracker.getInstance(project).modificationCount != sourceStamp)
                                throw ToolInputException("Source changed while opening scoped chat; choose To chat again")
                            scopedChartContext(project, built, revision, target)
                        }
                    }
                }.getOrElse {
                    status.text = "Could not capture chart context; try again."
                    return@launch
                }
            } catch (e: CancellationException) {
                ensureActive()
                if (!disposed && result === built && chatRevision == revision)
                    status.text = "Opening scoped chat cancelled. Choose To chat to retry."
                throw e
            }
            if (disposed) return@launch
            val context = object : FlowChartChatContext(host) {
                override val scoped = true
                override suspend fun snapshot(): String {
                    val current = chatContext.snapshot()
                    val stale = withContext(Dispatchers.EDT) {
                        revision != chatRevision || rebuilding ||
                            PsiModificationTracker.getInstance(project).modificationCount != sourceStamp
                    }
                    return Gson().toJson(mapOf("scoped_context" to com.google.gson.JsonParser.parseString(captured),
                        "captured_chart_fragment" to fragment, "scope_is_stale" to stale,
                        "current_chart" to com.google.gson.JsonParser.parseString(current)))
                }
            }
            val scope = CoroutineScope(chatScope.coroutineContext + SupervisorJob(chatScope.coroutineContext[Job]))
            val panel = ChatPanel(project,
                ChatSession(project, scope, ChartChatBackendFactory(project, context)), chartMode = true,
                scopeLabel = "${target.kind}: ${target.label.orEmpty().take(100)}")
            scopedChats[panel] = scope
            val title = target.label?.take(25)?.ifBlank { null } ?: target.kind
            chatTabs.addTab(title, panel)
            chatTabs.setTabComponentAt(chatTabs.indexOfComponent(panel), JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(JLabel(title))
                add(JButton("×").apply {
                    toolTipText = "Close scoped chat"
                    addActionListener {
                        scopedChats.remove(panel)?.cancel()
                        panel.dispose()
                        chatTabs.remove(panel)
                    }
                })
            })
            chatSection.reveal()
            chatTabs.selectedComponent = panel
            panel.input.requestFocusInWindow()
        }
    }

    private val storyTitle = JLabel().apply { font = font.deriveFont(java.awt.Font.BOLD) }
    private val storyPosition = JLabel()
    private val storyPrevious = JButton("◀").apply { toolTipText = "Previous beat (Page Up or Alt+Left on the chart)" }
    private val storyNext = JButton("▶").apply { toolTipText = "Next beat (Page Down or Alt+Right on the chart)" }
    private val storyCaption = com.intellij.ui.components.JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        columns = 20
    }
    /** What a replayed tour skipped or dropped, shown while that tour's story is open. */
    internal val storyNotice = com.intellij.ui.components.JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        columns = 20
        foreground = com.intellij.ui.JBColor.GRAY
        isVisible = false
    }
    /** The beats of the replayed tour the notice belongs to, and the notice. */
    private var tourNotice: Pair<List<StoryBeat>?, String?> = null to null
    internal val saveTourButton = JButton("Save tour").apply {
        toolTipText = "Save this story as a named tour of the project; replay it from Tours"
        addActionListener { askSaveTour() }
    }
    /** The active chat story: title, beat position and caption, stepped without the assistant. */
    internal val storyBar = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(2))).apply {
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(com.intellij.ui.JBColor.border()), JBUI.Borders.empty(4, 6))
        add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(storyTitle); add(storyPrevious); add(storyPosition); add(storyNext); add(saveTourButton)
        }, BorderLayout.NORTH)
        add(JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            isOpaque = false
            add(storyCaption, BorderLayout.CENTER)
            add(storyNotice, BorderLayout.SOUTH)
        }, BorderLayout.CENTER)
        add(JButton("×").apply {
            toolTipText = "Close the story; the current beat stays on the chart"
            addActionListener { flowCanvas.closeStory() }
        }, BorderLayout.EAST)
        isVisible = false
    }

    private fun updateStoryBar() {
        val story = flowCanvas.story
        storyBar.isVisible = story != null
        if (story?.beats !== tourNotice.first) tourNotice = null to null
        storyNotice.text = tourNotice.second.orEmpty()
        storyNotice.isVisible = tourNotice.second != null
        if (story != null) {
            storyTitle.text = story.title
            storyPosition.text = "${story.index + 1}/${story.beats.size}"
            storyCaption.text = story.beat.caption
            storyPrevious.isEnabled = story.index > 0
            storyNext.isEnabled = story.index < story.beats.lastIndex
        }
        storyBar.revalidate()
        storyBar.repaint()
    }

    init {
        border = JBUI.Borders.empty(8)
        flowCanvas.onStoryChange = ::updateStoryBar
        storyPrevious.addActionListener { flowCanvas.stepStory(-1) }
        storyNext.addActionListener { flowCanvas.stepStory(1) }
        fun row(vararg controls: Component) = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { controls.forEach(::add) }
        add(JPanel(BorderLayout(0, 4)).apply {
            add(CollapsibleControls(project, "insight.chart.controls", this@FlowChartPanel).apply {
                command(PanelCommand("Refresh", "Rebuild the application graph") { project.publishGraphEvent { rebuildRequested() } })
                command(enrichAction)
                command(reportAction)
                command(toursAction)
                separator()
                section("view", "View", row(
                    JLabel("Colour:"), colorBy, JLabel("Flow:"), flowChoice,
                    addFlowButton.apply { addActionListener { selectedFlow()?.let(::addFlow) } },
                    previousStep, nextStep, zoomControl({ flowCanvas.zoom }, { flowCanvas.zoom = it }, flowCanvas)))
                section("edit", "Edit", row(callsButton, callersButton, removeButton, clearButton,
                    JButton("Clear chat scene").apply {
                        toolTipText = "Remove the assistant's emphasis, badges, notes and links"
                        addActionListener { flowCanvas.showScene(ChartScene()) }
                    }))
                section("subflows", "Subflows", row(compact, summarizeButton, collapseButton,
                    JLabel("Expand a subflow to inspect its calls. Summarize adds LLM interpretations.")))
                searchSection = section("search", "Search", row(JLabel("Search chart:"), search, previousMatch, nextMatch, matchCount))
                chatSection = toggle("chat", "Chat", chatTabs)
            }, BorderLayout.NORTH)
            add(JPanel(BorderLayout(0, 4)).apply {
                add(status, BorderLayout.NORTH)
                add(summaryNotice, BorderLayout.SOUTH)
            }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(com.intellij.ui.OnePixelSplitter(false, 0.72f).apply {
            firstComponent = JPanel(BorderLayout()).apply {
                add(storyBar, BorderLayout.NORTH)
                add(JBScrollPane(flowCanvas), BorderLayout.CENTER)
            }
            secondComponent = chatTabs
            chatTabs.minimumSize = java.awt.Dimension(JBUI.scale(260), 0)
        }, BorderLayout.CENTER)
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
        compact.addActionListener { flowCanvas.setCompact(compact.isSelected) }
        collapseButton.addActionListener { flowCanvas.collapseRegions() }
        summarizeButton.toolTipText = "Send bounded source excerpts to the existing LLM server; grouping is validated against source call occurrences."
        summarizeButton.addActionListener {
            if (summaryJob?.isActive == true) summaryJob?.cancel() else summarizeSubflows()
        }
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
        enrichAction.isEnabled = false
        flowCanvas.setCompact(true)
        refreshLens()
        updateFlowControls()
        entryPointJob = service.scope.launch(Dispatchers.EDT) {
            entryPoints.entryPoints.collect {
                if (!disposed) {
                    flowLens = null
                    renderFlow()
                }
            }
        }
        // State and commands of the application graph panel, shared via the tool window's topic.
        project.messageBus.connect(this).subscribe(APPLICATION_GRAPH_TOPIC, object : ApplicationGraphListener {
            override fun graphBuilding() { rebuilding = true; setStatus("Building application graph…") }
            override fun graphBuilt(result: CallGraphResult) { rebuilding = false; showResult(result) }
            override fun graphBuildFailed(message: String) { rebuilding = false; setStatus(message) }
            override fun enrichmentChanged() = refreshLens()
            override fun statusChanged(text: String) = setStatus(text)
            override fun busyChanged(busy: Boolean) = setBusy(busy)
            override fun addToChart(start: FlowStart) = this@FlowChartPanel.addToChart(start)
        })
    }

    fun showResult(built: CallGraphResult) {
        chatRevision++
        result?.let { moveFlowChart(it.graph, built.graph) }
        result = built
        enrichAction.isEnabled = !enriching
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
            reportAction.isEnabled = enrichment.latest != null
        } finally { updatingLens = false }
        renderFlow()
    }

    fun setBusy(busy: Boolean) {
        enriching = busy
        enrichAction.isEnabled = !busy && result != null
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
        collapseButton.isEnabled = flowCanvas.board.openRegions.isNotEmpty()
        compact.isSelected = flowCanvas.board.compact
        summarizeButton.isEnabled = summaryJob?.isActive == true || (flowCanvas.board.compact && candidates().isNotEmpty())
        summarizeButton.text = if (summaryJob?.isActive == true) "Stop summary" else "Summarize subflows"
        status.text = if (result == null) "Building application graph…"
            else flowCanvas.chart?.let { "${it.lanes.size} columns · ${it.steps.size} function steps · ${it.regions.size} subflows · static in-project calls" }
                ?: "Add a function or flow to start a chart."
        updateSearch()
    }

    private fun updateSearch() {
        val nodes = result?.graph?.nodes.orEmpty()
        val visible = flowCanvas.chart?.steps.orEmpty().map { ChartMatch(node = it.node, name = nodes[it.node].name) } +
            flowCanvas.chart?.regions.orEmpty().map { ChartMatch(region = it.plan.evidence.key, name = it.plan.title) }
        matches = fuzzyNameSearch(search.text, visible.indices.toList()) { visible[it].name }.map { visible[it] }
        flowCanvas.markMatches(matches.mapNotNull { it.node }.toSet().takeIf { search.text.isNotBlank() })
        flowCanvas.markRegionMatches(matches.mapNotNull { it.region }.toSet())
        val index = matches.indexOfFirst { (it.node != null && it.node == flowCanvas.selectedNode) ||
            (it.region != null && it.region == flowCanvas.selectedRegion) }
        matchCount.text = when {
            search.text.isBlank() -> ""
            matches.isEmpty() -> "No matches"
            index >= 0 -> "${index + 1} of ${matches.size}"
            else -> "${matches.size} matches"
        }
        searchSection.hint = matchCount.text
        previousMatch.isEnabled = matches.isNotEmpty()
        nextMatch.isEnabled = matches.isNotEmpty()
    }

    private fun stepMatch(delta: Int) {
        if (matches.isEmpty()) return
        val index = matches.indexOfFirst { (it.node != null && it.node == flowCanvas.selectedNode) ||
            (it.region != null && it.region == flowCanvas.selectedRegion) }
        val next = if (index < 0) (if (delta > 0) 0 else matches.lastIndex) else Math.floorMod(index + delta, matches.size)
        val match = matches[next]
        match.node?.let(flowCanvas::selectNode) ?: match.region?.let(flowCanvas::selectRegion)
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
            ?: lens<FlowLensFor?>(null) { FlowLensFor(built.graph, snapshot, colour, FlowChartLens(snapshot, built.graph, colour,
                EntryPointLens.decorate(GraphDecorations.NONE, built.graph, entryPoints.entryPoints.value, project.basePath))) }
        flowLens = made
        if (made == null) flowCanvas.show(null, "Could not draw the flow chart.")
        else lens(Unit) { flowCanvas.show(made.lens.source(callSites, stepFacts, regionPlans)) }
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
            val (sites, facts) = runCatchingReported(thisLogger(), "Reading call sites failed") {
                // Automatic source loading must finish for branch conditions to appear. Rebuild/dispose still cancel its job.
                withBackgroundProgress(project, "Reading flow chart steps", cancellable = false) {
                    smartReadAction(project) {
                        val callees = missing.flatMap { id -> lens.calls[id].orEmpty().map { it.callee } }
                        CallSiteAnalyzer.collect(built, missing, callees) to SymbolFactsExtractor.extract(project, built, only = missing.toSet())
                    }
                }
            }.getOrDefault(emptyMap<Int, List<SourceSite>>() to emptyMap()) // Without call sites the calls keep the graph's order and have no branches.
            if (result !== built || disposed) return@launch
            rememberSteps(missing, sites, facts)
            flowJob = null
            renderFlow()
        }
    }

    /**
     * Only the structural regions on the chart are eligible, as drawn (folded helpers left out); deeper source is read
     * when the user drills down.
     */
    private fun candidates(): List<FlowRegionEvidence> = flowCanvas.chart?.regions.orEmpty()
        .filter { !it.plan.inferred }.map { it.plan.evidence }.filter { it.key !in regionPlans }.distinctBy { it.key }

    private fun summarizeSubflows() {
        val built = result ?: return
        val lens = flowLens?.lens ?: return
        val pending = candidates()
        val batch = pending.take(24)
        if (batch.isEmpty()) return
        summaryNotice.text = "Summarizing ${batch.size} bounded regions…"
        summarizeButton.text = "Stop summary"
        summaryJob = service.scope.launch(Dispatchers.EDT) {
            try {
                runCatchingReported(thisLogger(), "Summarizing subflows failed") {
                    withBackgroundProgress(project, "Summarizing subflows") {
                        val ids = batch.flatMap { listOf(it.owner) + it.sites.map(CallSite::callee) }.toSet()
                        val (facts, currentSites, stamp) = smartReadAction(project) {
                            val owners = batch.map { it.owner }.distinct()
                            val callees = owners.flatMap { lens.calls[it].orEmpty().map(CallGraphEdge::callee) }
                            Triple(SymbolFactsExtractor.extract(project, built, only = ids),
                                CallSiteAnalyzer.collect(built, owners, callees), PsiModificationTracker.getInstance(project).modificationCount)
                        }
                        val folded = if (flowCanvas.board.compact) lens.folded else emptySet()
                        val current = currentSites.flatMap { (owner, sites) -> flowRegionCandidates(owner, sites, folded) }.toSet()
                        if (batch.any { it !in current }) {
                            summaryNotice.text = "Source changed; refresh the chart before summarizing."
                            return@withBackgroundProgress
                        }
                        val summaries = enrichment.summarizeSubflows(batch, facts, lens.freshSummaries(facts))
                        if (disposed || result !== built) return@withBackgroundProgress
                        val unchanged = smartReadAction(project) { PsiModificationTracker.getInstance(project).modificationCount == stamp }
                        if (!unchanged || disposed || result !== built) {
                            summaryNotice.text = "Source changed during summary; stale interpretations were discarded."
                            return@withBackgroundProgress
                        }
                        regionPlans += summaries.plans
                        stepFacts += facts
                        renderFlow()
                        val accepted = summaries.plans.values.sumOf { it.size }
                        summaryNotice.text = "$accepted LLM subflows · ${summaries.failedBlocks} regions kept structural" +
                            (if (summaries.rejectedGroups > 0) " · ${summaries.rejectedGroups} invalid groups rejected" else "") +
                            (if (pending.size > batch.size) " · summarize again for remaining regions" else "")
                    }
                }.onFailure {
                    if (!disposed && result === built) summaryNotice.text = "Could not summarize; structural groups remain available."
                }
            } catch (e: CancellationException) {
                if (!disposed && result === built) summaryNotice.text = "Summary stopped; existing subflows retained."
                throw e
            } finally {
                if (summaryJob === coroutineContext.job) summaryJob = null
                if (!disposed) updateFlowControls()
            }
        }
    }

    private fun describeRegion(region: FlowRegion?) {
        if (region == null) return
        val evidence = region.plan.evidence
        selection.text = "${region.plan.title} · ${evidence.sites.size} call occurrences in " +
            (flowLens?.lens?.card(evidence.owner)?.title ?: "function") + " · right-click for source evidence"
        updateFlowControls()
    }

    /** Places [start] on the flow chart, switching to it. */
    fun addToChart(start: FlowStart) {
        flowCanvas.add(start)
        // With summaries, a placed function opens down its main path within a budget; without, only its own calls.
        if (flowCanvas.board.compact) flowLens?.lens?.let { flowCanvas.openCalls(it.openCalls(start.node)) }
        project.activateGraphTab(this)
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
        if (built == null || rebuilding) return
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
                project.publishGraphEvent { showInGraph(source) }
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
        summaryJob?.cancel()
        regionPlans.clear()
        summaryNotice.text = "Source changed; subflow summaries will be recomputed on request."
        callSites.clear()
        stepFacts.clear()
        stepsRead.clear()
        val ids = HashMap<String, Int>()
        next.nodes.forEach { ids.putIfAbsent(stableKey(it), it.id) }
        flowCanvas.remap { id -> previous.nodes.getOrNull(id)?.let { ids[stableKey(it)] } }
    }

    override fun dispose() {
        disposed = true
        entryPointJob?.cancel()
        chatScope.cancel()
        scopedChats.keys.forEach { it.dispose() }
        scopedChats.clear()
        chatPanel.dispose()
        flowJob?.cancel()
        summaryJob?.cancel()
    }
    private class FlowLensFor(val graph: CallGraph, val snapshot: EnrichmentSnapshot?, val colorBy: ColorBy, val lens: FlowChartLens)
    private companion object {
        const val MAX_EXPLAIN_FUNCTIONS = 80
        const val FLOW_HINT = "Click to select · Enter opens source · Up/Down step · Left toggles callers · Right/Space toggles calls · Delete removes column"
    }
}
