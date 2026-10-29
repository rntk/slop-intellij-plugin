package dev.insight.callgraph

import com.google.gson.JsonObject
import dev.insight.agent.ChatTool
import dev.insight.agent.ToolInputException
import dev.insight.agent.booleanArg
import dev.insight.agent.lenientIds
import dev.insight.agent.lenientInt
import dev.insight.agent.objectTool
import dev.insight.agent.optionalBooleanArg
import dev.insight.agent.optionalStringArg
import dev.insight.chat.ChartChatContext
import dev.insight.graph.FlowRegionEvidence
import dev.insight.llm.JsonSchemas
import dev.insight.llm.JsonSchemas.array
import dev.insight.llm.JsonSchemas.boolean
import dev.insight.llm.JsonSchemas.integer
import dev.insight.llm.JsonSchemas.string

/** Implement only the capabilities a session supports; their tools are registered together. */
interface ChartReader : ChartChatContext {
    override suspend fun snapshot(): String = state(0)
    suspend fun state(offset: Int): String
    suspend fun findNodes(query: String?, offset: Int, role: String? = null, effect: String? = null, entryPoint: Boolean? = null): String
    suspend fun describe(revision: Int, node: Int, offset: Int): String
    suspend fun functionOutline(revision: Int, nodes: List<Int>): String
    suspend fun flowCatalog(revision: Int, query: String? = null, offset: Int = 0): String
}

interface ChartEditor {
    suspend fun apply(action: String, revision: Int, nodes: List<Int>): String
    suspend fun groupCalls(revision: Int, owner: Int, blockKey: String, groups: List<ChartCallGroup>): String
    suspend fun setRegionExpansion(revision: Int, keys: List<String>, expanded: Boolean): String
}

interface ChartScenes {
    suspend fun showScene(revision: Int, scene: ChartScene): String
    suspend fun clearScene(revision: Int): String
    suspend fun presentStory(revision: Int, title: String, beats: List<StoryBeat>): String
}

interface ChartAnalysis {
    suspend fun tracePath(revision: Int, from: Int, to: Int, maxPaths: Int, maxDepth: Int): String
    suspend fun reachability(revision: Int, node: Int): String
    suspend fun suggestExternals(revision: Int, nodes: List<Int>): String
    suspend fun projectMap(offset: Int): String
}

interface ChartTours {
    suspend fun listTours(): String
    suspend fun playTour(name: String): String
}

