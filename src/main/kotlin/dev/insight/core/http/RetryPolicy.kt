package dev.insight.core.http

import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal data class RetryPolicy(
    val maxRetries: Int,
    val initialDelayMillis: Long,
    val retryAfterIsMinimum: Boolean,
) {
    init {
        require(maxRetries in 0..100) { "Max retries must be between 0 and 100" }
        require(initialDelayMillis in 1..60_000) { "Initial delay must be between 1 and 60000 ms" }
    }

    fun delayMillis(header: String?, attempt: Int): Long {
        val fromHeader = header?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { (it * 1000).toLong() }
            ?: header?.let {
                runCatching {
                    Duration.between(ZonedDateTime.now(), ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME))
                        .toMillis().coerceAtLeast(0)
                }.getOrNull()
            }
        val backoff = (initialDelayMillis * (1L shl attempt.coerceIn(0, 7))).coerceAtMost(60_000)
        return if (retryAfterIsMinimum) maxOf(fromHeader ?: 0, backoff) else fromHeader ?: backoff
    }

}
