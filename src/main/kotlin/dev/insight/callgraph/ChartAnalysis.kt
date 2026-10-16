package dev.insight.callgraph

import com.google.gson.Gson
import dev.insight.chat.ToolInputException

/** Deterministic, bounded and PSI-free questions about static in-project calls; safe off the EDT on an immutable graph. */
internal class StaticCalls(graph: CallGraph) {
    val callees: Map<Int, List<Int>>
    val callers: Map<Int, List<Int>>

    init {
        fun function(id: Int) = graph.nodes.getOrNull(id)?.let { it.inProject && it.kind == SymbolKind.FUNCTION } == true
        val edges = graph.edges.filter { it.kind == RelationKind.CALL && it.caller != it.callee && function(it.caller) && function(it.callee) }
        callees = edges.groupBy({ it.caller }, { it.callee }).mapValues { (_, ids) -> ids.distinct().sorted() }
        callers = edges.groupBy({ it.callee }, { it.caller }).mapValues { (_, ids) -> ids.distinct().sorted() }
    }

    /**
     * Up to [maxPaths] shortest simple paths from [from] to [to] with at most [maxDepth] calls, in order of length. Each
     * function is expanded at most [maxPaths] times and the whole search stops after [budget] steps (then truncated).
     */
    fun paths(from: Int, to: Int, maxPaths: Int, maxDepth: Int, next: Map<Int, List<Int>> = callees,
        budget: Int = 50_000): Pair<List<List<Int>>, Boolean> {
        val found = mutableListOf<List<Int>>()
        val expanded = HashMap<Int, Int>()
        val queue = ArrayDeque(listOf(listOf(from)))
        var work = 0
        while (queue.isNotEmpty() && found.size < maxPaths) {
            val path = queue.removeFirst()
            val last = path.last()
            if (last == to) { found += path; continue }
            if (path.size > maxDepth || expanded.merge(last, 1, Int::plus)!! > maxPaths) continue
            for (id in next[last].orEmpty()) {
                if (++work > budget) return found to true
                if (id !in path) queue.addLast(path + id)
            }
        }
        return found to false
    }
}

internal fun chartTracePath(graph: CallGraph, currentRevision: Int, revision: Int, from: Int, to: Int, maxPaths: Int, maxDepth: Int,
    steps: Map<Int, Int> = emptyMap()): String {
    validateChatNode(graph, currentRevision, revision, from)
    validateChatNode(graph, currentRevision, revision, to)
    if (from == to) throw ToolInputException("from and to must be different functions")
    val calls = StaticCalls(graph)
    val (paths, truncated) = calls.paths(from, to, maxPaths, maxDepth)
    fun node(id: Int) = chartNodeData(graph.nodes[id]) + mapOf("step_number" to steps[id], "visible" to (id in steps))
    val reverse = if (paths.isEmpty()) calls.paths(to, from, 1, maxDepth).first.firstOrNull() else null
    return Gson().toJson(mapOf("revision" to revision, "from" to from, "to" to to, "max_depth" to maxDepth,
        "paths_total" to paths.size, "search_truncated" to truncated,
        "paths" to paths.map { path -> mapOf("calls" to path.size - 1, "nodes" to path.map(::node)) },
        "reverse_path" to reverse?.let { path -> mapOf("calls" to path.size - 1, "node_ids" to path,
            "hint" to "No forward path, but $to reaches $from; swap from and to.") },
        "hidden_node_ids" to paths.flatten().distinct().filter { it !in steps },
        "evidence" to if (paths.isEmpty()) "No static in-project call path within max_depth. Dynamic dispatch, callbacks, events or " +
            "external code may still connect them; read source before claiming a relationship."
            else "Every hop is a static in-project call; reachability does not prove runtime order or that the path executes.",
    ))
}

/**
 * Who reaches [node]: entry points among its transitive callers (nearest first, with one example path), counts of direct
 * and transitive callers/callees, and rule effects. [entryPoint] is null when no entry point information exists.
 */