/** [followups] receives validated suggest_followups questions for the session's latest reply. */
fun chartTools(context: ChartChatContext, followups: suspend (List<String>) -> Unit = {}): List<ChatTool> {
    fun obj(vararg properties: Pair<String, JsonObject>, required: List<String> = properties.map { it.first }) =
        JsonSchemas.obj(*properties, required = required, additionalProperties = false)
    fun enum(values: List<Enum<*>>, description: String? = null) =
        JsonSchemas.enum(values.map { it.name.lowercase() }, description)
    val offset = "offset" to integer(minimum = 0, description = "Pagination offset; defaults to 0.")
    val mutation = obj("revision" to integer(), "node_ids" to array(integer(), minItems = 1, maxItems = 20))
    val sceneProperties = arrayOf(
        "title" to string(maxLength = ChartScene.MAX_TITLE, description = "Short caption shown on the chart."),
        "nodes" to array(obj(
            "id" to integer(),
            "role" to enum(SceneRole.entries, "focus: what the answer is about (default); context: surrounding steps; " +
                "source: where data enters; sink: where it ends up (storage, network, UI); problem: bug or risk; " +
                "transform: maps or enriches data; decision: routing, validation or guard; orchestrator: coordinates pipeline steps."),
            "badge" to string(maxLength = ChartScene.MAX_BADGE, description = "Step number used in your text, e.g. \"1\"."),
            required = listOf("id")), maxItems = ChartScene.MAX_NODES),
        "links" to array(obj(
            "from" to integer(), "to" to integer(), "kind" to enum(SceneLinkKind.entries),
            "label" to string(maxLength = ChartScene.MAX_LABEL),
            "payload" to string(maxLength = ChartScene.MAX_PAYLOAD, description = "Optional data contract or payload moving across this link."),
            required = listOf("from", "to", "kind")), maxItems = ChartScene.MAX_LINKS),
        "notes" to array(obj("node" to integer(), "text" to string(maxLength = ChartScene.MAX_NOTE)), maxItems = ChartScene.MAX_NOTES),
        "dim_others" to boolean(description = "Fade steps outside the scene; defaults to true."),
        "columns" to array(obj(
            "node" to integer(), "title" to string(maxLength = ChartScene.MAX_TITLE),
            "description" to string(maxLength = ChartScene.MAX_COLUMN_DESCRIPTION), required = listOf("node")),
            maxItems = ChartScene.MAX_COLUMNS,
            description = "Optional column roots with custom titles/descriptions to stage side-by-side lanes."),
        "reset_board" to boolean(description = "Clear previous columns before placing this scene; defaults to false."),
        "camera" to enum(SceneCamera.entries, "Viewport framing: focus_first (default) or fit_scene."),
        "groups" to array(obj(
            "label" to string(maxLength = ChartScene.MAX_SHORT_LABEL),
            "nodes" to array(integer(), minItems = 1), "kind" to enum(SceneGroupKind.entries),
            required = listOf("label", "nodes")), maxItems = ChartScene.MAX_GROUPS,
            description = "Labelled boxes behind layers or stages; a function in at most one group."),
        "externals" to array(obj(
            "id" to string(pattern = "^[a-z0-9][a-z0-9_-]{0,23}$", description = "Short slug."),
            "kind" to enum(ExternalKind.entries), "label" to string(maxLength = ChartScene.MAX_SHORT_LABEL),
            "connected" to array(obj(
                "node" to integer(),
                "direction" to enum(ExternalDirection.entries, "in: from the external into the function."),
                "label" to string(maxLength = ChartScene.MAX_SHORT_LABEL), required = listOf("node", "direction")),
                minItems = 1, maxItems = ChartScene.MAX_EXTERNAL_LINKS)), maxItems = ChartScene.MAX_EXTERNALS,
            description = "Systems outside the code where data enters or leaves. database/network ones must match rule effects (see suggest_externals)."),
        "snippets" to array(obj(
            "node" to integer(),
            "start_line" to integer(minimum = 1, description = "1-based line in the file, inside the function."),
            "end_line" to integer(minimum = 1, description = "At most ${ChartScene.MAX_SNIPPET_LINES} lines after start_line - 1."),
            "caption" to string(maxLength = ChartScene.MAX_SNIPPET_CAPTION), required = listOf("node", "start_line", "end_line")),
            maxItems = ChartScene.MAX_SNIPPETS,
            description = "Source lines shown beside a function's card; read from source when applied and returned for quoting."),
    )
    return buildList {
        (context as? ChartReader)?.let { context ->
            addAll(listOf(
                objectTool("get_chart_state", "Read current chart selection, columns, visible steps, calls, compact regions, expansion state and the current scene (paged).", obj(offset, required = emptyList())) {
                    context.state(it.nonnegativeInt("offset", 0))
                },
                objectTool("find_chart_nodes", "Find in-project functions by name, container, source path or semantic filters (role, effect, entry_point) and enriched summaries (paged).",
                    obj("query" to string(description = "Substring of function name, container, path or enriched summary."),
                        "role" to JsonSchemas.enum(FIND_ROLES), "effect" to JsonSchemas.enum(FIND_EFFECTS),
                        "entry_point" to boolean(), offset, required = emptyList())) { args ->
                    val query = args.optionalStringArg("query")?.trim()?.take(200)
                    val role = args.optionalChoice("role", FIND_ROLES)
                    val effect = args.optionalChoice("effect", FIND_EFFECTS)
                    val entryPoint = args.optionalBooleanArg("entry_point")
                    val off = args.nonnegativeInt("offset", 0)
                    if (query == null && role == null && effect == null && entryPoint == null)
                        throw ToolInputException("query or a semantic filter (role, effect, entry_point) is required")
                    context.findNodes(query, off, role, effect, entryPoint)
                },
                objectTool("get_chart_node_details", "Inspect a function and its static callers/callees, including hidden nodes (paged).",
                    obj("revision" to integer(), "node_id" to integer(), offset, required = listOf("revision", "node_id"))) {
                    context.describe(it.nonnegativeInt("revision"), it.nonnegativeInt("node_id"), it.nonnegativeInt("offset", 0))
                },
                objectTool("get_function_flow_outline", "Structured control-flow and call-site outline for 1 to 5 functions: ordered call occurrences with their enclosing conditions/loops, return/throw exits, dispatch implementations, compact subflows and groupable_blocks with exact block_key/occurrence_id strings for group_chart_calls.",
                    obj("revision" to integer(), "node_ids" to array(integer(), minItems = 1, maxItems = 5))) { args ->
                    context.functionOutline(args.nonnegativeInt("revision"), args.lenientIds("node_ids", 1..5))
                },
                objectTool("get_flow_catalog", "Catalog of named flows from enrichment and discovered/enriched entry points, with their spine steps, summaries and reached rule effects (paged, optional query filter).",
                    obj("revision" to integer(), "query" to string(), offset, required = listOf("revision"))) { args ->
                    val revision = args.nonnegativeInt("revision")
                    val query = args.get("query")?.takeIf { !it.isJsonNull }?.let { value ->
                        value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()
                            ?: throw ToolInputException("query must be a string")
                    }
                    val off = args.nonnegativeInt("offset", 0)
                    context.flowCatalog(revision, query, off)
                },
            ))
        }
        (context as? ChartEditor)?.let { context ->
            addAll(listOf(
                objectTool("group_chart_calls", "Replace one source-backed block with named high-level groups, initially collapsed and clickable " +
                    "for details. First place the owner and read get_function_flow_outline.groupable_blocks. Groups use consecutive occurrence_id " +
                    "strings in source order, within one block, with no overlaps. Ungrouped calls remain visible; groups:[] removes grouping " +
                    "for this block. Enables compact mode. Titles/descriptions are your interpretation; include source citations in descriptions. " +
                    "Undoable; cleared on board reset or graph rebuild, not saved in tours.",
                    obj("revision" to integer(), "owner_node_id" to integer(),
                        "block_key" to string(maxLength = ChartCallGroup.MAX_KEY),
                        "groups" to array(obj(
                            "occurrence_ids" to array(string(maxLength = 40), minItems = 2, maxItems = FlowRegionEvidence.MAX_REGION_CALLS),
                            "title" to string(maxLength = ChartCallGroup.MAX_TITLE),
                            "description" to string(maxLength = ChartCallGroup.MAX_DESCRIPTION),
                            required = listOf("occurrence_ids", "title")), maxItems = ChartCallGroup.MAX_GROUPS))) { args ->
                    context.groupCalls(args.nonnegativeInt("revision"), args.nonnegativeInt("owner_node_id"),
                        groupingText(args, "block_key", ChartCallGroup.MAX_KEY), parseChartCallGroups(args))
                },
                objectTool("set_chart_region_expansion", "Show or hide the details of existing compact regions. Use exact region_key strings " +
                    "from get_chart_state or group_chart_calls and the latest revision. Idempotent; validates all targets before changing any.",
                    obj("revision" to integer(),
                        "region_keys" to array(string(maxLength = ChartCallGroup.MAX_KEY), minItems = 1, maxItems = 20),
                        "expanded" to boolean())) { args ->
                    val keys = groupingStrings(args, "region_keys", 1, 20, ChartCallGroup.MAX_KEY)
                    val expanded = args.optionalBooleanArg("expanded") ?: throw ToolInputException("expanded must be true or false")
                    context.setRegionExpansion(args.nonnegativeInt("revision"), keys.distinct(), expanded)
                },
            ))
            addAll(listOf(
                "add_chart_nodes" to "Add existing project functions, connected to visible steps they call or are called by; unconnected ones become new columns. Does not create code or invented nodes.",
                "expand_chart_calls" to "Show calls for visible function IDs, preserving already open calls.",
                "expand_chart_callers" to "Show callers for visible function IDs, preserving already open callers.",
                "collapse_chart_calls" to "Hide calls for visible function IDs to reduce chart clutter.",
                "collapse_chart_callers" to "Hide callers for visible function IDs to reduce chart clutter.",
            ).map { (name, description) ->
                objectTool(name, description, mutation) { args ->
                    context.apply(name, args.nonnegativeInt("revision"), args.lenientIds("node_ids", 1..20))
                }
            })
        }
        (context as? ChartScenes)?.let { context ->
            addAll(listOf(
                objectTool("show_scene", "Show one point of your explanation on the chart: emphasize functions by role with optional step badges, " +
                    "draw typed links and short notes, and fade the rest. Replaces the previous scene, places hidden functions as continuations " +
                    "of the chart (link and note targets not listed become context), and focuses the first focus node. Validated as a whole; " +
                    "kind call links must be static calls from -> to. Optional groups (layers/stages), externals (where data enters or " +
                    "leaves) and snippets (exact source lines).", obj("revision" to integer(), *sceneProperties, required = listOf("revision", "nodes"))) {
                    context.showScene(it.nonnegativeInt("revision"), parseScene(it))
                },
                objectTool("present_story", "Present a guided tour of a multi-stage flow: ${ChartStory.MIN_BEATS}-${ChartStory.MAX_BEATS} beats " +
                    "(3-6 is best), big picture first, each a caption and a scene like show_scene. All beats are validated before " +
                    "anything changes; beat 1 is shown and the user steps through the rest on the chart. Replaces the current scene.",
                    obj("revision" to integer(), "title" to string(maxLength = ChartScene.MAX_TITLE),
                        "beats" to array(obj(
                            "caption" to string(maxLength = ChartStory.MAX_CAPTION, description = "What this beat shows, one or two sentences."),
                            "scene" to obj(*sceneProperties,
                                required = listOf("nodes"))), minItems = ChartStory.MIN_BEATS, maxItems = ChartStory.MAX_BEATS))) {
                    val (title, beats) = parseStory(it)
                    context.presentStory(it.nonnegativeInt("revision"), title, beats)
                },
                objectTool("clear_scene", "Remove the current scene's emphasis, badges, notes and links.", obj("revision" to integer())) {
                    context.clearScene(it.nonnegativeInt("revision"))
                },
            ))
        }
        (context as? ChartAnalysis)?.let { context ->
            addAll(listOf(
                objectTool("get_project_map", "The zoomed-out view of the project: areas (directories) with function, entry point and storage/network " +
                    "counts and hub functions, static call counts between areas, entry points and named flows (global sections on offset 0; " +
                    "areas paged). Marks what is unavailable until Entry Points search or Enrich runs. Start broad questions here.", obj(offset, required = emptyList())) {
                    context.projectMap(it.nonnegativeInt("offset", 0))
                },
                objectTool("trace_path", "Shortest static in-project call paths from one function to another (breadth-first, bounded). Reports a " +
                    "reverse path when only to -> from exists. Use it instead of expanding calls step by step.",
                    obj("revision" to integer(), "from" to integer(), "to" to integer(),
                        "max_paths" to integer(minimum = 1, maximum = 5, description = "Defaults to 3."),
                        "max_depth" to integer(minimum = 1, maximum = 12, description = "Most calls per path; defaults to 8."),
                        required = listOf("revision", "from", "to"))) {
                    context.tracePath(it.nonnegativeInt("revision"), it.nonnegativeInt("from"), it.nonnegativeInt("to"),
                        it.nonnegativeInt("max_paths", 3).coerceIn(1, 5), it.nonnegativeInt("max_depth", 8).coerceIn(1, 12))
                },
                objectTool("reachability", "Entry points that statically reach a function (nearest first, with a path), direct and transitive " +
                    "caller/callee counts, and rule-derived storage/network/throws effects.",
                    obj("revision" to integer(), "node" to integer())) {
                    context.reachability(it.nonnegativeInt("revision"), it.nonnegativeInt("node"))
                },
                objectTool("suggest_externals", "Rule effects of functions for grounding scene externals before drawing them: storage and network " +
                    "(with the matched imports, or the nearest callees that have the effect when only reached) and throws.",
                    obj("revision" to integer(), "node_ids" to array(integer(), minItems = 1, maxItems = ChartScene.MAX_NODES))) { args ->
                    context.suggestExternals(args.nonnegativeInt("revision"), args.lenientIds("node_ids", 1..ChartScene.MAX_NODES))
                },
            ))
        }
        (context as? ChartTours)?.let { context ->
            addAll(listOf(
                objectTool("list_tours", "Saved tours of this project: name, title, beat captions and main functions. Does not change the chart.", obj()) {
                    context.listTours()
                },
                objectTool("play_tour", "Replay a saved tour as a story on the current graph. Functions are matched by stable identity; missing ones, " +
                    "beats left empty and links, notes, externals or snippets that no longer resolve are skipped and reported.",
                    obj("name" to string(maxLength = SavedTour.MAX_NAME))) { args ->
                    val name = args.get("name")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()
                        ?.takeIf(String::isNotEmpty) ?: throw ToolInputException("name must be a nonempty string; list_tours shows the saved tours")
                    context.playTour(name.take(SavedTour.MAX_NAME))
                },
            ))
        }
        add(
            objectTool("suggest_followups", "Offer up to 3 short follow-up questions, shown as buttons under your answer; does not change the chart.",
                obj("questions" to array(string(maxLength = MAX_FOLLOWUP), minItems = 1, maxItems = 3))) {
                followups(parseFollowups(it))
                """{"ok":true,"note":"Shown under your answer; the user may click one."}"""
            }
        )
    }
}

