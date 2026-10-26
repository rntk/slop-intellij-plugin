package dev.insight.diff

import dev.insight.chat.ToolInputException
import dev.insight.review.ReviewSource
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.ReviewTopics
import dev.insight.review.SourceChunk
import dev.insight.review.SummaryKind
import dev.insight.review.SummaryNode
import dev.insight.review.buildSummaryTree
import dev.insight.review.buildTopicSummaryTree
import dev.insight.review.reviewFingerprint
import org.junit.Assert.*
import org.junit.Test

class ChunkedSummaryTreeTest {
    private data class Source(override val path: String, override val fingerprint: String = "1") : ReviewSource {
        override val absolutePath: String get() = "/repo/$path"
        override val facts: List<String> get() = emptyList()
    }

    /** [count] parts of ten lines each; [hash] gives each part's content hash. */
    private fun parts(count: Int, hash: (Int) -> String = { "h$it" }) =
        List(count) { SourceChunk(it, (it * 10 + 1)..(it * 10 + 10), hash(it)) }

    private fun ReviewSummaryTree.byIdentity(identity: String) = nodes.single { it.identity == identity }
    private fun ReviewSummaryTree.labels(node: SummaryNode) = node.children.map { get(it).label }

    private fun assertPreOrder(tree: ReviewSummaryTree) {
        tree.nodes.forEachIndexed { index, node ->
            assertEquals(index, node.id)
            node.parent?.let { assertTrue(it < node.id); assertTrue(node.id in tree[it].children) }
            node.children.forEach { assertEquals(node.id, tree[it].parent) }
        }
        assertEquals(tree.nodes.size, tree.nodes.map { it.identity }.toSet().size)
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected the batch to be rejected") } catch (_: ToolInputException) {}
    }

    @Test fun `tree without chunks keeps its labels, keys and the default root label`() {
        val files = listOf(Source("a/x.kt"), Source("b/c/d/y.kt"), Source("b/c/d/z.kt", "2"))
        val tree = buildSummaryTree(files)
        assertEquals("All changes", tree.root.label)
        assertEquals(listOf("a", "b"), tree.labels(tree.root))
        assertTrue(tree.nodes.none { it.kind == SummaryKind.CHUNK })
        // A file without parts keeps exactly the key it always had, so diff summaries are retained as before.
        tree.nodes.filter { it.kind == SummaryKind.FILE }.forEach { node ->
            assertEquals(reviewFingerprint("file", node.path, node.file!!.fingerprint), node.key)
            assertTrue(node.children.isEmpty())
        }
        val folder = tree.byIdentity("directory:b/c/d")
        assertEquals(reviewFingerprint("directory", "b/c/d", "d", *folder.children.map { tree[it].key }.toTypedArray()), folder.key)
        assertEquals(reviewFingerprint("root", "", "All changes", *tree.root.children.map { tree[it].key }.toTypedArray()), tree.root.key)
        assertEquals(tree.nodes.map { it.key }, buildSummaryTree(files, chunks = { emptyList() }).nodes.map { it.key })

        assertEquals("Whole scope", buildSummaryTree(files, rootLabel = "Whole scope").root.label)
        // A single top folder stays a folder beneath the root.
        val single = buildSummaryTree(listOf(Source("src/a.kt"), Source("src/b.kt")), rootLabel = "Whole scope")
        assertEquals("Whole scope", single.root.label)
        assertEquals(listOf("src"), single.labels(single.root))

        val renamed = ReviewFile("new.kt", "/repo/new.kt", "old.kt", "renamed", "", "1")
        assertEquals(listOf("renamed", "renamed from old.kt"), renamed.facts)
        assertEquals(listOf("added"), ReviewFile("a.kt", "/repo/a.kt", null, "added", "", "1").facts)
    }

    @Test fun `a chunked file has its parts beneath it`() {
        val big = Source("src/big.kt")
        val tree = buildSummaryTree(listOf(big, Source("src/small.kt")), chunks = { if (it == big) parts(3) else emptyList() })
        assertPreOrder(tree)
        val file = tree.byIdentity("file:src/big.kt")
        assertEquals(SummaryKind.FILE, file.kind)
        assertEquals(listOf("Lines 1–10", "Lines 11–20", "Lines 21–30"), tree.labels(file))
        val second = tree[file.children[1]]
        assertEquals(SummaryKind.CHUNK, second.kind)
        assertEquals("chunk:src/big.kt:1", second.identity)
        assertEquals(11..20, second.lines)
        assertEquals("src/big.kt:11-20", second.location)
        assertEquals(listOf("src/big.kt"), second.files)
        assertEquals("src/big.kt", second.path)
        assertSame(big, second.file)
        assertEquals(reviewFingerprint("chunk", "src/big.kt", "h1"), second.key)
        assertEquals(listOf("src/big.kt"), file.files)
        assertEquals(reviewFingerprint("file", "src/big.kt", "1", *file.children.map { tree[it].key }.toTypedArray()), file.key)
        assertTrue(tree.byIdentity("file:src/small.kt").children.isEmpty())
        assertEquals(listOf("src/big.kt", "src/small.kt"), tree.root.files)
    }

