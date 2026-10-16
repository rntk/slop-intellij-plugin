package dev.insight.callgraph

import com.google.gson.Gson
import dev.insight.chat.ToolInputException

/** Chat can name subsets of source-backed blocks, never invent chart structure. */
data class ChartCallGroup(val occurrenceIds: List<String>, val title: String, val description: String? = null) {
    companion object {
        const val MAX_TITLE = 70
        const val MAX_DESCRIPTION = 300
        const val MAX_GROUPS = FlowRegionEvidence.MAX_REGION_CALLS / 2
        const val MAX_KEY = 300
    }
}

internal fun groupingBlockData(block: FlowRegionEvidence): Map<String, Any?> = mapOf(
    "block_key" to block.key, "owner_node_id" to block.owner,
    "control_frames" to block.sites.first().frames,
    "occurrences" to block.sites.map { mapOf("occurrence_id" to it.occurrenceId, "callee_node_id" to it.callee,
        "offset" to it.offset, "text" to it.text?.take(160)) },
)

internal fun chartRegionData(region: FlowRegion): Map<String, Any?> = mapOf(
    "region_key" to region.plan.evidence.key, "owner" to region.plan.evidence.owner,
    "title" to region.plan.title, "description" to region.plan.description,
    "expanded" to region.expanded, "inferred" to region.plan.inferred,
    "members" to region.plan.evidence.sites.map { it.callee },
    "occurrence_ids" to region.plan.evidence.sites.map { it.occurrenceId },
    "members_total" to region.plan.evidence.sites.size,
    "effects" to region.effects.sorted(), "contains_entry_point" to region.containsEntryPoint,
)

/** Validates the entire replacement before recording undo or changing the board. Runs on the EDT. */
internal fun groupChartCalls(graph: CallGraph, canvas: FlowChartCanvas, currentRevision: Int, revision: Int,
    owner: Int, blockKey: String, groups: List<ChartCallGroup>, beforeChange: () -> Unit = {}): String {
    validateChatNode(graph, currentRevision, revision, owner)
    if (canvas.chart?.step(owner) == null)
        throw ToolInputException("Function $owner is not visible; add it to the chart first")
    val block = canvas.groupingBlocks(owner).firstOrNull { it.key == blockKey }
        ?: throw ToolInputException("Unknown or changed block; get_function_flow_outline for $owner and use a current groupable_blocks.block_key")
    if (groups.size > ChartCallGroup.MAX_GROUPS) throw ToolInputException("Too many groups for this block")
    val plans = groups.map { group ->
        if (group.title.isBlank() || group.title.length > ChartCallGroup.MAX_TITLE ||
            (group.description?.length ?: 0) > ChartCallGroup.MAX_DESCRIPTION)
            throw ToolInputException("Group title must be 1-${ChartCallGroup.MAX_TITLE} characters and description at most ${ChartCallGroup.MAX_DESCRIPTION}")
        if (group.occurrenceIds.size !in 2..FlowRegionEvidence.MAX_REGION_CALLS)
            throw ToolInputException("Each group needs 2-${FlowRegionEvidence.MAX_REGION_CALLS} consecutive occurrence IDs")
        val sites = group.occurrenceIds.map { occurrence -> block.sites.firstOrNull { it.occurrenceId == occurrence }
            ?: throw ToolInputException("Unknown occurrence $occurrence in block $blockKey; use get_function_flow_outline") }
        FlowRegionPlan(FlowRegionEvidence(owner, sites), group.title, group.description, inferred = true)
    }
    val valid = validateRegionPlans(block, plans)
    if (valid.size != plans.size)
        throw ToolInputException("Groups must contain consecutive occurrences in source order, without duplicates or overlaps; no chart changes applied")
    val wasColumn = canvas.board.starts.any { it.node == owner }
    beforeChange()
    canvas.groupCalls(block, valid)
    val regions = canvas.chart?.regions.orEmpty().filter { it.plan.evidence.key in valid.map { p -> p.evidence.key } }
    regions.firstOrNull()?.let { canvas.selectRegion(it.plan.evidence.key) }
    return Gson().toJson(mapOf("ok" to true, "revision" to currentRevision, "compact" to true,
        "owner_column_added" to (!wasColumn && canvas.board.starts.any { it.node == owner }),
        "block_key" to blockKey, "regions" to regions.map(::chartRegionData),
        "ungrouped_occurrence_ids" to block.sites.filter { site -> valid.none { site in it.evidence.sites } }.map { it.occurrenceId },
        "note" to "Groups start collapsed. Click a group or use set_chart_region_expansion to inspect its calls. Empty groups leaves this block ungrouped. Undo chart changes restores the previous grouping.",
        "evidence" to "Membership and order are source-backed; titles and descriptions are assistant interpretations. Decisions, loops and exits remain outside groups."))
}

internal fun setChartRegionExpansion(canvas: FlowChartCanvas, currentRevision: Int, revision: Int,
    keys: List<String>, expanded: Boolean, beforeChange: () -> Unit = {}): String {
    if (revision != currentRevision) throw ToolInputException("Stale graph revision; get_chart_state and resolve region keys again")
    if (keys.isEmpty() || keys.size > 20) throw ToolInputException("Give 1-20 region keys")
    val regions = canvas.chart?.regions.orEmpty().associateBy { it.plan.evidence.key }
    keys.forEach { if (it !in regions) throw ToolInputException("Region $it is not visible; refresh get_chart_state before changing it") }
    if (keys.any { regions.getValue(it).expanded != expanded }) {
        beforeChange()
        canvas.setRegionsExpanded(keys.toSet(), expanded)
    }
    return Gson().toJson(mapOf("ok" to true, "revision" to currentRevision,
        "regions" to canvas.chart?.regions.orEmpty().filter { it.plan.evidence.key in keys }.map(::chartRegionData)))
}
