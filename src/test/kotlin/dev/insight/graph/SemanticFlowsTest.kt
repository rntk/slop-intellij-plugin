package dev.insight.graph

import org.junit.Assert.*
import org.junit.Test

class SemanticFlowsTest {
    private fun source(sites: List<SourceSite>, plans: Map<String, List<FlowRegionPlan>> = emptyMap(),
        card: (Int) -> FlowCard = { FlowCard("f$it") }): FlowSource {
        val edges = sites.filterIsInstance<CallSite>().map { CallGraphEdge(0, it.callee) }.distinct()
        return FlowSource(edges.groupBy { it.caller }, emptyMap(), mapOf(0 to sites), plans, card = card)
    }
    private val calls = listOf(CallSite(1, 10), CallSite(2, 20), CallSite(1, 30))
    private fun board(open: Set<String> = emptySet()) = FlowBoard(listOf(FlowStart(0)), setOf(0, 1, 2), compact = true, openRegions = open)

    @Test fun `collapsed regions do not build or claim any child functions`() {
        val read = mutableListOf<Int>()
        val source = source(calls, card = { read += it; FlowCard("f$it") })
        val chart = buildFlowChart(board(), source)
        assertEquals(listOf(0), chart.steps.map { it.node })
        assertEquals(listOf(0), read)
        assertEquals(calls, chart.regions.single().plan.evidence.sites)
        assertTrue(chart.regions.single().items.isEmpty())
        // A function hidden in a collapsed region is still a proper card in an independent column.
        val two = buildFlowChart(board().copy(starts = listOf(FlowStart(0), FlowStart(1))), source)
        assertEquals(listOf(0, 1), two.steps.map { it.node })
    }

    @Test fun `expansion preserves repeated call occurrences and returns to the same compact view`() {
        val source = source(calls)
        val closed = buildFlowChart(board(), source)
        val key = closed.regions.single().plan.evidence.key
        val expanded = buildFlowChart(board(setOf(key)), source)
        val items = expanded.regions.single().items
        assertEquals(listOf(1, 2), items.filterIsInstance<FlowStep>().map { it.node })
        assertEquals(1, items.filterIsInstance<FlowRef>().single().node)
        assertEquals(2, items.filterIsInstance<FlowRef>().single().number)
        assertEquals(closed, buildFlowChart(board(), source))
    }

    @Test fun `decisions loops and exits are preserved outside region boundaries`() {
        val branch = ControlFrame(ControlKind.BRANCH, 5, 5, "ready", subject = "ready")
        val loop = ControlFrame(ControlKind.LOOP, 50, 50, "for x")
        val sites = listOf(CallSite(1, 10, listOf(branch)), CallSite(2, 20, listOf(branch)),
            ExitSite(21, listOf(branch), "return", "return"),
            CallSite(3, 55, listOf(loop)), CallSite(4, 60, listOf(loop)), CallSite(5, 70), CallSite(6, 80))
        val chart = buildFlowChart(board(), source(sites))
        val root = chart.lanes.single().root
        val decision = root.items.first() as FlowDecision
        assertTrue(ends(decision.branches.first().items))
        assertFalse(ends(decision.branches.last().items))
        assertTrue(root.items[1] is FlowLoop)
        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5, 6)), chart.regions.map { it.plan.evidence.sites.map(CallSite::callee) })
        assertEquals(chart.regions.map { it.plan.evidence }, flowRegionCandidates(0, sites))
    }

    @Test fun `partial model grouping preserves all ungrouped calls in source order`() {
        val candidate = FlowRegionEvidence(0, calls)
        val plan = FlowRegionPlan(FlowRegionEvidence(0, calls.take(2)), "Store data", inferred = true)
        val chart = buildFlowChart(board(), source(calls, mapOf(candidate.key to listOf(plan))))
        assertEquals("Store data", chart.regions.single().plan.title)
        // The third occurrence remains visible and is not claimed by the hidden first occurrence.
        assertEquals(1, chart.lanes.single().root.items.filterIsInstance<FlowStep>().single().node)
        val ungrouped = buildFlowChart(board(), source(calls, mapOf(candidate.key to emptyList())))
        assertTrue(ungrouped.regions.isEmpty())
        assertEquals(3, ungrouped.lanes.single().root.items.size)
    }

    @Test fun `unsafe proposals cannot merge disconnected reordered foreign or overlapping calls`() {
        val candidate = FlowRegionEvidence(0, calls)
        fun plan(owner: Int, sites: List<CallSite>) = FlowRegionPlan(FlowRegionEvidence(owner, sites), "Operation")
        val invalid = listOf(plan(1, calls), plan(0, calls.reversed()), plan(0, listOf(calls[0], calls[2])))
        assertTrue(validateRegionPlans(candidate, invalid).isEmpty())
        val overlapping = listOf(plan(0, calls.take(2)), plan(0, calls.drop(1)))
        assertEquals(listOf(overlapping.first()), validateRegionPlans(candidate, overlapping))
    }

    @Test fun `candidate work is bounded and unknown call order never creates a region`() {
        val sites = (1..15).map { CallSite(it, it * 10) }
        assertEquals(listOf(6, 6, 3), flowRegionCandidates(0, sites).map { it.sites.size })
        val noSites = FlowSource(mapOf(0 to sites.map { CallGraphEdge(0, it.callee) }), emptyMap()) { FlowCard("f$it") }
        assertTrue(buildFlowChart(board(), noSites).regions.isEmpty())
        val moved = board(setOf("old")).map { it + 10 }
        assertTrue(moved.compact)
        assertTrue(moved.openRegions.isEmpty())
    }

    @Test fun `compact boards fold helpers, regroup the calls around them and count them on the caller`() {
        val sites = listOf(CallSite(1, 10), CallSite(3, 15), CallSite(2, 20))
        val source = source(sites).copy(folded = setOf(3), compactCard = { FlowCard("Summary $it") })
        val chart = buildFlowChart(board(), source)
        val region = chart.regions.single()
        assertEquals(listOf(CallSite(1, 10), CallSite(2, 20)), region.plan.evidence.sites)
        assertEquals(flowRegionCandidates(0, sites, setOf(3)), listOf(region.plan.evidence))
        assertEquals("Summary 0", chart.root.card.title)
        assertEquals("+1 helper call folded", chart.root.card.details)
        // Fallback labels keep code names, not summaries.
        assertEquals("Calls in f0", region.plan.title.removePrefix(""))
        // Without compact mode nothing is folded and code cards return.
        val full = buildFlowChart(board().copy(compact = false), source)
        assertEquals(listOf(0, 1, 3, 2), full.steps.map { it.node })
        assertEquals("f0", full.root.card.title)
    }

    @Test fun `budgeted opening follows the main path, skips single steps and stops at the budget`() {
        val calls = mapOf(0 to listOf(1, 2), 1 to listOf(3, 4), 2 to listOf(5, 6, 7), 3 to listOf(8), 4 to listOf(1))
        val open = budgetedOpenCalls(0, { calls[it].orEmpty() }, { it != 2 }, budget = 6)
        // 0 opens (3 shown), 1 opens (5), 2 is a single step, 3 opens (6); 4 would exceed the budget.
        assertEquals(setOf(0, 1, 3), open)
        assertEquals(setOf(0), budgetedOpenCalls(0, { calls[it].orEmpty() }, { true }, budget = 1))
    }

    private val FlowChart.root get() = lanes.single().root
}
