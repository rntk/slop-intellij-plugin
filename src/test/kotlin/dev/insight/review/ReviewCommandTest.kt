package dev.insight.review

import dev.insight.diff.isSummariesRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewCommandTest {
    @Test fun `recognizes commands and existing aliases with focus instructions`() {
        assertEquals(ReviewCommand.TOPICS, ReviewCommand.parse("topics"))
        assertEquals(ReviewCommand.TOPICS, ReviewCommand.parse(" /TOPICS architecture "))
        assertEquals(ReviewCommand.SUMMARIES, ReviewCommand.parse("summaries"))
        assertEquals(ReviewCommand.SUMMARIES, ReviewCommand.parse("/summaries data flow"))
        assertEquals(ReviewCommand.EXPLAIN, ReviewCommand.parse("/explain\nwhy is this cached?"))
        assertTrue(isTopicsRequest("/topics architecture"))
        assertTrue(isSummariesRequest("/summaries data flow"))
        assertTrue(isExplainRequest("/explain src/Main.kt"))
    }

    @Test fun `requires a command boundary and preserves argument restrictions`() {
        listOf("/topicsExtra", "/summariesExtra", "/explainExtra", "explain", "topics architecture",
            "/topic-summaries extra", "/summaries\nextra", "Please explain this code").forEach {
            assertNull(it, ReviewCommand.parse(it))
        }
        assertFalse(isTopicsRequest("/topicsExtra"))
        assertFalse(isSummariesRequest("/summariesExtra"))
        assertFalse(isExplainRequest("/explainExtra"))
    }

    @Test fun `topic summary routing and progress use the same normalization`() {
        val question = "  /TOPIC-SUMMARIES  "
        assertEquals(ReviewCommand.TOPIC_SUMMARIES, ReviewCommand.parse(question))
        assertEquals("Generating code summaries", reviewProgressTitle(question, "code"))
        assertEquals("Generating change summaries", reviewProgressTitle(question, "change"))
        assertEquals("Generating code topics", reviewProgressTitle("/topics", "code"))
        assertEquals("Explaining the change", reviewProgressTitle("/explain file.kt", "change"))
        assertEquals("Insight code review chat", reviewProgressTitle("What does this do?", "code"))
    }
}
