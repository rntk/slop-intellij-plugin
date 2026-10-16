package dev.insight.callgraph

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import dev.insight.chat.ToolInputException

/** A function of a saved tour by the identity [stableKey] uses to follow functions across rebuilds. PSI-free. */
data class TourFunction(val name: String, val container: String, val path: String?, val location: String = "",
    val kind: SymbolKind = SymbolKind.FUNCTION) {
    val key: String get() = stableKey(CallGraphNode(0, name, container, location, 0, true, kind, path))
    val qualified: String get() = "$container.$name"

    companion object {
        fun of(node: CallGraphNode) = TourFunction(node.name, node.container, node.filePath, node.location, node.kind)
    }
}

/** A story saved by name. Its beat scenes refer to functions by index into [functions], never by graph ID. PSI-free. */
data class SavedTour(val name: String, val title: String, val functions: List<TourFunction>, val beats: List<StoryBeat>,
    val savedAt: Long = 0) {
    companion object { const val MAX_NAME = 80 }
}

/** IDs of every function a scene refers to, in order of first mention. */
private fun sceneIds(scene: ChartScene) = (scene.nodes.map { it.node } + scene.links.flatMap { listOf(it.from, it.to) } +
    scene.notes.map { it.node } + scene.groups.flatMap { it.nodes } + scene.externals.flatMap { e -> e.connected.map { it.node } } +
    scene.snippets.map { it.node }).distinct()

/** Replaces every ID of a scene whose IDs [id] all maps; external verification is redone on play. */
private fun ChartScene.mapAll(id: (Int) -> Int) = copy(nodes = nodes.map { it.copy(node = id(it.node)) },
    links = links.map { it.copy(from = id(it.from), to = id(it.to)) }, notes = notes.map { it.copy(node = id(it.node)) },
    groups = groups.map { g -> g.copy(nodes = g.nodes.map(id)) },
    externals = externals.map { e -> e.copy(connected = e.connected.map { it.copy(node = id(it.node)) }, verified = false) },
    snippets = snippets.map { it.copy(node = id(it.node)) })

/** [story] as a tour named [name]: its functions by stable identity, captured snippets kept for export. */
internal fun savedTour(name: String, story: ChartStory, savedAt: Long = System.currentTimeMillis()): SavedTour {
    val ids = story.beats.flatMap { sceneIds(it.scene) }.distinct()
    val index = ids.withIndex().associate { it.value to it.index }
    return SavedTour(name, story.title, ids.map { TourFunction.of(story.graph.nodes[it]) },
        story.beats.map { it.copy(scene = it.scene.mapAll(index::getValue)) }, savedAt)
}

/** A tour resolved against the current graph: beats with current IDs and what could not be carried over. */
internal data class TourPlay(val tour: SavedTour, val beats: List<StoryBeat>, val unresolved: List<TourFunction>,
    val relocated: List<TourFunction>, val skippedBeats: List<String>, val dropped: List<String>) {
    /** One readable notice for the story bar, or null when everything resolved. */
    val notice: String? get() = listOfNotNull(
        unresolved.takeIf { it.isNotEmpty() }?.let { "Missing functions skipped: ${it.joinToString { f -> f.qualified }}" },
        relocated.takeIf { it.isNotEmpty() }?.let { "Matched by name after moving: ${it.joinToString { f -> f.qualified }}" },
        skippedBeats.takeIf { it.isNotEmpty() }?.let { "Skipped beats: ${it.joinToString("; ")}" },
        dropped.takeIf { it.isNotEmpty() }?.let { "Dropped: ${it.joinToString("; ")}" },
    ).joinToString(". ").ifEmpty { null }
}

/**
 * Resolves [tour] against [graph]: functions by stable key, else by a unique in-project function with the same
 * container and name (a moved file). Missing functions are skipped; links, notes, externals and snippets that lose an
 * end are dropped, call links no longer backed by a static call are dropped, database/network externals no longer
 * grounded by [effects] are dropped, and a beat left without functions is skipped. Snippets keep their saved range for
 * [recaptureTourSnippets]. PSI-free.
 */
