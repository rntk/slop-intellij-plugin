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
        val leaf = result.children[0].children.single()
        assertTrue(leaf.file && leaf.children.isEmpty())
        assertEquals("a.kt", leaf.owner.path)
        assertTrue(result.children[1].children.single().file)
    }

    @Test fun commonClaimIsRootAndBranchesToBothFiles() {
        val result = summaryLinkTree(summaries(Summary(listOf(SummarySegment("Shared purpose", listOf(
            SummarySource.Claim(0, 0), SummarySource.Claim(1, 1),
        ))))))
        assertEquals("Shared purpose", result.text)
        assertEquals(2, result.children.size)
        assertEquals(listOf("a.kt", "b.kt"), result.children.map { it.owner.path })
        assertEquals(listOf("First behavior", "Second behavior"), result.children.map { it.text })
        assertTrue(result.children.all { it.file })
    }

    @Test fun chainOfSingleEntriesRepeatingOneSummaryReadsOnce() {
        val nested = buildSummaryTree(listOf(ReviewFile("src/main/a.kt", "/repo/src/main/a.kt", null, "modified", "-old\n+new\n", "1")))
        fun summaries(text: (SummaryNode) -> String) = ReviewSummaries(nested, nested.nodes.associate {
            it.id to Summary(listOf(SummarySegment(text(it), listOf(
                if (it.kind == SummaryKind.FILE) SummarySource.Rows(1, 1) else SummarySource.Claim(0, 0)))))
        })
        val result = summaryLinkTree(summaries { "Reads storage" })
        assertEquals("Reads storage", result.text)
        assertTrue(result.file && result.children.isEmpty())
        assertEquals("src/main/a.kt", result.directory())

        val differing = generateSequence(summaryLinkTree(summaries { "Level ${it.id}" })) { it.children.singleOrNull() }.map { it.text }.toList()
        assertEquals(listOf("Level 0", "Level 1", "Level 2", "Level 3"), differing)
    }

    @Test fun missingSummariesKeepFilesReachable() {
        val result = summaryLinkTree(ReviewSummaries(tree))
        assertEquals(listOf("a.kt", "b.kt"), result.children.map { it.text })
        assertTrue(result.children.all { it.file })
    }
}
