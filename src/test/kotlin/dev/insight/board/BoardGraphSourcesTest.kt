package dev.insight.board

import dev.insight.agent.ToolInputException
import dev.insight.diff.ReviewFile
import dev.insight.graph.CallGraph
import dev.insight.graph.CallGraphEdge
import dev.insight.graph.CallGraphNode
import dev.insight.graph.CallGraphResult
import dev.insight.graph.RelationKind
import dev.insight.graph.SymbolKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardGraphSourcesTest {
    private fun node(id: Int, name: String, container: String = "", line: Int = id + 1, path: String? = "/p/src/A.kt",
        kind: SymbolKind = SymbolKind.FUNCTION, inProject: Boolean = true) =
        CallGraphNode(id, name, container, "A.kt:$line", 0, inProject, kind, path)

    @Test
    fun `shortest call path prefers fewer steps and stops at the budget`() {
        val calls = mapOf(0 to listOf(1, 2), 1 to listOf(3), 2 to listOf(4), 4 to listOf(3), 3 to listOf(5))
        assertEquals(CallPath(listOf(0, 1, 3, 5), false), shortestCallPath(calls, 0, 5, 12))
        assertEquals(CallPath(listOf(0, 2), false), shortestCallPath(calls, 0, 2, 12))
        assertEquals(CallPath(listOf(3), false), shortestCallPath(calls, 3, 3, 12))
        assertEquals(CallPath(emptyList(), false), shortestCallPath(calls, 5, 0, 12))
        assertEquals(CallPath(emptyList(), true), shortestCallPath(calls, 0, 5, 3))
        assertEquals(CallPath(listOf(0, 1, 3, 5), false), shortestCallPath(calls, 0, 5, 4))
    }

    @Test
    fun `call adjacency uses call edges or the lens's calls`() {
        val graph = CallGraph(listOf(node(0, "a"), node(1, "b"), node(2, "c")),
            setOf(CallGraphEdge(0, 1), CallGraphEdge(0, 2, RelationKind.REFERENCE), CallGraphEdge(1, 1)), false)
        assertEquals(mapOf(0 to listOf(1)), callAdjacency(graph, null))
        assertEquals(mapOf(1 to listOf(2)), callAdjacency(graph, mapOf(1 to listOf(CallGraphEdge(1, 2)))))
    }

    @Test
    fun `graph sources resolve symbols, anchors, chains and callees`() {
        val graph = CallGraph(listOf(
            node(0, "main()"), node(1, "run()", "App", 7), node(2, "save(Int)", "Store", 3, "/p/src/Store.kt"),
            node(3, "save()", "Lib", 1, "/lib/Lib.kt", inProject = false), node(4, "x", kind = SymbolKind.VARIABLE),
            node(5, "Thing", kind = SymbolKind.CLASS, path = null),
        ), setOf(CallGraphEdge(0, 1), CallGraphEdge(1, 2), CallGraphEdge(1, 3)), false)
        val sources = BoardGraphSources(CallGraphResult(graph, emptyList()), null, "/p/")

        assertEquals(listOf(2), sources.symbols("save").map { it.node })
        assertEquals(listOf(3), sources.symbols("Lib.save").map { it.node })
        assertEquals(listOf(2), sources.symbols("SAVE", "src/Store.kt").map { it.node })
        assertTrue(sources.symbols("x").isEmpty())
        assertEquals(ResolvedSymbol(1, "App.run", "function · A.kt:7", null, BoardAnchor("src/A.kt", 7, 7)), sources.symbol(1))
        assertEquals("/lib/Lib.kt", sources.symbol(3)!!.anchor!!.path)
        assertEquals("function · A.kt:1 · library", sources.symbol(3)!!.meta)
        assertNull(sources.symbol(5)!!.anchor)
        assertNull(sources.symbol(42))

        val chain = sources.chain(0, 2)
        assertEquals(listOf("main", "App.run", "Store.save"), chain.steps.map { it.title })
        assertTrue(chain.complete)
        assertEquals(ResolvedChain(emptyList(), true), sources.chain(2, 0))
        assertEquals(listOf(2, 3), sources.callees(1).map { it.node })
        assertEquals(listOf(2), sources.callees(1, limit = 1).map { it.node })
    }

    @Test
    fun `relative paths and declaration anchors`() {
        assertEquals("src/A.kt", relativePath("/p/src/A.kt", "/p"))
        assertEquals("/q/A.kt", relativePath("/q/A.kt", "/p"))
        assertEquals("src/A.kt", relativePath("./src/A.kt", null))
        assertNull(nodeAnchor(CallGraphNode(0, "a", "", "", 0, true, filePath = "/p/A.kt"), "/p"))
    }

    private val patch = """
        diff --git a/A.kt b/A.kt
        @@ -1,2 +1,3 @@
         one
        +two
         three
        @@ -20,3 +21,2 @@
         keep
        -gone
         end
    """.trimIndent()

    @Test
    fun `patch excerpts select the hunks touching after-side lines`() {
        val all = patchExcerpt("A.kt", patch, null, null)
        assertEquals(BoardAnchor("A.kt", 1, 22), all.anchor)
        assertEquals(patch.lines().drop(1).joinToString("\n"), all.text)
        assertNull(all.notice)

        val second = patchExcerpt("A.kt", patch, 22, 30)
        assertEquals("@@ -20,3 +21,2 @@\n keep\n-gone\n end", second.text)
        assertEquals(BoardAnchor("A.kt", 21, 22), second.anchor)
        assertEquals("@@ -1,2 +1,3 @@\n one\n+two\n three", patchExcerpt("A.kt", patch, 2, null).text)

        assertEquals("No hunk of A.kt touches after-side lines 5-9; its hunks cover after-side lines 1-3, 21-22.",
            assertThrows(ToolInputException::class.java) { patchExcerpt("A.kt", patch, 5, 9) }.message)
        assertEquals("A.kt has no textual changes to show.",
            assertThrows(ToolInputException::class.java) { patchExcerpt("A.kt", "", null, null) }.message)
    }

    @Test
    fun `patch excerpts of removals anchor the before side and long ones are cut`() {
        val removed = patchExcerpt("A.kt", "@@ -1,2 +0,0 @@\n-a\n-b", null, null)
        assertEquals(BoardAnchor("A.kt", 1, 2, BoardSide.BEFORE), removed.anchor)

        val long = "@@ -1,0 +1,100 @@\n" + (1..100).joinToString("\n") { "+l$it" }
        val cut = patchExcerpt("A.kt", long, null, null)
        assertEquals(BoardLimits.MAX_CODE_LINES, cut.text.lines().size)
        assertEquals(BoardAnchor("A.kt", 1, BoardLimits.MAX_CODE_LINES - 1), cut.anchor)
        assertTrue(cut.notice!!.startsWith("The selected hunks have 101 patch rows"))
    }

    @Test
    fun `review sources read both sides and diffs of reviewed files only`() = runBlocking {
        val file = ReviewFile("src/A.kt", "/p/src/A.kt", "src/Old.kt", "modified", patch, "f", before = "b1\nb2\n", after = "a1\na2\na3")
        val added = ReviewFile("src/New.kt", "/p/src/New.kt", null, "added", "@@ -0,0 +1 @@\n+n", "g", after = "n")
        val sources = ReviewBoardSources(listOf(file, added)) { null }
        assertTrue(sources.hasComparison)
        assertEquals(ResolvedSource(BoardAnchor("src/A.kt", 2, 3), "a2\na3", null), sources.read(BoardAnchor("src/A.kt", 2, 3)))
        assertEquals("b2", sources.read(BoardAnchor("src/Old.kt", 2, 9, BoardSide.BEFORE)).text)
        assertEquals("The before side of src/New.kt is unavailable: the file is new.",
            assertThrows(ToolInputException::class.java) { runBlocking { sources.read(BoardAnchor("src/New.kt", 1, 1, BoardSide.BEFORE)) } }.message)
        assertEquals("src/B.kt is not in this comparison. Its files: src/A.kt, src/New.kt.",
            assertThrows(ToolInputException::class.java) { runBlocking { sources.read(BoardAnchor("src/B.kt", 1, 1)) } }.message)
        assertEquals("+n", sources.diff("src/New.kt").text.lines().last())
        assertFalse(runCatching { sources.symbols("a") }.isSuccess)
    }

    @Test
    fun `source excerpts clamp to the block limit`() {
        val text = (1..100).joinToString("\n") { "l$it" } + "\n"
        val excerpt = sourceExcerpt(BoardAnchor("A.kt", 10, 200), text)
        assertEquals(BoardAnchor("A.kt", 10, 89), excerpt.anchor)
        assertEquals("A code block shows at most 80 lines; showing lines 10-89.", excerpt.notice)
        assertEquals("l100", sourceExcerpt(BoardAnchor("A.kt", 100, 101), text).text)
    }

    @Test
    fun `the board graph loads on first use, is reused while unchanged and retries after a failure`() = runBlocking {
        val first = CallGraphResult(CallGraph(listOf(node(0, "a()")), emptySet(), false), emptyList())
        val second = CallGraphResult(CallGraph(listOf(node(0, "b()")), emptySet(), false), emptyList())
        val results = ArrayDeque(listOf(null, first, first, second))
        var loads = 0
        val loader = BoardGraphLoader({ loads++; results.removeFirst() }, { null }, "/p")
        val sources = ReviewBoardSources(emptyList(), loader::get)
        assertEquals("Nothing loads before a graph tool runs", 0, loads)
        assertFalse(runCatching { sources.symbols("a") }.isSuccess)
        val built = loader.get()
        assertEquals("Loads: the failed one and this", 2, loads)
        assertNotNull(built)
        assertEquals(listOf("a"), built!!.symbols("a").map { it.title })
        assertTrue("The same graph keeps its sources", built === loader.get())
        assertEquals(listOf("b"), loader.get()!!.symbols("b").map { it.title })
        assertEquals(4, loads)
    }
}
