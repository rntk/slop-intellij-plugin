package dev.insight

import dev.insight.callgraph.*
import dev.insight.entrypoints.EntryPoint
import dev.insight.entrypoints.EntryPointKind
import dev.insight.enrichment.InMemoryEnrichmentCache
import dev.insight.llm.Completion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ApplicationFlowsTest {
    private fun graph(names: List<String>, links: List<Pair<Int, Int>>) = CallGraph(
        names.mapIndexed { id, name -> CallGraphNode(id, name, "App", "App.kt:${id + 1}", 0, true, filePath = "/p/App.kt") },
        links.mapTo(linkedSetOf()) { CallGraphEdge(it.first, it.second) }, false, true)
    private fun entry(id: Int = 0) = EntryPoint("App.kt", id + 1, "f$id", EntryPointKind.APPLICATION,
        "Start $id", "", 1.0)
    private fun splitter(answer: String, maxNodes: Int = 120) = ApplicationFlowSplitter(
        { Completion(answer) }, InMemoryEnrichmentCache(), maxNodes)

    @Test fun `groups operations and preserves shared helpers without swallowing siblings through cycles`() = runBlocking {
        val graph = graph((0..5).map { "f$it" }, listOf(0 to 1, 0 to 2, 1 to 3, 2 to 3, 3 to 0, 2 to 4, 4 to 5))
        val result = splitter("""{"flows":[{"title":"Load","description":"Load data","branches":[1]},
            {"title":"Save","description":"Save data","branches":[2]}]}""")
            .split(graph, listOf(entry()), "/p")
        assertEquals(setOf(0, 1, 3), result.flows[0].nodes)
        assertEquals(setOf(0, 2, 3, 4, 5), result.flows[1].nodes)
        assertTrue(result.flows.all { it.inferred && !it.limited })
    }

    @Test fun `rejects invented and repeated branch IDs and covers omissions structurally`() = runBlocking {
        val result = splitter("""{"flows":[{"title":"Invented","branches":[99]},
            {"title":"Valid","branches":[1]}, {"title":"Duplicate","branches":[1,2]}]}""")
            .split(graph(listOf("f0", "f1", "f2"), listOf(0 to 1, 0 to 2)), listOf(entry()), "/p")
        assertEquals(2, result.flows.size)
        assertEquals(setOf(0, 1), result.flows[0].nodes)
        assertEquals(setOf(0, 2), result.flows[1].nodes)
        assertFalse(result.flows[1].inferred)
    }

    @Test fun `model failure falls back and other entrypoints stop traversal`() = runBlocking {
        val splitter = ApplicationFlowSplitter({ throw IOException("Offline") }, InMemoryEnrichmentCache())
        val result = splitter.split(graph((0..3).map { "f$it" }, listOf(0 to 1, 1 to 2, 2 to 3)),
            listOf(entry(), entry(2)), "/p")
        assertEquals(setOf(0, 1, 2), result.flows[0].nodes)
        assertEquals(setOf(2, 3), result.flows[1].nodes)
        assertEquals(2, result.failures)
    }

    @Test fun `reports unmatched file entrypoints and retains isolated starts`() = runBlocking {
        val file = graph(listOf("App.kt"), emptyList()).nodes[0].copy(kind = SymbolKind.FILE)
        assertEquals(listOf(entry()), splitter("{}").split(CallGraph(listOf(file), emptySet(), false), listOf(entry()), "/p").unmatched)
        val result = splitter("{}").split(graph(listOf("f0"), emptyList()), listOf(entry()), "/p")
        assertEquals(setOf(0), result.flows.single().nodes)
    }

    @Test fun `bounds even model groups with too many initial branches`() = runBlocking {
        val result = splitter("""{"flows":[{"title":"All","branches":[1,2,3]}]}""", maxNodes = 2)
            .split(graph((0..3).map { "f$it" }, listOf(0 to 1, 0 to 2, 0 to 3)), listOf(entry()), "/p")
        assertEquals(2, result.flows.single().nodes.size)
        assertTrue(result.flows.single().limited)
    }

    @Test fun `each flow draws only its own members and walked calls, levelled from the root`() = runBlocking {
        val base = graph((0..5).map { "f$it" }, listOf(0 to 1, 0 to 2, 1 to 3, 2 to 3, 3 to 0, 2 to 4, 4 to 5))
        val result = splitter("""{"flows":[{"title":"Load","branches":[1]}, {"title":"Save","branches":[2]}]}""")
            .split(base, listOf(entry()), "/p")
        val load = result.flows[0].graph(base)
        assertEquals(listOf(0, 1, 3), load.sources)
        assertEquals(listOf(0, 1, 2), load.graph.nodes.map { it.level })
        assertEquals(listOf("f0", "f1", "f3"), load.graph.nodes.map { it.name })
        // The sibling call 0 -> 2 belongs to the other flow; the cycle back to the root is a real walked call.
        assertEquals(setOf(CallGraphEdge(0, 1), CallGraphEdge(1, 2), CallGraphEdge(2, 0)), load.graph.edges)
        val save = result.flows[1].graph(base)
        assertEquals(listOf(0, 2, 3, 4, 5), save.sources)
        assertEquals(listOf(0, 1, 2, 2, 3), save.graph.nodes.map { it.level })
    }

    @Test fun `class entrypoints draw their seeded declarations`() = runBlocking {
        val nodes = graph(listOf("Owner", "f1", "f2"), emptyList()).nodes.map {
            if (it.id == 0) it.copy(kind = SymbolKind.CLASS) else it
        }
        val base = CallGraph(nodes, setOf(CallGraphEdge(0, 1, RelationKind.CONTAINS), CallGraphEdge(1, 2)), false)
        val flow = splitter("{}").split(base, listOf(entry().copy(symbol = "Owner")), "/p").flows.single()
        assertEquals(setOf(CallGraphEdge(0, 1, RelationKind.CONTAINS), CallGraphEdge(1, 2)), flow.graph(base).graph.edges)
    }

    @Test fun `flows never synthesize dispatch links from inheritance`() = runBlocking {
        val base = graph((0..3).map { "f$it" }, listOf(0 to 1, 2 to 3)).let {
            it.copy(edges = it.edges + CallGraphEdge(2, 1, RelationKind.INHERITS))
        }
        val flow = splitter("""{"flows":[{"title":"Run","branches":[1,2]}]}""")
            .split(base, listOf(entry()), "/p").flows.single()
        assertEquals(setOf(0, 1), flow.nodes)
        assertEquals(setOf(CallGraphEdge(0, 1)), flow.edges)
        flow.validateAgainst(base)
    }

    @Test fun `flow roots require an unambiguous path line and symbol match`() = runBlocking {
        val base = graph(listOf("f0", "f1"), listOf(0 to 1))
        val entries = listOf(entry().copy(symbol = "invented"), entry().copy(line = 99),
            entry().copy(path = "Other.kt"), entry().copy(symbol = ""))
        val noModel = ApplicationFlowSplitter({ error("Unmatched roots must not reach the model") }, InMemoryEnrichmentCache())
        val result = noModel.split(base, entries, "/p")
        assertTrue(result.flows.isEmpty())
        assertEquals(entries, result.unmatched)
        val ambiguous = base.copy(nodes = base.nodes + base.nodes[0].copy(id = 2))
        assertEquals(listOf(entry()), noModel.split(ambiguous, listOf(entry()), "/p").unmatched)
    }

    @Test fun `validation rejects missing nodes wrong links and disconnected members before drawing`() {
        val base = graph((0..3).map { "f$it" }, listOf(0 to 1, 2 to 3)).let {
            it.copy(edges = it.edges + setOf(CallGraphEdge(0, 2, RelationKind.REFERENCE),
                CallGraphEdge(0, 1, RelationKind.CONTAINS)))
        }
        val valid = ApplicationFlow("Run", "", entry(), 0, setOf(0, 1), false, false, setOf(CallGraphEdge(0, 1)))
        valid.validateAgainst(base)
        val invalid = listOf(
            valid.copy(root = 2),
            valid.copy(nodes = setOf(0, 99)),
            valid.copy(edges = setOf(CallGraphEdge(1, 0))),
            valid.copy(edges = setOf(CallGraphEdge(0, 1, RelationKind.CONTAINS))),
            valid.copy(edges = setOf(CallGraphEdge(0, 2, RelationKind.REFERENCE)), nodes = setOf(0, 2)),
            valid.copy(nodes = setOf(0, 1, 2, 3), edges = valid.edges + CallGraphEdge(2, 3)),
            valid.copy(nodes = setOf(0, 1, 2), edges = valid.edges + CallGraphEdge(0, 2)),
        )
        for (flow in invalid) {
            assertThrows(IllegalArgumentException::class.java) { flow.validateAgainst(base) }
            assertThrows(IllegalArgumentException::class.java) { flow.graph(base) }
        }
        assertThrows(IllegalArgumentException::class.java) { valid.graph(base.copy(edges = emptySet())) }
        assertThrows(IllegalArgumentException::class.java) {
            valid.graph(base.copy(nodes = base.nodes.map { if (it.id == 1) it.copy(inProject = false) else it }))
        }
    }

    @Test fun `malformed base graphs are rejected before requesting model output`() = runBlocking {
        val noModel = ApplicationFlowSplitter({ error("Invalid graph must not reach the model") }, InMemoryEnrichmentCache())
        val base = graph(listOf("f0", "f1"), listOf(0 to 1))
        for (invalid in listOf(base.copy(edges = setOf(CallGraphEdge(0, 99))),
            base.copy(nodes = base.nodes.reversed()), base.copy(nodes = listOf(base.nodes[0], base.nodes[0])))) {
            try {
                noModel.split(invalid, listOf(entry()), "/p")
                fail("Expected an invalid graph to be rejected")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `fresh and cached malformed model groups cannot add graph structure`() = runBlocking {
        var requests = 0
        val splitter = ApplicationFlowSplitter({
            requests++
            Completion("""{"flows":[null,7,{"title":"Bad","branches":[1,1]},
                {"title":"String","branches":["1"]},{"title":"Fraction","branches":[1.0]},
                {"title":"Missing","branches":[99]},{"title":"Negative","branches":[-1]},
                {"title":"Huge","branches":[99999999999999999999]}]}""")
        }, InMemoryEnrichmentCache())
        val base = graph(listOf("f0", "f1", "f2"), listOf(0 to 1, 0 to 2))
        repeat(2) {
            val result = splitter.split(base, listOf(entry()), "/p")
            assertEquals(listOf(setOf(0, 1), setOf(0, 2)), result.flows.map { it.nodes })
            assertTrue(result.flows.all { !it.inferred })
            result.flows.forEach { it.validateAgainst(base) }
        }
        assertEquals(1, requests)
    }
}