private fun groupingText(args: JsonObject, name: String, max: Int): String {
    val text = args.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()
        ?: throw ToolInputException("$name must be a string")
    if (text.isEmpty() || text.length > max) throw ToolInputException("$name must be 1-$max characters")
    return text
}

private fun groupingStrings(args: JsonObject, name: String, min: Int, max: Int, maxLength: Int): List<String> {
    val array = args.get(name)?.takeIf { it.isJsonArray }?.asJsonArray
        ?: throw ToolInputException("$name must be an array of strings")
    if (array.size() !in min..max) throw ToolInputException("$name needs $min-$max strings")
    return array.map { value ->
        val text = value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: throw ToolInputException("$name must contain strings")
        if (text.isBlank() || text.length > maxLength) throw ToolInputException("$name strings must be 1-$maxLength characters")
        text
    }
}

internal fun parseChartCallGroups(args: JsonObject): List<ChartCallGroup> {
    val groups = args.get("groups")?.takeIf { it.isJsonArray }?.asJsonArray
        ?: throw ToolInputException("groups must be an array")
    if (groups.size() > ChartCallGroup.MAX_GROUPS) throw ToolInputException("Use at most ${ChartCallGroup.MAX_GROUPS} groups per block")
    return groups.mapIndexed { i, value ->
        val group = value.takeIf { it.isJsonObject }?.asJsonObject ?: throw ToolInputException("groups[$i] must be an object")
        ChartCallGroup(groupingStrings(group, "occurrence_ids", 2, FlowRegionEvidence.MAX_REGION_CALLS, 40),
            groupingText(group, "title", ChartCallGroup.MAX_TITLE),
            if (group.has("description")) groupingText(group, "description", ChartCallGroup.MAX_DESCRIPTION) else null)
    }
}