    @Test fun `more than eight parts form groups with the union of their lines`() {
        val tree = buildSummaryTree(listOf(Source("big.kt")), chunks = { parts(20) })
        assertPreOrder(tree)
        val file = tree.byIdentity("file:big.kt")
        val groups = file.children.map(tree::get)
        assertEquals(listOf(7, 7, 6), groups.map { it.children.size })
        assertTrue(groups.all { it.kind == SummaryKind.CHUNK })
        assertEquals(listOf(1..70, 71..140, 141..200), groups.map { it.lines })
        assertEquals(listOf("Lines 1–70", "Lines 71–140", "Lines 141–200"), groups.map { it.label })
        assertEquals(listOf("chunk-group:big.kt:0:6", "chunk-group:big.kt:7:13", "chunk-group:big.kt:14:19"), groups.map { it.identity })
        assertEquals("big.kt:71-140", groups[1].location)
        assertEquals(listOf("big.kt"), groups[1].files)
        assertEquals(reviewFingerprint("chunk-group", "big.kt", *groups[1].children.map { tree[it].key }.toTypedArray()), groups[1].key)

        val large = buildSummaryTree(listOf(Source("big.kt")), maxChildren = 4, chunks = { parts(100) })
        assertPreOrder(large)
        assertTrue(large.nodes.all { it.children.size <= 4 })
        assertTrue(large.depth(large.nodes.last().id) >= 4)
        large.nodes.filter { it.kind == SummaryKind.CHUNK && it.children.isNotEmpty() }.forEach { group ->
            assertEquals(large[group.children.first()].lines!!.first..large[group.children.last()].lines!!.last, group.lines)
        }
        // Leaves in pre-order cover the file in order, without gaps.
        val leaves = large.nodes.filter { it.kind == SummaryKind.CHUNK && it.children.isEmpty() }
        assertEquals((0 until 100).map { "chunk:big.kt:$it" }, leaves.map { it.identity })
    }

    private fun scope(hash: (Int) -> String = { "h$it" }, fingerprint: String = "1"): ReviewSummaryTree {
        val big = Source("src/big.kt", fingerprint)
        return buildSummaryTree(listOf(big, Source("src/a.kt"), Source("lib/x.kt")), chunks = { if (it.path == big.path) parts(20, hash) else emptyList() })
    }

    @Test fun `changing one part keeps the other parts and unchanged groups`() {
        val tree = scope()
        val summaries = ReviewSummaries(tree).summarize(tree.nodes.associate { it.id to "About ${it.identity}." })
        assertTrue(summaries.complete)

        val next = scope(hash = { if (it == 10) "changed" else "h$it" }, fingerprint = "2")
        val kept = summaries.retainedIn(next)
        val dropped = next.nodes.filter { kept.text(it.id) == null }.map { it.identity }
        assertEquals(listOf("root", "directory:src", "file:src/big.kt", "chunk-group:src/big.kt:7:13", "chunk:src/big.kt:10"), dropped)
        assertEquals("About chunk-group:src/big.kt:0:6.", kept.text(next.byIdentity("chunk-group:src/big.kt:0:6").id))
        assertEquals("About chunk:src/big.kt:11.", kept.text(next.byIdentity("chunk:src/big.kt:11").id))
        assertEquals("About file:src/a.kt.", kept.text(next.byIdentity("file:src/a.kt").id))
    }

    @Test fun `ready lists parts and files first, then groups, the file and its folders`() {
        val tree = scope()
        var summaries = ReviewSummaries(tree)
        val chunkLeaves = tree.nodes.filter { it.kind == SummaryKind.CHUNK && it.children.isEmpty() }.map { it.id }
        val a = tree.byIdentity("file:src/a.kt").id
        val x = tree.byIdentity("file:lib/x.kt").id
        assertEquals(chunkLeaves + x + a, summaries.ready())
        summaries = summaries.summarize(summaries.ready().associateWith { "Leaf." })
        val groups = tree.byIdentity("file:src/big.kt").children
        assertEquals(groups, summaries.ready())
        summaries = summaries.summarize(groups.associateWith { "Group." })
        assertEquals(listOf(tree.byIdentity("file:src/big.kt").id), summaries.ready())
        summaries = summaries.summarize(summaries.ready().associateWith { "File." })
        assertEquals(listOf(tree.byIdentity("directory:src").id), summaries.ready())
        summaries = summaries.summarize(summaries.ready().associateWith { "Folder." })
        assertEquals(listOf(tree.root.id), summaries.ready())
    }

    @Test fun `a file is summarized only after its parts`() {
        val tree = scope()
        val summaries = ReviewSummaries(tree)
        val big = tree.byIdentity("file:src/big.kt")
        rejected { summaries.summarize(mapOf(big.id to "The whole file.")) }
        val group = tree[big.children[0]]
        rejected { summaries.summarize(mapOf(group.id to "Lines 1–70.")) }
        val written = summaries.summarize((group.children + group.id).associateWith { "Part." })
        assertEquals("Part.", written.text(group.id))
        assertEquals(0, ReviewSummaries(buildSummaryTree(emptyList())).total)
        val message = try { ReviewSummaries(buildSummaryTree(emptyList())).summarize(mapOf(0 to "x")); null } catch (e: ToolInputException) { e.message }
        assertEquals("There are no files to summarize.", message)
    }

    @Test fun `topic trees hold parts beneath their files`() {
        val big = Source("src/big.kt")
        val topics = ReviewTopics(listOf(big, Source("src/a.kt")), mapOf("src/big.kt" to "Core > Engine", "src/a.kt" to "Core > Engine"))
        val tree = buildTopicSummaryTree(topics) { if (it == big) parts(10) else emptyList() }
        assertPreOrder(tree)
        val file = tree.byIdentity("file:src/big.kt")
        assertEquals(2, file.children.size)
        assertEquals(listOf("chunk-group:src/big.kt:0:4", "chunk-group:src/big.kt:5:9"), file.children.map { tree[it].identity })
        assertEquals(10, tree.nodes.count { it.kind == SummaryKind.CHUNK && it.children.isEmpty() })
        assertTrue(buildTopicSummaryTree(topics).nodes.none { it.kind == SummaryKind.CHUNK })
    }
}
