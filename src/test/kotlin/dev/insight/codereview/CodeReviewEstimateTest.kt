package dev.insight.codereview

import dev.insight.review.ReviewSourceRange
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummarizer
import dev.insight.review.ReviewTopics
import dev.insight.review.summarize
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test

class CodeReviewEstimateTest {
    private val big = codeFile("src/lib/Big.kt", largeSource(300))
    private val files = (1..30).map { codeFile("src/app/F$it.kt", "fun f$it() = $it\n") } +
        listOf(codeFile("docs/README.md", "# Steps\n"), big)
    private val snapshot = codeSnapshot(*files.toTypedArray())

    @Test fun `the estimate matches the requests of a full run`() = runBlocking {
        val tree = codeSummaryTree(snapshot.files)
        val evidence = CodeSummaryEvidence(snapshot)
        val options = CODE_SUMMARY_OPTIONS.copy(concurrency = 1)
        val estimate = estimateSummaryRequests(ReviewSummaries(tree), options, evidence::size)
        val model = SummaryModel()
        var saved = ReviewSummaries(tree)
        val run = ReviewSummarizer(model::complete, null, options, evidence).run(saved, "", { saved.summarize(it).also { s -> saved = s } })
        Assert.assertTrue(run.summaries.complete)
        Assert.assertEquals(run.requests, estimate)
    }

    @Test fun `the estimate counts only missing work and restarts complete trees`() {
        val tree = codeSummaryTree(snapshot.files)
        val evidence = CodeSummaryEvidence(snapshot)
        val full = estimateSummaryRequests(ReviewSummaries(tree), CODE_SUMMARY_OPTIONS, evidence::size)
        val complete = fillAll(ReviewSummaries(tree))
        Assert.assertEquals(full, estimateSummaryRequests(complete, CODE_SUMMARY_OPTIONS, evidence::size))
        Assert.assertEquals(0, estimateSummaryRequests(complete, CODE_SUMMARY_OPTIONS, evidence::size, restartComplete = false))
        // Only the root is missing: one branch request.
        val rootless = ReviewSummaries(tree, tree.nodes.filter { it.id != tree.root.id }.associate { it.id to "x" })
        Assert.assertEquals(1, estimateSummaryRequests(rootless, CODE_SUMMARY_OPTIONS, evidence::size))
        Assert.assertEquals(0, estimateSummaryRequests(ReviewSummaries(codeSummaryTree(emptyList())), CODE_SUMMARY_OPTIONS, evidence::size))
    }

    @Test fun `topic estimates count missing files or every file of a complete result`() {
        val topics = ReviewTopics(files)
        Assert.assertEquals(files.size, estimateTopicRequests(topics))
        val partial = topics.assign(files[0].path, "Code>Steps").assign(files[1].path, "Code>Steps")
        Assert.assertEquals(files.size - 2, estimateTopicRequests(partial))
        val complete = files.fold(topics) { acc, file -> acc.assign(file.path, "Code>Steps") }
        Assert.assertEquals(files.size, estimateTopicRequests(complete))
    }

    @Test fun `source blocks show a part or the head of a file within the limits`() {
        val part = big.chunks[1].lines
        Assert.assertEquals(ReviewSourceRange(big.path, part.first, minOf(part.last, part.first + 199), CODE_SOURCE_SIDE), sourceBlockRange(big, part))
        val head = sourceBlockRange(big, null)!!
        Assert.assertEquals(1, head.startLine)
        Assert.assertTrue(head.endLine <= 200 && big.lines(1..head.endLine).length <= CODE_SOURCE_CHARS)
        val wide = codeFile("src/Wide.kt", (1..200).joinToString("\n") { "x".repeat(300) })
        val cut = sourceBlockRange(wide, null)!!
        Assert.assertTrue(wide.lines(1..cut.endLine).length <= CODE_SOURCE_CHARS)
        Assert.assertTrue(wide.lines(1..cut.endLine + 1).length > CODE_SOURCE_CHARS)
        Assert.assertNull(sourceBlockRange(codeFile("src/Line.kt", "y".repeat(CODE_SOURCE_CHARS + 1)), null))
        Assert.assertNull(sourceBlockRange(codeFile("src/Empty.kt", ""), null))
    }

    @Test fun `status line counts files skipped files and parts and suggests a subdirectory when cut`() {
        val loaded = snapshot.copy(skipped = listOf(SkippedFile("src/a.png", SkipReason.BINARY), SkippedFile("src/z.kt", SkipReason.LIMIT)),
            truncated = true, overLimit = 1_200)
        Assert.assertEquals("32 files · 1,201 skipped · 1 large file split into ${big.chunks.size} parts · Stopped at the scope limit: " +
            "1,200 files not loaded. Choose a subdirectory.", codeStatusText(loaded))
    }
}
