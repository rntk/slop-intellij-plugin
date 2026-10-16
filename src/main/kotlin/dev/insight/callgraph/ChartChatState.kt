package dev.insight.callgraph

import com.google.gson.Gson
import dev.insight.chat.ToolInputException

/** Bounded, PSI-free context. Pagination always includes graph revision and explicit totals. */
internal fun chartChatState(
    revision: Int, graph: CallGraph, canvas: FlowChartCanvas, offset: Int,
): String {
    val steps = canvas.chart?.steps.orEmpty()
    val regions = canvas.chart?.regions.orEmpty()
    val board = canvas.board
    val page = steps.drop(offset).take(8)
    val ids = page.map { it.node }.toSet()
    val calls = graph.edges.filter { it.kind == RelationKind.CALL && it.caller in ids }
    return Gson().toJson(mapOf(
        "revision" to revision, "compact" to board.compact,
        "selected_node" to canvas.selectedNode, "selected_region" to canvas.selectedRegion,
        "columns_total" to board.starts.size,
        "columns" to board.starts.drop(offset).take(8).map { mapOf("node_id" to it.node, "title" to it.title?.take(160)) },
        "steps_total" to steps.size, "offset" to offset,
        "next_offset" to (offset + 8).takeIf { it < maxOf(steps.size, regions.size, board.starts.size) },
        "steps" to page.map { step -> chartNodeData(graph.nodes[step.node]) + mapOf(
            "step_number" to step.number, "label" to step.card.title.take(160),
            "description" to step.card.description?.take(160), "effects_and_details" to step.card.details?.take(120),
            "calls_open" to (step.node in board.openCalls), "callers_open" to (step.node in board.openCallers),
            "scene_role" to canvas.scene.roles[step.node]?.role?.name?.lowercase(),
            "badge" to canvas.scene.roles[step.node]?.badge,
            "assistant_note" to canvas.scene.noteOf[step.node],
            "entry_point" to step.card.entryPoint,
            "calls_count" to step.calls, "callers_count" to step.callers,
        ) },
        "calls" to calls.take(80).map { listOf(it.caller, it.callee) }, "calls_truncated" to (calls.size > 80),
        "scene" to sceneData(canvas), "story" to storyData(canvas),
        "regions_total" to regions.size,
        "regions" to regions.drop(offset).take(8).map(::chartRegionData),
        "evidence" to "Static in-project calls only. Compact regions may hide calls; no runtime order is guaranteed. Read source for branch conditions and exits.",
    ))
}

internal fun chartNodeData(node: CallGraphNode): Map<String, Any?> = mapOf(
    "node_id" to node.id, "name" to node.name.take(120), "container" to node.container.take(120),
    "path" to node.filePath?.take(300), "location" to node.location.take(120),
    "qualified_name" to "${node.container}.${node.name}",
)

/** Validate every target before applying any change; callers run on the EDT. */
internal fun applyChartChatAction(
    graph: CallGraph, canvas: FlowChartCanvas, currentRevision: Int, action: String, revision: Int, nodes: List<Int>,
    beforeChange: () -> Unit = {},
): String {
    if (revision != currentRevision) throw ToolInputException("Stale graph revision; get_chart_state and resolve node IDs again")
    if (action !in setOf("add_chart_nodes", "expand_chart_calls", "expand_chart_callers", "collapse_chart_calls", "collapse_chart_callers"))
        throw ToolInputException("Unknown chart action: $action")
    nodes.forEach { id ->
        val node = graph.nodes.getOrNull(id)
        if (node == null || !node.inProject || node.kind != SymbolKind.FUNCTION)
            throw ToolInputException("Unknown in-project function ID: $id")
        if (action != "add_chart_nodes" && canvas.chart?.step(id) == null)
            throw ToolInputException("Function $id is not visible; add it to the chart first")
    }
    beforeChange()
    val before = canvas.chart?.steps.orEmpty().map { it.node }.toSet()
    var placements: List<Map<String, Any?>>? = null
    when (action) {
        "add_chart_nodes" -> placements = addConnected(graph, canvas, nodes)
        "expand_chart_calls" -> canvas.openCalls(nodes.toSet())
        "expand_chart_callers" -> nodes.filter { it !in canvas.board.openCallers }.forEach(canvas::toggleCallers)
        "collapse_chart_calls" -> nodes.filter { it in canvas.board.openCalls }.forEach(canvas::toggleCalls)
        "collapse_chart_callers" -> nodes.filter { it in canvas.board.openCallers }.forEach(canvas::toggleCallers)
    }
    return Gson().toJson(mapOf("ok" to true, "revision" to currentRevision, "action" to action, "node_ids" to nodes,
        "visible_steps" to canvas.chart?.steps?.size,
        "added_node_ids" to (canvas.chart?.steps.orEmpty().map { it.node }.toSet() - before),
        "removed_node_ids" to (before - canvas.chart?.steps.orEmpty().map { it.node }.toSet())) +
        listOfNotNull(placements?.let { "placements" to it }))
}

