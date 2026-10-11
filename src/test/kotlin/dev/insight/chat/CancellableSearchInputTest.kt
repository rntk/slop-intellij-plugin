package dev.insight.chat

import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Assert.*
import org.junit.Test

class CancellableSearchInputTest {
    @Test(timeout = 2000)
    fun `cancels pathological backtracking within a single line`() {
        var reads = 0
        val input = CancellableSearchInput("a".repeat(1000) + "!") {
            if (++reads == 2000) throw ProcessCanceledException()
        }
        assertThrows(ProcessCanceledException::class.java) { Regex("(a+)+$").containsMatchIn(input) }
        assertEquals(2000, reads)
    }

    @Test fun `preserves regex semantics and cancellation in subsequences`() {
        var reads = 0
        val input = CancellableSearchInput("xxHello 42yy") { reads++ }
        val slice = input.subSequence(2, 10)
        assertTrue(Regex("^hello\\s+\\d+$", RegexOption.IGNORE_CASE).matches(slice))
        assertTrue(reads > 0)
        assertEquals("Hello 42", slice.toString())
    }
}
