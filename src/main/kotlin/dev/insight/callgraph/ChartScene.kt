package dev.insight.callgraph

import com.google.gson.Gson
import dev.insight.agent.ToolInputException
import dev.insight.core.Hashing
import dev.insight.graph.CallGraph
import dev.insight.graph.FlowBoard
import dev.insight.graph.FlowStart
import dev.insight.graph.RelationKind
import dev.insight.graph.SymbolKind

enum class SceneRole { FOCUS, CONTEXT, SOURCE, SINK, PROBLEM, TRANSFORM, DECISION, ORCHESTRATOR }

/** Only [CALL] is static evidence; the other kinds are assistant explanations verified by reading source. */
enum class SceneLinkKind { CALL, DATA, EVENT, CALLBACK, ASYNC }

data class SceneNode(val node: Int, val role: SceneRole, val badge: String? = null)
data class SceneLink(val from: Int, val to: Int, val kind: SceneLinkKind, val label: String? = null, val payload: String? = null)
data class SceneNote(val node: Int, val text: String)

enum class SceneGroupKind { LAYER, STAGE, MODULE }
/** A labelled background box behind [nodes]: a layer, pipeline stage or module of the explanation. */
data class SceneGroup(val label: String, val nodes: List<Int>, val kind: SceneGroupKind? = null)

enum class ExternalKind { DATABASE, NETWORK, FILE, QUEUE, LLM, UI, OTHER }
enum class ExternalDirection { IN, OUT, BOTH }
data class ExternalLink(val node: Int, val direction: ExternalDirection, val label: String? = null)

/**
 * A system outside the code where data enters or leaves. [verified] is set by [checkChartScene] when rule effects
 * confirm a database (storage) or network external for every connected function; otherwise it is an explanation.
 */
data class SceneExternal(val id: String, val kind: ExternalKind, val label: String, val connected: List<ExternalLink>,
    val verified: Boolean = false)

/**
 * Lines [startLine]..[endLine] (1-based, in the file) of a function. [path], [lines] and [hash] are read from source
 * when the scene is applied and frozen into it; plain data, PSI-free.
 */
data class SceneSnippet(val node: Int, val startLine: Int, val endLine: Int, val caption: String? = null,
    val path: String? = null, val lines: List<String>? = null, val hash: String? = null)

/** The declaration of a function as read from source: project path, first line (1-based) and its lines. PSI-free. */
data class DeclarationSource(val path: String, val firstLine: Int, val lines: List<String>) {
    val lastLine get() = firstLine + lines.size - 1
}

data class SceneColumn(val node: Int, val title: String? = null, val description: String? = null)
enum class SceneCamera { FOCUS_FIRST, FIT_SCENE }

