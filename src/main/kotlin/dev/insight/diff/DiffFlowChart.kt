package dev.insight.diff

import com.intellij.ui.JBColor
import dev.insight.callgraph.CHANGED_COLOR
import dev.insight.callgraph.LineChange
import dev.insight.enrichment.FlowChartLens
import dev.insight.enrichment.SymbolFacts
import dev.insight.graph.CallGraph
import dev.insight.graph.CallGraphEdge
import dev.insight.graph.FlowBoard
import dev.insight.graph.FlowCard
import dev.insight.graph.FlowRegionPlan
import dev.insight.graph.FlowSource
import dev.insight.graph.FlowStart
import dev.insight.graph.SourceSite
import dev.insight.graph.SymbolKind
import dev.insight.graph.buildFlowChart
import java.awt.Color

/** How a declaration changed: lines added and removed within it, and whether it is [new] to the file. */
internal data class DeclarationChange(val added: Int, val removed: Int, val new: Boolean) {
    val label: String get() = if (new) "Δ new · +$added" else "Δ +$added −$removed"
}

/**
 * Changes of declarations from their new-side [ranges] (1-based, inclusive) in [after] and the line [changes] from
 * [before] (null for an added file). A removal counts for a declaration when it falls between two of its lines. A
 * declaration is new when all of its lines changed and no line of [before] starts like its header: the first line that
 * is not an annotation, up to its parameter list. Line comparison may pair an inserted declaration with an edit next
 * to it, so insertions alone cannot tell. A renamed declaration reads as new.
 */
internal fun declarationChanges(ranges: Map<Int, IntRange>, changes: List<LineChange>, after: String, before: String?): Map<Int, DeclarationChange> {
    val afterLines = after.lines()
    val beforeLines = before?.lines()?.map { it.trim() }
    return ranges.mapNotNull { (id, lines) ->
        // LineChange is 0-based and end-exclusive.
        val first = lines.first - 1
        val end = lines.last
        if (beforeLines == null) return@mapNotNull id to DeclarationChange(end - first, 0, true)
        var added = 0
        var removed = 0
        var touched = false
        for (change in changes) {
            val overlap = minOf(change.end2, end) - maxOf(change.start2, first)
            val deletedInside = change.end2 == change.start2 && change.start2 > first && change.start2 < end
            if (overlap > 0 || deletedInside) {
                added += overlap.coerceAtLeast(0)
                removed += change.end1 - change.start1
                touched = true
            }
        }
        val header = (first until end).asSequence().mapNotNull { afterLines.getOrNull(it)?.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("@") }
        val new = added == end - first && header != null && header.substringBefore('(').let { start -> beforeLines.none { it.startsWith(start) } }
        if (touched) id to DeclarationChange(added, removed, new) else null
    }.toMap()
}

/** Columns of a diff's flow chart, and the functions whose calls start open. */
internal data class DiffFlowPlan(val starts: List<Int>, val open: Set<Int>)

/**
 * Functions on a call path from one of the [changed] functions (in reading order) to another open their calls, so the
 * chart follows the change across functions and files while other callees stay closed as context. A column starts at
 * each change that no function on such a path calls; a cycle of changes starts at its first change.
 */
internal fun diffFlowPlan(calls: Map<Int, List<CallGraphEdge>>, changed: List<Int>): DiffFlowPlan {
    val forward = calls.mapValues { (_, edges) -> edges.map { it.callee } }
    val backward = HashMap<Int, MutableList<Int>>()
    calls.values.flatten().forEach { backward.getOrPut(it.callee) { mutableListOf() } += it.caller }
    fun reach(from: Collection<Int>, next: Map<Int, List<Int>>, within: Set<Int>? = null): Set<Int> {
        val seen = from.toHashSet()
        val queue = ArrayDeque(from)
        while (queue.isNotEmpty()) next[queue.removeFirst()].orEmpty().forEach {
            if ((within == null || it in within) && seen.add(it)) queue.add(it)
        }
        return seen
    }
    val onPath = reach(changed, forward).intersect(reach(changed, backward))
    val starts = mutableListOf<Int>()
    val covered = hashSetOf<Int>()
    fun place(id: Int) {
        starts += id
        covered += reach(listOf(id), forward, onPath)
    }
    changed.filter { id -> backward[id].orEmpty().none { it != id && it in onPath } }.forEach { if (it !in covered) place(it) }
    changed.forEach { if (it !in covered) place(it) }
    return DiffFlowPlan(starts, onPath)
}

