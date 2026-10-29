package dev.insight.diff

import dev.insight.review.ContextSummaryLauncher
import dev.insight.review.ContextSummaryRequest
import dev.insight.review.ReviewSummaries
import dev.insight.review.Summary
import dev.insight.review.ReviewSummaryRun
import dev.insight.review.buildSummaryTree
import dev.insight.review.summarize
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import dev.insight.review.ui.CanvasSummarySession

class CanvasSummarySessionTest {
    private val file = ReviewFile("a.kt", "/repo/a.kt", null, "modified", "-old\n+new\n", "1")
    private val tree = buildSummaryTree(listOf(file))
    private val start = ReviewSummaries(tree)
    private val request = ContextSummaryRequest("query", tree.summarizable.mapTo(hashSetOf()) { it.id })
    private val completed = start.summarize(request.nodeIds.associateWith { Summary.plain("Completed summary") })

    private class Launcher : ContextSummaryLauncher {
        val job = Job()
        lateinit var updated: (ReviewSummaries) -> Unit
        lateinit var done: (Result<ReviewSummaryRun>) -> Unit
        override fun launch(start: ReviewSummaries, request: ContextSummaryRequest,
                            updated: (ReviewSummaries) -> Unit, done: (Result<ReviewSummaryRun>) -> Unit): Job {
            this.updated = updated
            this.done = done
            return job
        }
    }

    @Test fun `versions and topic runs share a single generation slot`() {
        val launcher = Launcher()
        val session = CanvasSummarySession(launcher, { tree }, {})
        session.startVersion(start, request) {}
        session.startTopics(start, request) { _, _ -> fail("Must not start a concurrent topic run") }
        session.startVersion(start, request) { fail("Must not create a concurrent version") }
        assertEquals(1, session.versions.size)
        launcher.done(Result.success(ReviewSummaryRun(completed, 1, null)))
        assertFalse(session.running)
        session.startTopics(start, request) { _, _ -> }
        assertTrue(session.topicsRunning)
        session.startVersion(start, request) { fail("Must wait for the topic run") }
        session.dispose()
    }

    @Test fun `synchronous topic completion is saved after the starting snapshot`() {
        val saved = mutableListOf<ReviewSummaries>()
        val launcher = ContextSummaryLauncher { _, _, updated, done ->
            updated(completed)
            done(Result.success(ReviewSummaryRun(completed, 1, null)))
            Job()
        }
        val session = CanvasSummarySession(launcher, { tree }, {})
        session.startTopics(start, request) { snapshot, _ -> saved += snapshot }
        assertSame(start, saved.first())
        assertSame(completed, saved.last())
        assertFalse(session.running)
        assertNull(session.topicStatus)
        session.dispose()
    }

    @Test fun `version completion retains the final snapshot without a progress publication`() {
        val launcher = Launcher()
        val session = CanvasSummarySession(launcher, { tree }, {})
        session.startVersion(start, request) {}
        launcher.done(Result.success(ReviewSummaryRun(completed, 1, null)))
        assertSame(completed, session.versions.single().summaries)
        assertFalse(session.running)
        assertNull(session.versions.single().error)
        session.dispose()
    }

    @Test fun `disposing during launch cancels the job returned afterwards`() {
        val jobs = mutableListOf<Job>()
        lateinit var session: CanvasSummarySession
        val launcher = ContextSummaryLauncher { _, _, _, _ ->
            session.dispose()
            Job().also { jobs += it }
        }
        session = CanvasSummarySession(launcher, { tree }, {})
        session.startVersion(start, request) {}
        assertTrue(jobs.single().isCancelled)

        jobs.clear()
        session = CanvasSummarySession(launcher, { tree }, {})
        session.startTopics(start, request) { _, _ -> }
        assertTrue(jobs.single().isCancelled)
        assertFalse(session.running)
    }