/** The assistant's current picture on the chart; each scene replaces the previous one. PSI-free. */
data class ChartScene(
    val title: String? = null,
    val nodes: List<SceneNode> = emptyList(),
    val links: List<SceneLink> = emptyList(),
    val notes: List<SceneNote> = emptyList(),
    val dimOthers: Boolean = false,
    val groups: List<SceneGroup> = emptyList(),
    val externals: List<SceneExternal> = emptyList(),
    val snippets: List<SceneSnippet> = emptyList(),
    val columns: List<SceneColumn> = emptyList(),
    val resetBoard: Boolean = false,
    val camera: SceneCamera = SceneCamera.FOCUS_FIRST,
) {
    val roles: Map<Int, SceneNode> by lazy { nodes.associateBy { it.node } }
    val noteOf: Map<Int, String> by lazy { notes.associate { it.node to it.text } }
    val isEmpty get() = nodes.isEmpty() && links.isEmpty() && notes.isEmpty() && title == null && groups.isEmpty() &&
        externals.isEmpty() && snippets.isEmpty() && columns.isEmpty() && !resetBoard

    /**
     * The scene moved into a rebuilt graph: emphasis and groups follow their functions; source-dependent explanations
     * (links, notes, externals grounded in the old build's effects, captured snippets) are dropped.
     */
    fun map(id: (Int) -> Int?) = copy(nodes = nodes.mapNotNull { n -> id(n.node)?.let { n.copy(node = it) } }.distinctBy { it.node },
        links = emptyList(), notes = emptyList(), externals = emptyList(), snippets = emptyList(),
        groups = groups.map { g -> g.copy(nodes = g.nodes.mapNotNull(id).distinct()) }.filter { it.nodes.isNotEmpty() },
        columns = columns.mapNotNull { c -> id(c.node)?.let { c.copy(node = it) } }.distinctBy { it.node },
        resetBoard = false)

    companion object {
        const val MAX_NODES = 30
        const val MAX_LINKS = 30
        const val MAX_NOTES = 20
        const val MAX_TITLE = 120
        const val MAX_NOTE = 300
        const val MAX_LABEL = 60
        const val MAX_BADGE = 4
        const val MAX_GROUPS = 8
        const val MAX_EXTERNALS = 6
        const val MAX_EXTERNAL_LINKS = 6
        const val MAX_SHORT_LABEL = 40
        const val MAX_SNIPPETS = 3
        const val MAX_SNIPPET_LINES = 12
        const val MAX_SNIPPET_CAPTION = 80
        const val MAX_SNIPPET_LINE = 240
        const val MAX_PAYLOAD = 80
        const val MAX_COLUMNS = 8
        const val MAX_COLUMN_DESCRIPTION = 160
    }
}

internal const val EXPLANATION_EVIDENCE = "assistant explanation, not a static call; must be source-verified"
internal const val CALL_EVIDENCE = "static in-project call"
internal const val EXTERNAL_VERIFIED = "rule effects confirm it: every connected function has the matching effect, own or via calls"
internal const val EXTERNAL_UNVERIFIED = "assistant explanation, unverified by rule effects"

/** The rule effect that grounds an external of [kind], or null when the kind is always an explanation. */
internal fun groundingEffect(kind: ExternalKind) = when (kind) {
    ExternalKind.DATABASE -> "storage"
    ExternalKind.NETWORK -> "network"
    else -> null
}

/** A guided tour: [beats] are checked scenes with captions, stepped by the user without the assistant. PSI-free. */
class ChartStory(
    val title: String,
    val beats: List<StoryBeat>,
    internal val graph: CallGraph,
    val index: Int = 0,
    val id: Int = nextId.incrementAndGet(),
) {
    val beat get() = beats[index]
    fun at(index: Int) = ChartStory(title, beats, graph, index, id)

    companion object {
        private val nextId = java.util.concurrent.atomic.AtomicInteger()
        const val MIN_BEATS = 2
        const val MAX_BEATS = 8
        const val MAX_CAPTION = 300
    }
}

data class StoryBeat(val caption: String, val scene: ChartScene)

/** Board, scene, story and selection of a canvas, for restoring the chart to what it was before a reply. */
data class ChartCanvasState(val board: FlowBoard, val scene: ChartScene, val selected: Int?, val story: ChartStory? = null)

/**
 * One-level restore of the chart changes made by the latest reply of one chat session; runs on the EDT. The sessions of
 * a canvas share a [Group]: a point restores only while its session made the latest chat change of the chart in the
 * same graph revision, so undo in one chat never reverts another chat's reply or applies stale IDs.
 */
internal class ChartRestorePoint(private val canvas: FlowChartCanvas, private val group: Group = Group()) {
    class Group(val revision: () -> Int = { 0 }) { internal var last: ChartRestorePoint? = null }

    private var armed = false
    private var saved: ChartCanvasState? = null
    private var savedRevision = 0
    val available get() = saved != null && group.last === this && savedRevision == group.revision()

    /** A new reply: forget the previous restore point; the first change of this reply records a new one. */
    fun begin() { armed = true; saved = null }

    /** Called before every chat change of the chart. */
    fun checkpoint() {
        group.last = this
        if (!armed) return
        armed = false
        saved = canvas.saveState()
        savedRevision = group.revision()
    }

