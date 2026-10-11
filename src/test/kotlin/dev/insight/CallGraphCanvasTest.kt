package dev.insight

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.insight.callgraph.*
import java.awt.Dimension
import java.awt.Point
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JViewport

class CallGraphCanvasTest : LightJavaCodeInsightFixtureTestCase() {
    private fun canvas(onOpen: (Int) -> Unit = {}): Pair<CallGraphCanvas, JViewport> {
        val canvas = CallGraphCanvas({}, onOpen)
        canvas.show(CallGraph((0..79).map { CallGraphNode(it, "node$it", "", "", it % 4, true) }, emptySet(), false))
        val viewport = JViewport().apply {
            size = Dimension(320, 160)
            view = canvas
            viewSize = canvas.preferredSize
        }
        return canvas to viewport
    }

    private fun mouse(canvas: CallGraphCanvas, type: Int, point: Point, clicks: Int = 1): MouseEvent =
        MouseEvent(canvas, type, System.currentTimeMillis(), 0, point.x, point.y, clicks, false, MouseEvent.BUTTON1)

    fun `test wheel zoom preserves point under cursor and consumes scrolling`() {
        val (canvas, viewport) = canvas()
        viewport.viewPosition = Point(100, 100)
        val anchor = Point(300, 200)
        val screen = Point(anchor.x - viewport.viewPosition.x, anchor.y - viewport.viewPosition.y)
        val wheel = MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
            anchor.x, anchor.y, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1)
        canvas.mouseWheelListeners.forEach { it.mouseWheelMoved(wheel) }
        assertTrue(wheel.isConsumed)
        assertEquals(1.15, canvas.zoom, 0.0001)
        assertEquals(screen.x.toDouble(), anchor.x * canvas.zoom - viewport.viewPosition.x, 1.0)
        assertEquals(screen.y.toDouble(), anchor.y * canvas.zoom - viewport.viewPosition.y, 1.0)
        canvas.zoomAt(100.0, anchor)
        assertEquals(2.0, canvas.zoom)
        canvas.zoomAt(0.001, anchor)
        assertEquals(0.15, canvas.zoom)
        assertTrue(viewport.viewPosition.x >= 0 && viewport.viewPosition.y >= 0)
    }

    fun `test drag pans without selecting or opening a node and double click still navigates`() {
        var opened: Int? = null
        val (canvas, viewport) = canvas { opened = it }
        val click = Point(30, 30)
        canvas.mouseListeners.forEach { it.mousePressed(mouse(canvas, MouseEvent.MOUSE_PRESSED, click, 2)) }
        canvas.mouseListeners.forEach { it.mouseReleased(mouse(canvas, MouseEvent.MOUSE_RELEASED, click, 2)) }
        assertEquals(0, canvas.selected)
        assertEquals(0, opened)
        opened = null
        viewport.viewPosition = Point(100, 100)
        canvas.mouseListeners.forEach { it.mousePressed(mouse(canvas, MouseEvent.MOUSE_PRESSED, Point(120, 120), 2)) }
        canvas.mouseMotionListeners.forEach { it.mouseDragged(mouse(canvas, MouseEvent.MOUSE_DRAGGED, Point(90, 100), 2)) }
        canvas.mouseListeners.forEach { it.mouseReleased(mouse(canvas, MouseEvent.MOUSE_RELEASED, Point(90, 100), 2)) }
        assertEquals(Point(130, 120), viewport.viewPosition)
        assertEquals(0, canvas.selected)
        assertNull(opened)
    }

    fun `test arrow keys pan and clamp to graph bounds`() {
        val (canvas, viewport) = canvas()
        viewport.viewPosition = Point(100, 100)
        canvas.actionMap.get("RIGHT").actionPerformed(null)
        assertTrue(viewport.viewPosition.x > 100)
        val afterArrow = viewport.viewPosition.x
        canvas.actionMap.get("shift RIGHT").actionPerformed(null)
        assertTrue(viewport.viewPosition.x - afterArrow > afterArrow - 100)
        repeat(30) { canvas.actionMap.get("LEFT").actionPerformed(null) }
        assertEquals(0, viewport.viewPosition.x)
        repeat(30) { canvas.actionMap.get("shift DOWN").actionPerformed(null) }
        assertEquals(canvas.preferredSize.height - viewport.extentSize.height, viewport.viewPosition.y)
    }

    fun `test right click selects the node and shows its menu and Enter opens the selection`() {
        var opened: Int? = null
        var menuFor: Int? = null
        val canvas = CallGraphCanvas({}, { opened = it }, popup = { menuFor = it; null })
        canvas.show(CallGraph(listOf(CallGraphNode(0, "a", "", "", 0, true), CallGraphNode(1, "b", "", "", 1, true)), setOf(CallGraphEdge(0, 1)), false),
            mapOf(CallGraphEdge(0, 1) to 12))
        val press = MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 30, 30, 1, true, MouseEvent.BUTTON3)
        canvas.mouseListeners.forEach { it.mousePressed(press) }
        assertEquals(0, menuFor)
        assertEquals(0, canvas.selected)
        canvas.actionMap.get("open").actionPerformed(null)
        assertEquals(0, opened)
    }

    fun `test sections view lays out titled columns, paints them and explains a section on hover`() {
        val files = listOf("Intake.kt" to listOf("receive", "validate"), "Care.kt" to listOf("coordinate", "house", "treat", "employ"),
            "Exit.kt" to listOf("relocate", "settle"))
        val nodes = mutableListOf<CallGraphNode>()
        val edges = linkedSetOf<CallGraphEdge>()
        for ((file, functions) in files) {
            val fileId = nodes.size
            nodes += CallGraphNode(fileId, file, "", "", 0, true, SymbolKind.FILE, "/p/src/app/$file")
            for (function in functions) {
                edges += CallGraphEdge(fileId, nodes.size, RelationKind.CONTAINS)
                nodes += CallGraphNode(nodes.size, function, "", "", 0, true, SymbolKind.FUNCTION, "/p/src/app/$file")
            }
        }
        fun id(name: String) = nodes.single { it.name == name }.id
        listOf("receive" to "validate", "validate" to "coordinate", "coordinate" to "house", "coordinate" to "treat",
            "coordinate" to "employ", "house" to "relocate", "treat" to "settle", "employ" to "settle")
            .forEach { (from, to) -> edges += CallGraphEdge(id(from), id(to)) }
        val tree = CallGraph(nodes, edges, false, projectGraph = true).hierarchy()
        val view = tree.view(tree.expansion(GraphDetail.SYMBOL))
        val sections = dev.insight.enrichment.SectionLens.sections(tree, view, dev.insight.enrichment.SectionBy.FILE, null, "Journey")

        val canvas = CallGraphCanvas({}, {})
        canvas.show(view.graph, view.weights, sections)
        canvas.size = canvas.preferredSize
        assertEquals(listOf("Intake.kt", "Care.kt", "Exit.kt"), sections.sections.map { it.title })
        val image = java.awt.image.BufferedImage(canvas.width, canvas.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().also { canvas.paint(it) }.dispose()
        java.io.File(System.getProperty("java.io.tmpdir"), "sections-preview.png").also { javax.imageio.ImageIO.write(image, "png", it) }

        val graph = tree.graph
        val choice = { value: String -> dev.insight.enrichment.ChoiceValue(value, mapOf(value to 0.9), 0.9) }
        val domainOf = mapOf("receive" to "intake", "validate" to "intake", "coordinate" to "care", "house" to "care", "treat" to "care",
            "employ" to "care", "relocate" to "exit", "settle" to "exit")
        val enrichment = dev.insight.enrichment.Enrichment(
            domainOf.entries.associate { (name, domain) -> id(name) to dev.insight.enrichment.NodeAnnotation(domain = choice(domain), entryPoint = if (name == "receive") 0.9 else 0.1) },
            emptyMap(), emptyMap(), emptyMap(),
            listOf(dev.insight.enrichment.Domain("intake", "Entering", "Improved intake processes rapidly get people into correct care pathways"),
                dev.insight.enrichment.Domain("care", "Experiencing", "Coordinated care delivery across the response network"),
                dev.insight.enrichment.Domain("exit", "Exiting", "Sustainable exits help break the cycles of poverty")),
            listOf(dev.insight.enrichment.Flow("f", "Integrated care", "Consistent coordination throughout the journey", id("receive"),
                listOf(id("receive"), id("validate"), id("coordinate"), id("house")), emptySet())),
            mapOf("app/Care.kt" to dev.insight.enrichment.GroupLabel("app/Care.kt", "Coordinated care", "across the network")),
            emptyMap(), dev.insight.enrichment.EnrichmentStats())
        val facts = graph.nodes.associate { it.id to dev.insight.enrichment.SymbolFacts(it.id, it.name, it.kind, it.name, "", it.filePath?.removePrefix("/p/src/"), null, "", "", contentHash = "") }
        val snapshot = dev.insight.enrichment.EnrichmentSnapshot(graph, dev.insight.enrichment.EnrichmentInput("Journey", graph, facts), enrichment, java.nio.file.Path.of("r"))
        val enriched = dev.insight.enrichment.SectionLens.sections(tree, view, dev.insight.enrichment.SectionBy.DOMAIN, snapshot, "Journey")
        canvas.show(view.graph, view.weights, enriched)
        canvas.decorate(dev.insight.enrichment.EnrichmentLens.decorate(snapshot, graph, view, dev.insight.enrichment.LensOptions()))
        canvas.size = canvas.preferredSize
        val second = java.awt.image.BufferedImage(canvas.width, canvas.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        second.createGraphics().also { canvas.paint(it) }.dispose()
        java.io.File(System.getProperty("java.io.tmpdir"), "sections-enriched.png").also { javax.imageio.ImageIO.write(second, "png", it) }
        canvas.show(view.graph, view.weights, sections)
        canvas.decorate(GraphDecorations.NONE)
        canvas.size = canvas.preferredSize

        // Hovering the title band of the second column describes the section; hovering a node still describes the node.
        val tooltipAt = { x: Int, y: Int -> canvas.getToolTipText(MouseEvent(canvas, MouseEvent.MOUSE_MOVED, 0, 0, x, y, 0, false)) }
        val careFrame = (0 until canvas.width).first { x -> tooltipAt(x, 140)?.contains("Care.kt") == true }
        assertTrue(tooltipAt(careFrame, 140)!!.contains("4 symbols"))
    }

    fun `test highlighting a file keeps it, its neighbours and the selection in focus`() {
        val canvas = CallGraphCanvas({}, {})
        canvas.show(CallGraph((0..4).map { CallGraphNode(it, "n$it", "", "", it, true) },
            setOf(CallGraphEdge(0, 1), CallGraphEdge(2, 3), CallGraphEdge(3, 4)), false))
        assertNull(canvas.focus())
        canvas.highlight(FileGraphHighlight(setOf(0), setOf(1)))
        assertEquals(setOf(0, 1), canvas.focus())
        canvas.select(3)
        assertEquals(setOf(0, 1, 2, 3, 4), canvas.focus())
        canvas.highlight(FileGraphHighlight(emptySet(), emptySet()))
        assertNull(canvas.focus())
    }
}