/** Parses and bounds a show_scene request; graph checks (IDs, static calls, revision) happen when it is applied. */
internal fun parseScene(args: JsonObject): ChartScene {
    fun objects(name: String, max: Int) = (args.get(name) ?: com.google.gson.JsonArray()).let { value ->
        if (!value.isJsonArray) throw ToolInputException("$name must be an array")
        if (value.asJsonArray.size() > max) throw ToolInputException("A scene allows at most $max $name; split it into several scenes")
        value.asJsonArray.mapIndexed { index, item ->
            item.takeIf { it.isJsonObject }?.asJsonObject ?: throw ToolInputException("$name[$index] must be an object")
        }
    }
    fun JsonObject.text(name: String, max: Int, where: String): String? {
        val value = get(name)?.takeIf { !it.isJsonNull } ?: return null
        val text = value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()
            ?: throw ToolInputException("$where.$name must be a string")
        if (text.length > max) throw ToolInputException("$where.$name must be at most $max characters")
        return text.ifEmpty { null }
    }
    fun <T : Enum<T>> JsonObject.choice(name: String, values: Array<T>, where: String, default: T? = null): T {
        val text = text(name, 20, where) ?: return default ?: throw ToolInputException("$where.$name is required")
        return values.firstOrNull { it.name.equals(text, ignoreCase = true) }
            ?: throw ToolInputException("$where.$name must be one of ${values.joinToString { it.name.lowercase() }}")
    }
    if (!args.has("nodes")) throw ToolInputException("nodes is required; use clear_scene to remove the scene")
    val nodes = objects("nodes", ChartScene.MAX_NODES).mapIndexed { i, item ->
        SceneNode(item.nonnegativeInt("id", where = "nodes[$i]"), item.choice("role", SceneRole.entries.toTypedArray(), "nodes[$i]", SceneRole.FOCUS),
            item.text("badge", ChartScene.MAX_BADGE, "nodes[$i]"))
    }
    if (nodes.isEmpty()) throw ToolInputException("nodes must not be empty; use clear_scene to remove the scene")
    nodes.groupBy { it.node }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { throw ToolInputException("Node $it is listed twice; give it one role") }
    val links = objects("links", ChartScene.MAX_LINKS).mapIndexed { i, item ->
        SceneLink(item.nonnegativeInt("from", where = "links[$i]"), item.nonnegativeInt("to", where = "links[$i]"),
            item.choice("kind", SceneLinkKind.entries.toTypedArray(), "links[$i]"), item.text("label", ChartScene.MAX_LABEL, "links[$i]"),
            item.text("payload", ChartScene.MAX_PAYLOAD, "links[$i]"))
            .also { if (it.from == it.to) throw ToolInputException("links[$i] must join two different functions") }
    }.distinctBy { Triple(it.from, it.to, it.kind) }
    val notes = objects("notes", ChartScene.MAX_NOTES).mapIndexed { i, item ->
        SceneNote(item.nonnegativeInt("node", where = "notes[$i]"), item.text("text", ChartScene.MAX_NOTE, "notes[$i]")
            ?: throw ToolInputException("notes[$i].text must not be empty"))
    }
    notes.groupBy { it.node }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { throw ToolInputException("Node $it has two notes; merge them") }
    val dim = args.optionalBooleanArg("dim_others") ?: true
    val columns = objects("columns", ChartScene.MAX_COLUMNS).mapIndexed { i, item ->
        SceneColumn(item.nonnegativeInt("node", where = "columns[$i]"),
            item.text("title", ChartScene.MAX_TITLE, "columns[$i]"),
            item.text("description", ChartScene.MAX_COLUMN_DESCRIPTION, "columns[$i]"))
    }
    columns.groupBy { it.node }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
        throw ToolInputException("Column node $it is listed twice")
    }
    val resetBoard = args.booleanArg("reset_board")
    val camera = args.get("camera")?.takeIf { !it.isJsonNull }?.let {
        args.choice("camera", SceneCamera.entries.toTypedArray(), "scene")
    } ?: SceneCamera.FOCUS_FIRST
    val groups = objects("groups", ChartScene.MAX_GROUPS).mapIndexed { i, item ->
        SceneGroup(item.text("label", ChartScene.MAX_SHORT_LABEL, "groups[$i]") ?: throw ToolInputException("groups[$i].label must not be empty"),
            item.lenientIds("nodes", 1..ChartScene.MAX_NODES, "groups[$i].nodes"), item.get("kind")?.takeIf { !it.isJsonNull }?.let {
                item.choice("kind", SceneGroupKind.entries.toTypedArray(), "groups[$i]") })
    }
    groups.flatMap { it.nodes }.groupBy { it }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
        throw ToolInputException("Node $it is in two groups; a function belongs to at most one group")
    }
    val externals = objects("externals", ChartScene.MAX_EXTERNALS).mapIndexed { i, item ->
        val where = "externals[$i]"
        val id = item.text("id", 24, where)?.takeIf { it.matches(Regex("[a-z0-9][a-z0-9_-]*")) }
            ?: throw ToolInputException("$where.id must be a short slug such as \"orders-db\" (a-z, 0-9, - and _)")
        val connected = (item.get("connected")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw ToolInputException("$where.connected must be an array")).mapIndexed { j, value ->
            val link = value.takeIf { it.isJsonObject }?.asJsonObject ?: throw ToolInputException("$where.connected[$j] must be an object")
            ExternalLink(link.nonnegativeInt("node", where = "$where.connected[$j]"),
                link.choice("direction", ExternalDirection.entries.toTypedArray(), "$where.connected[$j]"),
                link.text("label", ChartScene.MAX_SHORT_LABEL, "$where.connected[$j]"))
        }.distinctBy { it.node }
        if (connected.size !in 1..ChartScene.MAX_EXTERNAL_LINKS)
            throw ToolInputException("$where.connected needs 1 to ${ChartScene.MAX_EXTERNAL_LINKS} functions")
        SceneExternal(id, item.choice("kind", ExternalKind.entries.toTypedArray(), where),
            item.text("label", ChartScene.MAX_SHORT_LABEL, where) ?: throw ToolInputException("$where.label must not be empty"), connected)
    }
    externals.groupBy { it.id }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { throw ToolInputException("External id \"$it\" is used twice") }
    val snippets = objects("snippets", ChartScene.MAX_SNIPPETS).mapIndexed { i, item ->
        val where = "snippets[$i]"
        val start = item.nonnegativeInt("start_line", where = where)
        val end = item.nonnegativeInt("end_line", where = where)
        if (start < 1 || end < start) throw ToolInputException("$where needs 1 <= start_line <= end_line (1-based file lines)")
        if (end - start + 1 > ChartScene.MAX_SNIPPET_LINES)
            throw ToolInputException("$where spans ${end - start + 1} lines; show at most ${ChartScene.MAX_SNIPPET_LINES}, just the lines that matter")
        SceneSnippet(item.nonnegativeInt("node", where = where), start, end, item.text("caption", ChartScene.MAX_SNIPPET_CAPTION, where))
    }
    return ChartScene(args.text("title", ChartScene.MAX_TITLE, "scene"), nodes, links, notes, dim, groups, externals, snippets, columns, resetBoard, camera)
}