internal fun chartReachability(graph: CallGraph, currentRevision: Int, revision: Int, node: Int,
    entryPoint: ((Int) -> Boolean)?, effects: ((Int) -> Pair<Set<String>, Set<String>>?)?, steps: Map<Int, Int> = emptyMap(),
    limit: Int = 5_000): String {
    validateChatNode(graph, currentRevision, revision, node)
    val calls = StaticCalls(graph)
    fun closure(next: Map<Int, List<Int>>): Pair<LinkedHashMap<Int, Int>, Boolean> {
        val parent = linkedMapOf(node to node)
        val queue = ArrayDeque(listOf(node))
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            for (other in next[id].orEmpty()) if (other !in parent) {
                if (parent.size > limit) return parent to true
                parent[other] = id
                queue.addLast(other)
            }
        }
        return parent to false
    }
    val (up, upTruncated) = closure(calls.callers)
    val (down, downTruncated) = closure(calls.callees)
    fun pathFrom(id: Int) = generateSequence(id) { up[it]?.takeIf { parent -> parent != it } }.toList()
    val entries = entryPoint?.let { up.keys.filter(it) }.orEmpty()
    val effect = effects?.invoke(node)
    return Gson().toJson(mapOf("revision" to revision, "node" to chartNodeData(graph.nodes[node]) + mapOf("step_number" to steps[node]),
        "direct_callers" to calls.callers[node].orEmpty().size, "direct_callees" to calls.callees[node].orEmpty().size,
        "transitive_callers" to up.size - 1, "transitive_callers_truncated" to upTruncated,
        "transitive_callees" to down.size - 1, "transitive_callees_truncated" to downTruncated,
        "entry_point_info" to if (entryPoint == null) "unavailable: run Entry Points search or Enrich" else "available",
        "is_entry_point" to entryPoint?.invoke(node),
        "reaching_entry_points_total" to entries.size,
        "reaching_entry_points" to entries.take(10).map { id -> chartNodeData(graph.nodes[id]) + mapOf(
            "calls_to_node" to pathFrom(id).size - 1, "path" to pathFrom(id), "visible" to (id in steps)) },
        "effects" to if (effects == null) "unavailable: run Enrich for rule effects"
            else mapOf("own" to effect?.first.orEmpty().sorted(), "reached_via_calls" to effect?.second.orEmpty().sorted()),
        "evidence" to "Static in-project calls and rule-derived effects (storage/network imports, throw/raise). Entry points come " +
            "from discovery and enrichment judgments. Reachability does not prove a path executes at runtime.",
    ))
}

/** Rule effects of a function: [own], [reached] (own plus via in-project calls) and the imports matched per own effect. */
data class RuleEffects(val own: Set<String>, val reached: Set<String>, val imports: Map<String, List<String>> = emptyMap())

/**
 * Per function of [nodes], the rule effects that ground externals: storage and network with the matched imports and,
 * when only reached, the nearest static callees that have the effect themselves; and throws. [effects] returns null
 * for a function when no enrichment is available.
 */
internal fun chartExternalEffects(graph: CallGraph, currentRevision: Int, revision: Int, nodes: List<Int>,
    effects: (Int) -> RuleEffects?, steps: Map<Int, Int> = emptyMap(), limit: Int = 5_000): String {
    nodes.forEach { validateChatNode(graph, currentRevision, revision, it) }
    val calls = StaticCalls(graph)
    fun carriers(node: Int, effect: String): List<Int> {
        val seen = hashSetOf(node)
        val queue = ArrayDeque(listOf(node))
        val found = mutableListOf<Int>()
        while (queue.isNotEmpty() && found.size < 3 && seen.size < limit) {
            for (next in calls.callees[queue.removeFirst()].orEmpty()) if (seen.add(next)) {
                if (effect in effects(next)?.own.orEmpty()) found += next else queue.addLast(next)
            }
        }
        return found.take(3)
    }
    return Gson().toJson(mapOf("revision" to revision, "nodes" to nodes.map { id ->
        val found = effects(id)
        chartNodeData(graph.nodes[id]) + mapOf("step_number" to steps[id], "visible" to (id in steps)) + if (found == null)
            mapOf("effects" to "unavailable: run Enrich for rule effects; externals you draw stay unverified explanations")
        else mapOf("effects" to listOf("storage", "network").associateWith { effect -> mapOf(
            "present" to (effect in found.reached || effect in found.own), "own" to (effect in found.own),
            "imports" to found.imports[effect].orEmpty().take(8),
            "via_node_ids" to if (effect in found.reached && effect !in found.own) carriers(id, effect) else emptyList()) } +
            mapOf("throws" to ("throws" in found.own)),
            "grounds_externals" to listOfNotNull("database (or file: storage covers SQL, ORM and file I/O)".takeIf { "storage" in found.reached + found.own },
                "network".takeIf { "network" in found.reached + found.own }))
    }, "evidence" to "Rule effects come from imports a function's file uses and its code mentions, propagated along in-project " +
        "calls, plus throw/raise; they come from the enrichment snapshot and may miss dynamic access. Database externals need " +
        "storage and network externals need network on every connected function; other kinds are your explanation."))
}

