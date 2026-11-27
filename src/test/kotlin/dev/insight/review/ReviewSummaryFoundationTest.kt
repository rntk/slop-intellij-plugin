package dev.insight.review

import com.google.gson.JsonParser
import dev.insight.context.ContextSummaryActions
import dev.insight.review.ui.SummariesCard
import dev.insight.review.ui.summaryEntryFill
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSummaryFoundationTest {
    private class Source(
        override val path: String,
        override val fingerprint: String = path,
        override val facts: List<String> = emptyList(),
    ) : ReviewSource {
        override val absolutePath = "/project/$path"
    }

    @Test fun `scopeNode normalizes empty directory to root and resolves path fallback`() {
        val tree = buildSummaryTree(listOf(Source("src/a/One.kt"), Source("src/b/Two.kt")))
        val root = tree.scopeNode("", SummaryKind.DIRECTORY)
        assertNotNull(root)
        assertEquals(SummaryKind.ROOT, root!!.kind)
        assertEquals(root, tree.scopeNode("", SummaryKind.ROOT))
        assertEquals(SummaryKind.FILE, tree.scopeNode("src/a/One.kt")!!.kind)
        assertEquals(SummaryKind.DIRECTORY, tree.scopeNode("src/a")!!.kind)
        assertNull(tree.scopeNode("missing/Path.kt"))
        assertEquals(listOf("src/a/One.kt", "src/b/Two.kt"), tree.fileNodes.map { it.path })
    }

    @Test fun `ReviewSummaries projections cover fileTexts nodeTexts textFor and absorbed root fillTarget`() {
        val tree = buildSummaryTree(listOf(Source("src/pkg/One.kt"), Source("src/pkg/Two.kt")))
        val one = tree.scopeNode("src/pkg/One.kt", SummaryKind.FILE)!!
        val two = tree.scopeNode("src/pkg/Two.kt", SummaryKind.FILE)!!
        val pkg = tree.scopeNode("src/pkg", SummaryKind.DIRECTORY)!!
        val empty = ReviewSummaries(tree)

        // Before pkg is summarized, filling the absorbed root targets the absorbed directory ("src/pkg").
        assertEquals("src/pkg", tree.rootDirectoryPrefix)
        assertEquals(pkg, empty.fillTarget(tree.root))
        assertEquals(one, empty.fillTarget(one))

        val partial = empty.summarize(
            mapOf(one.id to Summary.plain("First file."), two.id to Summary.plain("Second file."), pkg.id to Summary.plain("Package.")),
            reuseSingleChildren = false,
        )
        assertEquals(mapOf("src/pkg/One.kt" to "First file.", "src/pkg/Two.kt" to "Second file."), partial.fileTexts)
        assertEquals("Package.", partial.textFor("", SummaryKind.DIRECTORY))
        assertEquals("Package.", partial.textFor("src/pkg", SummaryKind.DIRECTORY))
        assertEquals("First file.", partial.textFor("src/pkg/One.kt", SummaryKind.FILE))
        assertEquals("Package.", partial.nodeTexts[""])
        assertEquals("Package.", partial.nodeTexts["src/pkg"])
        assertEquals("First file.", partial.nodeTexts["src/pkg/One.kt"])
        // Once the absorbed folder is summarized, root reads through reading and no longer needs filling.
        assertNull(partial.fillTarget(tree.root))
        assertNull(partial.fillTarget(one))
    }

    @Test fun `fillTarget ignores topic branches and empty trees`() {
        val topics = ReviewTopics(listOf(Source("a.kt"))).assign("a.kt", "Auth > Login")
        val topicTree = buildTopicSummaryTree(topics)
        val topicSummaries = ReviewSummaries(topicTree)
        val topicNode = topicTree.nodes.first { it.isTopicBranch }
        assertNull(topicSummaries.fillTarget(topicNode))

        val empty = ReviewSummaries(buildSummaryTree(emptyList()))
        assertNull(empty.fillTarget(empty.tree.root))
    }

    @Test fun `summaryEntryFill reflects pending running canGenerate and queueing`() {
        val tree = buildSummaryTree(listOf(Source("src/A.kt"), Source("src/B.kt")))
        val a = tree.scopeNode("src/A.kt", SummaryKind.FILE)!!
        val b = tree.scopeNode("src/B.kt", SummaryKind.FILE)!!
        val generated = mutableListOf<Pair<String, SummaryKind>>()
        val actions = ContextSummaryActions(
            available = { path, _ -> path != "src/B.kt" },
            generate = { path, kind -> generated += path to kind; true },
        )

        val pendingSnap = ReviewSummarySnapshot(ReviewSummaries(tree), running = false, canGenerate = true, pendingIds = setOf(a.id))
        val fillA = summaryEntryFill(pendingSnap, a, actions)!!
        assertTrue(fillA.pending)
        assertEquals("Summarizing…", fillA.label)
        assertFalse(fillA.enabled)

        val readySnap = ReviewSummarySnapshot(ReviewSummaries(tree), running = false, canGenerate = false)
        assertFalse(summaryEntryFill(readySnap, a, actions)!!.enabled)

        // SummariesCard keeps canGenerate = true so non-pending rows remain queueable when actions.available allows.
        val card = SummariesCard(ReviewSummaries(tree), running = false, canRun = false, pendingEntries = setOf(b.id))
        val cardFillA = summaryEntryFill(card.summarySnapshot, a, actions)!!
        assertFalse(cardFillA.pending)
        assertEquals("Summarize", cardFillA.label)
        assertTrue(cardFillA.enabled)
        cardFillA.run()
        assertEquals(listOf("src/A.kt" to SummaryKind.FILE), generated)
        assertNull(summaryEntryFill(card.summarySnapshot, a, null))
    }

    @Test fun `root fill with multiple top-level folders marks root pending and Summarizing`() {
        val tree = buildSummaryTree(listOf(Source("alpha/One.kt"), Source("beta/Two.kt")))
        assertEquals("", tree.rootDirectoryPrefix)
        var current = ReviewSummaries(tree)
        assertEquals(tree.root, current.fillTarget(tree.root))

        lateinit var onDone: (Result<ReviewSummaryRun>) -> Unit
        val job = Job()
        val fill = ScopedSummaryFill(
            launcher = ContextSummaryLauncher { _, _, _, done -> onDone = done; job },
            summaries = { current },
            canRun = { true },
            save = { updated, _ -> current = updated },
            changed = {},
        )
        val actions = ContextSummaryActions(
            available = { path, kind -> fill.canFill(path, kind) },
            generate = { path, kind -> fill.request(path, kind) },
        )

        val initialFill = summaryEntryFill(
            ReviewSummarySnapshot(current, pendingIds = fill.pendingIds(current)),
            tree.root,
            actions,
        )!!
        assertFalse(initialFill.pending)
        assertEquals("Summarize", initialFill.label)
        assertTrue(initialFill.enabled)

        initialFill.run()
        assertTrue(fill.busy)
        assertTrue(tree.root.id in fill.pendingIds(current))

        val runningFill = summaryEntryFill(
            ReviewSummarySnapshot(current, pendingIds = fill.pendingIds(current)),
            tree.root,
            actions,
        )!!
        assertTrue(runningFill.pending)
        assertEquals("Summarizing…", runningFill.label)
        assertFalse(runningFill.enabled)

        onDone(Result.success(ReviewSummaryRun(current, 0, null)))
        assertFalse(fill.busy)
        assertTrue(fill.pendingIds(current).isEmpty())
    }

    @Test fun `reviewSummariesJson paginates filters by subtree and includes custom extras`() {
        val tree = buildSummaryTree(listOf(Source("a/One.kt"), Source("b/Two.kt")))
        val one = tree.scopeNode("a/One.kt", SummaryKind.FILE)!!
        val summaries = ReviewSummaries(tree).summarize(mapOf(one.id to Summary.plain("Does one.")))

        val full = JsonParser.parseString(reviewSummariesJson(
            summaries = summaries,
            offset = 0,
            evidence = "test evidence",
            noteWhenEmpty = "empty note",
        ) { node, map -> map["custom"] = node.path }).asJsonObject

        assertEquals("test evidence", full["evidence"].asString)
        assertFalse(full.has("note"))
        val nodes = full["nodes"].asJsonArray.map { it.asJsonObject }
        assertTrue(nodes.any { it["location"].asString == "a/One.kt" && it["summary"].asString == "Does one." && it["custom"].asString == "a/One.kt" })

        val sub = JsonParser.parseString(reviewSummariesJson(
            summaries = summaries,
            offset = 0,
            under = "a",
            evidence = "test evidence",
            noteWhenEmpty = "empty note",
        )).asJsonObject
        assertEquals("a/", sub["under"].asString)
        assertEquals(2, sub["totals"].asJsonObject["nodes"].asInt)
        assertEquals(1, sub["totals"].asJsonObject["files"].asInt)
        assertEquals(2, sub["totals"].asJsonObject["summarized"].asInt)
    }
}
