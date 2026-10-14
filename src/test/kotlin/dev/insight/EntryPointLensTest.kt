package dev.insight

import dev.insight.callgraph.*
import dev.insight.entrypoints.EntryPoint
import dev.insight.entrypoints.EntryPointKind
import org.junit.Assert.*
import org.junit.Test
import java.awt.Color

class EntryPointLensTest {
    private fun node(id: Int, line: Int, path: String = "/p/src/App.kt", name: String = "main()") =
        CallGraphNode(id, name, "App", "App.kt:$line", 0, true, filePath = path)
    private fun entry(line: Int = 10, symbol: String = "App.main", path: String = "src/App.kt") =
        EntryPoint(path, line, symbol, EntryPointKind.APPLICATION, "Launch application", "Starts the server", 0.9)
    private fun decorate(nodes: List<CallGraphNode>, entries: List<EntryPoint> = listOf(entry())) =
        EntryPointLens.decorate(GraphDecorations.NONE, CallGraph(nodes, emptySet(), false), entries, "/p")

    @Test fun `source line disambiguates overloads and identical names in other files`() {
        val result = decorate(listOf(node(0, 10), node(1, 20), node(2, 10, "/p/other/App.kt")))
        assertEquals(setOf(0), result.nodes.keys)
        assertTrue(result.nodes.getValue(0).entryPoint)
        assertEquals(Emphasis.STRONG, result.nodes.getValue(0).emphasis)
        assertTrue(result.nodes.getValue(0).tooltip!!.contains("Starts the server"))
    }

    @Test fun `unique qualified symbol matches when discovery points inside the body`() {
        assertTrue(decorate(listOf(node(0, 10)), listOf(entry(12))).nodes.getValue(0).entryPoint)
        assertTrue(decorate(listOf(node(0, 10)), listOf(entry(path = "./src/../src/App.kt"))).nodes.getValue(0).entryPoint)
    }

    @Test fun `ambiguous symbol does not mark unrelated functions`() {
        assertTrue(decorate(listOf(node(0, 10), node(1, 20)), listOf(entry(15))).nodes.isEmpty())
        assertTrue(decorate(listOf(node(0, 10, name = "helper()")), listOf(entry(15))).nodes.isEmpty())
    }

    @Test fun `collapsed entrypoints merge with existing enrichment and diff decorations`() {
        val graph = CallGraph(listOf(node(0, 10), node(1, 20, name = "serve()")), emptySet(), false)
        val old = NodeDecoration(colors = listOf(Color.ORANGE to 1.0), badges = "Δ", tooltip = "Enrichment", step = 2)
        val base = GraphDecorations(nodes = mapOf(7 to old), spotlight = setOf(7))
        val result = EntryPointLens.decorate(base, graph, listOf(entry(), entry(20, "serve")), "/p") { 7 }
        val start = result.nodes.getValue(7)
        assertTrue(start.entryPoint)
        assertEquals(old.colors, start.colors)
        assertEquals("Δ", start.badges)
        assertEquals(old.step, start.step)
        assertEquals(base.spotlight, result.spotlight)
        assertTrue(start.tooltip!!.startsWith("Enrichment\n"))
        assertTrue(start.tooltip!!.contains("src/App.kt:20"))
        assertTrue(EntryPointLens.decorate(base, graph, listOf(entry()), "/p") { null } === base)
    }

    @Test fun `file entrypoints remain visible without a graph symbol`() {
        val file = node(0, 1).copy(kind = SymbolKind.FILE, name = "App.kt")
        assertTrue(decorate(listOf(file), listOf(entry(symbol = ""))).nodes.getValue(0).entryPoint)
        assertTrue(decorate(listOf(file), listOf(entry(symbol = ""))).nodes.getValue(0).tooltip!!.contains("Contains entry point"))
        assertTrue(decorate(listOf(node(0, 10).copy(inProject = false))).nodes.isEmpty())
        assertTrue(decorate(listOf(node(0, 10)), emptyList()).nodes.isEmpty())
    }
}
