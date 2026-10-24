package dev.insight.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.IOException

class RequestLimiterTest {
    @get:Rule val timeout = Timeout.seconds(10)

    @Test fun `limits concurrent work and admits waiting requests in order`() = runBlocking {
        val limiter = RequestLimiter(2)
        val entered = Channel<Int>(Channel.UNLIMITED)
        val gates = List(6) { CompletableDeferred<Unit>() }
        val jobs = gates.mapIndexed { index, gate ->
            async(start = CoroutineStart.UNDISPATCHED) {
                limiter.withPermit { entered.send(index); gate.await() }
            }
        }
        assertEquals(0, entered.receive())
        assertEquals(1, entered.receive())
        assertTrue(entered.tryReceive().isFailure)
        for (index in 0..3) {
            gates[index].complete(Unit)
            assertEquals(index + 2, entered.receive())
            assertTrue(entered.tryReceive().isFailure)
        }
        gates.forEach { it.complete(Unit) }
        jobs.forEach { it.await() }
    }

    @Test fun `resizing preserves running requests and wakes waiters on an increase`() = runBlocking {
        val limiter = RequestLimiter(2)
        val entered = Channel<Int>(Channel.UNLIMITED)
        val gates = List(5) { CompletableDeferred<Unit>() }
        val jobs = gates.mapIndexed { index, gate ->
            async(start = CoroutineStart.UNDISPATCHED) {
                limiter.withPermit { entered.send(index); gate.await() }
            }
        }
        assertEquals(0, entered.receive())
        assertEquals(1, entered.receive())
        limiter.resize(1)
        gates[0].complete(Unit)
        jobs[0].await()
        assertTrue(entered.tryReceive().isFailure)
        assertFalse(jobs[1].isCompleted)
        gates[1].complete(Unit)
        assertEquals(2, entered.receive())
        limiter.resize(3)
        assertEquals(3, entered.receive())
        assertEquals(4, entered.receive())
        gates.forEach { it.complete(Unit) }
        jobs.forEach { it.await() }
    }

    @Test fun `cancelled waiters and running requests release their capacity`() = runBlocking {
        val limiter = RequestLimiter(1)
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            limiter.withPermit { CompletableDeferred<Unit>().await() }
        }
        var cancelledEntered = false
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            limiter.withPermit { cancelledEntered = true }
        }
        val next = async(start = CoroutineStart.UNDISPATCHED) { limiter.withPermit { "done" } }
        cancelled.cancelAndJoin()
        first.cancelAndJoin()
        assertEquals("done", next.await())
        assertFalse(cancelledEntered)
    }

    @Test fun `cancellation after admission but before resumption returns the slot`() = runBlocking {
        val limiter = RequestLimiter(1)
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            limiter.withPermit { CompletableDeferred<Unit>().await() }
        }
        var cancelledEntered = false
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            limiter.withPermit { cancelledEntered = true }
        }
        limiter.resize(2)
        cancelled.cancelAndJoin()
        assertFalse(cancelledEntered)
        assertEquals("available", limiter.withPermit { "available" })
        first.cancelAndJoin()
    }

    @Test fun `exceptions release capacity and invalid limits are rejected`() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) { RequestLimiter(0) }
        val limiter = RequestLimiter(1)
        assertThrows(IllegalArgumentException::class.java) { limiter.resize(-1) }
        try {
            limiter.withPermit { throw IOException("failed") }
            fail("Expected IOException")
        } catch (_: IOException) {
            assertEquals("available", limiter.withPermit { "available" })
        }
    }
}