internal fun chartNodeDetails(graph: CallGraph, currentRevision: Int, revision: Int, node: Int, offset: Int): String {
    validateChatNode(graph, currentRevision, revision, node)
    val calls = graph.edges.filter { edge ->
        edge.kind == RelationKind.CALL && (edge.caller == node || edge.callee == node) &&
            graph.nodes.getOrNull(if (edge.caller == node) edge.callee else edge.caller)
                ?.let { it.inProject && it.kind == SymbolKind.FUNCTION } == true
    }
    return Gson().toJson(mapOf("revision" to revision, "node" to chartNodeData(graph.nodes[node]),
        "relationships_total" to calls.size, "offset" to offset,
        "next_offset" to (offset + 20).takeIf { it < calls.size },
        "relationships" to calls.drop(offset).take(20).map {
            mapOf("direction" to if (it.caller == node) "callee" else "caller",
                "node" to chartNodeData(graph.nodes[if (it.caller == node) it.callee else it.caller]))
        }))
}

internal fun validateChatNode(graph: CallGraph, currentRevision: Int, revision: Int, node: Int) {
    if (revision != currentRevision) throw ToolInputException("Stale graph revision; refresh and resolve IDs again")
    if (graph.nodes.getOrNull(node)?.let { it.inProject && it.kind == SymbolKind.FUNCTION } != true)
        throw ToolInputException("Unknown in-project function ID: $node")
}

/**
 * Adds [nodes] as continuations of what is visible where a static call connects them, so the chart grows from its
 * existing steps; only functions with no visible caller or callee become columns. Functions of the batch can connect
 * to each other: the next column is one no other pending function calls, and the rest are then tried against it.
 */
internal fun addConnected(graph: CallGraph, canvas: FlowChartCanvas, nodes: List<Int>): List<Map<String, Any?>> {
    val placements = linkedMapOf<Int, Map<String, Any?>>()
    nodes.filter { canvas.chart?.step(it) != null }.forEach { placements[it] = mapOf("node_id" to it, "placement" to "already_visible") }
    var pending = nodes.filter { it !in placements }
    while (pending.isNotEmpty()) {
        do {
            var progress = false
            for (id in pending) {
                val placement = if (canvas.chart?.step(id) != null) mapOf("node_id" to id, "placement" to "revealed_by_batch")
                else canvas.attach(id)?.let { mapOf("node_id" to id, "via_node_id" to it.via,
                    "placement" to if (it.kind == FlowAttachment.Kind.CALLS) "in_calls_of" else "in_callers_of") }
                if (placement != null) { placements[id] = placement; progress = true }
            }
            pending = pending.filter { it !in placements }
        } while (progress && pending.isNotEmpty())
        val calledByPending = graph.edges.filter { it.caller in pending }.mapTo(hashSetOf()) { it.callee }
        (pending.firstOrNull { it !in calledByPending } ?: pending.firstOrNull())?.let { id ->
            canvas.add(FlowStart(id))
            placements[id] = mapOf("node_id" to id, "placement" to "new_column")
            pending = pending - id
        }
    }
    nodes.firstOrNull()?.let(canvas::selectNode)
    return nodes.mapNotNull(placements::get)
}