    fun restore(): Boolean {
        val state = saved?.takeIf { available }
        saved = null
        canvas.restoreState(state ?: return false)
        return true
    }

    fun reset() { armed = false; saved = null }
}

/**
 * Checks [scene] against [graph] (in-project functions, size, static backing of call links, rule grounding of database
 * and network externals by [effects]: own plus reached effects, null when unknown) without changing anything. Snippets
 * must be [captured] from source already. Returns the scene with the targets it does not list added as context nodes.
 */
internal fun checkChartScene(graph: CallGraph, scene: ChartScene, effects: ((Int) -> Set<String>?)? = null,
    captured: Boolean = true): ChartScene {
    val ids = (scene.nodes.map { it.node } + scene.links.flatMap { listOf(it.from, it.to) } + scene.notes.map { it.node } +
        scene.groups.flatMap { it.nodes } + scene.externals.flatMap { e -> e.connected.map { it.node } } +
        scene.snippets.map { it.node } + scene.columns.map { it.node }).distinct()
    if (ids.size > ChartScene.MAX_NODES)
        throw ToolInputException("A scene can show at most ${ChartScene.MAX_NODES} functions including link, note, group, external " +
            "and snippet targets; split it into several scenes")
    ids.forEach { id ->
        val node = graph.nodes.getOrNull(id)
        if (node == null || !node.inProject || node.kind != SymbolKind.FUNCTION)
            throw ToolInputException("Unknown in-project function ID: $id; use find_chart_nodes to resolve it")
    }
    val calls = graph.edges.filter { it.kind == RelationKind.CALL }.mapTo(hashSetOf()) { it.caller to it.callee }
    scene.links.filter { it.kind == SceneLinkKind.CALL && (it.from to it.to) !in calls }.forEach {
        throw ToolInputException("Link ${it.from}->${it.to} has kind call but the graph has no static call from ${it.from} to ${it.to}" +
            (if ((it.to to it.from) in calls) " (it has ${it.to}->${it.from}; swap from and to)" else "") +
            ". Use trace_path for intermediate steps, or kind data/event/callback/async for a source-verified relationship.")
    }
    val externals = scene.externals.mapIndexed { i, external ->
        val effect = groundingEffect(external.kind) ?: return@mapIndexed external.copy(verified = false)
        val known = external.connected.map { link -> link.node to effects?.invoke(link.node) }
        known.firstOrNull { (_, found) -> found != null && effect !in found }?.let { (node, _) ->
            throw ToolInputException("externals[$i] \"${external.id}\" (${external.kind.name.lowercase()}): function $node " +
                "(${graph.nodes[node].name}) has no rule-derived $effect effect, own or via calls. Connect the functions that reach " +
                "$effect (suggest_externals lists them with the matched imports), or, if source you read shows access the rules " +
                "miss, use kind other and cite path:line in a note.")
        }
        external.copy(verified = known.all { it.second != null })
    }
    if (captured) scene.snippets.forEachIndexed { i, snippet ->
        if (snippet.lines == null) throw ToolInputException("snippets[$i]: the source of function ${snippet.node} was not read; send the scene again")
    }
    return scene.copy(nodes = scene.nodes + ids.filter { it !in scene.roles }.map { SceneNode(it, SceneRole.CONTEXT) }, externals = externals)
}

/** Checks every beat like [checkChartScene] without changing anything; errors name the beat. */
internal fun checkStoryBeats(graph: CallGraph, beats: List<StoryBeat>, effects: ((Int) -> Set<String>?)? = null,
    captured: Boolean = true): List<StoryBeat> {
    if (beats.size !in ChartStory.MIN_BEATS..ChartStory.MAX_BEATS)
        throw ToolInputException("A story needs ${ChartStory.MIN_BEATS} to ${ChartStory.MAX_BEATS} beats; use show_scene for a single point")
    return beats.mapIndexed { i, beat -> storyBeat(i) { beat.copy(scene = checkChartScene(graph, beat.scene, effects, captured)) } }
}