internal const val MAX_FOLLOWUP = 80

/** Parses and bounds a present_story request; every beat scene is parsed like show_scene and errors name the beat. */
internal fun parseStory(args: JsonObject): Pair<String, List<StoryBeat>> {
    val title = args.get("title")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()
        ?.takeIf(String::isNotEmpty) ?: throw ToolInputException("title must be a nonempty string")
    if (title.length > ChartScene.MAX_TITLE) throw ToolInputException("title must be at most ${ChartScene.MAX_TITLE} characters")
    val beats = args.get("beats")?.takeIf { it.isJsonArray }?.asJsonArray ?: throw ToolInputException("beats must be an array")
    if (beats.size() !in ChartStory.MIN_BEATS..ChartStory.MAX_BEATS)
        throw ToolInputException("A story needs ${ChartStory.MIN_BEATS} to ${ChartStory.MAX_BEATS} beats; use show_scene for a single point")
    return title to beats.mapIndexed { i, item ->
        val beat = item.takeIf { it.isJsonObject }?.asJsonObject ?: throw ToolInputException("beats[$i] must be an object")
        val caption = beat.get("caption")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()
            ?.takeIf(String::isNotEmpty) ?: throw ToolInputException("beats[$i].caption must be a nonempty string")
        if (caption.length > ChartStory.MAX_CAPTION) throw ToolInputException("beats[$i].caption must be at most ${ChartStory.MAX_CAPTION} characters")
        val scene = beat.get("scene")?.takeIf { it.isJsonObject }?.asJsonObject ?: throw ToolInputException("beats[$i].scene must be an object")
        StoryBeat(caption, try { parseScene(scene) } catch (e: ToolInputException) { throw ToolInputException("beats[$i].scene: ${e.message}") })
    }
}