/** Enriched summary, role, rule effects and entry-point status of a function for semantic search and outlines. */
data class NodeSemanticInfo(
    val summaryLabel: String? = null,
    val summaryDescription: String? = null,
    val role: String? = null,
    val ownEffects: Set<String> = emptySet(),
    val reachedEffects: Set<String> = emptySet(),
    val entryPoint: Boolean = false,
)

/**
 * Searches in-project functions by [query] (name, qualified name, path or enriched summary label/description) and
 * optional semantic filters ([role], [effect], [entryPoint]), paged 20 at a time.
 */
internal fun chartFindNodes(
    graph: CallGraph,
    revision: Int,
    query: String?,
    offset: Int = 0,
    role: String? = null,
    effect: String? = null,
    entryPoint: Boolean? = null,
    semantics: ((Int) -> NodeSemanticInfo?)? = null,
    steps: Map<Int, Int> = emptyMap(),
): String {
    val found = graph.nodes.filter { node ->
        if (!node.inProject || node.kind != SymbolKind.FUNCTION) return@filter false
        val info = semantics?.invoke(node.id)
        if (!query.isNullOrBlank()) {
            val q = query.trim()
            val matches = node.name.contains(q, ignoreCase = true) ||
                "${node.container}.${node.name}".contains(q, ignoreCase = true) ||
                node.filePath.orEmpty().contains(q, ignoreCase = true) ||
                info?.summaryLabel.orEmpty().contains(q, ignoreCase = true) ||
                info?.summaryDescription.orEmpty().contains(q, ignoreCase = true)
            if (!matches) return@filter false
        }
        if (!role.isNullOrBlank()) {
            if (info?.role?.equals(role.trim(), ignoreCase = true) != true) return@filter false
        }
        if (!effect.isNullOrBlank()) {
            val eff = effect.trim()
            val matches = info != null && (
                info.ownEffects.any { e -> e.equals(eff, ignoreCase = true) } ||
                info.reachedEffects.any { e -> e.equals(eff, ignoreCase = true) }
            )
            if (!matches) return@filter false
        }
        if (entryPoint != null) {
            if ((info?.entryPoint == true) != entryPoint) return@filter false
        }
        true
    }
    val nodesJson = found.drop(maxOf(0, offset)).take(20).map { node ->
        val base = chartNodeData(node) + mapOf(
            "step_number" to steps[node.id],
            "visible" to (node.id in steps),
        )
        val info = semantics?.invoke(node.id)
        if (info != null) {
            base + mapOf(
                "summary" to info.summaryLabel,
                "description" to info.summaryDescription?.take(160),
                "role" to info.role,
                "entry_point" to info.entryPoint,
                "effects" to (info.ownEffects + info.reachedEffects).sorted(),
            )
        } else {
            base
        }
    }
    return Gson().toJson(mapOf(
        "revision" to revision,
        "total" to found.size,
        "offset" to offset,
        "next_offset" to (offset + 20).takeIf { it < found.size },
        "semantics_available" to (semantics != null),
        "nodes" to nodesJson,
    ))
}

/**
 * Structured control-flow and call-site outline for 1 to 5 functions: ordered call occurrences with their enclosing
 * control frames (if/when/loop/try), explicit return/throw exits, dispatch implementations and compact subflow regions.
 */