/** Runs [block] for beat [index], naming the beat in its errors. */
internal fun <T> storyBeat(index: Int, block: () -> T): T = try { block() } catch (e: ToolInputException) {
    throw ToolInputException("beats[$index].scene: ${e.message}. No beat was shown; fix it and send the whole story again.")
}

/**
 * Reads the snippet lines of [scene] from [source] (a function's declaration, null when unavailable), rejecting ranges
 * outside the declaration, and freezes path, text and content hash into the scene. Callers hold a read action.
 */
internal fun captureSnippets(scene: ChartScene, source: (Int) -> DeclarationSource?): ChartScene {
    if (scene.snippets.isEmpty()) return scene
    return scene.copy(snippets = scene.snippets.mapIndexed { i, snippet ->
        val declaration = source(snippet.node) ?: throw ToolInputException("snippets[$i]: the source of function ${snippet.node} " +
            "is not available; drop the snippet and cite path:line in your text")
        if (snippet.startLine < declaration.firstLine || snippet.endLine > declaration.lastLine)
            throw ToolInputException("snippets[$i]: lines ${snippet.startLine}-${snippet.endLine} are outside function ${snippet.node}, " +
                "declared at ${declaration.path}:${declaration.firstLine}-${declaration.lastLine}; choose lines inside it")
        val lines = declaration.lines.subList(snippet.startLine - declaration.firstLine, snippet.endLine - declaration.firstLine + 1)
            .map { it.trimEnd().take(ChartScene.MAX_SNIPPET_LINE) }
        snippet.copy(path = declaration.path, lines = lines, hash = Hashing.sha256Of(lines.joinToString("\n")))
    })
}

/** Places the hidden functions of a checked [scene] as continuations of the chart, shows it and focuses its first focus node. */
internal fun placeChartScene(graph: CallGraph, canvas: FlowChartCanvas, scene: ChartScene, story: ChartStory? = null): List<Map<String, Any?>> {
    if (scene.resetBoard) {
        canvas.resetBoard(scene.columns.map { FlowStart(it.node, it.title, it.description) })
    } else if (scene.columns.isNotEmpty()) {
        for (col in scene.columns) {
            canvas.setColumn(FlowStart(col.node, col.title, col.description))
        }
    }
    val placements = addConnected(graph, canvas, scene.nodes.map { it.node }.filter { canvas.chart?.step(it) == null })
    canvas.showScene(scene, story)
    (scene.nodes.firstOrNull { it.role == SceneRole.FOCUS } ?: scene.nodes.firstOrNull())?.let { canvas.selectNode(it.node) }
    if (scene.camera == SceneCamera.FIT_SCENE) canvas.fitScene()
    return placements
}

/**
 * Validates the whole [scene] (revision, in-project functions, static backing of call links) before changing anything,
 * then places hidden functions as continuations of the chart and replaces the previous scene or story. Runs on the EDT.
 */