internal fun resolveTour(tour: SavedTour, graph: CallGraph, effects: ((Int) -> Set<String>?)? = null): TourPlay {
    val functions = graph.nodes.filter { it.inProject && it.kind == SymbolKind.FUNCTION }
    val byKey = HashMap<String, Int>()
    functions.forEach { byKey.putIfAbsent(stableKey(it), it.id) }
    val byName = functions.groupBy { it.container to it.name }
    val relocated = mutableListOf<TourFunction>()
    val ids = tour.functions.map { f ->
        byKey[f.key] ?: byName[f.container to f.name]?.singleOrNull()?.id?.also { relocated += f }
    }
    fun id(index: Int) = ids.getOrNull(index)
    fun label(index: Int) = tour.functions.getOrNull(index)?.let { tourTitle(it) } ?: "function $index"
    val calls = graph.edges.filter { it.kind == RelationKind.CALL }.mapTo(hashSetOf()) { it.caller to it.callee }
    val skipped = mutableListOf<String>()
    val dropped = mutableListOf<String>()
    val beats = tour.beats.mapIndexedNotNull { i, beat ->
        val s = beat.scene
        val where = "beat ${i + 1}"
        val nodes = s.nodes.mapNotNull { n -> id(n.node)?.let { n.copy(node = it) } }
        if (nodes.isEmpty()) { skipped += "$where \"${beat.caption.take(60)}\" (all its functions are missing)"; return@mapIndexedNotNull null }
        val links = s.links.mapNotNull { link ->
            val from = id(link.from)
            val to = id(link.to)
            when {
                from == null || to == null -> { dropped += "$where link ${label(link.from)} -> ${label(link.to)} (function missing)"; null }
                link.kind == SceneLinkKind.CALL && (from to to) !in calls -> {
                    dropped += "$where call link ${label(link.from)} -> ${label(link.to)} (no longer a static call)"; null }
                else -> link.copy(from = from, to = to)
            }
        }
        val notes = s.notes.mapNotNull { note -> id(note.node)?.let { note.copy(node = it) }
            ?: run { dropped += "$where note on ${label(note.node)} (function missing)"; null } }
        val groups = s.groups.map { g -> g.copy(nodes = g.nodes.mapNotNull(::id).distinct()) }.filter { it.nodes.isNotEmpty() }
        val externals = s.externals.mapNotNull { external ->
            val connected = external.connected.mapNotNull { link -> id(link.node)?.let { link.copy(node = it) } }
            val effect = groundingEffect(external.kind)
            when {
                connected.isEmpty() -> { dropped += "$where external \"${external.label}\" (no connected function left)"; null }
                effect != null && connected.any { link -> effects?.invoke(link.node)?.let { effect !in it } == true } -> {
                    dropped += "$where external \"${external.label}\" (rule effects no longer show $effect)"; null }
                else -> external.copy(connected = connected, verified = false)
            }
        }
        val snippets = s.snippets.mapNotNull { snippet -> id(snippet.node)?.let { snippet.copy(node = it) }
            ?: run { dropped += "$where snippet of ${label(snippet.node)} (function missing)"; null } }
        StoryBeat(beat.caption, s.copy(nodes = nodes, links = links, notes = notes, groups = groups, externals = externals, snippets = snippets))
    }
    return TourPlay(tour, beats, tour.functions.filterIndexed { i, _ -> ids[i] == null }, relocated, skipped, dropped)
}

/**
 * Reads the snippets of [play] again from [source] (current declarations): a range no longer inside its function is
 * dropped with a notice; changed text is kept and noted. Callers hold a read action.
 */
internal fun recaptureTourSnippets(play: TourPlay, source: (Int) -> DeclarationSource?): TourPlay {
    val dropped = play.dropped.toMutableList()
    val beats = play.beats.mapIndexed { i, beat ->
        beat.copy(scene = beat.scene.copy(snippets = beat.scene.snippets.mapNotNull { saved ->
            val where = "beat ${i + 1} snippet ${saved.path ?: "of function"}:${saved.startLine}-${saved.endLine}"
            try {
                captureSnippets(ChartScene(snippets = listOf(saved.copy(lines = null, hash = null))), source).snippets.single()
                    .also { if (saved.hash != null && it.hash != saved.hash) dropped += "$where text changed since the tour was saved (shown as it is now)" }
            } catch (e: ToolInputException) {
                dropped += "$where (${e.message?.substringAfter(": ")?.substringBefore(";")})"
                null
            }
        }))
    }
    return play.copy(beats = beats, dropped = dropped)
}

/**
 * Shows a resolved tour as a story on the canvas; beats that still fail validation are skipped. Throws when nothing is
 * left to show. [play] must be resolved against [graph]; snippets must be recaptured. Runs on the EDT.
 */
