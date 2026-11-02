package dev.insight.diff

import dev.insight.callgraph.CHANGED_COLOR
import dev.insight.callgraph.LineChange
import dev.insight.enrichment.FlowChartLens
import dev.insight.graph.CallGraph
import dev.insight.graph.CallGraphEdge
import dev.insight.graph.CallGraphNode
import dev.insight.graph.CallSite
import dev.insight.graph.FlowItem
import dev.insight.graph.FlowRef
import dev.insight.graph.FlowRegion
import dev.insight.graph.FlowStep
import dev.insight.graph.SourceSite
import dev.insight.graph.SymbolKind
import dev.insight.graph.buildFlowChart
import dev.insight.graph.children
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffFlowChartTest {
    private fun node(id: Int, name: String, inProject: Boolean = true, unresolved: Boolean = false) =
        CallGraphNode(id, name, "Owner$id", "File$id.kt:${id + 1}", 0, inProject, SymbolKind.FUNCTION, "/p/File$id.kt", unresolved = unresolved)

    private fun calls(vararg edges: Pair<Int, Int>) = edges.map { (from, to) -> CallGraphEdge(from, to) }.groupBy { it.caller }

    private fun render(item: FlowItem): String = when (item) {
        is FlowStep -> "n${item.node}" + if (item.items.isEmpty()) "" else item.items.joinToString(" ", "(", ")", transform = ::render)
        is FlowRef -> "->n${item.node}"
        is FlowRegion -> if (item.expanded) item.items.joinToString(" ", "{", "}", transform = ::render) else "[${item.plan.title}]"
        else -> children(item).joinToString(" ", transform = ::render)
    }

    @Test fun `declarations count the lines changed inside them and are new when every line and the header are new`() {
        val after = (1..16).joinToString("\n") { "line $it" }
        val before = (1..14).joinToString("\n") { "line $it" }
        val ranges = mapOf(1 to 1..5, 2 to 6..10, 3 to 11..12, 4 to 13..14, 5 to 15..16)
        val changes = listOf(
            LineChange(1, 2, 1, 3), // line 2 rewritten as lines 2-3
            LineChange(7, 9, 7, 7), // two lines removed between lines 7 and 8
            LineChange(10, 10, 10, 12), // lines 11-12 inserted, but their header is an existing line
            LineChange(12, 14, 14, 16), // lines 15-16 replace two lines; their header is not a line of before
        )
        assertEquals(mapOf(
            1 to DeclarationChange(2, 1, false),
            2 to DeclarationChange(0, 2, false),
            3 to DeclarationChange(2, 0, false),
            5 to DeclarationChange(2, 2, true),
        ), declarationChanges(ranges, changes, after, before))
        assertEquals(DeclarationChange(5, 0, true), declarationChanges(ranges, emptyList(), after, null)[1])
        assertEquals("Δ +2 −1", DeclarationChange(2, 1, false).label)
        assertEquals("Δ new · +2", DeclarationChange(2, 0, true).label)
    }

    @Test fun `columns start at changes no path calls and only paths between changes open`() {
        // 0 → 1 → 2 joins two changes through an unchanged function; 3 is unchanged context; 6 changes on its own.
        val plan = diffFlowPlan(calls(0 to 1, 1 to 2, 0 to 3, 6 to 3, 2 to 4), listOf(0, 2, 6))
        assertEquals(listOf(0, 6), plan.starts)
        assertEquals(setOf(0, 1, 2, 6), plan.open)
        // A cycle of changes starts at its first change.
        assertEquals(listOf(5), diffFlowPlan(calls(5 to 7, 7 to 5), listOf(5, 7)).starts)
    }

    @Test fun `chart follows the change across files, marks it, and leaves external and unresolved calls out`() {
        val graph = CallGraph(listOf(node(0, "run"), node(1, "helper"), node(2, "save"), node(3, "log"),
            node(4, "external", inProject = false), node(5, "unknown", unresolved = true), node(6, "other")),
            setOf(CallGraphEdge(0, 1), CallGraphEdge(1, 2), CallGraphEdge(0, 3), CallGraphEdge(2, 4), CallGraphEdge(0, 5), CallGraphEdge(6, 3)),
            truncated = false)
        val changes = mapOf(0 to DeclarationChange(3, 1, false), 2 to DeclarationChange(4, 0, true), 6 to DeclarationChange(1, 1, false))
        val chart = DiffFlowChart(graph, FlowChartLens(null, graph), changes, listOf(0, 2, 6), emptyMap(), emptyMap())
        val source = chart.source()
        val built = buildFlowChart(chart.board(source, compact = false), source)
        assertEquals(listOf("n0(n1(n2) n3)", "n6(->n3)"), built.lanes.map { render(it.root) })
        assertEquals("Δ +3 −1 · File0.kt:1", built.lanes.first().description)
        val save = built.step(2)!!.card
        assertTrue(save.meta!!.startsWith("Δ new · +4"))
        assertEquals(CHANGED_COLOR, save.color)
        assertNotEquals(CHANGED_COLOR, built.step(3)!!.card.color)
        assertTrue(built.step(3)!!.card.tooltip!!.contains("Not changed"))
    }

    @Test fun `compact boards expand subflows that hold changed code and keep context grouped`() {
        val graph = CallGraph((0..5).map { node(it, "f$it") },
            setOf(CallGraphEdge(0, 1), CallGraphEdge(0, 2), CallGraphEdge(3, 4), CallGraphEdge(3, 5)), truncated = false)
        val sites: Map<Int, List<SourceSite>> = mapOf(
            0 to listOf(CallSite(1, 10), CallSite(2, 20)),
            3 to listOf(CallSite(4, 10), CallSite(5, 20)),
        )
        val chart = DiffFlowChart(graph, FlowChartLens(null, graph), mapOf(0 to DeclarationChange(1, 0, false),
            2 to DeclarationChange(1, 0, false), 3 to DeclarationChange(1, 0, false)), listOf(0, 2, 3), sites, emptyMap())
        val source = chart.source()
        val board = chart.board(source)
        assertTrue(board.compact)
        val built = buildFlowChart(board, source)
        // The run 1, 2 holds the change 2 and opens; the run 4, 5 of the change 3 is unchanged context and stays grouped.
        assertEquals(listOf("n0({n1 n2})", "n3([Calls in f3])"), built.lanes.map { render(it.root) })
        assertNotNull(built.step(2))
        assertFalse(built.regions.single { it.plan.evidence.owner == 3 }.expanded)
    }
}