internal fun applyChartScene(graph: CallGraph, canvas: FlowChartCanvas, currentRevision: Int, revision: Int, scene: ChartScene,
    effects: ((Int) -> Set<String>?)? = null, beforeChange: () -> Unit = {}): String {
    if (revision != currentRevision) throw ToolInputException("Stale graph revision; get_chart_state and resolve node IDs again")
    val full = checkChartScene(graph, scene, effects)
    beforeChange()
    val replaced = !canvas.scene.isEmpty
    val closedStory = canvas.story != null
    val before = canvas.chart?.steps.orEmpty().map { it.node }.toSet()
    val placements = placeChartScene(graph, canvas, full)
    return Gson().toJson(mapOf("ok" to true, "revision" to currentRevision, "replaced_previous_scene" to replaced,
        "closed_story" to closedStory, "title" to full.title, "dim_others" to full.dimOthers,
        "camera" to full.camera.name.lowercase(), "reset_board" to full.resetBoard,
        "columns" to full.columns.map { mapOf("node_id" to it.node, "title" to it.title, "description" to it.description) },
        "applied" to mapOf("nodes" to full.nodes.size, "links" to full.links.size, "notes" to full.notes.size,
            "groups" to full.groups.size, "externals" to full.externals.size, "snippets" to full.snippets.size,
            "columns" to full.columns.size),
        "implicit_context_nodes" to full.nodes.drop(scene.nodes.size).map { it.node }, "placements" to placements,
        "added_node_ids" to (canvas.chart?.steps.orEmpty().map { it.node }.toSet() - before),
        "not_visible" to full.nodes.map { it.node }.filter { canvas.chart?.step(it) == null },
        "links" to full.links.map { mapOf("from" to it.from, "to" to it.to, "kind" to it.kind.name.lowercase(),
            "payload" to it.payload,
            "evidence" to if (it.kind == SceneLinkKind.CALL) CALL_EVIDENCE else EXPLANATION_EVIDENCE) },
        "externals" to full.externals.map(::externalData),
        "snippets" to full.snippets.map { snippetData(it) + mapOf("text" to it.lines?.joinToString("\n")) },
        "evidence" to "Roles, badges, notes, groups and non-call links are your explanation; only call links are static evidence. " +
            "Externals are verified only where reported; snippet text is the source captured now, quote it as path:line."))
}

private fun externalData(external: SceneExternal) = mapOf("id" to external.id, "kind" to external.kind.name.lowercase(),
    "label" to external.label, "connected" to external.connected.map { mapOf("node_id" to it.node,
        "direction" to it.direction.name.lowercase(), "label" to it.label) },
    "evidence" to if (external.verified) EXTERNAL_VERIFIED else EXTERNAL_UNVERIFIED)

private fun snippetData(snippet: SceneSnippet) = mapOf("node_id" to snippet.node, "path" to snippet.path,
    "start_line" to snippet.startLine, "end_line" to snippet.endLine, "caption" to snippet.caption, "content_hash" to snippet.hash)

/** Validates every beat like show_scene before changing anything, then shows the first beat. Runs on the EDT. */
internal fun presentChartStory(graph: CallGraph, canvas: FlowChartCanvas, currentRevision: Int, revision: Int, title: String,
    beats: List<StoryBeat>, effects: ((Int) -> Set<String>?)? = null, beforeChange: () -> Unit = {}): String {
    if (revision != currentRevision) throw ToolInputException("Stale graph revision; get_chart_state and resolve node IDs again")
    val checked = checkStoryBeats(graph, beats, effects)
    beforeChange()
    val story = ChartStory(title, checked, graph)
    val placements = placeChartScene(graph, canvas, story.beat.scene, story)
    return Gson().toJson(mapOf("ok" to true, "revision" to currentRevision, "title" to title, "beats_total" to checked.size,
        "current_beat" to 1, "placements" to placements,
        "not_visible" to story.beat.scene.nodes.map { it.node }.filter { canvas.chart?.step(it) == null },
        "note" to "Beat 1 is shown. The user steps through the beats on the chart; show_scene or clear_scene closes the story."))
}

/** Shows beat [index] of the canvas story with its placements; false when there is no story or no such other beat. EDT. */
internal fun showStoryBeat(canvas: FlowChartCanvas, index: Int): Boolean {
    val story = canvas.story ?: return false
    if (index !in story.beats.indices || index == story.index) return false
    val next = story.at(index)
    placeChartScene(story.graph, canvas, next.beat.scene, next)
    return true
}

/** The active story as reported to the model. */
internal fun storyData(canvas: FlowChartCanvas): Map<String, Any?>? = canvas.story?.let { story ->
    mapOf("title" to story.title, "current_beat" to story.index + 1, "beats_total" to story.beats.size,
        "captions" to story.beats.map { it.caption })
}

/**
 * Selects [node] for a reference in a reply written at [revision], placing it as a continuation when hidden. Returns a
 * notice instead when the graph was rebuilt since or the function is unknown, so a stale ID never selects a wrong step.
 */
