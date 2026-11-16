package dev.insight.diff

import dev.insight.review.ContextSummaryLauncher
import dev.insight.review.ContextSummaryRequest
import dev.insight.review.FileSummaryFill
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummaryRun
import dev.insight.review.Summary
import dev.insight.review.SummaryKind
import dev.insight.review.buildSummaryTree
import dev.insight.review.targetedEntries
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSummaryFillTest {
    private val files = listOf("app/a.kt", "app/b.kt", "docs/c.md").map { ReviewFile(it, "/repo/$it", null, "modified", "-old\n+new\n", "1") }
    private val tree = buildSummaryTree(files)
    private fun node(path: String) = tree.nodes.single { it.kind == SummaryKind.FILE && it.path == path }

    private class Launcher : ContextSummaryLauncher {
        val requests = mutableListOf<ContextSummaryRequest>()
        private class Run(val start: ReviewSummaries, val request: ContextSummaryRequest,
                          val updated: (ReviewSummaries) -> Unit, val done: (Result<ReviewSummaryRun>) -> Unit)
        private val pending = mutableListOf<Run>()
        override fun launch(start: ReviewSummaries, request: ContextSummaryRequest,
                            updated: (ReviewSummaries) -> Unit, done: (Result<ReviewSummaryRun>) -> Unit): Job {
            requests += request
            pending += Run(start, request, updated, done)
            return Job()
        }
        /** Writes every requested entry, as the summary pipeline would. */
        fun finish() {
            val run = pending.removeAt(0)
            val written = run.start.summarize(run.request.nodeIds.associateWith { Summary.plain("Generated ${run.start.tree[it].path}") }, reuseSingleChildren = false)
            run.updated(written)
            run.done(Result.success(ReviewSummaryRun(written, 1, null)))
        }
        fun fail(message: String) {
            pending.removeAt(0).done(Result.failure(IllegalStateException(message)))
        }
    }

    private var summaries = ReviewSummaries(tree)
    private val launcher = Launcher()
    private val failures = mutableListOf<String>()
    private var changes = 0
    private val fill = FileSummaryFill(launcher, { summaries }, { true },
        save = { updated, ids -> targetedEntries(summaries, updated, ids).forEach { summaries = summaries.summarize(it) } },
        changed = { changes++ }, failed = { paths, message -> failures += "${paths.joinToString()}: $message" })

    @Test
    fun aRequestedFileIsWrittenIntoTheReviewSummariesThroughTheTargetedPipeline() {
        assertTrue(fill.request("app/a.kt"))
        assertEquals(setOf("app/a.kt"), fill.pending)
        val request = launcher.requests.single()
        assertEquals("Only the file's own missing entries are generated", setOf(node("app/a.kt").id), request.nodeIds)
        assertFalse(request.regenerate)

        launcher.finish()
        assertEquals("Generated app/a.kt", summaries.text(node("app/a.kt").id))
        assertNull("Other files are left for later runs", summaries.text(node("app/b.kt").id))
        assertTrue(fill.pending.isEmpty())
        assertFalse("A file with a summary needs no fill", fill.request("app/a.kt"))
        assertTrue(changes > 0)
    }

    @Test
    fun filesRequestedDuringARunWaitAndGoTogetherInTheNext() {
        fill.request("app/a.kt")
        assertTrue(fill.request("app/b.kt"))
        assertTrue(fill.request("docs/c.md"))
        assertTrue("A pending file is not asked for twice", fill.request("app/b.kt"))
        assertEquals(1, launcher.requests.size)
        assertEquals(setOf("app/a.kt", "app/b.kt", "docs/c.md"), fill.pending)

        launcher.finish()
        assertEquals(2, launcher.requests.size)
        assertEquals(setOf(node("app/b.kt").id, node("docs/c.md").id), launcher.requests[1].nodeIds)
        launcher.finish()
        assertTrue(fill.pending.isEmpty())
        assertEquals(listOf("app/a.kt", "app/b.kt", "docs/c.md").map { "Generated $it" },
            listOf("app/a.kt", "app/b.kt", "docs/c.md").map { summaries.text(node(it).id) })
    }

    @Test
    fun aFailedRunIsReportedAndLeavesTheFileMissing() {
        fill.request("app/a.kt")
        launcher.fail("No model")
        assertEquals(listOf("app/a.kt: No model"), failures)
        assertTrue(fill.pending.isEmpty())
        assertNull(summaries.text(node("app/a.kt").id))
        assertTrue("It can be asked for again", fill.request("app/a.kt"))
    }

    @Test
    fun unknownFilesAndBlockedRunsAreRefused() {
        assertFalse(fill.request("missing.kt"))
        val blocked = FileSummaryFill(launcher, { summaries }, { false }, { _, _ -> }, {})
        assertFalse(blocked.request("app/a.kt"))
        assertTrue(launcher.requests.isEmpty())
    }
}