/** [board] with each compact subflow holding one of [keep] expanded, so grouping never hides changed code. */
internal fun expandRegionsHolding(board: FlowBoard, source: FlowSource, keep: Set<Int>): FlowBoard {
    var next = board
    repeat(MAX_REGION_ROUNDS) {
        if (!next.compact || next.starts.isEmpty()) return next
        val keys = buildFlowChart(next, source).regions
            .filter { region -> !region.expanded && region.plan.evidence.sites.any { it.callee in keep } }
            .mapTo(hashSetOf()) { it.plan.evidence.key }
        if (keys.isEmpty()) return next
        next = next.copy(openRegions = next.openRegions + keys)
    }
    return next
}

private const val MAX_REGION_ROUNDS = 16

/** Dot colour of functions the diff does not change; changed ones use [CHANGED_COLOR]. */
private val UNCHANGED_COLOR: Color = JBColor(Color(0xB4B9C0), Color(0x6A6F76))

/** [card] marked with [change]: its line counts first in the meta line and the changed colour; unchanged cards turn grey. */
internal fun markChange(card: FlowCard, change: DeclarationChange?): FlowCard =
    if (change == null) card.copy(color = UNCHANGED_COLOR, tooltip = listOfNotNull(card.tooltip, "Not changed in this diff").joinToString("\n"))
    else card.copy(
        meta = listOfNotNull(change.label, card.meta).joinToString(" · "),
        color = CHANGED_COLOR,
        tooltip = listOfNotNull("Changed in this diff: ${change.label}", card.tooltip).joinToString("\n"),
    )

/**
 * A change read as a flow chart, PSI-free: the in-project calls of [graph] (an exploration of the changed files) with
 * [lens] cards marked by [changes]. [changed] functions in reading order head the columns of [plan]; the calls between
 * changes open, other callees stay closed, and compact boards never fold or group away changed code.
 */
internal class DiffFlowChart(
    val graph: CallGraph,
    val lens: FlowChartLens,
    val changes: Map<Int, DeclarationChange>,
    val changed: List<Int>,
    val callSites: Map<Int, List<SourceSite>>,
    val facts: Map<Int, SymbolFacts>,
) {
    /** Resolved project functions and classes; external, unresolved and local symbols stay off the chart. */
    private fun charted(id: Int) = graph.nodes.getOrNull(id)?.let { !it.unresolved && it.inProject && it.kind in CHARTED } == true

    private val calls = lens.calls.filterKeys(::charted).mapValues { (_, edges) -> edges.filter { charted(it.callee) } }.filterValues { it.isNotEmpty() }
    private val callers = lens.callers.filterKeys(::charted).mapValues { (_, ids) -> ids.filter(::charted) }.filterValues { it.isNotEmpty() }

    val plan = diffFlowPlan(calls, changed)

    /** The calls and marked cards, with subflow interpretations [regionPlans] where summarized. */
    fun source(regionPlans: Map<String, List<FlowRegionPlan>> = emptyMap()): FlowSource {
        val base = lens.source(callSites, facts, regionPlans)
        return base.copy(
            calls = calls,
            callers = callers,
            folded = base.folded - plan.open,
            compactCard = base.compactCard?.let { card -> { id: Int -> markChange(card(id), changes[id]) } },
            card = { id -> markChange(base.card(id), changes[id]) },
        )
    }

    /** The opening board: a column per start, calls open along the change, subflows holding it expanded. */
    fun board(source: FlowSource, compact: Boolean = true): FlowBoard = expandRegionsHolding(FlowBoard(
        starts = plan.starts.map { FlowStart(it, description = listOfNotNull(changes[it]?.label, graph.nodes[it].location.ifBlank { null }).joinToString(" · ")) },
        openCalls = plan.open + plan.starts,
        compact = compact,
    ), source, plan.open)

    private companion object {
        val CHARTED = setOf(SymbolKind.FUNCTION, SymbolKind.CLASS)
    }
}
