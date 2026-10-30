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

    @Test fun fullSummaryListsShownAncestorsAndMarksOnlyTheRoutesParts() {
        val summaries = summaries(Summary(listOf(
            SummarySegment("One theme", listOf(SummarySource.Claim(0, 0))),
            SummarySegment("Another theme", listOf(SummarySource.Claim(1, 1))),
        )))
        val result = summaryLinkTree(summaries)
        val second = result.fullSummary(tree, result.children[1].children.single().key)
        assertEquals(listOf(tree.root.id to 0, tree.nodes.single { it.path == "b.kt" }.id to 1), second.map { it.node.id to it.depth })
        assertEquals(listOf(setOf(1), setOf(1)), second.map { it.route })
        val part = result.fullSummary(tree, result.children[0].key)
        assertEquals(listOf(tree.root.id), part.map { it.node.id })
        assertEquals(setOf(0), part.single().route)
        assertTrue(result.fullSummary(tree, "missing").isEmpty())

        // Folders absorbed into the root, and the parts merged into a leaf, read as the entries they show as.
        val nested = buildSummaryTree(listOf(ReviewFile("src/main/a.kt", "/repo/src/main/a.kt", null, "modified", "-old\n+new\n", "1")))
        val repeated = ReviewSummaries(nested, nested.nodes.associate {
            it.id to Summary(listOf(SummarySegment("Reads storage", listOf(
                if (it.kind == SummaryKind.FILE) SummarySource.Rows(1, 1) else SummarySource.Claim(0, 0)))))
        })
        val leaf = summaryLinkTree(repeated)
        val chain = leaf.fullSummary(nested, leaf.key)
        assertEquals(listOf(SummaryKind.ROOT, SummaryKind.FILE), chain.map { it.node.kind })
        assertTrue(chain.all { it.route == setOf(0) })
    }

    @Test fun pathNavigationFindsEveryOccurrenceAndOnlyItsAncestors() {
        val result = summaryLinkTree(summaries(Summary(listOf(
            SummarySegment("First route", listOf(SummarySource.Claim(0, 0))),
            SummarySegment("Second route", listOf(SummarySource.Claim(0, 1))),
            SummarySegment("Other file", listOf(SummarySource.Claim(1, 0))),
        ))))
        val first = result.children[0]
        val second = result.children[1]
        val expected = setOf(result.key, first.key, first.children.single().key,
            second.key, second.children.single().key)
        assertEquals(expected, result.pathKeys("a.kt"))
        assertEquals(expected, result.pathKeys(" ./a.kt "))
        assertEquals(expected, result.pathKeys("\"/repo/a.kt\""))
        assertEquals(expected, result.pathKeys("\\repo\\a.kt"))
        assertTrue(result.pathKeys("missing/a.kt").isEmpty())
        assertTrue(result.pathKeys("kt").isEmpty())
        assertTrue(result.pathKeys("").isEmpty())
    }
}
