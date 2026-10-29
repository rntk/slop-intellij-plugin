package dev.insight.callgraph

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.insight.chat.ChartAnalysis
import dev.insight.chat.ChartEditor
import dev.insight.chat.ChartReader
import dev.insight.chat.ChartReferences
import dev.insight.chat.ChartScenes
import dev.insight.chat.ChartTours
import dev.insight.chat.ToolInputException
import dev.insight.enrichment.FlowChartLens
import dev.insight.enrichment.SymbolFacts
import dev.insight.enrichment.SymbolFactsExtractor
import dev.insight.entrypoints.EntryPointService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What chart chat sessions read and change on their Flow Chart; EDT only except [project]. */
internal interface FlowChartHost {
    val project: Project
    val canvas: FlowChartCanvas
    /** Shared by the chart's sessions; a restore point only undoes the latest chat change and never across a rebuild. */
    val restoreGroup: ChartRestorePoint.Group
    val disposed: Boolean
    val building: Boolean
    val result: CallGraphResult?
    /** Changes with every build; chat node IDs are only valid for one revision. */
    val revision: Int
    /** The enrichment lens drawn for [graph]; null without enrichment of that graph. */
    fun lens(graph: CallGraph): FlowChartLens?
    /** Steps whose call sites and source facts were read for the current build, and those call sites. */
    val stepsRead: Set<Int>
    val callSites: Map<Int, List<SourceSite>>
    /** Keeps reads of [nodes] made for [built] and redraws their steps; ignored after a rebuild or dispose. */
    fun storeSteps(built: CallGraphResult, nodes: List<Int>, sites: Map<Int, List<SourceSite>>, facts: Map<Int, SymbolFacts>)
    suspend fun playTour(name: String, beforeChange: () -> Unit): String
}

/** The finished graph, or a tool error when the chart is closed, rebuilding or not built yet (EDT). */
internal fun FlowChartHost.built(): CallGraphResult {
    if (disposed) throw ToolInputException("Chart closed")
    if (building) throw ToolInputException("Graph is rebuilding; retry after it finishes")
    return result ?: throw ToolInputException("Graph is not ready yet")
}

/** Own plus reached rule effects for grounding externals; null without enrichment. */
internal fun groundingEffects(lens: FlowChartLens?): ((Int) -> Set<String>?)? =
    lens?.let { l -> { id: Int -> l.effectsOf(id)?.let { it.own + it.reached } } }

