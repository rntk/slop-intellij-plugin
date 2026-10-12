package dev.insight.callgraph

import org.junit.Assert.*
import org.junit.Test
import java.awt.Rectangle

class FlowChartTest {
    private fun edges(vararg calls: Pair<Int, Int>) = calls.map { (from, to) -> CallGraphEdge(from, to) }

    /** Calls by caller with callees in [order], callers from the same edges, cards titled `n<id>`. */
    private fun source(edges: List<CallGraphEdge>, sites: Map<Int, List<CallSite>> = emptyMap(), order: List<Int> = (0..20).toList(),
        card: (Int) -> FlowCard = { FlowCard("n$it") }) =
        FlowSource(edges.sortedBy { order.indexOf(it.callee) }.groupBy { it.caller },
            edges.filter { it.kind == RelationKind.CALL }.groupBy { it.callee }.mapValues { (_, e) -> e.map { it.caller } }, sites, card)

    /** Node 0 placed on the chart with every function's calls open. */
    private fun chart(edges: List<CallGraphEdge>, sites: Map<Int, List<CallSite>> = emptyMap(), order: List<Int> = (0..20).toList()) =
        buildFlowChart(FlowBoard(listOf(FlowStart(0, "Flow", "What it does")), openCalls = (0..20).toSet()), source(edges, sites, order))

    private val FlowChart.root get() = lanes.single().root

    private fun branch(construct: Int, branch: Int, label: String, otherwise: Boolean = false, subject: String? = null, kind: ControlKind = ControlKind.BRANCH) =
        ControlFrame(kind, construct, branch, label, otherwise, subject)

    /**
     * Compact text of an item tree: steps `n<id>#<number>` with open callers in `[…]` before and open calls in `(…)`
     * after, refs `->number`, decisions `?question[label: …|…]`, loops `loop label{…}`.
     */
    private fun render(item: FlowItem): String = when (item) {
        is FlowStep -> (item.upstream?.joinToString(" ", "[", "] ", transform = ::render) ?: "") + "n${item.node}#${item.number}" +
            if (item.items.isEmpty()) "" else item.items.joinToString(" ", "(", ")", transform = ::render)
        is FlowRef -> "->${item.number}"
        is FlowDecision -> "?${item.question}" + item.branches.joinToString("|", "[", "]") { b -> b.label + ":" + b.items.joinToString(" ", transform = ::render) }
        is FlowLoop -> "loop ${item.label}{" + item.items.joinToString(" ", transform = ::render) + "}"
    }

    @Test fun `steps follow call sites in source order, then calls without a known site in the order of the calls`() {
        val sites = mapOf(0 to listOf(CallSite(3, 30), CallSite(1, 10), CallSite(9, 5)))
        val chart = chart(edges(0 to 1, 0 to 2, 0 to 3, 1 to 4), sites, order = listOf(0, 2, 1, 3, 4))
        assertEquals("n0#1(n1#2(n4#3) n3#4 n2#5)", render(chart.root))
        assertEquals(listOf(0, 1, 4, 3, 2), chart.steps.map { it.node })
        assertEquals("Flow", chart.lanes.single().title)
    }

    @Test fun `closed calls and callers are counted but not listed`() {
        val chart = buildFlowChart(FlowBoard(listOf(FlowStart(1))), source(edges(0 to 1, 1 to 2, 1 to 3, 4 to 1)))
        assertEquals("n1#1", render(chart.root))
        assertEquals(2, chart.root.calls)
        assertEquals(2, chart.root.callers)
        assertNull(chart.root.upstream)
        assertEquals("n1", chart.lanes.single().title)
    }

    @Test fun `a symbol reached again becomes a reference and cycles end`() {
        val chart = chart(edges(0 to 1, 0 to 2, 2 to 1, 1 to 0))
        assertEquals("n0#1(n1#2(->1) n2#3(->2))", render(chart.root))
    }

    @Test fun `a single if becomes a yes or no question with an empty no branch`() {
        val sites = mapOf(0 to listOf(CallSite(1, 10), CallSite(2, 20, listOf(branch(15, 15, "ready", subject = "ready"))), CallSite(3, 40)))
        val chart = chart(edges(0 to 1, 0 to 2, 0 to 3), sites)
        assertEquals("n0#1(n1#2 ?ready?[yes:n2#3|no:] n3#4)", render(chart.root))
        val decision = chart.root.items[1] as FlowDecision
        assertEquals(listOf(BranchKind.CASE, BranchKind.OTHERWISE), decision.branches.map { it.kind })
    }

