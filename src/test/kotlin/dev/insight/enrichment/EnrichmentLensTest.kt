package dev.insight.enrichment

import dev.insight.graph.CallGraph
import dev.insight.graph.CallGraphEdge
import dev.insight.graph.CallGraphNode
import dev.insight.graph.DetailGraph
import dev.insight.graph.EdgeStyle
import dev.insight.graph.Emphasis
import dev.insight.graph.GraphDetail
import dev.insight.graph.RelationKind
import dev.insight.graph.SymbolKind
import dev.insight.graph.hierarchy
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrichmentLensTest {
    private val service = "/p/src/app/Service.kt"
    private val util = "/p/src/app/Util.kt"
    private val serviceTest = "/p/src/test/ServiceTest.kt"

    // Service.kt: 0 file, 1 class Service { 2 handle, 3 load, 4 report, 5 state } · Util.kt: 6 file, 7 format · ServiceTest.kt: 8 file, 9 testHandle
    private val nodes = listOf(
        CallGraphNode(0, "Service.kt", "", "", 0, true, SymbolKind.FILE, service),
        CallGraphNode(1, "Service", "", "", 0, true, SymbolKind.CLASS, service),
        CallGraphNode(2, "handle", "Service", "", 0, true, SymbolKind.FUNCTION, service),
        CallGraphNode(3, "load", "Service", "", 0, true, SymbolKind.FUNCTION, service),
        CallGraphNode(4, "report", "Service", "", 0, true, SymbolKind.FUNCTION, service),
        CallGraphNode(5, "state", "Service", "", 0, true, SymbolKind.PROPERTY, service),
        CallGraphNode(6, "Util.kt", "", "", 0, true, SymbolKind.FILE, util),
        CallGraphNode(7, "format", "", "", 0, true, SymbolKind.FUNCTION, util),
        CallGraphNode(8, "ServiceTest.kt", "", "", 0, true, SymbolKind.FILE, serviceTest),
        CallGraphNode(9, "testHandle", "", "", 0, true, SymbolKind.FUNCTION, serviceTest),
    )
    private fun call(from: Int, to: Int) = CallGraphEdge(from, to)
    private val graph = CallGraph(nodes, listOf(0 to 1, 1 to 2, 1 to 3, 1 to 4, 1 to 5, 6 to 7, 8 to 9)
        .mapTo(linkedSetOf()) { (from, to) -> CallGraphEdge(from, to, RelationKind.CONTAINS) } +
        listOf(call(2, 3), call(2, 4), call(2, 7), call(3, 7), call(4, 7), call(9, 2)), false, projectGraph = true)

    private fun choice(value: String, confidence: Double) = ChoiceValue(value, mapOf(value to confidence), confidence)
    private val annotations = mapOf(
        2 to NodeAnnotation(
            concerns = mapOf("network" to 0.9, "storage" to 0.7, "errors" to 0.65, "logging" to 0.62, "test" to 0.1, "ui" to 0.3),
            layer = choice("application", 0.9), importance = ScoreValue(4.0, 4, 0.7), entryPoint = 0.9, domain = choice("orders", 0.8)),
        3 to NodeAnnotation(concerns = mapOf("storage" to 0.8), phase = choice("test", 0.4), layer = choice("infrastructure", 0.7),
            importance = ScoreValue(2.0, 4, 0.5), entryPoint = 0.6, domain = choice("orders", 0.6)),
        4 to NodeAnnotation(layer = choice("domain", 0.8), importance = ScoreValue(1.0, 4, 0.9), domain = choice("billing", 1.0)),
        5 to NodeAnnotation(concerns = mapOf("test" to 0.6)),
        7 to NodeAnnotation(layer = choice("utility", 0.9), importance = ScoreValue(0.0, 4, 1.0), utility = 0.9, domain = choice(Domain.NONE, 0.5)),
        8 to NodeAnnotation(layer = choice("test", 0.5)),
        9 to NodeAnnotation(domain = choice("orders", 1.0)),
    )
    private val visibility = mapOf(0 to 0.15, 1 to 0.2, 2 to 0.9, 3 to 0.5, 4 to 0.3, 5 to 0.25, 6 to 0.05, 7 to 0.1, 8 to 0.01, 9 to 0.02)
    private val edgeAnnotations = mapOf(
        call(2, 3) to EdgeAnnotation(0.0, 0.9),
        call(2, 4) to EdgeAnnotation(0.8, 0.6),
        call(2, 7) to EdgeAnnotation(0.0, 0.2),
        call(3, 7) to EdgeAnnotation(0.1, 0.6),
        call(4, 7) to EdgeAnnotation(0.9, 0.0),
    )
    private val flows = listOf(
        Flow("f1", "Handle", null, 2, listOf(2, 1, 3, 7), setOf(call(2, 3), call(3, 7))),
        Flow("f2", "Test", null, 9, listOf(9, 2), setOf(call(9, 2))),
    )
    private val domains = listOf(Domain("orders", "Orders", ""), Domain("billing", "Billing", ""))

    private fun snapshot(of: CallGraph = graph): EnrichmentSnapshot {
        val facts = of.nodes.associate { node ->
            node.id to SymbolFacts(node.id, "${node.filePath}|${node.name}", node.kind, node.name, node.container,
                node.filePath?.removePrefix("/p/"), "kotlin", "", "", contentHash = "${node.id}")
        }
        val enrichment = Enrichment(annotations, mapOf(9 to Heuristics(setOf("test"))), emptyMap(), edgeAnnotations, domains, flows,
            mapOf("src/app" to GroupLabel("src/app", "Application core", "Handles orders.")), visibility, EnrichmentStats())
        return EnrichmentSnapshot(of, EnrichmentInput("demo", of, facts), enrichment, Path.of("report.json"))
    }

    private fun view(detail: GraphDetail, of: CallGraph = graph) = of.hierarchy().let { it.view(it.expansion(detail)) }
    private fun DetailGraph.id(name: String) = graph.nodes.single { it.name == name }.id
    private fun decorate(view: DetailGraph, options: LensOptions = LensOptions(), of: CallGraph = graph, from: EnrichmentSnapshot = snapshot()) =
        EnrichmentLens.decorate(from, of, view, options)

    @Test fun `hides tests and utilities but never the spotlighted flow`() {
        val tests = LensOptions(hideTests = true)
        // 9 by tag, 5 by test concern, 8 by layer; 3 has a test phase with too little confidence.
        assertEquals(setOf(5, 8, 9), EnrichmentLens.hiddenSymbols(snapshot(), graph, tests))
        assertEquals(setOf(7), EnrichmentLens.hiddenSymbols(snapshot(), graph, LensOptions(hideUtilities = true)))
        assertEquals(emptySet<Int>(), EnrichmentLens.hiddenSymbols(snapshot(), graph, LensOptions(hideUtilities = true, flowId = "f1")))
        // The test file holding the flow's entry stays too.
        assertEquals(setOf(5), EnrichmentLens.hiddenSymbols(snapshot(), graph, tests.copy(flowId = "f2")))
        assertEquals(emptySet<Int>(), EnrichmentLens.hiddenSymbols(snapshot(), graph, LensOptions()))
    }

    @Test fun `maps snapshot ids to a rebuilt graph by stable keys`() {
        // The rebuild adds a file in front, shifting every id by one, and an overload of load at the end.
        val shifted = CallGraph(
            listOf(CallGraphNode(0, "Aaa.kt", "", "", 0, true, SymbolKind.FILE, "/p/src/app/Aaa.kt")) +
                nodes.map { it.copy(id = it.id + 1) } + nodes[3].copy(id = 11),
            graph.edges.mapTo(linkedSetOf()) { it.copy(caller = it.caller + 1, callee = it.callee + 1) }, false, projectGraph = true)
        assertEquals(setOf(8), EnrichmentLens.hiddenSymbols(snapshot(), shifted, LensOptions(hideUtilities = true)))
        val remapped = Remapped.of(snapshot(), shifted)
        assertEquals(annotations[3], remapped.nodes[4])
        assertNull(remapped.nodes[11])
        assertEquals(listOf(3, 2, 4, 8), remapped.flows.first().nodes)
        assertEquals(edgeAnnotations[call(4, 7)], remapped.edges[call(5, 8)])

        assertEquals(listOf(3, 4, 8), EnrichmentLens.flowSteps(snapshot(), shifted, "f1"))
        assertEquals(listOf(2, 3, 7), EnrichmentLens.flowSteps(snapshot(), graph, "f1"))
        val renamed = graph.copy(nodes = nodes.map { if (it.id == 3) it.copy(name = "loadAll") else it })
        assertEquals(listOf(2, 7), EnrichmentLens.flowSteps(snapshot(), renamed, "f1"))
        assertEquals(emptyList<Int>(), EnrichmentLens.flowSteps(snapshot(), graph, "missing"))

        val view = view(GraphDetail.SYMBOL, shifted)
        val decorations = decorate(view, LensOptions(flowId = "f1"), shifted)
        val handle = decorations.nodes.getValue(view.id("handle"))
        assertEquals("NSE", handle.badges)
        assertEquals(1, handle.step)
        assertEquals(EdgeStyle.ERROR, decorations.edges[CallGraphEdge(view.id("handle"), view.id("report"))])
    }

    @Test fun `symbols get colour, confidence, badges, entry points and judgments`() {
        val view = view(GraphDetail.SYMBOL)
        val decorations = decorate(view)
        val handle = decorations.nodes.getValue(view.id("handle"))
        val orders = decorations.legend.first().color
        assertEquals(listOf(orders to 1.0), handle.colors)
        assertEquals(0.8, handle.confidence, 1e-9)
        assertEquals("NSE", handle.badges)
        assertTrue(handle.entryPoint)
        assertEquals("domain: Orders · layer: application · importance: 4.0/4 · concerns: network, storage, errors, logging", handle.tooltip)
        assertFalse(decorations.nodes.getValue(view.id("load")).entryPoint)
        assertEquals(listOf("Orders", "Billing", "None"), decorations.legend.map { it.label })

        val app = decorations.nodes.getValue(view.id("app/"))
        assertEquals("Application core", app.label)
        assertEquals("Handles orders.", app.tooltip)

        val layers = decorate(view, LensOptions(colorBy = ColorBy.LAYER)).legend.map { it.label }
        assertEquals(listOf("Application", "Domain", "Infrastructure", "Utility", "Test"), layers)
        val importance = decorate(view, LensOptions(colorBy = ColorBy.IMPORTANCE))
        assertEquals(listOf("Trivial", "Minor", "Supporting", "Core"), importance.legend.map { it.label })
        assertEquals(0.7, importance.nodes.getValue(view.id("handle")).confidence, 1e-9)
        assertTrue(decorate(view, LensOptions(colorBy = ColorBy.NONE)).let { none ->
            none.legend.isEmpty() && none.nodes.values.all { it.colors.isEmpty() }
        })
    }

    @Test fun `groups mix the colours of their enriched members`() {
        val top = graph.hierarchy().view(emptySet())
        val decorations = decorate(top)
        val (orders, billing, none) = decorations.legend.map { it.color }
        val app = decorations.nodes.getValue(top.id("app/"))
        assertEquals(listOf(orders to 2.0, billing to 1.0, none to 1.0), app.colors)
        assertEquals((0.8 + 0.6 + 1.0 + 0.5) / 4, app.confidence, 1e-9)
        assertEquals(listOf(orders to 1.0), decorations.nodes.getValue(top.id("test/")).colors)
        // Hidden tests drop out of the mix and the legend.
        val excluded = EnrichmentLens.hiddenSymbols(snapshot(), graph, LensOptions(hideTests = true))
        val withoutTests = graph.hierarchy().view(emptySet(), excluded)
        assertEquals(listOf("app/"), withoutTests.graph.nodes.map { it.name })
        assertEquals(listOf("Orders", "Billing", "None"), decorate(withoutTests).legend.map { it.label })
    }

    @Test fun `emphasis follows the visibility percentile and groups take their strongest member`() {
        val view = view(GraphDetail.SYMBOL)
        val decorations = decorate(view)
        fun emphasis(name: String) = decorations.nodes[view.id(name)]?.emphasis ?: Emphasis.NORMAL
        assertEquals(Emphasis.STRONG, emphasis("handle"))
        assertEquals(Emphasis.NORMAL, emphasis("load"))
        assertEquals(Emphasis.NORMAL, emphasis("report"))
        assertEquals(Emphasis.FAINT, emphasis("format"))
        assertEquals(Emphasis.FAINT, emphasis("testHandle"))
        assertEquals(Emphasis.STRONG, emphasis("app/"))
        assertEquals(Emphasis.STRONG, emphasis("Service"))
        assertEquals(Emphasis.FAINT, emphasis("test/"))
    }

    @Test fun `call edges are styled from their underlying calls and filtered`() {
        val view = view(GraphDetail.SYMBOL)
        fun edge(from: String, to: String) = CallGraphEdge(view.id(from), view.id(to))
        val decorations = decorate(view)
        assertEquals(mapOf(
            edge("handle", "load") to EdgeStyle.MAIN, edge("handle", "report") to EdgeStyle.ERROR,
            edge("handle", "format") to EdgeStyle.INCIDENTAL, edge("load", "format") to EdgeStyle.MAIN,
            edge("report", "format") to EdgeStyle.ERROR,
        ), decorations.edges)
        assertTrue(decorations.hiddenEdges.isEmpty())
        assertEquals(setOf(edge("handle", "report"), edge("handle", "format"), edge("report", "format")),
            decorate(view, LensOptions(edges = EdgeFilter.MAIN_PATH)).hiddenEdges)
        assertEquals(setOf(edge("handle", "load"), edge("handle", "format"), edge("load", "format"), edge("testHandle", "handle")),
            decorate(view, LensOptions(edges = EdgeFilter.ERROR_PATHS)).hiddenEdges)

        // Service.kt → Util.kt merges an incidental, a main and an error call.
        val files = view(GraphDetail.FILE)
        val merged = decorate(files, LensOptions(edges = EdgeFilter.ERROR_PATHS))
        val serviceToUtil = CallGraphEdge(files.id("Service.kt"), files.id("Util.kt"))
        assertEquals(EdgeStyle.MAIN, merged.edges[serviceToUtil])
        assertTrue(serviceToUtil in merged.hiddenEdges)
        assertTrue(merged.hiddenEdges.all { it.kind == RelationKind.CALL })
    }

    @Test fun `spotlights a flow and numbers its shown functions`() {
        val view = view(GraphDetail.SYMBOL)
        val decorations = decorate(view, LensOptions(flowId = "f1", edges = EdgeFilter.ERROR_PATHS))
        assertEquals(listOf("handle", "Service", "load", "format").map { view.id(it) }.toSet(), decorations.spotlight)
        val flowEdges = setOf(CallGraphEdge(view.id("handle"), view.id("load")), CallGraphEdge(view.id("load"), view.id("format")))
        assertEquals(flowEdges, decorations.spotlightEdges)
        assertTrue(decorations.hiddenEdges.none { it in flowEdges })
        assertEquals(mapOf("handle" to 1, "load" to 2, "format" to 3),
            decorations.nodes.filterValues { it.step != null }.entries.associate { view.graph.nodes[it.key].name to it.value.step })

        val files = view(GraphDetail.FILE)
        val folded = decorate(files, LensOptions(flowId = "f1"))
        assertEquals(setOf(files.id("Service.kt"), files.id("Util.kt")), folded.spotlight)
        assertEquals(setOf(CallGraphEdge(files.id("Service.kt"), files.id("Util.kt"))), folded.spotlightEdges)
        assertTrue(folded.nodes.values.none { it.step != null })
        assertNull(decorate(files).spotlight)
    }

    @Test fun `lifted initializer calls retain styles filters and flow highlights`() {
        val local = CallGraphNode(10, "panel", "handle", "", 0, true, SymbolKind.VARIABLE, service)
        val original = graph.copy(nodes = nodes + local, edges = graph.edges - call(2, 7) +
            CallGraphEdge(2, 10, RelationKind.CONTAINS) + call(10, 7))
        for (style in listOf(EdgeStyle.ERROR, EdgeStyle.MAIN)) {
            val annotation = if (style == EdgeStyle.ERROR) EdgeAnnotation(0.9, 0.0) else EdgeAnnotation(0.0, 0.9)
            val base = snapshot(original)
            val enriched = EnrichmentSnapshot(original, base.input, base.enrichment.copy(
                edges = mapOf(call(2, 7) to annotation),
                flows = listOf(Flow("local", "Initialize", null, 2, listOf(2, 7), setOf(call(2, 7)))),
            ), base.report)
            val tree = original.hierarchy()
            // Exercise both a folded function and its expanded local declaration.
            for (expanded in listOf(tree.expansion(GraphDetail.SYMBOL) - 2, tree.expansion(GraphDetail.SYMBOL))) {
                val shown = tree.view(expanded)
                val edge = CallGraphEdge(shown.shownId(10)!!, shown.shownId(7)!!)
                val options = LensOptions(edges = EdgeFilter.ERROR_PATHS)
                val decorations = decorate(shown, options, original, enriched)
                assertEquals(style, decorations.edges[edge])
                assertEquals(style != EdgeStyle.ERROR, edge in decorations.hiddenEdges)
                val spotlight = decorate(shown, options.copy(flowId = "local"), original, enriched)
                assertEquals(setOf(edge), spotlight.spotlightEdges)
                assertFalse(edge in spotlight.hiddenEdges)
            }
            assertEquals(listOf(2, 7), EnrichmentLens.flowSteps(enriched, original, "local"))
        }
    }

    @Test fun `dispatch spotlight follows the reverse displayed inheritance relationship`() {
        val original = graph.copy(edges = graph.edges + CallGraphEdge(3, 2, RelationKind.INHERITS))
        val base = snapshot(original)
        val enriched = EnrichmentSnapshot(original, base.input, base.enrichment.copy(flows = listOf(
            Flow("dispatch", "Dispatch", null, 2, listOf(2, 3), setOf(CallGraphEdge(2, 3, RelationKind.INHERITS))),
        )), base.report)
        val shown = view(GraphDetail.SYMBOL, original)
        val decorations = decorate(shown, LensOptions(flowId = "dispatch"), original, enriched)
        assertEquals(setOf(CallGraphEdge(shown.id("load"), shown.id("handle"), RelationKind.INHERITS)),
            decorations.spotlightEdges)
    }
}
