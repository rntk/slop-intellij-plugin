package dev.insight

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.callgraph.*
import java.awt.Point
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JMenuItem

class FlowChartCanvasTest : BasePlatformTestCase() {
    /** 0 → 1 → (2, 3); 0 → 4; callers 5 → 0 and 6 → 0; 5 → 8; 7 → 5 → 7. */
    private fun source(): FlowSource {
        val edges = listOf(0 to 1, 1 to 2, 1 to 3, 0 to 4, 5 to 0, 6 to 0, 5 to 8, 7 to 5, 5 to 7).map { (from, to) -> CallGraphEdge(from, to) }
        return FlowSource(edges.groupBy { it.caller }, edges.groupBy { it.callee }.mapValues { (_, e) -> e.map { it.caller } }) {
            FlowCard("Service.step$it", "file.kt:$it", "Does step $it.")
        }
    }

    private fun canvas(opened: MutableList<Int> = mutableListOf()) = FlowChartCanvas({}, { opened += it },
        onToChat = { error("Left-click must not open scoped chat") }).apply {
        show(source())
        add(FlowStart(0, "Flow", "Does things"))
        size = preferredSize
    }

    private fun FlowChartCanvas.nodes() = geometry!!.cards.keys

    private fun FlowChartCanvas.pill(node: Int, callers: Boolean) =
        geometry!!.shapes.filterIsInstance<FlowPill>().single { it.toggle == node && it.callers == callers }

