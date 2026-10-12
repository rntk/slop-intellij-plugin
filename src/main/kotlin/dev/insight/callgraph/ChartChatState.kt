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
            "highlighted" to (step.node in canvas.chatHighlights),
            "calls_count" to step.calls, "callers_count" to step.callers,
        ) },
        "calls" to calls.take(80).map { listOf(it.caller, it.callee) }, "calls_truncated" to (calls.size > 80),
        "regions_total" to regions.size,
        "regions" to regions.drop(offset).take(8).map { mapOf(
            "owner" to it.plan.evidence.owner, "title" to it.plan.title.take(160),
            "expanded" to it.expanded, "inferred" to it.plan.inferred,
            "members" to it.plan.evidence.sites.take(8).map { site -> site.callee },
            "members_total" to it.plan.evidence.sites.size,
        ) },
        "evidence" to "Static in-project calls only. Compact regions may hide calls; no runtime order is guaranteed. Read source for branch conditions and exits.",
    ))
}

internal fun chartNodeData(node: CallGraphNode): Map<String, Any?> = mapOf(
    "node_id" to node.id, "name" to node.name.take(120), "container" to node.container.take(120),
    "path" to node.filePath?.take(300), "location" to node.location.take(120),
)

/** Validate every target before applying any change; callers run on the EDT. */
internal fun applyChartChatAction(
    graph: CallGraph, canvas: FlowChartCanvas, currentRevision: Int, action: String, revision: Int, nodes: List<Int>,
): String {
    if (revision != currentRevision) throw ToolInputException("Stale graph revision; get_chart_state and resolve node IDs again")
    nodes.forEach { id ->
        val node = graph.nodes.getOrNull(id)
        if (node == null || !node.inProject || node.kind != SymbolKind.FUNCTION)
            throw ToolInputException("Unknown in-project function ID: $id")
        if (action != "add_chart_nodes" && canvas.chart?.step(id) == null)
            throw ToolInputException("Function $id is not visible; add it to the chart first")
    }
    var placements: List<Map<String, Any?>>? = null
    when (action) {
        "add_chart_nodes" -> placements = addConnected(graph, canvas, nodes)
        "highlight_chart_nodes" -> {
            canvas.highlightChat(nodes.toSet())
            nodes.firstOrNull()?.let(canvas::selectNode)
        }
        "expand_chart_calls" -> canvas.openCalls(nodes.toSet())
        "expand_chart_callers" -> nodes.filter { it !in canvas.board.openCallers }.forEach(canvas::toggleCallers)
        else -> throw ToolInputException("Unknown chart action: $action")
    }
    return Gson().toJson(mapOf("ok" to true, "revision" to currentRevision, "action" to action, "node_ids" to nodes,
        "visible_steps" to canvas.chart?.steps?.size) + listOfNotNull(placements?.let { "placements" to it }))
}

/**
 * Adds [nodes] as continuations of what is visible where a static call connects them, so the chart grows from its
 * existing steps; only functions with no visible caller or callee become columns. Functions of the batch can connect
 * to each other: the next column is one no other pending function calls, and the rest are then tried against it.
 */
private fun addConnected(graph: CallGraph, canvas: FlowChartCanvas, nodes: List<Int>): List<Map<String, Any?>> {
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
