package dev.insight.enrichment

import dev.insight.callgraph.SectionMetrics
import dev.insight.callgraph.SectionText
import dev.insight.callgraph.layoutSections
import dev.insight.graph.CallGraph
import dev.insight.graph.CallGraphEdge
import dev.insight.graph.CallGraphNode
import dev.insight.graph.DetailGraph
import dev.insight.graph.GraphDetail
import dev.insight.graph.GraphSections
import dev.insight.graph.RelationKind
import dev.insight.graph.Section
import dev.insight.graph.SectionFooter
import dev.insight.graph.SymbolKind
import dev.insight.graph.hierarchy
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionLensTest {
    private val service = "/p/src/app/Service.kt"
    private val util = "/p/src/app/util/Util.kt"
    private val serviceTest = "/p/src/test/ServiceTest.kt"

    // Service.kt: 0 file, 1 class Service { 2 handle, 3 load } · Util.kt: 4 file, 5 format · ServiceTest.kt: 6 file, 7 testHandle
    private val nodes = listOf(
        CallGraphNode(0, "Service.kt", "", "", 0, true, SymbolKind.FILE, service),
        CallGraphNode(1, "Service", "", "", 0, true, SymbolKind.CLASS, service),
        CallGraphNode(2, "handle", "Service", "", 0, true, SymbolKind.FUNCTION, service),
        CallGraphNode(3, "load", "Service", "", 0, true, SymbolKind.FUNCTION, service),
        CallGraphNode(4, "Util.kt", "", "", 0, true, SymbolKind.FILE, util),
        CallGraphNode(5, "format", "", "", 0, true, SymbolKind.FUNCTION, util),
        CallGraphNode(6, "ServiceTest.kt", "", "", 0, true, SymbolKind.FILE, serviceTest),
        CallGraphNode(7, "testHandle", "", "", 0, true, SymbolKind.FUNCTION, serviceTest),
    )
    private fun call(from: Int, to: Int) = CallGraphEdge(from, to)
    private val graph = CallGraph(nodes, listOf(0 to 1, 1 to 2, 1 to 3, 4 to 5, 6 to 7)
        .mapTo(linkedSetOf()) { (from, to) -> CallGraphEdge(from, to, RelationKind.CONTAINS) } +
        listOf(call(2, 3), call(2, 5), call(3, 5), call(7, 2)), false, projectGraph = true)
    private val tree = graph.hierarchy()

    private fun choice(value: String) = ChoiceValue(value, mapOf(value to 0.9), 0.9)
    private val annotations = mapOf(
        2 to NodeAnnotation(layer = choice("application"), domain = choice("orders"), entryPoint = 0.9, importance = ScoreValue(4.0, 4, 0.8)),
        3 to NodeAnnotation(layer = choice("infrastructure"), domain = choice("orders"), importance = ScoreValue(2.0, 4, 0.8)),
        5 to NodeAnnotation(layer = choice("utility"), domain = choice(Domain.NONE), importance = ScoreValue(0.0, 4, 0.8)),
        7 to NodeAnnotation(layer = choice("test")),
    )
    private val domains = listOf(Domain("orders", "Orders", "Takes and stores orders."))

    private fun snapshot(flows: List<Flow> = listOf(Flow("f1", "Handle order", "Handles an order end to end.", 2, listOf(2, 3, 5), emptySet()))): EnrichmentSnapshot {
        val facts = graph.nodes.associate { node ->
            node.id to SymbolFacts(node.id, "${node.filePath}|${node.name}", node.kind, node.name, node.container,
                node.filePath?.removePrefix("/p/"), "kotlin", "", "", contentHash = "${node.id}")
        }
        val enrichment = Enrichment(annotations, emptyMap(), emptyMap(), emptyMap(), domains, flows,
            mapOf("src/app/Service.kt" to GroupLabel("src/app/Service.kt", "Order service", "Handles incoming orders.")),
            emptyMap(), EnrichmentStats())
        return EnrichmentSnapshot(graph, EnrichmentInput("demo", graph, facts), enrichment, Path.of("report.json"))
    }

    private val symbols: DetailGraph = tree.view(tree.expansion(GraphDetail.SYMBOL))
    private fun DetailGraph.id(name: String) = graph.nodes.single { it.name == name }.id
    private fun GraphSections.of(name: String) = sections[sectionOf.getValue(symbols.id(name))]

    @Test fun `groups symbols by file without enrichment and leaves expanded containers out`() {
        val result = SectionLens.sections(tree, symbols, SectionBy.FILE, null, "demo")
        assertEquals(setOf("Service.kt", "Util.kt", "ServiceTest.kt"), result.sections.map { it.title }.toSet())
        // Users left of what they use: the test calls the service, which calls the utility.
        assertEquals(listOf("ServiceTest.kt", "Service.kt", "Util.kt"), result.sections.map { it.title })
        assertEquals("Service.kt", result.of("handle").title)
        assertEquals("Service.kt", result.of("Service").title)
        for (file in listOf("Service.kt", "Util.kt", "ServiceTest.kt", "app/", "util/")) {
            symbols.graph.nodes.firstOrNull { it.name == file }?.let { assertNull(file, result.sectionOf[it.id]) }
        }
        val serviceSection = result.of("handle")
        assertEquals("app/Service.kt", serviceSection.description)
        assertEquals(listOf("3 symbols", "calls 2 out · 1 in"), serviceSection.facts)
        assertEquals("demo · by file", result.title)
        assertEquals("Calls between sections", result.footer?.title)
        assertEquals(FooterSample("Service.kt → Util.kt", "2 calls"), result.footer!!.items.first().let { FooterSample(it.headline, it.text) })
    }

    @Test fun `file sections use group labels and judgments and the footer lists flows`() {
        val result = SectionLens.sections(tree, symbols, SectionBy.FILE, snapshot(), "demo")
        val serviceSection = result.of("handle")
        assertEquals("Order service", serviceSection.headline)
        assertEquals("Handles incoming orders.", serviceSection.description)
        assertTrue(serviceSection.facts.toString(), "1 entry point" in serviceSection.facts)
        assertTrue(serviceSection.facts.toString(), "mostly Orders" in serviceSection.facts)
        assertNotNull(serviceSection.color)
        assertEquals("Flows", result.footer?.title)
        assertEquals("Handle order", result.footer!!.items.single().headline)
    }

    @Test fun `groups by enrichment judgments with descriptions and fixed orders`() {
        val byDomain = SectionLens.sections(tree, symbols, SectionBy.DOMAIN, snapshot(), "demo")
        assertEquals("Orders", byDomain.of("handle").title)
        assertEquals("Takes and stores orders.", byDomain.of("handle").description)
        assertEquals("in 1 file", byDomain.of("handle").facts[1])
        // The class has no judgment of its own and goes with its methods.
        assertEquals("Orders", byDomain.of("Service").title)
        assertEquals("Unclassified", byDomain.of("testHandle").title)
        assertEquals("Orders", byDomain.sections.first().title)
        assertEquals(setOf("Orders", "Unclassified", "Cross-cutting"), byDomain.sections.map { it.title }.toSet())

        val byLayer = SectionLens.sections(tree, symbols, SectionBy.LAYER, snapshot(), "demo")
        assertEquals(listOf("Test", "Application", "Infrastructure", "Utility"), byLayer.sections.map { it.title })
        assertTrue(byLayer.of("format").description!!.startsWith("Generic helpers"))

        val byImportance = SectionLens.sections(tree, symbols, SectionBy.IMPORTANCE, snapshot(), "demo")
        assertEquals(listOf("Core", "Supporting", "Trivial", "Unclassified"), byImportance.sections.map { it.title })

        val notEnriched = SectionLens.sections(tree, symbols, SectionBy.LAYER, null, "demo")
        assertEquals(listOf("Not enriched"), notEnriched.sections.map { it.title })
        assertTrue(notEnriched.subtitle!!.contains("use Enrich"))
    }

    @Test fun `collapsed folders become their own section`() {
        val overview = tree.view(emptySet())
        val result = SectionLens.sections(tree, overview, SectionBy.FILE, null, "demo")
        assertEquals(overview.graph.nodes.size, result.sectionOf.size)
        assertTrue(result.sections.all { it.description!!.startsWith("Folder (collapsed)") })
    }

    @Test fun `layout puts sections side by side with aligned bodies`() {
        val sections = SectionLens.sections(tree, symbols, SectionBy.FILE, null, "demo")
        val text = object : SectionText {
            override fun banner(sections: GraphSections, width: Int) = 40
            override fun title() = 20
            override fun annotation(section: Section, width: Int) = if (section.title == "Service.kt") 50 else 30
            override fun footer(footer: SectionFooter, width: Int) = 60
        }
        val metrics = SectionMetrics(nodeWidth = 100, nodeHeight = 20, columnGap = 30, rowGap = 10, padding = 20, sectionGap = 5, margin = 10, maxRows = 2)
        val layout = layoutSections(symbols.graph, sections, metrics, text)
        val frames = layout.sections!!
        assertEquals(3, frames.columns.size)
        assertEquals(50, frames.annotationHeight)
        assertTrue(frames.columns.zipWithNext().all { (a, b) -> a.maxX + metrics.sectionGap == b.x.toDouble() && a.height == b.height })
        assertEquals(sections.sectionOf.keys, layout.boxes.keys)
        for ((id, section) in sections.sectionOf) {
            val box = layout.boxes.getValue(id)
            assertTrue(frames.columns[section].contains(box))
            assertTrue(box.y >= frames.columns[section].y + frames.titleHeight + frames.annotationHeight)
        }
        // Service is used by its methods' container edge, so handle and load sit in a column right of it.
        val service = layout.boxes.getValue(symbols.id("Service"))
        assertTrue(layout.boxes.getValue(symbols.id("handle")).x > service.x)
        val footer = frames.footer!!
        assertEquals(frames.columns.first().maxY.toInt() + metrics.sectionGap, footer.y)
        assertEquals(footer.maxY.toInt() + metrics.margin, layout.size.height)
        assertFalse(layout.boxes.values.any { a -> layout.boxes.values.any { b -> a !== b && a.intersects(b) } })
    }

    private data class FooterSample(val headline: String, val text: String?)
}