internal fun openChartReference(graph: CallGraph, canvas: FlowChartCanvas, currentRevision: Int, revision: Int, node: Int): String? {
    if (revision != currentRevision) return "The chart was rebuilt after this message, so its function references are stale; ask again."
    if (graph.nodes.getOrNull(node)?.let { it.inProject && it.kind == SymbolKind.FUNCTION } != true)
        return "Function $node is not in the current graph."
    if (canvas.chart?.step(node) == null) addConnected(graph, canvas, listOf(node))
    return if (canvas.selectNode(node)) null else "Could not show this function on the chart."
}

/** The function name for a reference written at [revision], or null when it is stale or not an in-project function. */
internal fun chartReferenceName(graph: CallGraph?, currentRevision: Int, revision: Int, node: Int): String? =
    graph?.takeIf { revision == currentRevision }?.nodes?.getOrNull(node)?.takeIf { it.inProject && it.kind == SymbolKind.FUNCTION }?.name

internal fun chartBeatTitle(canvas: FlowChartCanvas?, currentRevision: Int, revision: Int, beat: Int,
    storyId: Int? = canvas?.story?.id): String? =
    canvas?.takeIf { revision == currentRevision }?.story?.takeIf { storyId != null && it.id == storyId }?.beats?.getOrNull(beat - 1)?.caption

internal fun openChartBeat(canvas: FlowChartCanvas, currentRevision: Int, revision: Int, beat: Int,
    storyId: Int? = canvas.story?.id): String? {
    if (revision != currentRevision) return "The chart was rebuilt after this message, so its story references are stale; ask again."
    val story = canvas.story ?: return "No story is open on the chart."
    if (storyId == null || story.id != storyId) return "A different story is now open on the chart, so this beat reference is stale."
    if (beat !in 1..story.beats.size) return "Beat $beat is not in the current story (1..${story.beats.size})."
    if (beat - 1 != story.index) showStoryBeat(canvas, beat - 1)
    return null
}

internal fun clearChartScene(canvas: FlowChartCanvas, currentRevision: Int, revision: Int, beforeChange: () -> Unit = {}): String {
    if (revision != currentRevision) throw ToolInputException("Stale graph revision; get_chart_state and resolve node IDs again")
    if (!canvas.scene.isEmpty || canvas.story != null) { beforeChange(); canvas.showScene(ChartScene()) }
    return Gson().toJson(mapOf("ok" to true, "revision" to currentRevision, "scene" to "cleared"))
}

/** The current scene as reported to the model. */
internal fun sceneData(canvas: FlowChartCanvas): Map<String, Any?> {
    val scene = canvas.scene
    fun visible(id: Int) = canvas.geometry?.cards?.containsKey(id) == true
    return mapOf("title" to scene.title, "dim_others" to scene.dimOthers,
        "camera" to scene.camera.name.lowercase(), "reset_board" to scene.resetBoard,
        "columns" to scene.columns.map { mapOf("node_id" to it.node, "title" to it.title, "description" to it.description) },
        "nodes" to scene.nodes.map { mapOf("node_id" to it.node, "role" to it.role.name.lowercase(), "badge" to it.badge,
            "step_number" to canvas.chart?.step(it.node)?.number, "visible" to visible(it.node)) },
        "links" to scene.links.map { mapOf("from" to it.from, "to" to it.to, "kind" to it.kind.name.lowercase(), "label" to it.label,
            "payload" to it.payload,
            "visible" to (visible(it.from) && visible(it.to)),
            "evidence" to if (it.kind == SceneLinkKind.CALL) CALL_EVIDENCE else EXPLANATION_EVIDENCE) },
        "notes" to scene.notes.map { mapOf("node_id" to it.node, "text" to it.text) },
        "groups" to scene.groups.map { mapOf("label" to it.label, "kind" to it.kind?.name?.lowercase(), "node_ids" to it.nodes,
            "visible_node_ids" to it.nodes.filter(::visible)) },
        "externals" to scene.externals.map(::externalData),
        "snippets" to scene.snippets.map { snippetData(it) + mapOf("visible" to visible(it.node)) })
}