internal fun parseFollowups(args: JsonObject): List<String> {
    val array = args.get("questions")?.takeIf { it.isJsonArray }?.asJsonArray ?: throw ToolInputException("questions must be an array")
    if (array.size() !in 1..3) throw ToolInputException("Give 1 to 3 questions")
    return array.mapIndexed { i, value ->
        val text = value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()?.replace(Regex("\\s+"), " ")
            ?.takeIf(String::isNotEmpty) ?: throw ToolInputException("questions[$i] must be a nonempty string")
        if (text.length > MAX_FOLLOWUP) throw ToolInputException("questions[$i] must be at most $MAX_FOLLOWUP characters; shorten it")
        text
    }.distinct()
}

private fun JsonObject.nonnegativeInt(name: String, default: Int? = null, where: String? = null): Int {
    val label = where?.let { "$it.$name" } ?: name
    return lenientInt(name, label, nonnegative = true) ?: default ?: throw ToolInputException("$label is required")
}

private val FIND_ROLES = listOf("core", "validation", "error-handling", "logging", "plumbing", "utility")
private val FIND_EFFECTS = listOf("storage", "network", "throws")


/** Null when missing or null; otherwise one of [values], ignoring case. */
private fun JsonObject.optionalChoice(name: String, values: List<String>): String? =
    optionalStringArg(name)?.trim()?.lowercase()?.also {
        if (it !in values) throw ToolInputException("$name must be one of ${values.joinToString()}")
    }
