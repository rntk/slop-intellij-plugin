package dev.insight.callgraph

import dev.insight.graph.CallGraph
import dev.insight.graph.CallGraphEdge
import dev.insight.graph.CallGraphNode
import dev.insight.graph.Emphasis
import dev.insight.graph.GraphDecorations
import dev.insight.graph.GraphDetail
import dev.insight.graph.NodeDecoration
import dev.insight.graph.RelationKind
import dev.insight.graph.SymbolKind
import dev.insight.graph.hierarchy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffModelTest {
    private fun function(id: Int, name: String = "f$id", path: String = "/p/a.kt") =
        CallGraphNode(id, name, "", "", 0, true, SymbolKind.FUNCTION, path)

    private fun calls(size: Int, vararg edges: Pair<Int, Int>, extra: Set<CallGraphEdge> = emptySet()) =
        CallGraph(List(size) { function(it) }, edges.mapTo(linkedSetOf()) { CallGraphEdge(it.first, it.second) } + extra, false, projectGraph = true)

    private fun modified(path: String, vararg hunks: IntRange) = FileDiff(path, null, FileDiff.Status.MODIFIED, 1, 1, hunks.toList())

    // --- hunks ---------------------------------------------------------------------------------------------------

    @Test fun `changed lines become 1-based inclusive ranges without context`() {
        // 0-based lines 4..6 (end exclusive 7) replaced: 1-based 5..7.
        assertEquals(listOf(5..7), hunksOf(listOf(LineChange(4, 5, 4, 7)), 20, contextLines = 0))
    }

    @Test fun `pure deletion anchors a single line like git's +N,0 header`() {
        // Lines removed after new line 3 (0-based start2 = 3): anchor 3.
        assertEquals(listOf(3..3), hunksOf(listOf(LineChange(3, 6, 3, 3)), 20, contextLines = 0))
        // Removed at the top of the file: git reports +0,0, anchored at line 1.
        assertEquals(listOf(1..1), hunksOf(listOf(LineChange(0, 2, 0, 0)), 20, contextLines = 0))
    }

    @Test fun `context widens ranges, clamps to the file and merges touching hunks`() {
        assertEquals(listOf(2..10), hunksOf(listOf(LineChange(4, 5, 4, 7)), 20))
        assertEquals(listOf(1..4), hunksOf(listOf(LineChange(0, 1, 0, 1)), 4))
        // 1-based 5..5 and 12..12 with three lines of context: 2..8 and 9..15 touch, so they merge.
        assertEquals(listOf(2..15), hunksOf(listOf(LineChange(4, 5, 4, 5), LineChange(11, 12, 11, 12)), 30))
        assertEquals(listOf(2..8, 17..23), hunksOf(listOf(LineChange(4, 5, 4, 5), LineChange(19, 20, 19, 20)), 30))
        // A deletion after new line 3 with context covers the lines around the gap, as git's hunk does.
        assertEquals(listOf(1..6), hunksOf(listOf(LineChange(3, 6, 3, 3)), 20))
    }

    @Test fun `file diffs count added and removed lines and detect renames`() {
        val diff = fileDiffOf("/p/b.kt", "/p/a.kt", listOf(LineChange(1, 3, 1, 2), LineChange(5, 5, 4, 7)), 10, contextLines = 0)
        assertEquals(FileDiff.Status.RENAMED, diff.status)
        assertEquals("/p/b.kt", diff.path)
        assertEquals("/p/a.kt", diff.oldPath)
        assertEquals(4, diff.added)
        assertEquals(2, diff.removed)
        assertEquals(listOf(2..2, 5..7), diff.hunks)
        assertEquals(FileDiff.Status.MODIFIED, fileDiffOf("C:\\p\\a.kt", null, emptyList(), 1).status)
        assertEquals("C:/p/a.kt", fileDiffOf("C:\\p\\a.kt", null, emptyList(), 1).path)
    }

    @Test fun `line counts follow git`() {
        assertEquals(0, lineCount(""))
        assertEquals(1, lineCount("a"))
        assertEquals(1, lineCount("a\n"))
        assertEquals(2, lineCount("a\nb"))
    }

    // --- mapping -------------------------------------------------------------------------------------------------

    @Test fun `nodes overlapping a hunk are changed`() {
        val ranges = mapOf(
            0 to ("/p/a.kt" to 1..4), 1 to ("/p/a.kt" to 5..9), 2 to ("/p/a.kt" to 10..20), 3 to ("/p/b.kt" to 5..9),
        )
        assertEquals(setOf(1), mapChangesToNodes(listOf(modified("/p/a.kt", 7..7)), ranges))
        // Inclusive at both ends.
        assertEquals(setOf(0, 1, 2), mapChangesToNodes(listOf(modified("/p/a.kt", 4..10)), ranges))
        assertEquals(emptySet<Int>(), mapChangesToNodes(listOf(modified("/p/c.kt", 1..100)), ranges))
    }

    @Test fun `deletion anchor maps onto the enclosing node`() {
        val ranges = mapOf(0 to ("/p/a.kt" to 1..2), 1 to ("/p/a.kt" to 3..8))
        val diff = fileDiffOf("/p/a.kt", null, listOf(LineChange(4, 6, 4, 4)), 8, contextLines = 0)
        assertEquals(setOf(1), mapChangesToNodes(listOf(diff), ranges))
    }

    @Test fun `added files map every node and deleted files map none`() {
        val ranges = mapOf(0 to ("/p/new.kt" to 1..3), 1 to ("/p/new.kt" to 4..6), 2 to ("/p/gone.kt" to 1..5))
        val added = addedFileDiff("/p/new.kt", 6)
        assertEquals(listOf(1..6), added.hunks)
        assertEquals(6, added.added)
        val deleted = deletedFileDiff("/p/gone.kt", 5)
        assertEquals(5, deleted.removed)
        assertEquals(setOf(0, 1), mapChangesToNodes(listOf(added, deleted), ranges))
        // Even with hunks, a deleted file never maps.
        assertEquals(emptySet<Int>(), mapChangesToNodes(listOf(deleted.copy(hunks = listOf(1..5))), ranges))
    }

    @Test fun `paths are compared with forward slashes`() {
        val ranges = mapOf(0 to ("C:\\p\\a.kt" to 1..3))
        assertEquals(setOf(0), mapChangesToNodes(listOf(modified("C:/p/a.kt", 2..2)), ranges))
    }

    @Test fun `containers of changed nodes are dropped`() {
        // 0 file > 1 class > 2 method, 3 other method; 4 another file.
        val parent = intArrayOf(-1, 0, 1, 1, -1)
        assertEquals(setOf(2), keepInnermost(setOf(0, 1, 2), parent))
        assertEquals(setOf(2, 3), keepInnermost(setOf(0, 1, 2, 3), parent))
        assertEquals(setOf(0, 4), keepInnermost(setOf(0, 4), parent))
    }

    // --- pruning -------------------------------------------------------------------------------------------------

    // 0 -> 1 -> 2 -> 3 -> 4, 5 -> 2, 2 -> 6, 7 unrelated
    private val chain = calls(8, 0 to 1, 1 to 2, 2 to 3, 3 to 4, 5 to 2, 2 to 6)

    @Test fun `unlimited hops walk to the roots and leaves`() {
        assertEquals(setOf(0, 1, 2, 3, 4, 5, 6), pruneToChangedNeighborhood(chain, setOf(2), null, null))
    }

    @Test fun `zero hops keep only the changed nodes`() {
        assertEquals(setOf(2), pruneToChangedNeighborhood(chain, setOf(2), 0, 0))
    }

    @Test fun `one hop keeps direct callers and callees`() {
        assertEquals(setOf(1, 2, 3, 5, 6), pruneToChangedNeighborhood(chain, setOf(2), 1, 1))
        assertEquals(setOf(0, 1, 2, 5), pruneToChangedNeighborhood(chain, setOf(2), null, 0))
        assertEquals(setOf(2, 3, 4, 6), pruneToChangedNeighborhood(chain, setOf(2), 0, null))
    }

    @Test fun `walks are directional so siblings are excluded`() {
        // p(0) -> a(1), p(0) -> s(2): changing a keeps its caller p but not p's other callee s.
        assertEquals(setOf(0, 1), pruneToChangedNeighborhood(calls(3, 0 to 1, 0 to 2), setOf(1), null, null))
        // a(0) -> c(2), b(1) -> c(2): changing a keeps its callee c but not c's other caller b.
        assertEquals(setOf(0, 2), pruneToChangedNeighborhood(calls(3, 0 to 2, 1 to 2), setOf(0), null, null))
    }

    @Test fun `multi source walk uses the shortest distance`() {
        // 0 -> 1 -> 2 -> 3; seeds 0 and 2 with one hop down reach 1 and 3.
        assertEquals(setOf(0, 1, 2, 3), pruneToChangedNeighborhood(calls(4, 0 to 1, 1 to 2, 2 to 3), setOf(0, 2), 0, 1))
    }

    @Test fun `only call edges are walked by default`() {
        val graph = calls(3, 0 to 1, extra = setOf(CallGraphEdge(1, 2, RelationKind.REFERENCE), CallGraphEdge(2, 1, RelationKind.CONTAINS)))
        assertEquals(setOf(0, 1), pruneToChangedNeighborhood(graph, setOf(1), null, null))
        assertEquals(setOf(0, 1, 2), pruneToChangedNeighborhood(graph, setOf(1), null, null, setOf(RelationKind.CALL, RelationKind.REFERENCE)))
    }

    @Test fun `no seeds in the graph keep every node`() {
        assertEquals((0..7).toSet(), pruneToChangedNeighborhood(chain, emptySet(), 1, 1))
        assertEquals((0..7).toSet(), pruneToChangedNeighborhood(chain, setOf(42), 1, 1))
    }

    // --- display -------------------------------------------------------------------------------------------------

    /** File 0 holds functions 1 (changed), 2 (its caller) and 3 (unrelated). */
    private fun fileGraph(): CallGraph {
        val file = CallGraphNode(0, "a.kt", "", "", 0, true, SymbolKind.FILE, "/p/a.kt")
        val edges = linkedSetOf(
            CallGraphEdge(0, 1, RelationKind.CONTAINS), CallGraphEdge(0, 2, RelationKind.CONTAINS), CallGraphEdge(0, 3, RelationKind.CONTAINS),
            CallGraphEdge(2, 1),
        )
        return CallGraph(listOf(file, function(1), function(2), function(3)), edges, false, projectGraph = true)
    }

    @Test fun `changed only keeps the neighborhood under its file`() {
        val tree = fileGraph().hierarchy()
        val diff = DiffResult("HEAD", listOf(modified("/p/a.kt", 1..1)), setOf(1), setOf(1, 2), tree.graph)
        val only = changedOnlyNodes(tree, diff)
        assertEquals(setOf(0, 1, 2), only.filterTo(hashSetOf()) { tree.nodes[it].kind != SymbolKind.DIRECTORY })
        val view = tree.view(emptySet(), only = only)
        assertEquals(setOf(0, 1, 2), view.sources.filterTo(hashSetOf()) { tree.nodes[it].kind != SymbolKind.DIRECTORY })
    }

    @Test fun `decorations emphasize changes, keep neighbors and fade the rest`() {
        val tree = fileGraph().hierarchy()
        val diff = DiffResult("main", listOf(FileDiff("/p/a.kt", null, FileDiff.Status.MODIFIED, 4, 2, listOf(1..1))), setOf(1), setOf(1, 2), tree.graph)
        val view = tree.view(tree.expansion(GraphDetail.SYMBOL))
        val base = GraphDecorations(nodes = mapOf(view.shownId(3)!! to NodeDecoration(badges = "N")))
        val decorated = decorateDiff(base, view, diff)
        val changed = decorated.nodes.getValue(view.shownId(1)!!)
        assertEquals(Emphasis.STRONG, changed.emphasis)
        assertEquals("Δ", changed.badges)
        assertEquals(listOf(CHANGED_COLOR to 1.0), changed.colors)
        assertEquals(Emphasis.NORMAL, decorated.nodes.getValue(view.shownId(2)!!).emphasis)
        val other = decorated.nodes.getValue(view.shownId(3)!!)
        assertEquals(Emphasis.FAINT, other.emphasis)
        assertEquals("N", other.badges)
        val file = decorated.nodes.getValue(view.shownId(0)!!)
        assertEquals(Emphasis.FAINT, file.emphasis)
        assertTrue(decorated.legend.any { it.color == CHANGED_COLOR && "main" in it.label })

        // Collapsed, the file holds the change and shows the file's line counts.
        val collapsed = tree.view(emptySet())
        val folded = decorateDiff(GraphDecorations.NONE, collapsed, diff).nodes.getValue(collapsed.shownId(0)!!)
        assertEquals(Emphasis.STRONG, folded.emphasis)
        assertEquals("Δ +4 −2", folded.badges)
    }
}
