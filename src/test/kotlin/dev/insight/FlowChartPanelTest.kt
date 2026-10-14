package dev.insight

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SearchTextField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.SmartPointerManager
import com.intellij.util.ui.UIUtil
import dev.insight.callgraph.*
import java.awt.Container
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JTabbedPane
import dev.insight.chat.ChatPanel
import com.intellij.testFramework.PlatformTestUtil
import java.awt.Point

class FlowChartPanelTest : BasePlatformTestCase() {
    private fun result(shift: Int = 0): CallGraphResult {
        val names = List(shift) { "padding$it" } + listOf("handleRequest", "loadData", "renderPage")
        val nodes = names.mapIndexed { id, name ->
            CallGraphNode(id, name, "Service", "service.kt:$id", 0, true, SymbolKind.FUNCTION, "/service.kt")
        }
        return CallGraphResult(CallGraph(nodes, linkedSetOf(CallGraphEdge(shift, shift + 1), CallGraphEdge(shift, shift + 2)), false,
            projectGraph = true), List(nodes.size) { null })
    }

    private fun controls(container: Container): List<java.awt.Component> = container.components.flatMap {
        listOf(it) + if (it is Container) controls(it) else emptyList()
    }

    private fun button(panel: FlowChartPanel, label: String) = controls(panel).filterIsInstance<JButton>().single { it.text == label }

    fun `test To chat opens independent tabs that can be closed separately`() {
        val panel = FlowChartPanel(project, {}, {}, {}, {}, {}, { false })
        try {
            panel.showResult(result())
            panel.addToChart(FlowStart(0))
            val tabs = controls(panel).filterIsInstance<JTabbedPane>().single()
            fun open(node: Int, count: Int) {
                val bounds = panel.flowCanvas.geometry!!.cards.getValue(node).bounds
                panel.flowCanvas.contextMenuAt(Point(bounds.centerX.toInt(), bounds.centerY.toInt()))!!
                    .components.filterIsInstance<JMenuItem>().single { it.text == "To chat" }.doClick()
                PlatformTestUtil.waitWithEventsDispatching("Scoped chat should open", { tabs.tabCount == count }, 5000)
            }
            open(1, 2)
            val first = tabs.selectedComponent as ChatPanel
            first.input.text = "Explain loading"
            open(2, 3)
            val second = tabs.selectedComponent as ChatPanel
            assertNotSame(first, second)
            assertEquals("", second.input.text)
            second.input.text = "Explain rendering"
            assertEquals("Explain loading", first.input.text)
            controls(tabs.getTabComponentAt(1) as Container).filterIsInstance<JButton>().single { it.text == "×" }.doClick()
            assertEquals(2, tabs.tabCount)
            assertSame(second, tabs.selectedComponent)
            assertEquals("Explain rendering", second.input.text)
            panel.showResult(result(2))
            assertSame(second, tabs.selectedComponent)
        } finally { panel.dispose() }
    }

    fun `test chart controls operate without enrichment and survive rebuilds`() {
        var activated = 0
        val panel = FlowChartPanel(project, { activated++ }, {}, {}, {}, {}, { false })
        try {
            panel.showResult(result())
            panel.addToChart(FlowStart(0))
            assertEquals(1, activated)
            val chart = panel.flowCanvas
            assertEquals(listOf(0, 1, 2), chart.chart!!.steps.map { it.node })
            assertTrue(button(panel, "Hide calls").isEnabled)
            assertFalse(button(panel, "Show callers").isEnabled)
            assertTrue(button(panel, "Remove column").isEnabled)
            button(panel, "Hide calls").doClick()
            assertEquals(listOf(0), chart.chart!!.steps.map { it.node })
            button(panel, "Show calls").doClick()
            chart.selectNode(1)
            assertFalse(button(panel, "Remove column").isEnabled)
            chart.zoom = 1.5
            panel.showResult(result(2))
            assertEquals(listOf(2, 3, 4), chart.chart!!.steps.map { it.node })
            assertEquals(3, chart.selectedNode)
            assertEquals(1.5, chart.zoom)
            button(panel, "Clear chart").doClick()
            assertNull(chart.chart)
            assertFalse(button(panel, "Clear chart").isEnabled)
        } finally { panel.dispose() }
    }

    fun `test chart search matches visible steps and keeps context in independent panels`() {
        val first = FlowChartPanel(project, {}, {}, {}, {}, {}, { false })
        val second = FlowChartPanel(project, {}, {}, {}, {}, {}, { false })
        try {
            for (panel in listOf(first, second)) {
                panel.showResult(result())
                panel.addToChart(FlowStart(0))
            }
            val search = controls(first).filterIsInstance<SearchTextField>().single()
            search.text = "lD"
            assertEquals(setOf(1), first.flowCanvas.searchMatches)
            assertEquals(listOf(0, 1, 2), first.flowCanvas.chart!!.steps.map { it.node })
            button(first, "Next").doClick()
            assertEquals(1, first.flowCanvas.selectedNode)
            assertEquals(0, second.flowCanvas.selectedNode)
            assertNull(second.flowCanvas.searchMatches)
            first.flowCanvas.toggleCalls(0)
            assertEquals(emptySet<Int>(), first.flowCanvas.searchMatches)
            assertFalse(button(first, "Next").isEnabled)
            first.flowCanvas.toggleCalls(0)
            assertEquals(setOf(1), first.flowCanvas.searchMatches)
            search.text = ""
            assertNull(first.flowCanvas.searchMatches)
        } finally { first.dispose(); second.dispose() }
    }

    fun `test editor additions wait for a graph rebuild and land in the chart`() {
        val file = myFixture.configureByText("Service.java", "class Service { void handleRequest() {} }") as PsiJavaFile
        val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer<com.intellij.psi.PsiElement>(file.classes.single().methods.single())
        var building = true
        val panel = FlowChartPanel(project, {}, {}, {}, {}, {}, { building })
        try {
            panel.addToChart(pointer)
            assertTrue(panel.flowCanvas.board.starts.isEmpty())
            val graph = result().graph
            val nodes = graph.nodes.map { it.copy(filePath = file.virtualFile.path) }
            building = false
            panel.showResult(CallGraphResult(graph.copy(nodes = nodes), listOf(pointer, null, null)))
            UIUtil.dispatchAllInvocationEvents()
            assertEquals(listOf(FlowStart(0)), panel.flowCanvas.board.starts)
        } finally { panel.dispose() }
    }

    fun `test shared analysis actions route to their owner`() {
        val actions = mutableListOf<String>()
        val panel = FlowChartPanel(project, {}, { actions += "graph:$it" }, { actions += "refresh" },
            { actions += "enrich" }, { actions += "report" }, { false })
        try {
            panel.showResult(result())
            button(panel, "Refresh").doClick()
            button(panel, "Enrich").doClick()
            assertEquals(listOf("refresh", "enrich"), actions)
            assertFalse(button(panel, "Report").isEnabled)
        } finally { panel.dispose() }
    }
}