    @Test fun `only the else branch calling still asks the if condition`() {
        val sites = mapOf(0 to listOf(CallSite(1, 30, listOf(branch(10, 25, "", otherwise = true, subject = "cached")))))
        assertEquals("n0#1(?cached?[yes:|no:n1#2])", render(chart(edges(0 to 1), sites).root))
    }

    @Test fun `an else-if chain comparing one subject asks about the subject`() {
        val chain = { branchAt: Int, label: String -> branch(5, branchAt, label, subject = "path == \"/a\"") }
        val sites = mapOf(0 to listOf(
            CallSite(1, 10, listOf(chain(5, "path == \"/a\""))),
            CallSite(2, 30, listOf(chain(25, "path == \"/b\""))),
            CallSite(3, 50, listOf(branch(5, 45, "", otherwise = true, subject = "path == \"/a\""))),
        ))
        assertEquals("n0#1(?path?[\"/a\":n1#2|\"/b\":n2#3|else:n3#4])", render(chart(edges(0 to 1, 0 to 2, 0 to 3), sites).root))
    }

    @Test fun `different conditions keep their labels and gain an otherwise path`() {
        val sites = mapOf(0 to listOf(
            CallSite(1, 10, listOf(branch(5, 5, "a > 1", subject = "a > 1"))),
            CallSite(2, 30, listOf(branch(5, 25, "b", subject = "a > 1"))),
        ))
        assertEquals("n0#1(?Which condition?[a > 1:n1#2|b:n2#3|otherwise:])", render(chart(edges(0 to 1, 0 to 2), sites).root))
    }

    @Test fun `cases of a when, nested conditions, loops and error handlers`() {
        val case = { at: Int, label: String, otherwise: Boolean -> branch(5, at, label, otherwise, subject = "kind", kind = ControlKind.CASE) }
        val loop = ControlFrame(ControlKind.LOOP, 100, 100, "for (x in xs)")
        val sites = mapOf(0 to listOf(
            CallSite(1, 10, listOf(case(8, "A", false))),
            CallSite(2, 20, listOf(case(8, "A", false), branch(15, 15, "x", subject = "x"))),
            CallSite(3, 40, listOf(case(35, "", true))),
            CallSite(4, 110, listOf(loop)),
            CallSite(5, 210, listOf(ControlFrame(ControlKind.ERROR, 200, 205, "IOException e"))),
        ))
        assertEquals("n0#1(?kind?[A:n1#2 ?x?[yes:n2#3|no:]|default:n3#4] loop for (x in xs){n4#5} ?Fails?[no:|on error IOException e:n5#6])",
            render(chart(edges(0 to 1, 0 to 2, 0 to 3, 0 to 4, 0 to 5), sites).root))
    }

    @Test fun `a when without a default gains an other cases path`() {
        val sites = mapOf(0 to listOf(CallSite(1, 10, listOf(branch(5, 8, "A", subject = null, kind = ControlKind.CASE)))))
        assertEquals("n0#1(?Which case?[A:n1#2|other cases:])", render(chart(edges(0 to 1), sites).root))
    }

    @Test fun `dispatch to several implementations forks by owner`() {
        val titles = listOf("Main.run", "Backend.send", "HttpBackend.send", "FileBackend.send")
        val source = source(edges(0 to 1) + listOf(CallGraphEdge(1, 2, RelationKind.INHERITS), CallGraphEdge(1, 3, RelationKind.INHERITS)),
            card = { FlowCard(titles[it]) })
        val chart = buildFlowChart(FlowBoard(listOf(FlowStart(0)), openCalls = setOf(0, 1)), source)
        val dispatch = chart.root.items.filterIsInstance<FlowStep>().single().items.single() as FlowDecision
        assertEquals(DecisionKind.DISPATCH, dispatch.kind)
        assertEquals(listOf("HttpBackend", "FileBackend"), dispatch.branches.map { it.label })
    }

    @Test fun `callers are numbered before the function and can open their own calls and callers`() {
        // 2 → 0 → 1; 3 → 0; 2 → 5; 4 → 2.
        val source = source(edges(0 to 1, 2 to 0, 3 to 0, 2 to 5, 4 to 2))
        val board = FlowBoard(listOf(FlowStart(0)), openCalls = setOf(0, 2), openCallers = setOf(0, 2))
        assertEquals("[[n4#1] n2#2(->5 n5#3) n3#4] n0#5(n1#6)",
            render(buildFlowChart(board, source).root))
    }

