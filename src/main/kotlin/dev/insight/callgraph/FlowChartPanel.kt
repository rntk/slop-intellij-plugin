package dev.insight.callgraph

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import dev.insight.enrichment.ColorBy
import dev.insight.enrichment.EnrichmentService
import dev.insight.enrichment.EnrichmentSnapshot
import dev.insight.enrichment.Flow
import dev.insight.enrichment.FlowChartLens
import dev.insight.enrichment.SymbolFacts
import dev.insight.enrichment.SymbolFactsExtractor
import dev.insight.entrypoints.EntryPointService
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.pom.Navigatable
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.google.gson.Gson
import dev.insight.chat.ChartChatContext
import dev.insight.chat.ChatPanel
import dev.insight.chat.ChatSession
import dev.insight.chat.ToolInputException
import dev.insight.ui.CollapsibleControls
import dev.insight.ui.PanelCommand
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
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
    private val entryPoints = project.service<EntryPointService>()
    private var entryPointJob: Job? = null
    private var result: CallGraphResult? = null
    private var disposed = false
    private var enriching = false
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
    private val colorBy = JComboBox(ColorBy.entries.toTypedArray()).apply { selectedItem = ColorBy.DOMAIN }
    private val flowChoice = flowChoiceControl()
    private val addFlowButton = JButton("Add flow")
    private val previousStep = JButton("◀").apply { toolTipText = "Previous chart step" }
    private val nextStep = JButton("▶").apply { toolTipText = "Next chart step" }
    private val callsButton = JButton("Show calls")
    private val callersButton = JButton("Show callers")
    private val removeButton = JButton("Remove column")
    private val clearButton = JButton("Clear chart")
    private val enrichAction = PanelCommand("Enrich") { enrich() }
    private val reportAction = PanelCommand("Report") { openReport() }
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
    private data class OutlineCapture(
        val built: CallGraphResult,
        val current: Int,
        val steps: Map<Int, Int>,
        val lens: FlowChartLens?,
        val knownSites: Map<Int, List<SourceSite>>,
        val regions: List<FlowRegion>,
        val missing: List<Int>,
    )

    /** Chart tools for one chat session; each session owns its restore point. */
    private inner class SessionChartContext : ChartChatContext {
        private val restorePoint = ChartRestorePoint(flowCanvas, restoreGroup)
        private fun graph(): CallGraph {
            if (disposed) throw ToolInputException("Chart closed")
            if (building()) throw ToolInputException("Graph is rebuilding; retry after it finishes")
            return result?.graph ?: throw ToolInputException("Graph is not ready yet")
        }

        private fun semantics(lens: FlowChartLens?): ((Int) -> NodeSemanticInfo?)? = lens?.let { l ->
            { id: Int ->
                val summary = l.summaries[id]
                val effects = l.effectsOf(id)
                NodeSemanticInfo(
                    summaryLabel = summary?.label,
                    summaryDescription = summary?.description,
                    role = summary?.role?.id,
                    ownEffects = effects?.own.orEmpty(),
                    reachedEffects = effects?.reached.orEmpty(),
                    entryPoint = l.isEntryPoint(id),
                )
            }
        }

        override suspend fun state(offset: Int): String = withContext(Dispatchers.EDT) {
            if (disposed) throw ToolInputException("Chart closed")
            val graph = result?.graph
            if (graph == null || building()) "{\"status\":\"Graph is building; tools will be available when it finishes\"}"
            else chartChatState(chatRevision, graph, flowCanvas, offset)
        }

        override suspend fun find(query: String, offset: Int): String = findNodes(query, offset)

        override suspend fun findNodes(query: String?, offset: Int, role: String?, effect: String?, entryPoint: Boolean?): String =
            analyze { graph, current, steps, lens -> chartFindNodes(graph, current, query, offset, role, effect, entryPoint, semantics(lens), steps) }

        override suspend fun functionOutline(revision: Int, nodes: List<Int>): String {
            val (built, current, steps, lens, knownSites, regions, missing) = withContext(Dispatchers.EDT) {
                val built = built()
                if (revision != chatRevision) throw ToolInputException("Stale graph revision; refresh and resolve IDs again")
                if (nodes.size !in 1..5) throw ToolInputException("Give 1 to 5 node IDs")
                nodes.forEach { validateChatNode(built.graph, chatRevision, revision, it) }
                val lens = flowLens?.takeIf { it.graph === built.graph }?.lens
                val steps = flowCanvas.chart?.steps.orEmpty().associate { it.node to it.number }
                val regions = flowCanvas.chart?.regions.orEmpty()
                val missing = nodes.filter { it !in stepsRead && built.pointers.getOrNull(it) != null }
                val knownSites = HashMap(callSites)
                OutlineCapture(built, chatRevision, steps, lens, knownSites, regions, missing)
            }
            val newSites = if (missing.isNotEmpty()) {
                val (sites, facts) = smartReadAction(project) {
                    val callees = missing.flatMap { id -> lens?.calls?.get(id).orEmpty().map { it.callee } }
                    CallSiteAnalyzer.collect(built, missing, callees) to
                        SymbolFactsExtractor.extract(project, built, only = missing.toSet())
                }
                withContext(Dispatchers.EDT) {
                    if (result === built && !disposed) {
                        stepsRead += missing
                        callSites += sites
                        stepFacts += facts
                        if (flowCanvas.chart?.steps.orEmpty().any { it.node in missing }) renderFlow()
                    }
                }
                sites
            } else emptyMap()
            val combinedSites = knownSites + newSites
            return withContext(Dispatchers.Default) {
                chartFunctionOutline(built.graph, current, revision, nodes, combinedSites, regions, semantics(lens), steps)
            }
        }

        override suspend fun flowCatalog(revision: Int, query: String?, offset: Int): String = analyze { graph, current, steps, lens ->
            val mapData = projectMapData(graph, lens)
            chartFlowCatalog(graph, current, revision, query, offset,
                lens?.flows?.map { CatalogFlow(it.id, it.name, it.description, it.entry, it.nodes) },
                mapData.entryPoints, semantics(lens), steps)
        }

        override suspend fun apply(action: String, revision: Int, nodes: List<Int>): String = withContext(Dispatchers.EDT) {
            applyChartChatAction(graph(), flowCanvas, chatRevision, action, revision, nodes, restorePoint::checkpoint)
        }

        override suspend fun describe(revision: Int, node: Int, offset: Int): String = withContext(Dispatchers.EDT) {
            chartNodeDetails(graph(), chatRevision, revision, node, offset)
        }

        override suspend fun showScene(revision: Int, scene: ChartScene): String {
            val read = captured(revision, listOf(scene), story = false).single()
            return withContext(Dispatchers.EDT) {
                val graph = graph()
                applyChartScene(graph, flowCanvas, chatRevision, revision, read, effects(graph), restorePoint::checkpoint)
            }
        }

        private fun effects(graph: CallGraph) = groundingEffects(graph)

        /**
         * Checks [scenes] on the EDT, then reads their snippets from source in a background read action. The scenes are
         * applied afterwards on the EDT, where a rebuild in between fails the revision check.
         */
        private suspend fun captured(revision: Int, scenes: List<ChartScene>, story: Boolean): List<ChartScene> {
            if (scenes.none { it.snippets.isNotEmpty() }) return scenes
            val built = withContext(Dispatchers.EDT) {
                val graph = graph()
                if (revision != chatRevision) throw ToolInputException("Stale graph revision; get_chart_state and resolve node IDs again")
                if (story) checkStoryBeats(graph, scenes.map { StoryBeat("", it) }, effects(graph), captured = false)
                else checkChartScene(graph, scenes.single(), effects(graph), captured = false)
                result ?: throw ToolInputException("Graph is not ready yet")
            }
            return smartReadAction(project) {
                scenes.mapIndexed { i, scene ->
                    val read = { captureSnippets(scene) { declarationSource(project, built, it) } }
                    if (story) storyBeat(i, read) else read()
                }
            }
        }

        override suspend fun clearScene(revision: Int): String = withContext(Dispatchers.EDT) {
            graph()
            clearChartScene(flowCanvas, chatRevision, revision, restorePoint::checkpoint)
        }

        /** Captures the immutable graph, lens and visible steps on the EDT; the bounded search runs in the background. */
        private suspend fun <T> analyze(block: (CallGraph, Int, Map<Int, Int>, FlowChartLens?) -> T): T {
            val (captured, lens) = withContext(Dispatchers.EDT) {
                val graph = graph()
                Triple(graph, chatRevision, flowCanvas.chart?.steps.orEmpty().associate { it.node to it.number }) to
                    flowLens?.takeIf { it.graph === graph }?.lens
            }
            return withContext(Dispatchers.Default) { block(captured.first, captured.second, captured.third, lens) }
        }

        override suspend fun tracePath(revision: Int, from: Int, to: Int, maxPaths: Int, maxDepth: Int): String =
            analyze { graph, current, steps, _ -> chartTracePath(graph, current, revision, from, to, maxPaths, maxDepth, steps) }

        override suspend fun reachability(revision: Int, node: Int): String = analyze { graph, current, steps, lens ->
            val effects = lens?.takeIf { it.effectsOf(node) != null }?.let { l -> { id: Int -> l.effectsOf(id)?.let { it.own to it.reached } } }
            chartReachability(graph, current, revision, node, lens?.takeIf { it.knowsEntryPoints }?.let { it::isEntryPoint }, effects, steps)
        }

        override suspend fun suggestExternals(revision: Int, nodes: List<Int>): String = analyze { graph, current, steps, lens ->
            chartExternalEffects(graph, current, revision, nodes, { id ->
                lens?.effectsOf(id)?.let { RuleEffects(it.own, it.reached, lens.effectImports(id)) } }, steps)
        }

        override suspend fun presentStory(revision: Int, title: String, beats: List<StoryBeat>): String {
            val read = captured(revision, beats.map { it.scene }, story = true)
            return withContext(Dispatchers.EDT) {
                val graph = graph()
                presentChartStory(graph, flowCanvas, chatRevision, revision, title,
                    beats.zip(read) { beat, scene -> beat.copy(scene = scene) }, effects(graph), restorePoint::checkpoint)
            }
        }

        override suspend fun projectMap(offset: Int): String = analyze { graph, current, _, lens ->
            chartProjectMap(graph, current, offset, projectMapData(graph, lens))
        }

        override suspend fun listTours(): String = withContext(Dispatchers.EDT) { tourListData(tours.tours) }

        override suspend fun playTour(name: String): String = this@FlowChartPanel.playTour(name, restorePoint::checkpoint)

        override fun beginReply() = restorePoint.begin()
        override val restorable: Boolean get() = !disposed && restorePoint.available
        override fun restore(): Boolean = !disposed && !building() && restorePoint.restore()

        override val revision: Int get() = chatRevision
        override val storyId: Int? get() = flowCanvas.story?.id
        override fun referenceName(revision: Int, node: Int) =
            chartReferenceName(result?.graph?.takeIf { !building() }, chatRevision, revision, node)
        override fun openReference(revision: Int, node: Int): String? {
            if (disposed) return "Chart closed."
            val graph = result?.graph?.takeIf { !building() } ?: return "The graph is rebuilding; try again when it finishes."
            return openChartReference(graph, flowCanvas, chatRevision, revision, node)
        }
        override fun beatTitle(revision: Int, beat: Int): String? = beatTitle(revision, beat, storyId)
        override fun beatTitle(revision: Int, beat: Int, storyId: Int?): String? =
            chartBeatTitle(flowCanvas.takeIf { !disposed && !building() }, chatRevision, revision, beat, storyId)

        override fun openBeat(revision: Int, beat: Int): String? = openBeat(revision, beat, storyId)
        override fun openBeat(revision: Int, beat: Int, storyId: Int?): String? {
            if (disposed) return "Chart closed."
            if (building() || result == null) return "The graph is rebuilding; try again when it finishes."
            return openChartBeat(flowCanvas, chatRevision, revision, beat, storyId)
        }
        override fun hoverReference(revision: Int, node: Int?) {
            if (!disposed) flowCanvas.hover(node?.takeIf { revision == chatRevision })
        }
    }
    /** Own plus reached rule effects for grounding externals; null without enrichment of the current graph. EDT. */
    private fun groundingEffects(graph: CallGraph): ((Int) -> Set<String>?)? = flowLens?.takeIf { it.graph === graph }?.lens
        ?.let { lens -> { id: Int -> lens.effectsOf(id)?.let { it.own + it.reached } } }

    /** Discovery, enrichment and flow selector data for [chartProjectMap]; PSI-free, safe off the EDT. */
    private fun projectMapData(graph: CallGraph, lens: FlowChartLens?): ProjectMapData {
        fun function(node: CallGraphNode) = node.inProject && node.kind == SymbolKind.FUNCTION
        val discovered = entryPoints.entryPoints.value.flatMap { entry ->
            EntryPointLens.targets(graph, entry, project.basePath).filter(::function)
                .map { MapEntryPoint(it.id, entry.title, entry.kind.label, "discovery") }
        }
        val judged = lens?.takeIf { it.knowsEntryPoints }?.let { l ->
            graph.nodes.filter { function(it) && l.isEntryPoint(it.id) }.map {
                MapEntryPoint(it.id, l.summaries[it.id]?.label ?: FlowChartLens.title(graph, it.id), "enrichment judgment", "enrichment")
            }
        }
        val enriched = lens?.flows != null
        return ProjectMapData(if (discovered.isEmpty() && judged == null) null else (discovered + judged.orEmpty()).distinctBy { it.node },
            lens?.flows?.map { MapFlow(it.name, it.description, it.entry, it.nodes.size) },
            lens?.takeIf { enriched }?.let { l -> { id: Int -> l.effectsOf(id)?.own } },
            lens?.summaries?.takeIf { it.isNotEmpty() }?.let { s -> { id: Int -> s[id]?.label } }, project.basePath)
    }

    private val tours = project.service<ChartTourStore>()

    private fun built(): CallGraphResult {
        if (disposed) throw ToolInputException("Chart closed")
        if (building()) throw ToolInputException("Graph is rebuilding; retry after it finishes")
        return result ?: throw ToolInputException("Graph is not ready yet")
    }

    /**
     * Resolves saved tour [name] against the current graph on the EDT, reads its snippets again in a background read
     * action, then shows it as a story with a notice of what was skipped; a rebuild in between fails instead.
     */
    internal suspend fun playTour(name: String, beforeChange: () -> Unit = {}): String {
        val (built, play) = withContext(Dispatchers.EDT) {
            val tour = tours.find(name) ?: throw ToolInputException("No saved tour named \"$name\"; list_tours shows the saved tours")
            val built = built()
            built to resolveTour(tour, built.graph, groundingEffects(built.graph))
        }
        val read = if (play.beats.none { it.scene.snippets.isNotEmpty() }) play
            else smartReadAction(project) { recaptureTourSnippets(play) { declarationSource(project, built, it) } }
        return withContext(Dispatchers.EDT) {
            if (built() !== built) throw ToolInputException("The graph was rebuilt while the tour was loading; play it again")
            playChartTour(built.graph, flowCanvas, chatRevision, read, groundingEffects(built.graph), beforeChange) { notice ->
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
                        try { playTour(tour.name) } catch (e: ToolInputException) { status.text = e.message }
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
                    ApplicationManager.getApplication().executeOnPooledThread {
                        java.nio.file.Files.writeString(target.file.toPath(), text)
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

    internal val chatContext: ChartChatContext = SessionChartContext()
    private val chatPanel = ChatPanel(project, ChatSession(project, chatScope, chatContext), chartMode = true)
    private val chatTabs = JTabbedPane().apply { addTab("Chart", chatPanel) }
    private lateinit var chatSection: CollapsibleControls.Section
    private lateinit var searchSection: CollapsibleControls.Section
    private val scopedChats = mutableMapOf<ChatPanel, CoroutineScope>()

    /** Every invocation gets an independent history and a fixed source anchor. */
    private fun openScopedChat(target: FlowChatTarget) {
        val built = result ?: return
        if (building() || disposed) return
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
                smartReadAction(project) {
                    if (PsiModificationTracker.getInstance(project).modificationCount != sourceStamp)
                        throw ToolInputException("Source changed while opening scoped chat; choose To chat again")
                    scopedChartContext(project, built, revision, target)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                thisLogger().warn("Could not capture chart chat context", e)
                status.text = "Could not capture chart context; try again."
                return@launch
            }
            if (disposed) return@launch
            val context = object : ChartChatContext by SessionChartContext() {
                override suspend fun snapshot(): String {
                    val current = chatContext.snapshot()
                    val stale = withContext(Dispatchers.EDT) {
                        revision != chatRevision || building() ||
                            PsiModificationTracker.getInstance(project).modificationCount != sourceStamp
                    }
                    return Gson().toJson(mapOf("scoped_context" to com.google.gson.JsonParser.parseString(captured),
                        "captured_chart_fragment" to fragment, "scope_is_stale" to stale,
                        "current_chart" to com.google.gson.JsonParser.parseString(current)))
                }
            }
            val scope = CoroutineScope(chatScope.coroutineContext + SupervisorJob(chatScope.coroutineContext[Job]))
            val panel = ChatPanel(project, ChatSession(project, scope, context), chartMode = true,
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
                command(PanelCommand("Refresh", "Rebuild the application graph") { refresh() })
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
        summarizeButton.isEnabled = summaryJob?.isActive == true || (compact.isSelected && candidates().isNotEmpty())
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
                emptyMap<Int, List<SourceSite>>() to emptyMap()
            }
            if (result !== built || disposed) return@launch
            stepsRead += missing
            callSites += sites
            stepFacts += facts
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
                    return@launch
                }
                val summaries = enrichment.summarizeSubflows(batch, facts, lens.freshSummaries(facts))
                if (disposed || result !== built) return@launch
                val unchanged = smartReadAction(project) { PsiModificationTracker.getInstance(project).modificationCount == stamp }
                if (!unchanged || disposed || result !== built) {
                    summaryNotice.text = "Source changed during summary; stale interpretations were discarded."
                    return@launch
                }
                regionPlans += summaries.plans
                stepFacts += facts
                renderFlow()
                val accepted = summaries.plans.values.sumOf { it.size }
                summaryNotice.text = "$accepted LLM subflows · ${summaries.failedBlocks} regions kept structural" +
                    (if (summaries.rejectedGroups > 0) " · ${summaries.rejectedGroups} invalid groups rejected" else "") +
                    (if (pending.size > batch.size) " · summarize again for remaining regions" else "")
            } catch (e: CancellationException) {
                if (!disposed && result === built) summaryNotice.text = "Summary stopped; existing subflows retained."
                throw e
            } catch (e: Exception) {
                thisLogger().warn("Summarizing subflows failed", e)
                if (!disposed && result === built) summaryNotice.text = "Could not summarize; structural groups remain available."
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
        const val FLOW_HINT = "Click to select · Enter opens source · Up/Down step · Left toggles callers · Right/Space toggles calls · Delete removes column"
    }
}
