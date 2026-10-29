package dev.insight.core

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class RunReportedTest {
    private val log = Logger.getInstance(RunReportedTest::class.java)

    private class TestControlFlowException : Exception("control flow"), ControlFlowException

    @Test
    fun returnsTheResultOnSuccessIncludingNull() {
        assertEquals(Result.success(7), runCatchingReported(log, "failed") { 7 })
        val nullResult: Result<String?> = runCatchingReported(log, "failed") { null }
        assertTrue(nullResult.isSuccess)
        assertNull(nullResult.getOrThrow())
    }

    @Test
    fun capturesExceptionsAndDescribesMessageOrClassName() {
        val withMessage = runCatchingReported(log, "failed") { error("boom") }
        assertEquals("boom", describeFailure(withMessage.exceptionOrNull()!!))

        val withoutMessage = runCatchingReported(log, "failed") { throw IllegalStateException() }
        assertEquals("IllegalStateException", describeFailure(withoutMessage.exceptionOrNull()!!))
    }

    @Test
    fun cancellationPropagatesUntouched() {
        val original = CancellationException("stop")
        try {
            runCatchingReported(log, "failed") { throw original }
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertSame(original, e)
        }
    }

    @Test
    fun controlFlowExceptionsPropagateUnreported() {
        val original = TestControlFlowException()
        try {
            runCatchingReported(log, "failed") { throw original }
            fail("control flow must propagate")
        } catch (e: TestControlFlowException) {
            assertSame(original, e)
        }
    }
}
