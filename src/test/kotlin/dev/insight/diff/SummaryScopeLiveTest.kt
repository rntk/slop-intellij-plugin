package dev.insight.diff

import dev.insight.llm.LlamaCppClient
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummaryOptions
import dev.insight.review.SummaryKind
import dev.insight.review.SummarySource
import dev.insight.review.buildSummaryTree
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Duration

/**
 * Evaluates file summaries against a live llama.cpp server; set `INSIGHT_LIVE=1` (and optionally `INSIGHT_LLM_URL`).
 * Related sibling files share one request, so a summary that borrows its neighbor's change shows here as the neighbor's
 * vocabulary; a deleted file must read as removed behavior.
 */
class SummaryScopeLiveTest {
    private fun file(path: String, status: String, patch: String) = ReviewFile(path, "/repo/$path", null, status, patch, "1")

    /** Drops the background scoring job; dimming is untouched here. */
    private val scoring = file("review/ReviewTabSupport.kt", "modified", """
        @@ -40,14 +40,6 @@ internal class ReviewTabSupport(
             fun refresh(summaries: ReviewSummaries) {
                 view.show(summaries)
        -        scoreJob?.cancel()
        -        scoreJob = scope.launch(Dispatchers.Default) {
        -            val scores = scorer.score(summaries)
        -            withContext(Dispatchers.EDT) { view.showScores(scores) }
        -        }
             }
        -
        -    private var scoreJob: Job? = null
        -    private val scorer = SummaryScorer(settings)
         }
    """.trimIndent() + "\n")

    /** Computes the dimming of reviewed rows synchronously; nothing about scoring. */
    private val dimming = file("review/SummaryReviewView.kt", "modified", """
        @@ -22,9 +22,8 @@ internal class SummaryReviewView(
             fun markReviewed(paths: Set<String>) {
                 reviewed += paths
        -        dimJob?.cancel()
        -        dimJob = scope.launch { applyDimming(links.dimmed(reviewed)) }
        +        applyDimming(links.dimmed(reviewed))
             }
        -    private var dimJob: Job? = null
    """.trimIndent() + "\n")

    private val deleted = file("review/WordLinker.kt", "deleted", """
        @@ -1,8 +0,0 @@
        -package dev.insight.review
        -
        -/** Links each word of a parent summary to the child summaries that mention it. */
        -internal class WordLinker {
        -    fun link(parent: String, children: List<String>): Map<Int, Set<Int>> =
        -        parent.split(' ').withIndex().associate { (index, word) -> index to children.indices.filter { word in children[it] }.toSet() }
        -}
    """.trimIndent() + "\n")

    @Test fun `each file summary stays within its own patch and a deleted file reads as removed`() = runBlocking {
        assumeTrue(System.getenv("INSIGHT_LIVE") == "1")
        val client = LlamaCppClient(System.getenv("INSIGHT_LLM_URL") ?: "http://192.168.0.147:8989", requestTimeout = Duration.ofSeconds(180))
        val tree = buildSummaryTree(listOf(scoring, dimming, deleted))
        var saved = ReviewSummaries(tree)
        diffSummarizer(client::complete, null, ReviewSummaryOptions(concurrency = 1))
            .run(saved, "", { saved.summarize(it).also { next -> saved = next } }, nodeIds = tree.nodes.filter { it.kind == SummaryKind.FILE }.map { it.id }.toSet())
        fun summary(file: ReviewFile) = saved.summary(tree.nodes.single { it.location == file.path }.id) ?: error("No summary for ${file.path}")
        listOf(scoring, dimming, deleted).forEach { println("${it.path}: ${summary(it).text}") }

        val scoringText = summary(scoring).text.lowercase()
        val dimmingText = summary(dimming).text.lowercase()
        assertFalse("The scoring file borrowed the dimming change: $scoringText", "dim" in scoringText || "synchron" in scoringText)
        assertFalse("The dimming file borrowed the scoring change: $dimmingText", "scor" in dimmingText)
        assertTrue("A deleted file describes removed behavior: ${summary(deleted).text}",
            Regex("remov|delet|drop|no longer|eliminat").containsMatchIn(summary(deleted).text.lowercase()))
        // Every claim of a leaf cites rows inside its own patch.
        for (file in listOf(scoring, dimming, deleted)) {
            val rows = 1..patchRows(file.patch)
            summary(file).segments.flatMap { it.sources }.forEach { source ->
                assertTrue(source is SummarySource.Rows && source.first in rows && source.last in rows)
            }
        }
    }
}