internal fun playChartTour(graph: CallGraph, canvas: FlowChartCanvas, currentRevision: Int, play: TourPlay,
    effects: ((Int) -> Set<String>?)? = null, beforeChange: () -> Unit = {}, shown: (String?) -> Unit = {}): String {
    val skipped = play.skippedBeats.toMutableList()
    val beats = play.beats.mapNotNull { beat ->
        try { beat.copy(scene = checkChartScene(graph, beat.scene, effects)) } catch (e: ToolInputException) {
            skipped += "\"${beat.caption.take(60)}\" (${e.message?.take(120)})"
            null
        }
    }
    val checked = play.copy(beats = beats, skippedBeats = skipped)
    if (beats.isEmpty()) throw ToolInputException("Tour \"${play.tour.name}\" has no beat left in the current graph. " +
        (checked.notice ?: "") + ". Build a new story with present_story instead.")
    beforeChange()
    val story = ChartStory(play.tour.title, beats.take(ChartStory.MAX_BEATS), graph)
    val placements = placeChartScene(graph, canvas, story.beat.scene, story)
    shown(checked.notice)
    return Gson().toJson(mapOf("ok" to true, "revision" to currentRevision, "name" to play.tour.name, "title" to play.tour.title,
        "beats_saved" to play.tour.beats.size, "beats_total" to story.beats.size, "current_beat" to 1,
        "captions" to story.beats.map { it.caption }, "placements" to placements,
        "functions_resolved" to play.tour.functions.size - play.unresolved.size, "functions_saved" to play.tour.functions.size,
        "unresolved_functions" to play.unresolved.map { mapOf("qualified_name" to it.qualified, "path" to it.path) },
        "matched_by_name" to play.relocated.map { it.qualified },
        "skipped_beats" to skipped, "dropped" to checked.dropped,
        "note" to "Beat 1 is shown; the user steps through the beats. Tours are saved explanations: re-check claims that depend " +
            "on source, and mention anything skipped or dropped."))
}

/** Listing of saved tours for the model. */
internal fun tourListData(tours: List<SavedTour>): String = Gson().toJson(mapOf("total" to tours.size,
    "tours" to tours.take(50).map { mapOf("name" to it.name, "title" to it.title, "beats" to it.beats.size,
        "captions" to it.beats.map { b -> b.caption.take(80) }, "functions" to it.functions.take(12).map { f -> tourTitle(f) },
        "functions_total" to it.functions.size) },
    "note" to "play_tour {name} replays a tour against the current graph; missing functions are skipped and reported."))

/** `Owner.name` for a tour function, like flow chart titles. */
internal fun tourTitle(f: TourFunction): String {
    val name = f.name.substringBefore('(')
    val owner = f.container.substringBefore(" in ").substringAfterLast('.').substringAfterLast('/').trim()
    return if (owner.isEmpty() || owner == name) name else "$owner.$name"
}

/**
 * [tour] as Markdown: title, then per beat the caption, its functions (name, role, badge, path:line), links, notes and
 * snippet code blocks. Fences and inline code are longer than any backtick run they contain. [basePath] shortens paths.
 */