    @Test fun `a placed function heads its own column and is a reference elsewhere, numbered in reading order`() {
        val source = source(edges(0 to 1, 0 to 2, 1 to 2))
        val chart = buildFlowChart(FlowBoard(listOf(FlowStart(0, "First"), FlowStart(1)), openCalls = setOf(0, 1)), source)
        assertEquals(listOf("First", "n1"), chart.lanes.map { it.title })
        assertEquals("n0#1(->3 n2#2)", render(chart.lanes[0].root))
        assertEquals("n1#3(->2)", render(chart.lanes[1].root))
        assertEquals(listOf(0, 2, 1), chart.steps.map { it.node })
        assertEquals(2, chart.step(2)?.callers)
    }

    @Test fun `a board moves into another graph, dropping functions that are gone`() {
        val board = FlowBoard(listOf(FlowStart(1, "A"), FlowStart(2), FlowStart(3)), openCalls = setOf(1, 2), openCallers = setOf(3))
        val moved = board.map { mapOf(1 to 10, 3 to 10, 2 to null)[it] }
        assertEquals(listOf(FlowStart(10, "A")), moved.starts)
        assertEquals(setOf(10), moved.openCalls)
        assertEquals(setOf(10), moved.openCallers)
    }

    @Test fun `steps within count nested steps but not references`() {
        val chart = chart(edges(0 to 1, 1 to 2, 0 to 3, 3 to 2))
        assertEquals(3, stepsWithin(chart.root))
        assertEquals(1, stepsWithin(chart.root.items.first()))
    }

    @Test fun `control headers read conditions, cases and loops in several languages`() {
        assertEquals("a && b(c)", ControlText.header("if (a && b(c)) {\n  x()\n}").label)
        assertEquals(14, ControlText.header("if (a && b(c)) {\n  x()\n}").end)
        assertEquals("path == \"/a: b\"", ControlText.header("elif path == \"/a: b\":\n    go()").label)
        assertEquals("err != nil", ControlText.header("if err := f(); err != nil {").label)
        assertEquals("is Loaded", ControlText.header("is Loaded -> show()").label)
        assertEquals("\"a\", \"b\"", ControlText.header("case \"a\", \"b\" -> handle()").label)
        assertEquals("Point(x=0)", ControlText.header("case Point(x=0):\n  pass").label)
        assertEquals("", ControlText.header("default:\n  break;").label)
        assertEquals("for (x in xs)", ControlText.header("for (x in xs) process(x)").raw)
        assertEquals("for x in items", ControlText.header("for x in items:\n    do(x)").raw)
        assertEquals("ValueError as e", ControlText.header("except ValueError as e:\n  log(e)").label)
        assertEquals("cond", ControlText.header("else if (cond) {").label)
        assertTrue(ControlText.clip("x".repeat(100)).length <= ControlText.MAX_LABEL)
        assertTrue(ControlText.startsWithWord("  else {", "else"))
        assertFalse(ControlText.startsWithWord("elseValue", "else"))
    }


    private val metrics = FlowMetrics(cardWidth = 200, gutter = 10, dotRadius = 4, gap = 10, pillHeight = 20, pillPadding = 8,
        branchGap = 20, framePadding = 10, decisionHeight = 30, decisionMaxWidth = 240, bannerWidth = 300, margin = 20, emptyBranch = 20)

    private val measure = object : FlowMeasure {
        override fun card(step: FlowStep, width: Int) = 50
        override fun firstLine() = 8
        override fun text(text: String) = text.length * 6
        override fun line() = 16
        override fun banner(lane: FlowLane, width: Int) = 40
    }

    private fun assertApart(geometry: FlowGeometry) {
        val boxes = geometry.cards.values.map { it.bounds }
        for ((i, a) in boxes.withIndex()) for (b in boxes.drop(i + 1)) assertFalse("$a overlaps $b", a.intersects(b))
        assertTrue(boxes.all { Rectangle(0, 0, geometry.size.width, geometry.size.height).contains(it) })
    }

