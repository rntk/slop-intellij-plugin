package dev.insight.core

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import kotlin.coroutines.cancellation.CancellationException

/** The text shown for a failure: its message, or the exception's class name when it has none. */
internal fun describeFailure(error: Throwable): String = error.message ?: error.javaClass.simpleName

/**
 * Runs [block] and wraps its outcome in a [Result]. Cancellation and IDE control-flow exceptions propagate untouched.
 * Any other [Exception] is logged with [what] at warning level and returned as a failure [Result].
 *
 * Inline, so [block] may suspend and perform non-local returns.
 */
internal inline fun <T> runCatchingReported(
    log: Logger,
    what: String,
    block: () -> T,
): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    if (e is ControlFlowException) throw e
    log.warn(what, e)
    Result.failure(e)
}
