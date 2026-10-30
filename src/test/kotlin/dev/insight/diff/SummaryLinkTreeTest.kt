package dev.insight.diff

import dev.insight.review.*
import dev.insight.review.ui.*
import org.junit.Assert.*
import org.junit.Test

class SummaryLinkTreeTest {
    private val files = listOf("a.kt", "b.kt").map { ReviewFile(it, "/repo/$it", null, "modified", "-old\n+new\n", "1") }
    private val tree = buildSummaryTree(files)
    private fun summaries(root: Summary) = ReviewSummaries(tree, tree.nodes.associate { node ->
        node.id to if (node.id == 0) root else Summary(listOf(
            SummarySegment("First behavior", listOf(SummarySource.Rows(1, 1))),
            SummarySegment("Second behavior", listOf(SummarySource.Rows(2, 2))),
        ))
    })

    @Test fun independentPartsHaveEmptyRootAndFollowOnlyCitedSegments() {
        val result = summaryLinkTree(summaries(Summary(listOf(
            SummarySegment("One theme", listOf(SummarySource.Claim(0, 0))),
            SummarySegment("Another theme", listOf(SummarySource.Claim(1, 1))),
        ))))
        assertEquals("", result.text)
        assertEquals(listOf("One theme", "Another theme"), result.children.map { it.text })
        assertEquals("First behavior", result.children[0].children.single().text)
        assertEquals("Second behavior", result.children[1].children.single().text)
        assertEquals("a.kt", result.children[0].children.single().children.single().text)
        assertTrue(result.children[1].children.single().children.single().file)
    }

    @Test fun commonClaimIsRootAndBranchesToBothFiles() {
        val result = summaryLinkTree(summaries(Summary(listOf(SummarySegment("Shared purpose", listOf(
            SummarySource.Claim(0, 0), SummarySource.Claim(1, 1),
        ))))))
        assertEquals("Shared purpose", result.text)
        assertEquals(2, result.children.size)
        assertEquals(listOf("a.kt", "b.kt"), result.children.map { it.children.single().owner.path })
    }

    @Test fun missingSummariesKeepFilesReachable() {
        val result = summaryLinkTree(ReviewSummaries(tree))
        assertEquals(listOf("a.kt", "b.kt"), result.children.map { it.text })
        assertTrue(result.children.all { it.file })
    }
}
