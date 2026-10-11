package dev.insight.enrichment

import dev.insight.callgraph.CallGraph
import dev.insight.callgraph.CallGraphNode
import dev.insight.callgraph.SymbolKind
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TaxonomyDiscoveryTest {
    private val prompts = mutableListOf<CompletionRequest>()

    private fun model(reply: String): suspend (CompletionRequest) -> Completion = { prompts += it; Completion(reply) }

    private val input = EnrichmentInput("insight", CallGraph(
        listOf(CallGraphNode(0, "Graph.kt", "", "", 0, true, SymbolKind.FILE), CallGraphNode(1, "GraphLayout", "", "", 1, true, SymbolKind.CLASS)),
        emptySet(), false,
    ), mapOf(
        0 to SymbolFacts(0, "k0", SymbolKind.FILE, "Graph.kt", "", "src/Graph.kt", "kotlin", "", "", contentHash = "a"),
        1 to SymbolFacts(1, "k1", SymbolKind.CLASS, "GraphLayout", "", "src/Graph.kt", "kotlin", "class GraphLayout", "", contentHash = "b"),
    ))

    private val labels = mapOf(
        "src" to GroupLabel("src", "Plugin sources", null),
        "src/Graph.kt" to GroupLabel("src/Graph.kt", "Call graph layout", "Places graph nodes."),
    )

    @Test fun `validates, slugifies and deduplicates domains`() = runBlocking {
        val domains = TaxonomyDiscovery(model("""{"domains":[
            {"id":"Call Graph","name":"Call graph analysis","description":"Builds call graphs."},
            {"id":"call-graph","name":"Graph layout"},
            {"id":"none","name":"Chat assistant","description":"Answers questions."},
            {"id":"","name":"   "},
            "junk"
        ]}"""), InMemoryEnrichmentCache()).discover(input, labels)
        assertEquals(listOf("call-graph", "call-graph-2", "none-2"), domains.map { it.id })
        assertEquals("Graph layout", domains[1].description)
        val user = prompts.single().messages.last().content
        // Directories come before files.
        assertTrue(user, user.indexOf("src: Plugin sources") < user.indexOf("src/Graph.kt: Call graph layout — Places graph nodes."))
    }

    @Test fun `falls back to paths and declarations without labels, and caches the answer`() = runBlocking {
        val cache = InMemoryEnrichmentCache()
        val reply = """{"domains":[{"id":"layout","name":"Layout","description":"Layout."}]}"""
        assertEquals(1, TaxonomyDiscovery(model(reply), cache).discover(input, emptyMap()).size)
        assertTrue(prompts.single().messages.last().content.contains("src/Graph.kt: GraphLayout"))
        assertEquals(listOf("layout"), TaxonomyDiscovery({ throw IOException("not cached") }, cache).discover(input, emptyMap()).map { it.id })
    }

    @Test fun `returns no domains when the model fails`() = runBlocking {
        assertEquals(emptyList<Domain>(), TaxonomyDiscovery({ throw IOException("down") }, InMemoryEnrichmentCache()).discover(input, labels))
        assertEquals(emptyList<Domain>(), TaxonomyDiscovery(model("I cannot"), InMemoryEnrichmentCache()).discover(input, labels))
    }
}