internal fun tourMarkdown(tour: SavedTour, basePath: String? = null): String = buildString {
    fun code(text: String): String {
        val run = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
        val ticks = "`".repeat(run + 1)
        return if (run == 0) "$ticks$text$ticks" else "$ticks $text $ticks"
    }
    fun path(path: String?) = path?.replace('\\', '/')?.let { p -> basePath?.let { p.removePrefix("${it.replace('\\', '/')}/") } ?: p }
    fun name(index: Int) = tour.functions.getOrNull(index)?.let { code(tourTitle(it)) } ?: "function $index"
    fun at(f: TourFunction) = listOfNotNull(path(f.path), f.location.substringAfterLast(':', "").toIntOrNull()?.toString())
        .joinToString(":").ifEmpty { null }
    append("# ").append(tour.title.replace('\n', ' ')).append("\n\n")
    tour.beats.forEachIndexed { i, beat ->
        val scene = beat.scene
        append("## ").append(i + 1).append(". ").append(beat.caption.replace('\n', ' ')).append("\n\n")
        scene.title?.takeIf { it != beat.caption }?.let { append("_").append(it).append("_\n\n") }
        scene.nodes.forEach { node ->
            val f = tour.functions.getOrNull(node.node)
            append("- ").append(name(node.node)).append(" — ").append(node.role.name.lowercase())
            node.badge?.let { append(", step ").append(it) }
            f?.let(::at)?.let { append(" — ").append(it) }
            append('\n')
        }
        if (scene.links.isNotEmpty()) {
            append("\nLinks:\n")
            scene.links.forEach { link ->
                append("- ").append(name(link.from)).append(" → ").append(name(link.to)).append(" (").append(link.kind.name.lowercase()).append(")")
                link.label?.let { append(": ").append(it) }
                append('\n')
            }
        }
        if (scene.notes.isNotEmpty()) {
            append("\nNotes:\n")
            scene.notes.forEach { append("- ").append(name(it.node)).append(": ").append(it.text.replace('\n', ' ')).append('\n') }
        }
        scene.snippets.forEach { snippet ->
            val lines = snippet.lines ?: return@forEach
            append('\n')
            snippet.caption?.let { append("**").append(it).append("** ") }
            append("(").append(path(snippet.path) ?: name(snippet.node)).append(":").append(snippet.startLine).append("-").append(snippet.endLine).append(")\n\n")
            val run = lines.flatMap { Regex("`+").findAll(it).map { m -> m.value.length }.toList() }.maxOrNull() ?: 0
            val fence = "`".repeat(maxOf(3, run + 1))
            append(fence).append(snippet.path?.substringAfterLast('.', "")?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,6}")) } ?: "").append('\n')
            lines.forEach { append(it).append('\n') }
            append(fence).append('\n')
        }
        append('\n')
    }
}.trimEnd() + "\n"

/** Saved tours as JSON, written by hand so a damaged tour never discards the others and defaults survive. */
internal object TourJson {
    private const val VERSION = 1
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun write(tours: List<SavedTour>): String = gson.toJson(JsonObject().apply {
        addProperty("version", VERSION)
        add("tours", JsonArray().also { array -> tours.forEach { array.add(tour(it)) } })
    })

    fun read(text: String?): List<SavedTour> {
        if (text.isNullOrBlank()) return emptyList()
        val root = try { JsonParser.parseString(text).asJsonObject } catch (_: Exception) { return emptyList() }
        return root.getAsJsonArray("tours")?.mapNotNull { item ->
            try { tour(item.asJsonObject) } catch (_: RuntimeException) { null }
        }.orEmpty()
    }

