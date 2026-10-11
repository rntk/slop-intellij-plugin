package dev.insight

import dev.insight.callgraph.CallDirection
import dev.insight.callgraph.CallGraph
import dev.insight.callgraph.CallGraphEdge
import dev.insight.callgraph.CallGraphNode
import dev.insight.callgraph.LayoutMetrics
import dev.insight.callgraph.exploreCalls
import dev.insight.callgraph.layoutCallGraph
import dev.insight.callgraph.highlightFile
import dev.insight.callgraph.DetailGraph
import dev.insight.callgraph.hierarchy
import dev.insight.callgraph.GraphDetail
import dev.insight.callgraph.RelationKind
import dev.insight.callgraph.SymbolKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallGraphModelTest {
    // main -> a -> b -> c, test -> a, b -> a (cycle), a -> println (library)
    private val calls = mapOf(
        "main" to listOf("a"), "test" to listOf("a"), "a" to listOf("b", "println"),
        "b" to listOf("c", "a"), "c" to emptyList(), "println" to emptyList(),
    )
    private val callers = calls.keys.associateWith { name -> calls.filterValues { name in it }.keys.toList() }

    private fun explore(root: String, direction: CallDirection, depth: Int = 3, maxNodes: Int = 100) = runBlocking {
        exploreCalls(root, direction, depth, maxNodes, { callers.getValue(it) }, { calls.getValue(it) }, { it != "println" })
    }

    private fun edgeNames(items: List<String>, edges: Set<CallGraphEdge>) =
        edges.map { items[it.caller] to items[it.callee] }.toSet()

    @Test fun `callees are positive levels with caller to callee edges`() {
        val result = explore("a", CallDirection.CALLEES)
        assertEquals(listOf("a", "b", "println", "c"), result.items)
        assertEquals(listOf(0, 1, 1, 2), result.levels)
        assertEquals(setOf("a" to "b", "a" to "println", "b" to "c", "b" to "a"), edgeNames(result.items, result.edges))
    }

    @Test fun `callers are negative levels and keep call direction`() {
        val result = explore("a", CallDirection.CALLERS, depth = 1)
        assertEquals(listOf("a", "main", "test", "b"), result.items)
        assertEquals(listOf(0, -1, -1, -1), result.levels)
        assertTrue(("main" to "a") in edgeNames(result.items, result.edges))
    }

    @Test fun `both directions share nodes reached from either side`() {
        val result = explore("a", CallDirection.BOTH, depth = 1)
        assertEquals(1, result.items.count { it == "b" })
        assertEquals(-1, result.levels[result.items.indexOf("b")])
        assertTrue(("a" to "b") in edgeNames(result.items, result.edges))
        assertTrue(("b" to "a") in edgeNames(result.items, result.edges))
    }

    @Test fun `node reached as a caller is still expanded as a callee`() {
        val result = explore("a", CallDirection.BOTH, depth = 2)
        assertTrue(("b" to "c") in edgeNames(result.items, result.edges))
    }

    @Test fun `node limit truncates exploration`() {
        val result = explore("a", CallDirection.BOTH, maxNodes = 3)
        assertEquals(3, result.items.size)
        assertTrue(result.truncated)
        assertFalse(explore("a", CallDirection.BOTH).truncated)
    }

    @Test fun `non expandable nodes are leaves`() {
        val result = runBlocking {
            exploreCalls("main", CallDirection.CALLEES, 5, 100, { callers.getValue(it) }, { calls.getValue(it) }, { it != "a" })
        }
        assertEquals(listOf("main", "a"), result.items)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `depth must be positive`() { explore("a", CallDirection.BOTH, depth = 0) }

    @Test fun `layout puts callers left and callees right without overlap`() {
        val nodes = listOf(0 to 0, 1 to -1, 2 to -1, 3 to 1, 4 to 2).map { (id, level) ->
            CallGraphNode(id, "f$id", "", "", level, true)
        }
        val graph = CallGraph(nodes, setOf(CallGraphEdge(1, 0), CallGraphEdge(2, 0), CallGraphEdge(0, 3), CallGraphEdge(3, 4)), false)
        val layout = layoutCallGraph(graph, LayoutMetrics(100, 20, 30, 10, 5))
        val boxes = layout.boxes
        assertTrue(boxes.getValue(1).x < boxes.getValue(0).x)
        assertTrue(boxes.getValue(0).x < boxes.getValue(3).x && boxes.getValue(3).x < boxes.getValue(4).x)
        assertFalse(boxes.getValue(1).intersects(boxes.getValue(2)))
        boxes.values.forEach { assertTrue(it.maxX <= layout.size.width && it.maxY <= layout.size.height) }
    }

    @Test fun `empty project has an empty layout and highlight`() {
        val graph = CallGraph(emptyList(), emptySet(), false, projectGraph = true)
        assertTrue(layoutCallGraph(graph, LayoutMetrics(100, 20, 30, 10, 5)).boxes.isEmpty())
        assertTrue(graph.highlightFile(null).owned.isEmpty())
        assertTrue(graph.highlightFile("/missing.kt").related.isEmpty())
    }

    private fun node(id: Int, kind: SymbolKind, path: String) = CallGraphNode(id, "n$id", "", "", 0, true, kind, path)

    // src/a/A.kt: 0 file, 1 class, 2 fun · src/a/B.kt: 3 file, 4 fun · src/b/c/C.kt: 5 file, 6 fun
    private val project = CallGraph(listOf(
        node(0, SymbolKind.FILE, "/p/src/a/A.kt"), node(1, SymbolKind.CLASS, "/p/src/a/A.kt"), node(2, SymbolKind.FUNCTION, "/p/src/a/A.kt"),
        node(3, SymbolKind.FILE, "/p/src/a/B.kt"), node(4, SymbolKind.FUNCTION, "/p/src/a/B.kt"),
        node(5, SymbolKind.FILE, "/p/src/b/c/C.kt"), node(6, SymbolKind.FUNCTION, "/p/src/b/c/C.kt"),
    ), setOf(
        CallGraphEdge(0, 1, RelationKind.CONTAINS), CallGraphEdge(1, 2, RelationKind.CONTAINS),
        CallGraphEdge(3, 4, RelationKind.CONTAINS), CallGraphEdge(5, 6, RelationKind.CONTAINS),
        CallGraphEdge(0, 4, RelationKind.IMPORT), CallGraphEdge(2, 4, RelationKind.CALL),
        CallGraphEdge(2, 1, RelationKind.REFERENCE), CallGraphEdge(4, 6, RelationKind.CALL),
        CallGraphEdge(2, 6, RelationKind.REFERENCE), CallGraphEdge(6, 4, RelationKind.CALL),
    ), false, projectGraph = true)

    private fun DetailGraph.named(name: String) = graph.nodes.single { it.name == name }.id

    @Test fun `hierarchy groups files into directories below the shared root and merges single child chains`() {
        val tree = project.hierarchy()
        assertEquals(listOf("a/", "b/c/"), tree.roots.map { tree.nodes[it].name })
        assertTrue(tree.roots.all { tree.nodes[it].kind == SymbolKind.DIRECTORY })
        val a = tree.roots[0]
        assertEquals(listOf(0, 3), tree.children[a])
        assertEquals(listOf(1), tree.children[0])
        assertEquals(listOf(2), tree.children[1])
        assertEquals("2 files", tree.nodes[a].location)
        assertEquals(setOf(a), tree.ancestors("/p/src/a/A.kt"))
        assertTrue(tree.ancestors("/missing.kt").isEmpty())
    }

    @Test fun `collapsed groups fold their descendants and merge relationships`() {
        val tree = project.hierarchy()
        val top = tree.view(emptySet())
        assertEquals(listOf("a/", "b/c/"), top.graph.nodes.map { it.name })
        assertTrue(top.graph.projectGraph)
        val a = top.named("a/")
        val c = top.named("b/c/")
        assertEquals(5, top.graph.nodes[a].hidden)
        assertFalse(top.graph.nodes[a].expanded)
        // A call and a reference from a/ into b/c/ become one call edge counting both.
        val down = CallGraphEdge(a, c, RelationKind.CALL)
        assertEquals(setOf(down, CallGraphEdge(c, a, RelationKind.CALL)), top.graph.edges)
        assertEquals(2, top.weights[down])
        assertEquals(a, top.shownId(2))
        assertEquals(tree.roots, top.sources)
        val highlight = top.highlightFile("/p/src/b/c/C.kt")
        assertEquals(setOf(c), highlight.owned)
        assertEquals(setOf(a), highlight.related)
    }

    @Test fun `expanded groups stay shown and link to their members`() {
        val tree = project.hierarchy()
        val view = tree.view(setOf(tree.roots[0]))
        assertEquals(listOf("a/", "n0", "n3", "b/c/"), view.graph.nodes.map { it.name })
        val (a, fileA, fileB, c) = listOf("a/", "n0", "n3", "b/c/").map { view.named(it) }
        assertTrue(view.graph.nodes[a].expanded)
        assertEquals(0, view.graph.nodes[a].hidden)
        assertEquals(2, view.graph.nodes[fileA].hidden)
        assertEquals(0, view.sources[fileA])
        assertEquals(setOf(
            CallGraphEdge(a, fileA, RelationKind.CONTAINS), CallGraphEdge(a, fileB, RelationKind.CONTAINS),
            CallGraphEdge(fileA, fileB, RelationKind.CALL), CallGraphEdge(fileB, c, RelationKind.CALL),
            CallGraphEdge(fileA, c, RelationKind.REFERENCE), CallGraphEdge(c, fileB, RelationKind.CALL),
        ), view.graph.edges)
        // Users sit left of what they depend on; groups sit left of their members.
        val levels = view.graph.nodes.map { it.level }
        assertTrue(levels[fileA] > levels[a])
        assertTrue(levels[fileB] > levels[fileA])
        val all = tree.view(tree.expansion(GraphDetail.SYMBOL))
        assertEquals(project.nodes.size + 2, all.graph.nodes.size)
        assertTrue(all.graph.nodes.none { it.hidden > 0 })
    }

    @Test fun `detail presets choose which groups start expanded`() {
        val tree = project.hierarchy()
        assertEquals(tree.roots.toSet(), tree.expansion(GraphDetail.FILE))
        assertEquals(tree.expansion(GraphDetail.SYMBOL), tree.expansion(GraphDetail.OVERVIEW))
        // With room for four nodes the smaller group is expanded first and the larger one stays folded.
        val c = tree.roots[1]
        assertEquals(setOf(c, 5), tree.expansion(GraphDetail.OVERVIEW, budget = 4))
        assertEquals(4, tree.view(tree.expansion(GraphDetail.OVERVIEW, budget = 4)).graph.nodes.size)
        assertEquals(setOf(c, 5), tree.groupsWithin(c))
    }

    @Test fun `expanded groups are identified by stable keys across rebuilds`() {
        val tree = project.hierarchy()
        val expanded = setOf(tree.roots[1], 0)
        val keys = expanded.map(tree::key)
        val rebuilt = project.copy(edges = project.edges + CallGraphEdge(4, 2, RelationKind.CALL)).hierarchy()
        assertEquals(expanded, rebuilt.ids(keys))
    }

    @Test fun `excluded nodes and groups they empty are left out with their relationships`() {
        val tree = project.hierarchy()
        val all = tree.view(tree.expansion(GraphDetail.SYMBOL), excluded = setOf(1))
        // File A.kt held only the excluded class, so it disappears too.
        assertEquals(listOf("a/", "n3", "n4", "b/c/", "n5", "n6"), all.graph.nodes.map { it.name })
        listOf(0, 1, 2).forEach { assertEquals(null, all.shownId(it)) }
        val (fn4, fn6) = listOf("n4", "n6").map { all.named(it) }
        assertEquals(setOf(CallGraphEdge(fn4, fn6, RelationKind.CALL), CallGraphEdge(fn6, fn4, RelationKind.CALL)),
            all.graph.edges.filterTo(hashSetOf()) { it.kind != RelationKind.CONTAINS })

        val top = tree.view(emptySet(), excluded = setOf(1))
        assertEquals(2, top.graph.nodes[top.named("a/")].hidden)
        assertEquals(2, top.graph.nodes[top.named("b/c/")].hidden)
        assertEquals(listOf("a/"), tree.view(emptySet(), excluded = setOf(5)).graph.nodes.map { it.name })
        val unchanged = tree.view(setOf(tree.roots[0]))
        val same = tree.view(setOf(tree.roots[0]), excluded = emptySet())
        assertEquals(unchanged.graph, same.graph)
    }

    @Test fun `search matches names fuzzily with the best match first`() {
        val names = listOf("ProjectGraphToolWindow", "renderGraph", "reveal", "GraphHierarchy")
        val tree = CallGraph(names.mapIndexed { id, name -> CallGraphNode(id, name, "", "", 0, true, SymbolKind.FILE, "/p/$name.kt") },
            emptySet(), false, projectGraph = true).hierarchy()
        assertEquals(listOf(0), tree.search("pgtw"))
        assertEquals(listOf(1), tree.search(" rendgr "))
        val graph = tree.search("graph")
        assertEquals(3, graph.first())
        assertEquals(setOf(0, 1, 3), graph.toSet())
        assertTrue(tree.search("  ").isEmpty())
        assertTrue(tree.search("xyz").isEmpty())
    }

    @Test fun `filtered view shows only the given nodes with their relationships`() {
        val tree = project.hierarchy()
        val view = tree.view(emptySet(), only = setOf(1, 4, 6))
        assertEquals(listOf("n1", "n4", "n6"), view.graph.nodes.map { it.name })
        assertEquals(listOf(1, 4, 6), view.sources)
        val (n1, n4, n6) = listOf("n1", "n4", "n6").map { view.named(it) }
        // n2 folds into n1; the file and directories are left out with their import.
        assertEquals(n1, view.shownId(2))
        assertEquals(null, view.shownId(0))
        assertEquals(1, view.graph.nodes[n1].hidden)
        assertFalse(view.graph.nodes[n1].expanded)
        assertEquals(setOf(CallGraphEdge(n1, n4, RelationKind.CALL), CallGraphEdge(n1, n6, RelationKind.REFERENCE),
            CallGraphEdge(n4, n6, RelationKind.CALL), CallGraphEdge(n6, n4, RelationKind.CALL)), view.graph.edges)
        // Nested matches are linked to their nearest shown ancestor; excluded matches are left out.
        val nested = tree.view(emptySet(), excluded = setOf(6), only = setOf(tree.roots[0], 2, 6))
        assertEquals(listOf("a/", "n2"), nested.graph.nodes.map { it.name })
        assertTrue(CallGraphEdge(nested.named("a/"), nested.named("n2"), RelationKind.CONTAINS) in nested.graph.edges)
        assertTrue(tree.view(emptySet(), only = emptySet()).graph.nodes.isEmpty())
    }

    @Test fun `empty project has an empty view`() {
        val view = CallGraph(emptyList(), emptySet(), false, projectGraph = true).hierarchy().view(emptySet())
        assertTrue(view.graph.nodes.isEmpty())
        assertTrue(view.highlightFile("/a.kt").owned.isEmpty())
    }

    @Test fun `layout of a long dependency chain does not overflow the stack`() {
        val size = 100_000
        val files = (0 until size).map { node(it, SymbolKind.FILE, "/p/f$it.kt") }
        val chain = CallGraph(files, (1 until size).mapTo(linkedSetOf()) { CallGraphEdge(it - 1, it, RelationKind.CALL) }, false, true)
        val view = chain.hierarchy().view(emptySet())
        assertEquals(size - 1, view.graph.nodes.last().level)
    }
}
