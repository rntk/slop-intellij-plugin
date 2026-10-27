package dev.insight.diff

import dev.insight.codereview.CodeFile
import dev.insight.codereview.CodeReviewState
import dev.insight.codereview.CodeScope
import dev.insight.codereview.CodeSnapshot
import dev.insight.review.ReviewDiskCache
import dev.insight.review.ReviewedFiles
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SummaryReviewTest {
    @get:Rule val temp = TemporaryFolder()
    private fun file(path: String, hash: String = "1") = ReviewFile(path, "/repo/$path", null, "modified", "patch", hash)
    private fun disk() = ReviewDiskCache(temp.root.toPath().resolve("cache"))

    @Test fun `directory marks persist and a changed diff clears only that file`() {
        val files = listOf(file("src/a.kt"), file("src/b.kt"), file("doc.md"))
        val snapshot = ReviewSnapshot(files, emptyList())
        fun state(next: ReviewSnapshot = snapshot) = DiffReviewState(cache = disk()).apply { update(next) }
        val original = state()
        val directory = original.summaries.tree.nodes.single { it.path == "src" }
        original.setReviewed(directory, true)
        assertTrue(original.reviewed.isReviewed(directory))
        assertFalse(original.reviewed.isReviewed(original.summaries.tree.root))
        val restored = state()
        assertTrue(restored.reviewed.isReviewed(directory))
        restored.update(snapshot.copy(files = listOf(files[0].copy(fingerprint = "2"), files[1], files[2])))
        val tree = restored.summaries.tree
        assertFalse(restored.reviewed.isReviewed(tree.nodes.single { it.path == "src/a.kt" }))
        assertTrue(restored.reviewed.isReviewed(tree.nodes.single { it.path == "src/b.kt" }))
        assertFalse(restored.reviewed.isReviewed(tree.nodes.single { it.path == "src" }))
        restored.setReviewed(tree.nodes.single { it.path == "src" }, false)
        // Unmarking the new evidence leaves the old version's mark intact.
        assertEquals(setOf(ReviewedFiles.key(files[0])), state().reviewed.keys)
    }

    @Test fun `source reviews keep marks across regeneration but invalidate edited evidence`() {
        val files = listOf(CodeFile("a.kt", "/repo/a.kt", "val a = 1"), CodeFile("b.kt", "/repo/b.kt", "val b = 1"))
        val snapshot = CodeSnapshot(CodeScope("", "/repo"), files)
        fun state() = CodeReviewState(cache = disk()).apply { update(snapshot) }
        val original = state()
        original.setReviewed(original.summaries.tree.root, true)
        original.clearSummaries()
        val restored = state()
        assertTrue(restored.reviewed.isReviewed(restored.summaries.tree.root))
        restored.update(snapshot.copy(files = listOf(CodeFile("a.kt", "/repo/a.kt", "val a = 2"), files[1])))
        assertFalse(restored.reviewed.isReviewed(restored.summaries.tree.root))
        assertTrue(restored.reviewed.isReviewed(restored.summaries.tree.nodes.single { it.path == "b.kt" }))
        restored.setReviewed(restored.summaries.tree.root, false)
        assertEquals(setOf(ReviewedFiles.key(files[0])), state().reviewed.keys)
    }

    @Test fun `switching diff comparisons isolates review marks`() {
        val snapshot = ReviewSnapshot(listOf(file("a.kt")), emptyList())
        for (cache in listOf(null, disk())) {
            val state = DiffReviewState(cache = cache).apply { update(snapshot) }
            state.setReviewed(state.summaries.tree.root, true)
            state.update(snapshot.copy(comparison = ReviewComparison(ReviewDiffRequest(ReviewDiffMode.REVISIONS, "/repo", "main", "dev"))))
            assertTrue(state.reviewed.keys.isEmpty())
            state.update(snapshot)
            if (cache != null) assertTrue(state.reviewed.isReviewed(state.summaries.tree.root))
        }
    }
}
