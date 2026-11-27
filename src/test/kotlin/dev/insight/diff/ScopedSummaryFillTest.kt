package dev.insight.diff

import dev.insight.review.ContextSummaryLauncher
import dev.insight.review.ContextSummaryRequest
import dev.insight.review.ReviewDiskCache
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummaryRun
import dev.insight.review.ScopedSummaryFill
import dev.insight.review.Summary
import dev.insight.review.SummaryKind
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScopedSummaryFillTest {
    @get:Rule val temp = TemporaryFolder()
    private val files = listOf("app/a.kt", "app/b.kt", "docs/c.md").map { ReviewFile(it, "/repo/$it", null, "modified", "-old\n+new\n", "1") }
    private val comparisonSnapshot = ReviewSnapshot(files, emptyList())
    private val state = DiffReviewState().apply { update(comparisonSnapshot) }
    private val summaries: ReviewSummaries get() = state.summaries
    private fun node(path: String, kind: SummaryKind = SummaryKind.FILE) = summaries.tree.nodes.single { it.kind == kind && it.path == path }

    private class Launcher : ContextSummaryLauncher {
        val requests = mutableListOf<ContextSummaryRequest>()
        val jobs = mutableListOf<Job>()
        var failOnLaunch = false
        private class Run(val start: ReviewSummaries, val request: ContextSummaryRequest,
                          val updated: (ReviewSummaries) -> Unit, val done: (Result<ReviewSummaryRun>) -> Unit)
        private val pending = mutableListOf<Run>()
        override fun launch(start: ReviewSummaries, request: ContextSummaryRequest,
                            updated: (ReviewSummaries) -> Unit, done: (Result<ReviewSummaryRun>) -> Unit): Job {
            check(!failOnLaunch) { "Could not start the model" }
            requests += request
            pending += Run(start, request, updated, done)
            return Job().also { jobs += it }
        }
        /** Writes every requested entry, as the summary pipeline would. */
        fun finish(writeIds: Set<Int>? = null) {
            val run = pending.removeAt(0)
            val entries = (writeIds ?: run.request.nodeIds).associateWith { Summary.plain("Generated ${run.start.tree[it].path}") }
            val written = run.start.summarize(entries, reuseSingleChildren = false)
            run.updated(written)
            run.done(Result.success(ReviewSummaryRun(written, 1, null)))
        }
        fun fail(message: String) {
            pending.removeAt(0).done(Result.failure(IllegalStateException(message)))
        }
    }

    private val launcher = Launcher()
    private val failures = mutableListOf<ScopedSummaryFill.Failure>()
    private var changes = 0
    private var ready = true
    private val fill = ScopedSummaryFill(launcher, { summaries }, { ready },
        save = { updated, ids -> state.fillSummaries(updated, ids) },
        changed = { changes++ }, failed = { failures += it })

    @Test
    fun aRequestedFileIsWrittenIntoTheReviewSummariesThroughTheTargetedPipeline() {
        assertTrue(fill.request("app/a.kt", SummaryKind.FILE))
        assertEquals(setOf("app/a.kt"), fill.pendingFiles)
        val request = launcher.requests.single()
        assertEquals("Only the file's own missing entries are generated", setOf(node("app/a.kt").id), request.nodeIds)
        assertFalse(request.regenerate)
        assertEquals("Summarizing app/a.kt", request.progressTitle)

        launcher.finish()
        assertEquals("Generated app/a.kt", summaries.text(node("app/a.kt").id))
        assertNull("Other files are left for later runs", summaries.text(node("app/b.kt").id))
        assertTrue(fill.pendingFiles.isEmpty())
        assertFalse("A file with a summary needs no fill", fill.request("app/a.kt", SummaryKind.FILE))
        assertTrue(changes > 0)
    }

    @Test
    fun fillingAFileKeepsItsSingleChildDirectoryOutsideTheRequestedScope() {
        assertTrue(fill.request("docs/c.md", SummaryKind.FILE))
        launcher.finish()

        assertEquals("Generated docs/c.md", summaries.text(node("docs/c.md").id))
        assertNull(summaries.text(node("docs", SummaryKind.DIRECTORY).id))
        assertTrue(fill.canFill("docs", SummaryKind.DIRECTORY))
        assertTrue(fill.request("docs", SummaryKind.DIRECTORY))
        assertEquals(setOf(node("docs", SummaryKind.DIRECTORY).id), launcher.requests.last().nodeIds)
    }

    @Test
    fun aDirectoryFillsOnlyItsMissingSubtreeAndPersistsTheResult() {
        val cache = temp.newFolder().toPath()
        fun open() = DiffReviewState(cache = ReviewDiskCache(cache)).apply { update(comparisonSnapshot) }
        val tab = open()
        val folder = node("app", SummaryKind.DIRECTORY)
        val a = node("app/a.kt")
        val b = node("app/b.kt")
        tab.summarize(tab.revision, mapOf(a.id to Summary.plain("Cached a.")))
        val directoryFill = ScopedSummaryFill(launcher, { tab.summaries }, { true },
            save = { updated, ids -> tab.fillSummaries(updated, ids) }, changed = {})

        assertTrue(directoryFill.request("app", SummaryKind.DIRECTORY))
        assertTrue(directoryFill.pending("app", SummaryKind.DIRECTORY))
        assertFalse(directoryFill.canFill("app/b.kt", SummaryKind.FILE))
        assertEquals(setOf("app/b.kt"), directoryFill.pendingFiles)
        assertEquals(setOf(b.id, folder.id), launcher.requests.single().nodeIds)

        launcher.finish()
        val restored = open().summaries
        assertEquals("Cached a.", restored.text(a.id))
        assertEquals("Generated app/b.kt", restored.text(b.id))
        assertEquals("Generated app", restored.text(folder.id))
        assertNull(restored.text(restored.tree.root.id))
        assertNull(restored.text(node("docs/c.md").id))
        assertFalse(directoryFill.canFill("app", SummaryKind.DIRECTORY))
    }

    @Test
    fun filesRequestedDuringARunWaitAndGoTogetherInTheNext() {
        fill.request("app/a.kt", SummaryKind.FILE)
        assertTrue(fill.request("app/b.kt", SummaryKind.FILE))
        assertTrue(fill.request("docs/c.md", SummaryKind.FILE))
        assertTrue("A pending file is not asked for twice", fill.request("app/b.kt", SummaryKind.FILE))
        assertEquals(1, launcher.requests.size)
        assertEquals(setOf("app/a.kt", "app/b.kt", "docs/c.md"), fill.pendingFiles)

        launcher.finish()
        assertEquals(2, launcher.requests.size)
        assertEquals(setOf(node("app/b.kt").id, node("docs/c.md").id), launcher.requests[1].nodeIds)
        launcher.finish()
        assertTrue(fill.pendingFiles.isEmpty())
        assertEquals(listOf("app/a.kt", "app/b.kt", "docs/c.md").map { "Generated $it" },
            listOf("app/a.kt", "app/b.kt", "docs/c.md").map { summaries.text(node(it).id) })
    }

    @Test
    fun queuedFolderIncludesItsFilesAndSkipsWhatTheCurrentRunFilled() {
        fill.request("app/a.kt", SummaryKind.FILE)
        fill.request("app/b.kt", SummaryKind.FILE)
        assertTrue(fill.request("app", SummaryKind.DIRECTORY))
        assertTrue(fill.request("app/b.kt", SummaryKind.FILE))

        launcher.finish()
        assertEquals(setOf(node("app/b.kt").id, node("app", SummaryKind.DIRECTORY).id), launcher.requests[1].nodeIds)
        assertEquals(setOf("app/b.kt"), fill.pendingFiles)
        launcher.finish()
        assertFalse(fill.busy)
    }

    @Test
    fun pendingQueueResumesOnceTheReviewIsAvailable() {
        fill.request("app/a.kt", SummaryKind.FILE)
        fill.request("docs/c.md", SummaryKind.FILE)
        ready = false
        launcher.finish()
        fill.resume()
        assertEquals(1, launcher.requests.size)
        assertEquals(setOf("docs/c.md"), fill.pendingFiles)

        ready = true
        fill.resume()
        assertEquals(2, launcher.requests.size)
        launcher.finish()
        assertFalse(fill.busy)
    }

    @Test
    fun cancelledWritesCannotLandInTheReview() {
        fill.request("app", SummaryKind.DIRECTORY)
        fill.request("docs/c.md", SummaryKind.FILE)
        fill.cancel()
        assertFalse(fill.busy)
        launcher.finish()
        assertEquals(0, summaries.count)
        assertEquals(1, launcher.requests.size)
        assertTrue(fill.request("docs/c.md", SummaryKind.FILE))
    }

    @Test
    fun cancellingOwnedRunPreservesQueuedFillsAndRejectsLateWrites() {
        assertTrue(fill.request("docs/c.md", SummaryKind.FILE))
        val cancel = checkNotNull(fill.requestOwned("app/a.kt", SummaryKind.FILE))
        assertTrue(fill.request("app/b.kt", SummaryKind.FILE))
        launcher.finish()
        assertEquals(setOf(node("app/a.kt").id), launcher.requests.last().nodeIds)
        cancel()

        assertTrue(launcher.jobs[1].isCancelled)
        assertFalse(launcher.jobs[2].isCancelled)
        assertEquals(setOf("app/b.kt"), fill.pendingFiles)
        launcher.finish()
        assertNull(summaries.text(node("app/a.kt").id))
        launcher.finish()
        assertEquals("Generated app/b.kt", summaries.text(node("app/b.kt").id))
        assertTrue(failures.isEmpty())

        assertTrue(fill.request("app/a.kt", SummaryKind.FILE))
        cancel()
        assertFalse(launcher.jobs.last().isCancelled)
    }

    @Test
    fun cancellingQueuedOwnedFillPreservesRunningAndQueuedOrdinaryFills() {
        assertTrue(fill.request("app/a.kt", SummaryKind.FILE))
        assertNull(fill.requestOwned("app/a.kt", SummaryKind.FILE))
        val cancel = checkNotNull(fill.requestOwned("app/b.kt", SummaryKind.FILE))
        assertTrue(fill.request("docs/c.md", SummaryKind.FILE))
        assertNull(fill.requestOwned("docs/c.md", SummaryKind.FILE))
        cancel()

        assertFalse(launcher.jobs.single().isCancelled)
        assertEquals(setOf("app/a.kt", "docs/c.md"), fill.pendingFiles)
        launcher.finish()
        assertEquals(setOf(node("docs/c.md").id), launcher.requests.last().nodeIds)
        launcher.finish()
        assertNull(summaries.text(node("app/b.kt").id))
    }

    @Test
    fun coalescedOwnedFillLetsOrdinaryFolderRequestFinish() {
        assertTrue(fill.request("docs/c.md", SummaryKind.FILE))
        val cancel = checkNotNull(fill.requestOwned("app/b.kt", SummaryKind.FILE))
        assertTrue(fill.request("app", SummaryKind.DIRECTORY))
        launcher.finish()
        cancel()

        assertFalse(launcher.jobs.last().isCancelled)
        assertEquals(setOf(node("app/a.kt").id, node("app/b.kt").id, node("app", SummaryKind.DIRECTORY).id),
            launcher.requests.last().nodeIds)
        launcher.finish()
        assertEquals("Generated app/b.kt", summaries.text(node("app/b.kt").id))
    }

    @Test
    fun aFailedRunIsReportedAndLeavesTheFileMissing() {
        fill.request("app/a.kt", SummaryKind.FILE)
        launcher.fail("No model")
        val failure = failures.single()
        assertEquals(setOf(ScopedSummaryFill.Target("app/a.kt", SummaryKind.FILE)), failure.targets)
        assertEquals(1, failure.missingCount)
        assertEquals("No model", failure.reason)
        assertTrue(fill.pendingFiles.isEmpty())
        assertNull(summaries.text(node("app/a.kt").id))
        assertTrue("It can be asked for again", fill.request("app/a.kt", SummaryKind.FILE))
    }

    @Test
    fun aPartialDirectoryResultReportsTheMissingEntriesAndRetriesOnlyThose() {
        fill.request("app", SummaryKind.DIRECTORY)
        launcher.finish(setOf(node("app/a.kt").id))
        val failure = failures.single()
        assertEquals(setOf(ScopedSummaryFill.Target("app", SummaryKind.DIRECTORY)), failure.targets)
        assertEquals(2, failure.missingCount)
        assertTrue(fill.request("app", SummaryKind.DIRECTORY))
        assertEquals(setOf(node("app/b.kt").id, node("app", SummaryKind.DIRECTORY).id), launcher.requests.last().nodeIds)
    }

    @Test
    fun aThrowingLauncherReportsFailureAndReleasesTheQueue() {
        launcher.failOnLaunch = true
        fill.request("app/a.kt", SummaryKind.FILE)
        assertEquals("Could not start the model", failures.single().reason)
        assertFalse(fill.busy)
        launcher.failOnLaunch = false
        assertTrue(fill.request("app/a.kt", SummaryKind.FILE))
        launcher.finish()
        assertFalse(fill.busy)
    }

    @Test
    fun unknownFilesAndBlockedRunsAreRefused() {
        assertFalse(fill.request("missing.kt", SummaryKind.FILE))
        assertFalse(fill.request("app", SummaryKind.FILE))
        assertFalse(fill.request("app/a.kt", SummaryKind.DIRECTORY))
        val blocked = ScopedSummaryFill(launcher, { summaries }, { false }, { _, _ -> }, {})
        assertFalse(blocked.request("app/a.kt", SummaryKind.FILE))
        assertTrue(launcher.requests.isEmpty())
    }
}
