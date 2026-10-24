package dev.insight.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A shared, resizable limit. Waiting callers suspend in arrival order and can be cancelled. */
internal class RequestLimiter(maxConcurrentRequests: Int) {
    private val lock = Any()
    private val waiting = ArrayDeque<CompletableDeferred<Unit>>()
    private var active = 0
    private var limit = maxConcurrentRequests.also { require(it > 0) { "Concurrent request limit must be positive" } }

    /** Running requests finish normally when the limit is lowered. */
    fun resize(maxConcurrentRequests: Int) {
        require(maxConcurrentRequests > 0) { "Concurrent request limit must be positive" }
        val admitted = synchronized(lock) {
            limit = maxConcurrentRequests
            admitWaiting()
        }
        admitted.forEach { it.complete(Unit) }
    }

    suspend fun <T> withPermit(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    private suspend fun acquire() {
        currentCoroutineContext().ensureActive()
        val waiter = synchronized(lock) {
            if (active < limit && waiting.isEmpty()) {
                active++
                null
            } else CompletableDeferred<Unit>().also { waiting.addLast(it) }
        } ?: return
        try {
            waiter.await()
        } catch (e: Throwable) {
            val admitted = synchronized(lock) {
                // A removed waiter already owns a slot, even if cancellation beat its resumption.
                if (!waiting.remove(waiter)) active--
                admitWaiting()
            }
            admitted.forEach { it.complete(Unit) }
            throw e
        }
    }

    private fun release() {
        val admitted = synchronized(lock) {
            active--
            admitWaiting()
        }
        admitted.forEach { it.complete(Unit) }
    }

    /** Called under [lock]; complete the returned signals outside the lock. */
    private fun admitWaiting(): List<CompletableDeferred<Unit>> = buildList {
        while (active < limit && waiting.isNotEmpty()) {
            active++
            add(waiting.removeFirst())
        }
    }
}

/** One budget per API across all clients, providers, jobs, and projects in this IDE. */
internal object ApiRequestLimits {
    const val DEFAULT_MAX_CONCURRENT_REQUESTS = 4
    val llm = RequestLimiter(DEFAULT_MAX_CONCURRENT_REQUESTS)
    val decisions = RequestLimiter(DEFAULT_MAX_CONCURRENT_REQUESTS)
}