    private fun paint(canvas: FlowChartCanvas) {
        val image = BufferedImage(canvas.width.coerceAtLeast(1), canvas.height.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try { canvas.paint(g) } finally { g.dispose() }
    }

    private fun click(canvas: FlowChartCanvas, point: Point, clicks: Int = 1) {
        val press = MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0, 0, point.x, point.y, clicks, false, MouseEvent.BUTTON1)
        val release = MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 0, 0, point.x, point.y, clicks, false, MouseEvent.BUTTON1)
        canvas.mouseListeners.forEach { it.mousePressed(press) }
        canvas.mouseListeners.forEach { it.mouseReleased(release) }
    }

    private fun centre(bounds: java.awt.Rectangle) = Point(bounds.centerX.toInt(), bounds.centerY.toInt())

    fun `test chat menu captures cards callers and connectors at the current zoom`() {
        val targets = mutableListOf<FlowChatTarget>()
        val canvas = FlowChartCanvas({}, {}, onToChat = { targets += it }).apply {
            show(source())
            add(FlowStart(0))
            toggleCallers(0)
            zoom = 1.5
        }
        fun scaled(point: Point) = Point((point.x * canvas.zoom).toInt(), (point.y * canvas.zoom).toInt())
        val caller = canvas.geometry!!.cards.getValue(5)
        val menu = canvas.contextMenuAt(scaled(centre(caller.bounds)))!!
        menu.components.filterIsInstance<JMenuItem>().single { it.text == "To chat" }.doClick()
        assertEquals(listOf(5), targets.single().nodes)
        assertEquals(5, targets.single().owner)
        val segment = canvas.geometry!!.shapes.filterIsInstance<FlowSegment>().first {
            canvas.geometry!!.chatTargets[it]?.owner == 1 && it.from.distance(it.to) > 16
        }
        val middle = Point((segment.from.x + segment.to.x) / 2, (segment.from.y + segment.to.y) / 2)
        val target = canvas.chatTargetAt(scaled(middle))!!
        assertEquals("connector", target.kind)
        assertEquals(1, target.owner)
        assertNotNull(canvas.contextMenuAt(scaled(middle)))
        canvas.selectNode(4)
        assertEquals(5, targets.single().owner)
    }

    fun `test region chat scopes keep their owning function and occurrence evidence`() {
        val sites = listOf(CallSite(1, 10), CallSite(2, 20))
        val source = FlowSource(mapOf(0 to listOf(CallGraphEdge(0, 1), CallGraphEdge(0, 2))), emptyMap(),
            callSites = mapOf(0 to sites)) { FlowCard("function$it") }
        val canvas = FlowChartCanvas({}, {}, onToChat = {}).apply {
            show(source)
            setCompact(true)
            add(FlowStart(0))
        }
        val box = canvas.geometry!!.shapes.filterIsInstance<FlowRegionBox>().single()
        val target = canvas.chatTargetAt(centre(box.bounds))!!
        assertEquals("region", target.kind)
        assertEquals(0, target.owner)
        assertEquals(listOf(1, 2), target.nodes)
        assertEquals(sites, target.region!!.sites)
        canvas.contextMenuAt(centre(box.bounds))!!.components.filterIsInstance<JMenuItem>()
            .single { it.text == "Expand subflow" }.doClick()
        assertNotNull(canvas.chart!!.step(1))
        assertEquals(sites, target.region!!.sites)
    }

    fun `test decision and branch connectors retain owner instead of a nearby callee`() {
        val frame = ControlFrame(ControlKind.BRANCH, 10, 12, "ready")
        val canvas = FlowChartCanvas({}, {}, onToChat = {}).apply {
            show(FlowSource(mapOf(0 to listOf(CallGraphEdge(0, 1))), emptyMap(),
                callSites = mapOf(0 to listOf(CallSite(1, 30, listOf(frame))))) { FlowCard("function$it") })
            add(FlowStart(0))
        }
        val decision = canvas.geometry!!.shapes.filterIsInstance<FlowDecisionBox>().single()
        val target = canvas.chatTargetAt(centre(decision.bounds))!!
        assertEquals("decision", target.kind)
        assertEquals(0, target.owner)
        assertEquals("Which condition?", target.label)
        val branch = canvas.geometry!!.shapes.filterIsInstance<FlowPill>().first { it.style == FlowPillStyle.CASE }
        assertEquals(0, canvas.chatTargetAt(centre(branch.bounds))!!.owner)
        val branchLines = canvas.geometry!!.shapes.filterIsInstance<FlowSegment>().filter {
            canvas.geometry!!.chatTargets[it]?.kind == "decision"
        }
        assertTrue(branchLines.isNotEmpty())
        assertTrue(branchLines.all { canvas.geometry!!.chatTargets[it]!!.owner == 0 })
    }

    fun `test painting leaves the layout unchanged`() {
        val canvas = canvas()
        canvas.toggleCalls(1)
        val before = canvas.geometry
        val shapes = before!!.shapes.toString()
        repeat(3) { paint(canvas) }
        assertEquals(shapes, canvas.geometry!!.shapes.toString())
        assertEquals(before.size, canvas.geometry!!.size)
    }

    fun `test an added function shows its direct calls and is selected`() {
        val canvas = canvas()
        assertEquals(setOf(0, 1, 4), canvas.nodes())
        assertEquals(0, canvas.selectedNode)
        assertEquals("Flow", canvas.chart!!.lanes.single().title)
    }

    fun `test clicking a card selects it, a double click opens it and arrows step through the chart`() {
        val opened = mutableListOf<Int>()
        val canvas = canvas(opened)
        canvas.toggleCalls(1)
        click(canvas, centre(canvas.geometry!!.cards.getValue(1).bounds))
        assertEquals(1, canvas.selectedNode)
        click(canvas, centre(canvas.geometry!!.cards.getValue(2).bounds), clicks = 2)
        assertEquals(listOf(2), opened)
        assertEquals(3, canvas.step(1))
        assertEquals(3, canvas.selectedNode)
        assertEquals(4, canvas.step(5))
        assertEquals(0, canvas.step(-10))
    }

    fun `test the calls pill shows and hides calls, and a hidden selection is cleared`() {
        val canvas = canvas()
        val closed = canvas.geometry!!
        click(canvas, centre(canvas.pill(1, callers = false).bounds))
        assertEquals(setOf(0, 1, 2, 3, 4), canvas.nodes())
        assertTrue(canvas.geometry!!.size.height > closed.size.height)
        canvas.select(3)
        click(canvas, centre(canvas.pill(1, callers = false).bounds))
        assertEquals(setOf(0, 1, 4), canvas.nodes())
        assertNull(canvas.selectedNode)
    }

    fun `test callers open level by level and a caller opens its own calls on the same chart`() {
        val canvas = canvas()
        click(canvas, centre(canvas.pill(0, callers = true).bounds))
        assertEquals(0, canvas.selectedNode)
        assertTrue(canvas.nodes().containsAll(listOf(5, 6)))
        assertFalse(7 in canvas.nodes())
        // The caller's calls: the function below it is a reference, its other call a new step.
        click(canvas, centre(canvas.pill(5, callers = false).bounds))
        assertTrue(8 in canvas.nodes())
        assertTrue(canvas.geometry!!.shapes.filterIsInstance<FlowRefBox>().any { it.ref.node == 0 })
        // The caller's callers, then a cycle back to it.
        click(canvas, centre(canvas.pill(5, callers = true).bounds))
        assertTrue(7 in canvas.nodes())
        click(canvas, centre(canvas.pill(7, callers = true).bounds))
        val reference = canvas.geometry!!.shapes.filterIsInstance<FlowRefBox>().single { it.ref.node == 5 }
        click(canvas, centre(reference.bounds))
        assertEquals(5, canvas.selectedNode)
        // Hiding the callers of 0 hides everything above it but remembers what was open.
        canvas.toggleCallers(0)
        assertNull(canvas.selectedNode)
        assertEquals(setOf(0, 1, 4), canvas.nodes())
        canvas.toggleCallers(0)
        assertTrue(canvas.nodes().containsAll(listOf(5, 6, 7, 8)))
        paint(canvas)
    }

    fun `test a function opened in a new column heads it and is a reference in the first`() {
        val canvas = canvas()
        canvas.add(FlowStart(4))
        assertEquals(2, canvas.chart!!.lanes.size)
        assertEquals(4, canvas.selectedNode)
        assertTrue(canvas.geometry!!.cards.getValue(4).bounds.x > canvas.geometry!!.cards.getValue(0).bounds.x)
        assertEquals(4, canvas.geometry!!.shapes.filterIsInstance<FlowRefBox>().single().ref.node)
        canvas.add(FlowStart(4))
        assertEquals(2, canvas.chart!!.lanes.size)
        canvas.removeColumn(4)
        assertEquals(1, canvas.chart!!.lanes.size)
        assertTrue(4 in canvas.nodes())
        assertTrue(canvas.geometry!!.shapes.none { it is FlowRefBox })
    }

    fun `test the board and selection survive a new source and a rebuilt graph`() {
        val canvas = canvas()
        canvas.toggleCalls(1)
        canvas.select(2)
        canvas.show(source())
        assertEquals(setOf(0, 1, 2, 3, 4), canvas.nodes())
        assertEquals(2, canvas.selectedNode)
        // In the rebuilt graph every id moved up by 10 and node 3 is gone.
        canvas.remap { if (it == 3) null else it + 10 }
        val moved = source().let { s ->
            FlowSource(s.calls.entries.associate { (k, v) -> k + 10 to v.filter { it.callee != 3 }.map { CallGraphEdge(it.caller + 10, it.callee + 10) } },
                emptyMap()) { FlowCard("Service.step${it - 10}") }
        }
        canvas.show(moved)
        assertEquals(setOf(10, 11, 12, 14), canvas.nodes())
        assertEquals(12, canvas.selectedNode)
        canvas.show(null, "Building")
        assertNull(canvas.geometry)
        canvas.show(moved)
        canvas.clear()
        assertNull(canvas.chart)
        assertNull(canvas.selectedNode)
    }

    fun `test compact cards expand on click retain evidence and collapse with the frame pill`() {
        val sites = listOf(CallSite(1, 10), CallSite(2, 20), CallSite(1, 30))
        val candidate = FlowRegionEvidence(0, sites)
        val plan = FlowRegionPlan(candidate, "Store user data", "Writes a user record.", inferred = true)
        val opened = mutableListOf<Int>()
        val canvas = FlowChartCanvas({}, { opened += it },
            onToChat = { error("Left-click must not open scoped chat") }).apply {
            setCompact(true)
            show(FlowSource(mapOf(0 to listOf(CallGraphEdge(0, 1), CallGraphEdge(0, 2))), emptyMap(),
                mapOf(0 to sites), mapOf(candidate.key to listOf(plan))) { FlowCard("f$it") })
            add(FlowStart(0))
            size = preferredSize
        }
        assertEquals(setOf(0), canvas.nodes())
        val region = canvas.geometry!!.shapes.filterIsInstance<FlowRegionBox>().single()
        val compactHeight = canvas.preferredSize.height
        assertNull(canvas.step(1))
        assertEquals(candidate.key, canvas.selectedRegion)
        assertEquals(0, canvas.step(-1))
        assertTrue(canvas.selectRegion(candidate.key))
        canvas.markRegionMatches(setOf(candidate.key))
        paint(canvas)
        click(canvas, centre(region.bounds))
        assertEquals(setOf(0, 1, 2), canvas.nodes())
        assertEquals(candidate.key, canvas.selectedRegion)
        assertTrue(canvas.preferredSize.height > compactHeight)
        assertTrue(opened.isEmpty())
        val pill = canvas.geometry!!.shapes.filterIsInstance<FlowPill>().single { it.regionKey == candidate.key }
        click(canvas, centre(pill.bounds))
        assertEquals(setOf(0), canvas.nodes())
        canvas.setCompact(false)
        assertEquals(setOf(0, 1, 2), canvas.nodes())
        assertNull(canvas.selectedRegion)
        canvas.setCompact(true)
        canvas.clear()
        assertTrue(canvas.board.compact)
        paint(canvas)
    }
}