internal fun chartFunctionOutline(
    graph: CallGraph,
    currentRevision: Int,
    revision: Int,
    nodes: List<Int>,
    callSites: Map<Int, List<SourceSite>>,
    regions: List<FlowRegion> = emptyList(),
    semantics: ((Int) -> NodeSemanticInfo?)? = null,
    steps: Map<Int, Int> = emptyMap(),
): String {
    if (nodes.size !in 1..5) throw ToolInputException("Give 1 to 5 node IDs")
    nodes.forEach { validateChatNode(graph, currentRevision, revision, it) }
    val functions = nodes.map { id ->
        val node = graph.nodes[id]
        val sites = callSites[id].orEmpty().sortedWith(sourceOrder)
        val calls = sites.filterIsInstance<CallSite>().map { site ->
            mapOf(
                "callee_node_id" to site.callee,
                "callee_name" to graph.nodes.getOrNull(site.callee)?.name,
                "offset" to site.offset,
                "text" to site.text?.take(160),
                "control_frames" to site.frames,
            )
        }
        val exits = sites.filterIsInstance<ExitSite>().map { site ->
            mapOf(
                "keyword" to site.keyword,
                "text" to site.text.take(160),
                "offset" to site.offset,
                "control_frames" to site.frames,
            )
        }
        val implementations = graph.edges.filter {
            it.kind == RelationKind.INHERITS && it.caller == id &&
                graph.nodes.getOrNull(it.callee)?.let { n -> n.inProject && n.kind == SymbolKind.FUNCTION } == true
        }.map { chartNodeData(graph.nodes[it.callee]) }
        val subflows = regions.filter { it.plan.evidence.owner == id }.map { r ->
            mapOf(
                "key" to r.plan.evidence.key,
                "title" to r.plan.title,
                "description" to r.plan.description,
                "inferred" to r.plan.inferred,
                "expanded" to r.expanded,
                "callee_node_ids" to r.plan.evidence.sites.map { s -> s.callee },
            )
        }
        val base = chartNodeData(node) + mapOf(
            "step_number" to steps[id],
            "visible" to (id in steps),
            "call_sites_loaded" to (id in callSites),
            "call_sites" to calls.take(40),
            "call_sites_total" to calls.size,
            "exits" to exits.take(20),
            "exits_total" to exits.size,
            "dispatch_implementations" to implementations.take(15),
            "subflows" to subflows,
        )
        val info = semantics?.invoke(id)
        if (info != null) {
            base + mapOf(
                "summary" to info.summaryLabel,
                "description" to info.summaryDescription?.take(160),
                "role" to info.role,
                "entry_point" to info.entryPoint,
                "effects" to (info.ownEffects + info.reachedEffects).sorted(),
            )
        } else {
            base
        }
    }
    return Gson().toJson(mapOf(
        "revision" to revision,
        "functions" to functions,
        "evidence" to "Call sites and exits come from static source analysis of each function's declaration; control_frames show the conditions or loops around each call or exit.",
    ))
}

data class CatalogFlow(val id: String, val title: String, val description: String?, val entry: Int, val nodes: List<Int>)

/**
 * Lists named flows from enrichment and discovered/enriched entry points, optionally filtered by [query], with their
 * spine steps and reached rule effects so the assistant can build multi-beat stories directly.
 */