    @Test fun `layout keeps cards apart, alternates the main spine and puts branches side by side`() {
        val sites = mapOf(0 to listOf(
            CallSite(1, 10),
            CallSite(2, 20, listOf(branch(15, 15, "x", subject = "x"))),
            CallSite(3, 30, listOf(branch(15, 25, "", otherwise = true, subject = "x"))),
            CallSite(4, 50),
        ))
        val chart = chart(edges(0 to 1, 0 to 2, 0 to 3, 0 to 4, 1 to 5), sites)
        val geometry = layoutFlowChart(chart, metrics, measure)
        val cards = geometry.cards
        assertEquals(chart.steps.map { it.node }.toSet(), cards.keys)
        assertApart(geometry)
        // Main spine: the entry left, its first callee right.
        assertTrue(cards.getValue(0).leftOfSpine)
        assertFalse(cards.getValue(1).leftOfSpine)
        // Branch steps sit side by side, right of their own lines, below the decision and above the step after it.
        val yes = cards.getValue(2)
        val no = cards.getValue(3)
        assertEquals(yes.bounds.y, no.bounds.y)
        assertTrue(yes.bounds.maxX <= no.bounds.x)
        assertFalse(yes.leftOfSpine || no.leftOfSpine)
        val decision = geometry.shapes.filterIsInstance<FlowDecisionBox>().single().bounds
        assertTrue(decision.maxY < yes.bounds.y && yes.bounds.maxY < cards.getValue(4).bounds.y)
        // The calls of node 1 are framed and headed by a pill that hides them.
        assertEquals(1, geometry.shapes.filterIsInstance<FlowPill>().count { it.toggle == 1 })
        assertTrue(geometry.shapes.filterIsInstance<FlowFrame>().isNotEmpty())
        // Each step inside 0 is called only by 0, so no caller pills.
        assertTrue(geometry.shapes.filterIsInstance<FlowPill>().none { it.callers })
    }

    @Test fun `closed calls are a pill naming how many functions are called`() {
        val source = source(edges(0 to 1, 1 to 2, 1 to 3))
        val open = layoutFlowChart(buildFlowChart(FlowBoard(listOf(FlowStart(0)), openCalls = setOf(0, 1)), source), metrics, measure)
        val closed = layoutFlowChart(buildFlowChart(FlowBoard(listOf(FlowStart(0)), openCalls = setOf(0)), source), metrics, measure)
        assertEquals(setOf(0, 1, 2, 3), open.cards.keys)
        assertEquals(setOf(0, 1), closed.cards.keys)
        assertTrue(closed.shapes.filterIsInstance<FlowPill>().any { it.toggle == 1 && !it.callers && "2 calls" in it.text })
        assertTrue(closed.size.height < open.size.height)
    }

    @Test fun `callers appear above a function in parallel columns and cycles become references`() {
        val source = FlowSource(mapOf(0 to edges(0 to 1)), mapOf(0 to listOf(2, 3), 2 to listOf(4), 4 to listOf(2), 1 to listOf(0))) { FlowCard("n$it") }
        fun layout(openCallers: Set<Int>) =
            layoutFlowChart(buildFlowChart(FlowBoard(listOf(FlowStart(0)), setOf(0), openCallers), source), metrics, measure)
        val initial = layout(emptySet())
        assertEquals(setOf(0, 1), initial.cards.keys)
        assertTrue(initial.shapes.filterIsInstance<FlowPill>().single { it.callers }.let { it.toggle == 0 && "2 callers" in it.text })
        val disclosed = layout(setOf(0))
        val root = disclosed.cards.getValue(0).bounds
        val a = disclosed.cards.getValue(2).bounds
        val b = disclosed.cards.getValue(3).bounds
        assertEquals(a.y, b.y)
        assertTrue(a.maxY < root.y && b.maxY < root.y)
        assertTrue(a.maxX < b.x)
        // Callers have caller pills of their own.
        assertTrue(disclosed.shapes.filterIsInstance<FlowPill>().any { it.callers && it.toggle == 2 })
        val cycle = layout(setOf(0, 2, 4))
        assertEquals(setOf(0, 1, 2, 3, 4), cycle.cards.keys)
        assertEquals(2, cycle.shapes.filterIsInstance<FlowRefBox>().single().ref.node)
        assertApart(cycle)
    }

    @Test fun `columns stand side by side under their own banners`() {
        val source = source(edges(0 to 1, 2 to 3))
        val geometry = layoutFlowChart(buildFlowChart(FlowBoard(listOf(FlowStart(0, "A"), FlowStart(2, "B")), openCalls = setOf(0, 2)), source),
            metrics, measure)
        assertApart(geometry)
        val banners = geometry.shapes.filterIsInstance<FlowBanner>()
        assertEquals(listOf("A", "B"), banners.map { it.lane.title })
        assertTrue(banners[0].bounds.maxX <= banners[1].bounds.x)
        val cards = geometry.cards
        assertTrue(maxOf(cards.getValue(0).bounds.maxX, cards.getValue(1).bounds.maxX) < minOf(cards.getValue(2).bounds.x, cards.getValue(3).bounds.x))
        assertEquals(cards.getValue(0).bounds.y, cards.getValue(2).bounds.y)
    }
}
