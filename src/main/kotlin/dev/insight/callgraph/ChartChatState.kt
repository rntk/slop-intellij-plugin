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
        "next_offset" to (offset + 8).takeIf { it < maxOf(steps.size, regions.size, board.starts.size, canvas.chatLinks.size) },
        "steps" to page.map { step -> chartNodeData(graph.nodes[step.node]) + mapOf(
            "step_number" to step.number, "label" to step.card.title.take(160),
            "description" to step.card.description?.take(160), "effects_and_details" to step.card.details?.take(120),
            "calls_open" to (step.node in board.openCalls), "callers_open" to (step.node in board.openCallers),
            "highlighted" to (step.node in canvas.chatHighlights),
            "assistant_note" to canvas.chatNotes[step.node],
            "calls_count" to step.calls, "callers_count" to step.callers,
        ) },
        "calls" to calls.take(80).map { listOf(it.caller, it.callee) }, "calls_truncated" to (calls.size > 80),
        "regions_total" to regions.size,
        "annotation_links_total" to canvas.chatLinks.size,
        "annotation_links" to canvas.chatLinks.entries.drop(offset).take(8).map { (ends, text) ->
            mapOf("from" to ends.first, "to" to ends.second, "text" to text,
                "visible" to (canvas.geometry?.cards?.let { ends.first in it && ends.second in it } == true),
                "evidence" to "assistant explanation, not a static call")
        },
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
    "qualified_name" to "${node.container}.${node.name}",
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
    val before = canvas.chart?.steps.orEmpty().map { it.node }.toSet()
    var placements: List<Map<String, Any?>>? = null
    when (action) {
        "clear_chart_annotations" -> canvas.clearChatAnnotations()
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
        "visible_steps" to canvas.chart?.steps?.size,
        "added_node_ids" to (canvas.chart?.steps.orEmpty().map { it.node }.toSet() - before)) + listOfNotNull(placements?.let { "placements" to it }))
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

private fun validateChatNode(graph: CallGraph, currentRevision: Int, revision: Int, node: Int) {
    if (revision != currentRevision) throw ToolInputException("Stale graph revision; refresh and resolve IDs again")
    if (graph.nodes.getOrNull(node)?.let { it.inProject && it.kind == SymbolKind.FUNCTION } != true)
        throw ToolInputException("Unknown in-project function ID: $node")
}

internal fun applyChartAnnotation(graph: CallGraph, canvas: FlowChartCanvas, currentRevision: Int,
    revision: Int, node: Int, target: Int?, text: String): String {
    listOfNotNull(node, target).forEach {
        validateChatNode(graph, currentRevision, revision, it)
        if (text.isNotEmpty() && canvas.chart?.step(it) == null) throw ToolInputException("Function $it is not visible; add it first")
    }
    if (text.length > 400) throw ToolInputException("Use at most 400 characters")
    if (node == target) throw ToolInputException("An explanatory arrow needs two different functions")
    canvas.annotateChat(node, target, text)
    return Gson().toJson(mapOf("ok" to true, "revision" to revision, "node_id" to node, "target_id" to target,
        "text" to text, "evidence" to "assistant explanation, not a static call"))
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