internal fun chartFlowCatalog(
    graph: CallGraph,
    currentRevision: Int,
    revision: Int,
    query: String? = null,
    offset: Int = 0,
    flows: List<CatalogFlow>? = null,
    entryPoints: List<MapEntryPoint>? = null,
    semantics: ((Int) -> NodeSemanticInfo?)? = null,
    steps: Map<Int, Int> = emptyMap(),
): String {
    if (revision != currentRevision) throw ToolInputException("Stale graph revision; refresh and resolve IDs again")
    val validFlows = flows?.filter { graph.nodes.getOrNull(it.entry)?.let { n -> n.inProject && n.kind == SymbolKind.FUNCTION } == true }
    val matchingFlows = if (query.isNullOrBlank()) validFlows else validFlows?.filter { flow ->
        val q = query.trim()
        flow.title.contains(q, ignoreCase = true) ||
            flow.description.orEmpty().contains(q, ignoreCase = true) ||
            flow.nodes.any { id ->
                graph.nodes.getOrNull(id)?.let { n ->
                    n.name.contains(q, ignoreCase = true) ||
                        "${n.container}.${n.name}".contains(q, ignoreCase = true) ||
                        n.filePath.orEmpty().contains(q, ignoreCase = true) ||
                        semantics?.invoke(id)?.summaryLabel.orEmpty().contains(q, ignoreCase = true) ||
                        semantics?.invoke(id)?.summaryDescription.orEmpty().contains(q, ignoreCase = true)
                } == true
            }
    }
    val validEntryPoints = entryPoints?.filter { graph.nodes.getOrNull(it.node)?.let { n -> n.inProject && n.kind == SymbolKind.FUNCTION } == true }
    val matchingEntries = if (query.isNullOrBlank()) validEntryPoints else validEntryPoints?.filter { ep ->
        val q = query.trim()
        val node = graph.nodes.getOrNull(ep.node)
        val info = semantics?.invoke(ep.node)
        ep.title.contains(q, ignoreCase = true) ||
            ep.kind.contains(q, ignoreCase = true) ||
            node?.let {
                it.name.contains(q, ignoreCase = true) ||
                    "${it.container}.${it.name}".contains(q, ignoreCase = true) ||
                    it.filePath.orEmpty().contains(q, ignoreCase = true)
            } == true ||
            info?.summaryLabel.orEmpty().contains(q, ignoreCase = true) ||
            info?.summaryDescription.orEmpty().contains(q, ignoreCase = true)
    }

    val pagedFlows = matchingFlows?.drop(maxOf(0, offset))?.take(10)?.map { flow ->
        val spine = flow.nodes
            .filter { id -> graph.nodes.getOrNull(id)?.let { it.inProject && it.kind == SymbolKind.FUNCTION } == true }
            .take(15)
            .map { id ->
                val info = semantics?.invoke(id)
                chartNodeData(graph.nodes[id]) + mapOf(
                    "step_number" to steps[id],
                    "visible" to (id in steps),
                    "summary" to info?.summaryLabel,
                    "role" to info?.role,
                )
            }
        val effects = flow.nodes.flatMap { id ->
            val info = semantics?.invoke(id)
            if (info != null) info.ownEffects + info.reachedEffects else emptySet()
        }.toSet().sorted()
        mapOf(
            "id" to flow.id,
            "title" to flow.title.take(120),
            "description" to flow.description?.take(200),
            "entry" to chartNodeData(graph.nodes[flow.entry]),
            "functions_total" to flow.nodes.size,
            "spine_nodes" to spine,
            "effects_reached" to effects,
        )
    }

    val unavailable = listOfNotNull(
        "named flows: run Enrich".takeIf { flows == null },
        "entry points: run the Entry Points search or Enrich".takeIf { entryPoints == null },
    )

    val result = linkedMapOf<String, Any?>()
    result["revision"] = revision
    result["offset"] = offset
    result["next_offset"] = matchingFlows?.let { (offset + 10).takeIf { next -> next < it.size } }
    if (matchingFlows != null) {
        result["flows_total"] = matchingFlows.size
        result["total"] = matchingFlows.size
        result["flows"] = pagedFlows
    } else {
        result["flows"] = "unavailable: run Enrich to name flows"
    }

    if (offset == 0) {
        result["entry_points_total"] = matchingEntries?.size
        result["entry_points"] = matchingEntries?.take(20)?.map { ep ->
            mapOf(
                "node_id" to ep.node,
                "name" to graph.nodes[ep.node].name,
                "title" to ep.title,
                "kind" to ep.kind,
                "source" to ep.source,
                "summary" to semantics?.invoke(ep.node)?.summaryLabel,
                "visible" to (ep.node in steps),
            )
        } ?: "unavailable: run the Entry Points search or Enrich"
    }

    result["unavailable"] = unavailable
    result["evidence"] = "Named flows come from enrichment; entry points come from discovery and enrichment judgments; spine steps follow static in-project calls. Reachability does not prove a path executes at runtime."

    return Gson().toJson(result)
}

