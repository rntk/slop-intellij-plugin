package dev.insight.diff

import dev.insight.review.summarize
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewTopics
import dev.insight.review.buildTopicSummaryTree
import dev.insight.review.targetedEntries
import org.junit.Assert.*
import org.junit.Test

class TargetedEntriesTest {
    private val files = listOf("app/a.kt", "app/b.kt", "docs/c.kt", "docs/d.kt").map {
        ReviewFile(it, "/repo/$it", null, "modified", "-old\n+new", "1")
    }
    private val tree = buildTopicSummaryTree(ReviewTopics(files, files.associate {
        it.path to if (it.path.startsWith("app/")) "App" else "Docs"
    }))
    private val app = tree.nodes.single { it.identity == "topic:app" }
    private val docs = tree.nodes.single { it.identity == "topic:docs" }

    @Test fun `targeted saves keep seeded dependencies without saving unrelated topics`() {
        val current = ReviewSummaries(tree)
        val updated = ReviewSummaries(tree, tree.nodes.associate { it.id to "Summary ${it.identity}" })
        val batches = targetedEntries(current, updated, setOf(app.id))
        val entries = batches.flatMap { it.entries }.associate { it.key to it.value }

        assertEquals(setOf(app.id) + app.children, entries.keys)
        assertFalse(entries.containsKey(docs.id))
        assertFalse(entries.containsKey(tree.root.id))
        var saved = current
        batches.forEach { saved = saved.summarize(it) }
        assertEquals(updated.text(app.id), saved.text(app.id))
        app.children.forEach { assertEquals(updated.text(it), saved.text(it)) }
        assertNull(saved.text(docs.id))
    }

    @Test fun `targeted saves preserve existing dependencies and wait for missing children`() {
        val first = app.children.first()
        val current = ReviewSummaries(tree, mapOf(first to "Existing child"))
        val partial = ReviewSummaries(tree, mapOf(app.id to "New parent", first to "Seeded child"))
        assertTrue(targetedEntries(current, partial, setOf(app.id)).isEmpty())

        val complete = ReviewSummaries(tree, tree.nodes.associate { it.id to "New ${it.identity}" })
        val entries = targetedEntries(current, complete, setOf(app.id)).flatMap { it.entries }
            .associate { it.key to it.value }
        assertEquals(setOf(app.id) + app.children.drop(1), entries.keys)
        assertFalse(entries.containsKey(first))
        assertTrue(targetedEntries(current, complete, emptySet()).isEmpty())
    }
}
