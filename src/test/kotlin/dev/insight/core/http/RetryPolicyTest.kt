package dev.insight.core.http

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class RetryPolicyTest {
    private val llm = RetryPolicy(6, 1000, retryAfterIsMinimum = true)
    private val decisions = RetryPolicy(6, 500, retryAfterIsMinimum = false)

    @Test fun backoffGrowsAndCapsForBothProviders() {
        assertEquals(1000L, llm.delayMillis(null, 0))
        assertEquals(500L, decisions.delayMillis(null, 0))
        assertEquals(4000L, llm.delayMillis(null, 2))
        assertEquals(2000L, decisions.delayMillis(null, 2))
        assertEquals(60000L, llm.delayMillis(null, 100))
        assertEquals(60000L, decisions.delayMillis(null, 100))
    }

    @Test fun retryAfterPreservesProviderSemantics() {
        assertEquals(1000L, llm.delayMillis("0", 0))
        assertEquals(0L, decisions.delayMillis("0", 0))
        assertEquals(1500L, decisions.delayMillis("1.5", 0))
        assertEquals(120000L, llm.delayMillis("120", 0))
        assertEquals(120000L, decisions.delayMillis("120", 0))
        for (header in listOf("invalid", "-1", "NaN", "Infinity")) {
            assertEquals(1000L, llm.delayMillis(header, 0))
            assertEquals(500L, decisions.delayMillis(header, 0))
        }
        val past = ZonedDateTime.now().minusDays(1).format(DateTimeFormatter.RFC_1123_DATE_TIME)
        assertEquals(1000L, llm.delayMillis(past, 0))
        assertEquals(0L, decisions.delayMillis(past, 0))
    }
}
