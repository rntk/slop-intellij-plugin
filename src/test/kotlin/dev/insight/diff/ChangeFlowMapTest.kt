package dev.insight.diff

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.AbstractButton
import javax.swing.JLabel
import javax.swing.JToggleButton
import javax.swing.text.JTextComponent

class ChangeFlowMapTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture() = IdeaTestFixtureFactory.getFixtureFactory().createTempDirTestFixture()

    private fun flows(): ChangeFlows {
        val names = listOf("Checkout.submit", "Cart.validate", "Pricing.calculate", "Discount.apply", "Order.save", "Receipt.send", "Legacy.checkout", "CheckoutTest.submit")
        val nodes = names.mapIndexed { id, name -> ChangeNode(id, "src/${name.substringBefore('.')}.kt", "/src/$id.kt",
            name.substringAfter('.'), name, when (id) {
                2 -> ChangeStatus.CONTEXT
                4, 5, 7 -> ChangeStatus.ADDED
                6 -> ChangeStatus.DELETED
                else -> ChangeStatus.MODIFIED
            }, "fun ${name.substringAfter('.')}()", id + 10, added = 5, removed = 2, test = id == 7,
            steps = if (id == 6) null else emptyList(), hunk = listOf(HunkLine('+', "applyDiscount()", after = id + 10))) }
        val edges = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 5, 4 to 1, 0 to 4, 7 to 0, 3 to 3).map { ChangeEdge(it.first, it.second) }
        val graph = ChangeGraph(nodes, edges, listOf(ChangeCluster(0, nodes.indices.toList(), "Checkout flow")),
            nodes.map { it.path }.distinct().map { ChangeFile(it, "modified", calls = true) })
        return ChangeFlows(graph, nodeNotes = mapOf(
            0 to NodeAnnotation("Checks the cart before placing an order.", ChangeIntent.BEHAVIOR_CHANGE, ChangeImportance.KEY),
            1 to NodeAnnotation("Rejects missing items and invalid quantities.", ChangeIntent.FIX, ChangeImportance.SUPPORTING),
            3 to NodeAnnotation("Applies the VIP discount before saving.", ChangeIntent.NEW_BEHAVIOR, ChangeImportance.KEY, "Verify rounding at the discount boundary"),
            4 to NodeAnnotation("Persists the discounted order total.", ChangeIntent.WIRING, ChangeImportance.SUPPORTING),
            5 to NodeAnnotation("Sends the receipt after the order is saved.", ChangeIntent.NEW_BEHAVIOR, ChangeImportance.SUPPORTING),
        ), themes = listOf(ChangeTheme("VIP pricing at checkout", "Checkout validates the cart, applies VIP pricing, and saves the discounted order before sending a receipt.", listOf(0))))
    }

    fun testBranchesCyclesAndDisconnectedStepsKeepExactlyTheirSourceEdges() {
        val flows = flows()
        for (width in listOf(280, 600, 850)) {
            val geometry = changeMapGeometry(flows.graph, flows.graph.clusters.single(), width, 108, 38, 42, 32, 13)
            assertEquals(flows.graph.nodes.indices.toSet(), geometry.nodes.keys)
            assertEquals(flows.graph.edges.toSet(), geometry.connectors.map { it.edge }.toSet())
            assertTrue("Deleted, unlinked code has no invented connector", geometry.connectors.none { it.edge.from == 6 || it.edge.to == 6 })
            val bounds = geometry.nodes.values.toList()
            bounds.forEachIndexed { index, rect ->
                assertTrue(rect.x >= 0 && rect.y >= 0 && rect.x + rect.width <= geometry.size.width && rect.y + rect.height <= geometry.size.height)
                assertTrue(bounds.drop(index + 1).none { it.intersects(rect) })
            }
            geometry.connectors.forEach { connector ->
                connector.points.forEach { point ->
                    assertTrue("Connector must fit the map: $point", point.x in 0..geometry.size.width && point.y in 0..geometry.size.height)
                }
                connector.points.zipWithNext().forEach { (from, to) ->
                    assertTrue(from.x == to.x || from.y == to.y)
                    geometry.nodes.forEach { (id, rect) ->
                        if (id != connector.edge.from && id != connector.edge.to) {
                            assertFalse("${connector.edge} crosses step $id", rect.intersectsLine(Line2D.Double(from, to)))
                        }
                    }
                }
                assertEquals(Point(geometry.nodes.getValue(connector.edge.to).x,
                    geometry.nodes.getValue(connector.edge.to).y + 13), connector.points.last())
            }
        }
    }

    fun testEmptyClusterHasNoConnectors() {
        val geometry = changeMapGeometry(ChangeGraph(emptyList(), emptyList(), emptyList(), emptyList()),
            ChangeCluster(0, emptyList(), "Empty"), 850, 108, 38, 42, 32, 13)
        assertTrue(geometry.nodes.isEmpty())
        assertTrue(geometry.connectors.isEmpty())
        assertEquals(0, geometry.size.height)
    }

    fun testMapSelectionDiffAndOutlineSwitchSurviveAnnotationAndZoom() {
        val flows = flows()
        val canvas = NoteCanvas({}, {}).apply { setSize(1200, 900) }
        try {
            canvas.showNotes(emptyList(), flows = flows)
            assertTrue(descendants(canvas).filterIsInstance<ChangeFlowMap>().isNotEmpty())
            assertFalse(texts(canvas).any { it.startsWith("Outline ·") })
            descendants(canvas).filterIsInstance<AbstractButton>().single { it.text == "Discount.apply" }.doClick()
            assertEquals(3, canvas.flowsView.selected[0])
            assertTrue(texts(canvas).contains("⚑ Verify rounding at the discount boundary"))
            descendants(canvas).filterIsInstance<AbstractButton>().single { it.text == "Show changed lines" }.doClick()
            assertTrue(texts(canvas).any { it.contains("applyDiscount()") })

            descendants(canvas).filterIsInstance<JToggleButton>().single { it.text == "Outline" }.doClick()
            assertEquals(ChangeFlowsMode.OUTLINE, canvas.flowsView.mode)
            assertTrue(descendants(canvas).filterIsInstance<ChangeFlowMap>().isEmpty())
            assertTrue(texts(canvas).any { it.contains("applyDiscount()") })
            descendants(canvas).filterIsInstance<JToggleButton>().single { it.text == "Map" }.doClick()
            assertEquals(3, canvas.flowsView.selected[0])
            val interpreted = flows.annotate(FlowAnnotationUpdate(nodes = mapOf(3 to flows.nodeNotes.getValue(3).copy(summary = "Updated interpretation."))))
            canvas.showNotes(emptyList(), flows = interpreted)
            assertEquals(3, canvas.flowsView.selected[0])
            assertTrue(texts(canvas).contains("Updated interpretation."))
            assertEquals(3, canvas.flowsView.selected[0])
            paint(canvas)
            canvas.showNotes(emptyList(), flows = ChangeFlows(flows.graph.copy()))
            assertTrue(canvas.flowsView.selected.isEmpty())
            assertEquals(ChangeFlowsMode.MAP, canvas.flowsView.mode)
        } finally { com.intellij.openapi.util.Disposer.dispose(canvas) }
    }

    fun testMapPaintsAtSeveralZoomLevelsAndWritesPreview() {
        val flows = flows()
        for (zoom in listOf(0.6, 1.0, 1.8)) {
            val state = ChangeFlowsViewState().apply { reset(flows) }
            val panel = ChangeFlowsPanel(flows, state, (860 * zoom).toInt(), zoom,
                ChangeFlowActions({}, {}, {}, {}, {})).apply {
                isOpaque = true
                background = com.intellij.util.ui.UIUtil.getListBackground()
                size = preferredSize
            }
            layout(panel)
            val image = paint(panel)
            assertTrue(panel.width > 0 && panel.height > 0)
            if (zoom == 1.0) {
                val output = File("build/reports/change-flow-map-preview.png")
                output.parentFile.mkdirs()
                ImageIO.write(image, "png", output)
            }
        }
    }

    fun testLargeMapsDiscloseHiddenConnectionsAndRevealFollowedSteps() {
        val nodes = (0 until 30).map { id -> flows().graph.nodes.first().copy(id = id, name = "step$id", qualifiedName = "Flow.step$id") }
        val edges = (0 until 29).map { ChangeEdge(it, it + 1) }
        val flows = ChangeFlows(ChangeGraph(nodes, edges, listOf(ChangeCluster(0, nodes.indices.toList(), "Large flow")), emptyList()))
        val canvas = NoteCanvas({}, {}).apply { setSize(1200, 900) }
        try {
            canvas.showNotes(emptyList(), flows = flows)
            fun map() = descendants(canvas).filterIsInstance<ChangeFlowMap>().single()
            assertEquals(CHANGE_MAP_PREVIEW_STEPS, map().geometry.nodes.size)
            assertTrue(texts(canvas).any { it.contains("12 of 30 steps") })
            descendants(canvas).filterIsInstance<AbstractButton>().single { it.text == "Show all 30 steps (+18)" }.doClick()
            assertEquals(30, map().geometry.nodes.size)
            descendants(canvas).filterIsInstance<AbstractButton>().single { it.text == "Show fewer steps" }.doClick()
            assertEquals(12, map().geometry.nodes.size)
            canvas.flowsView.focus(flows, 28)
            canvas.showNotes(emptyList(), flows = flows)
            assertEquals(30, map().geometry.nodes.size)
            assertEquals(28, canvas.flowsView.selected[0])
            descendants(canvas).filterIsInstance<AbstractButton>().single { it.text == "Show fewer steps" }.doClick()
            assertEquals(12, map().geometry.nodes.size)
            assertTrue(canvas.flowsView.selected.isEmpty())
        } finally { com.intellij.openapi.util.Disposer.dispose(canvas) }
    }

    private fun descendants(container: Container): List<Component> = container.components.flatMap {
        listOf(it) + ((it as? Container)?.let(::descendants) ?: emptyList())
    }

    private fun texts(container: Container) = descendants(container).mapNotNull {
        (it as? JLabel)?.text ?: (it as? AbstractButton)?.text ?: (it as? JTextComponent)?.text
    }

    private fun layout(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach(::layout)
    }

    private fun paint(component: Component): BufferedImage {
        val image = BufferedImage(component.width.coerceAtLeast(1), component.height.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try { component.paint(graphics) } finally { graphics.dispose() }
        return image
    }
}