    @Test fun `removing topics while saving the starting snapshot prevents launch`() {
        val launcher = ContextSummaryLauncher { _, _, _, _ ->
            fail("A removed topic run must not launch")
            Job()
        }
        val session = CanvasSummarySession(launcher, { tree }, {})
        session.startTopics(start, request) { _, _ -> session.stopTopics() }
        assertFalse(session.running)
        session.dispose()
    }

    @Test fun `removing topics ignores late callbacks even after another run starts`() {
        val launcher = Launcher()
        val saved = mutableListOf<ReviewSummaries>()
        val session = CanvasSummarySession(launcher, { tree }, {})
        session.startTopics(start, request) { snapshot, _ -> saved += snapshot }
        val oldUpdate = launcher.updated
        val oldDone = launcher.done
        session.stopTopics()
        assertTrue(launcher.job.isCancelled)
        session.startTopics(start, request) { snapshot, _ -> saved += snapshot }
        val saves = saved.size
        oldUpdate(completed)
        oldDone(Result.success(ReviewSummaryRun(completed, 1, null)))
        assertEquals(saves, saved.size)
        assertTrue(session.topicsRunning)
        session.dispose()
    }

    @Test fun `disposing cancels jobs and ignores late publications`() {
        val launcher = Launcher()
        var changes = 0
        val session = CanvasSummarySession(launcher, { tree }, { changes++ })
        session.startVersion(start, request) {}
        session.dispose()
        val before = changes
        launcher.updated(completed)
        launcher.done(Result.success(ReviewSummaryRun(completed, 1, null)))
        assertTrue(launcher.job.isCancelled)
        assertFalse(session.running)
        assertEquals(before, changes)
        assertSame(start.tree, session.versions.single().summaries.tree)
        assertEquals(0, session.versions.single().summaries.count)
    }

    @Test fun `disposing topics prevents further saves and refreshes`() {
        val launcher = Launcher()
        var saves = 0
        var changes = 0
        val session = CanvasSummarySession(launcher, { tree }, { changes++ })
        session.startTopics(start, request) { _, _ -> saves++ }
        session.dispose()
        val before = changes
        launcher.updated(completed)
        launcher.done(Result.success(ReviewSummaryRun(completed, 1, null)))
        assertTrue(launcher.job.isCancelled)
        assertEquals(1, saves)
        assertEquals(before, changes)
        assertFalse(session.running)
    }

    @Test fun `deleting a version invalidates callbacks before cancelling its job`() {
        val launcher = Launcher()
        var changes = 0
        val session = CanvasSummarySession(launcher, { tree }, { changes++ })
        session.startVersion(start, request) {}
        session.delete(session.versions.single())
        val before = changes
        launcher.updated(completed)
        launcher.done(Result.success(ReviewSummaryRun(completed, 1, null)))
        assertTrue(launcher.job.isCancelled)
        assertTrue(session.versions.isEmpty())
        assertEquals(before, changes)
        session.dispose()
    }

    @Test fun `launch failures release the generation slot and expose an error`() {
        val launcher = ContextSummaryLauncher { _, _, _, _ -> throw IllegalStateException("Unable to launch") }
        val session = CanvasSummarySession(launcher, { tree }, {})
        session.startTopics(start, request) { _, _ -> }
        assertFalse(session.running)
        assertEquals("Unable to launch", session.topicStatus)
        session.startVersion(start, request) {}
        assertFalse(session.running)
        assertEquals("Unable to launch", session.versions.single().error)
        session.dispose()
    }

    @Test fun `source changes cancel obsolete versions and retain surviving summaries`() {
        val launcher = Launcher()
        val session = CanvasSummarySession(launcher, { tree }, {})
        session.startVersion(start, request) {}
        launcher.updated(completed)
        val nextTree = buildSummaryTree(listOf(file.copy(path = "b.kt")))
        session.retain(nextTree)
        val version = session.versions.single()
        assertTrue(launcher.job.isCancelled)
        assertFalse(version.running)
        assertNotNull(version.error)
        assertSame(nextTree, version.summaries.tree)
        launcher.updated(completed)
        assertSame(nextTree, version.summaries.tree)
        session.dispose()
    }
}