    private fun JsonObject.str(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.int(name: String): Int = get(name).asInt
    private fun JsonObject.array(name: String): List<JsonObject> = getAsJsonArray(name)?.map { it.asJsonObject }.orEmpty()
    private fun <T : Enum<T>> choice(values: Array<T>, text: String?, default: T? = null): T =
        values.firstOrNull { it.name.equals(text, ignoreCase = true) } ?: default ?: error("Unknown value $text")
    private fun obj(vararg pairs: Pair<String, Any?>) = JsonObject().apply {
        pairs.forEach { (k, v) ->
            when (v) {
                null -> {}
                is String -> addProperty(k, v)
                is Number -> addProperty(k, v)
                is Boolean -> addProperty(k, v)
                is JsonElement -> add(k, v)
                else -> error("Unsupported $v")
            }
        }
    }
    private fun <T> list(items: List<T>, item: (T) -> JsonElement) = JsonArray().also { a -> items.forEach { a.add(item(it)) } }
    private fun ints(items: List<Int>) = JsonArray().also { a -> items.forEach(a::add) }

    private fun tour(t: SavedTour) = obj("name" to t.name, "title" to t.title, "saved_at" to t.savedAt,
        "functions" to list(t.functions) { f -> obj("name" to f.name, "container" to f.container, "path" to f.path,
            "location" to f.location, "kind" to f.kind.name) },
        "beats" to list(t.beats) { b -> obj("caption" to b.caption, "scene" to scene(b.scene)) })

    private fun scene(s: ChartScene) = obj("title" to s.title, "dim_others" to s.dimOthers,
        "nodes" to list(s.nodes) { obj("fn" to it.node, "role" to it.role.name.lowercase(), "badge" to it.badge) },
        "links" to list(s.links) { obj("from" to it.from, "to" to it.to, "kind" to it.kind.name.lowercase(), "label" to it.label) },
        "notes" to list(s.notes) { obj("fn" to it.node, "text" to it.text) },
        "groups" to list(s.groups) { obj("label" to it.label, "kind" to it.kind?.name?.lowercase(), "fns" to ints(it.nodes)) },
        "externals" to list(s.externals) { e -> obj("id" to e.id, "kind" to e.kind.name.lowercase(), "label" to e.label,
            "connected" to list(e.connected) { obj("fn" to it.node, "direction" to it.direction.name.lowercase(), "label" to it.label) }) },
        "snippets" to list(s.snippets) { obj("fn" to it.node, "start_line" to it.startLine, "end_line" to it.endLine,
            "caption" to it.caption, "path" to it.path, "hash" to it.hash,
            "lines" to it.lines?.let { lines -> JsonArray().also { a -> lines.forEach(a::add) } }) })

    private fun tour(o: JsonObject): SavedTour {
        val functions = o.array("functions").map { f ->
            TourFunction(f.str("name")!!, f.str("container").orEmpty(), f.str("path"), f.str("location").orEmpty(),
                choice(SymbolKind.entries.toTypedArray(), f.str("kind"), SymbolKind.FUNCTION))
        }
        val beats = o.array("beats").map { b -> StoryBeat(b.str("caption").orEmpty(), scene(b.getAsJsonObject("scene"), functions.size)) }
        require(beats.isNotEmpty())
        return SavedTour(o.str("name")!!, o.str("title") ?: o.str("name")!!, functions, beats, o.get("saved_at")?.asLong ?: 0)
    }

    private fun scene(o: JsonObject, size: Int): ChartScene {
        fun JsonObject.fn(name: String = "fn") = int(name).also { require(it in 0 until size) }
        return ChartScene(o.str("title"),
            o.array("nodes").map { SceneNode(it.fn(), choice(SceneRole.entries.toTypedArray(), it.str("role"), SceneRole.CONTEXT), it.str("badge")) },
            o.array("links").map { SceneLink(it.fn("from"), it.fn("to"), choice(SceneLinkKind.entries.toTypedArray(), it.str("kind")), it.str("label")) },
            o.array("notes").map { SceneNote(it.fn(), it.str("text").orEmpty()) },
            o.get("dim_others")?.asBoolean ?: true,
            o.array("groups").map { g -> SceneGroup(g.str("label").orEmpty(), g.getAsJsonArray("fns").map { it.asInt.also { id -> require(id in 0 until size) } },
                g.str("kind")?.let { choice(SceneGroupKind.entries.toTypedArray(), it) }) },
            o.array("externals").map { e -> SceneExternal(e.str("id").orEmpty(), choice(ExternalKind.entries.toTypedArray(), e.str("kind"), ExternalKind.OTHER),
                e.str("label").orEmpty(), e.array("connected").map { ExternalLink(it.fn(), choice(ExternalDirection.entries.toTypedArray(), it.str("direction"), ExternalDirection.BOTH), it.str("label")) }) },
            o.array("snippets").map { SceneSnippet(it.fn(), it.int("start_line"), it.int("end_line"), it.str("caption"), it.str("path"),
                it.getAsJsonArray("lines")?.map { line -> line.asString }, it.str("hash")) })
    }
}

/** Saved tours of the project, in the project's `.idea/insight-tours.xml` as one JSON document. EDT. */
@Service(Service.Level.PROJECT)
@State(name = "InsightChartTours", storages = [Storage("insight-tours.xml")])
class ChartTourStore : SimplePersistentStateComponent<ChartTourStore.ToursState>(ToursState()) {
    class ToursState : BaseState() { var json by string() }

    val tours: List<SavedTour> get() = TourJson.read(state.json)

    fun find(name: String): SavedTour? = tours.firstOrNull { it.name == name.trim() } ?: tours.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    /** Adds [tour], replacing a tour of the same name. */
    fun put(tour: SavedTour) { write(tours.filter { !it.name.equals(tour.name, ignoreCase = true) } + tour) }

    fun rename(name: String, to: String): Boolean {
        val target = to.trim().take(SavedTour.MAX_NAME)
        if (target.isEmpty() || tours.any { it.name.equals(target, ignoreCase = true) && it.name != name }) return false
        val current = tours
        if (current.none { it.name == name }) return false
        write(current.map { if (it.name == name) it.copy(name = target) else it })
        return true
    }

    fun delete(name: String) { write(tours.filter { it.name != name }) }

    private fun write(tours: List<SavedTour>) { state.json = TourJson.write(tours) }
}
