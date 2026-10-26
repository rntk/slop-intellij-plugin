package dev.insight.diff

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.review.ContextSummaryRequest
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummaryRun
import dev.insight.review.buildSummaryTree
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking

class ReviewLauncherCancellationTest : BasePlatformTestCase() {
    private val start = ReviewSummaries(buildSummaryTree(emptyList<ReviewFile>()))
    private val request = ContextSummaryRequest("", emptySet())

    fun testTaskCancellationCompletesCardWhileOwningJobIsActive() = runBlocking {
        val cancellation = CancellationException("Progress cancelled")
        var result: Result<ReviewSummaryRun>? = null
        val launcher = ReviewContextSummaryLauncher(project, this) { throw cancellation }

        val job = launcher.launch(start, request, { fail("No summaries should be published") }, { result = it })
        job.join()

        assertFalse(job.isCancelled)
        assertSame(cancellation, result?.exceptionOrNull())
    }

    fun testOwningJobCancellationDoesNotPublishCompletion() = runBlocking {
        var completed = false
        val launcher = ReviewContextSummaryLauncher(project, this) {
            currentCoroutineContext().cancel()
            currentCoroutineContext().ensureActive()
            error("Cancelled job must stop")
        }

        val job = launcher.launch(start, request, { fail("No summaries should be published") }, { completed = true })
        job.join()

        assertTrue(job.isCancelled)
        assertFalse(completed)
    }
}