/** Chart tools for one chat session; each session owns its restore point. */
internal open class FlowChartChatContext(private val host: FlowChartHost) :
    ChartReader, ChartEditor, ChartScenes, ChartAnalysis, ChartTours, ChartReferences {
    private val project get() = host.project
    private val canvas get() = host.canvas
    private val entryPoints = project.service<EntryPointService>()
    private val tours = project.service<ChartTourStore>()
    private val restorePoint = ChartRestorePoint(canvas, host.restoreGroup)

    private fun graph(): CallGraph = host.built().graph

    private data class OutlineCapture(
        val built: CallGraphResult,
        val current: Int,
        val steps: Map<Int, Int>,
        val lens: FlowChartLens?,
        val knownSites: Map<Int, List<SourceSite>>,
        val regions: List<FlowRegion>,
        val missing: List<Int>,
    )

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
        if (host.disposed) throw ToolInputException("Chart closed")
        val graph = host.result?.graph
        if (graph == null || host.building) "{\"status\":\"Graph is building; tools will be available when it finishes\"}"
        else chartChatState(host.revision, graph, canvas, offset)
    }

    override suspend fun findNodes(query: String?, offset: Int, role: String?, effect: String?, entryPoint: Boolean?): String =
        analyze { graph, current, steps, lens -> chartFindNodes(graph, current, query, offset, role, effect, entryPoint, semantics(lens), steps) }

    override suspend fun functionOutline(revision: Int, nodes: List<Int>): String {
        val (built, current, steps, lens, knownSites, regions, missing) = withContext(Dispatchers.EDT) {
            val built = host.built()
            if (revision != host.revision) throw ToolInputException("Stale graph revision; refresh and resolve IDs again")
            if (nodes.size !in 1..5) throw ToolInputException("Give 1 to 5 node IDs")
            nodes.forEach { validateChatNode(built.graph, host.revision, revision, it) }
            val lens = host.lens(built.graph)
            val steps = canvas.chart?.steps.orEmpty().associate { it.node to it.number }
            val regions = canvas.chart?.regions.orEmpty()
            val missing = nodes.filter { it !in host.stepsRead && built.pointers.getOrNull(it) != null }
            OutlineCapture(built, host.revision, steps, lens, HashMap(host.callSites), regions, missing)
        }
        val newSites = if (missing.isNotEmpty()) {
            val (sites, facts) = smartReadAction(project) {
                val callees = missing.flatMap { id -> lens?.calls?.get(id).orEmpty().map { it.callee } }
                CallSiteAnalyzer.collect(built, missing, callees) to
                    SymbolFactsExtractor.extract(project, built, only = missing.toSet())
            }
            withContext(Dispatchers.EDT) { host.storeSteps(built, missing, sites, facts) }
            sites
        } else emptyMap()
        val combinedSites = knownSites + newSites
        return withContext(Dispatchers.Default) {
            chartFunctionOutline(built.graph, current, revision, nodes, combinedSites, regions, semantics(lens), steps,
                groupingSource = lens?.source(combinedSites))
        }
    }

    override suspend fun flowCatalog(revision: Int, query: String?, offset: Int): String = analyze { graph, current, steps, lens ->
        val mapData = projectMapData(graph, lens)
        chartFlowCatalog(graph, current, revision, query, offset,
            lens?.flows?.map { CatalogFlow(it.id, it.name, it.description, it.entry, it.nodes) },
            mapData.entryPoints, semantics(lens), steps)
    }

    override suspend fun apply(action: String, revision: Int, nodes: List<Int>): String = withContext(Dispatchers.EDT) {
        applyChartChatAction(graph(), canvas, host.revision, action, revision, nodes, restorePoint::checkpoint)
    }

    override suspend fun groupCalls(revision: Int, owner: Int, blockKey: String, groups: List<ChartCallGroup>): String =
        withContext(Dispatchers.EDT) {
            groupChartCalls(graph(), canvas, host.revision, revision, owner, blockKey, groups, restorePoint::checkpoint)
        }

    override suspend fun setRegionExpansion(revision: Int, keys: List<String>, expanded: Boolean): String =
        withContext(Dispatchers.EDT) {
            graph()
            setChartRegionExpansion(canvas, host.revision, revision, keys, expanded, restorePoint::checkpoint)
        }

    override suspend fun describe(revision: Int, node: Int, offset: Int): String = withContext(Dispatchers.EDT) {
        chartNodeDetails(graph(), host.revision, revision, node, offset)
    }

    override suspend fun showScene(revision: Int, scene: ChartScene): String {
        val read = captured(revision, listOf(scene), story = false).single()
        return withContext(Dispatchers.EDT) {
            val graph = graph()
            applyChartScene(graph, canvas, host.revision, revision, read, effects(graph), restorePoint::checkpoint)
        }
    }

    private fun effects(graph: CallGraph) = groundingEffects(host.lens(graph))

    /**
     * Checks [scenes] on the EDT, then reads their snippets from source in a background read action. The scenes are
     * applied afterwards on the EDT, where a rebuild in between fails the revision check.
     */
    private suspend fun captured(revision: Int, scenes: List<ChartScene>, story: Boolean): List<ChartScene> {
        if (scenes.none { it.snippets.isNotEmpty() }) return scenes
        val built = withContext(Dispatchers.EDT) {
            val built = host.built()
            val graph = built.graph
            if (revision != host.revision) throw ToolInputException("Stale graph revision; get_chart_state and resolve node IDs again")
            if (story) checkStoryBeats(graph, scenes.map { StoryBeat("", it) }, effects(graph), captured = false)
            else checkChartScene(graph, scenes.single(), effects(graph), captured = false)
            built
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
        clearChartScene(canvas, host.revision, revision, restorePoint::checkpoint)
    }

    /** Captures the immutable graph, lens and visible steps on the EDT; the bounded search runs in the background. */
    private suspend fun <T> analyze(block: (CallGraph, Int, Map<Int, Int>, FlowChartLens?) -> T): T {
        val (captured, lens) = withContext(Dispatchers.EDT) {
            val graph = graph()
            Triple(graph, host.revision, canvas.chart?.steps.orEmpty().associate { it.node to it.number }) to host.lens(graph)
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
            presentChartStory(graph, canvas, host.revision, revision, title,
                beats.zip(read) { beat, scene -> beat.copy(scene = scene) }, effects(graph), restorePoint::checkpoint)
        }
    }

    override suspend fun projectMap(offset: Int): String = analyze { graph, current, _, lens ->
        chartProjectMap(graph, current, offset, projectMapData(graph, lens))
    }

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

    override suspend fun listTours(): String = withContext(Dispatchers.EDT) { tourListData(tours.tours) }

    override suspend fun playTour(name: String): String = host.playTour(name, restorePoint::checkpoint)

    override fun beginReply() = restorePoint.begin()
    override val restorable: Boolean get() = !host.disposed && restorePoint.available
    override fun restore(): Boolean = !host.disposed && !host.building && restorePoint.restore()

    override val revision: Int get() = host.revision
    override val storyId: Int? get() = canvas.story?.id
    override fun referenceName(revision: Int, node: Int) =
        chartReferenceName(host.result?.graph?.takeIf { !host.building }, host.revision, revision, node)
    override fun openReference(revision: Int, node: Int): String? {
        if (host.disposed) return "Chart closed."
        val graph = host.result?.graph?.takeIf { !host.building } ?: return "The graph is rebuilding; try again when it finishes."
        return openChartReference(graph, canvas, host.revision, revision, node)
    }
    override fun beatTitle(revision: Int, beat: Int, storyId: Int?): String? =
        chartBeatTitle(canvas.takeIf { !host.disposed && !host.building }, host.revision, revision, beat, storyId)

    override fun openBeat(revision: Int, beat: Int, storyId: Int?): String? {
        if (host.disposed) return "Chart closed."
        if (host.building || host.result == null) return "The graph is rebuilding; try again when it finishes."
        return openChartBeat(canvas, host.revision, revision, beat, storyId)
    }
    override fun hoverReference(revision: Int, node: Int?) {
        if (!host.disposed) canvas.hover(node?.takeIf { revision == host.revision })
    }
}
