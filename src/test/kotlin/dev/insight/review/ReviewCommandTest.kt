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
        assertEquals(ReviewCommand.GROUPING, ReviewCommand.parse("grouping"))
        assertEquals(ReviewCommand.GROUPING, ReviewCommand.parse(" /GROUPING error handling "))
        assertEquals(ReviewCommand.SUMMARIES, ReviewCommand.parse("summaries"))
        assertEquals(ReviewCommand.SUMMARIES, ReviewCommand.parse("/summaries data flow"))
        assertEquals(ReviewCommand.EXPLAIN, ReviewCommand.parse("/explain\nwhy is this cached?"))
        assertTrue(isTopicsRequest("/topics architecture"))
        assertTrue(isGroupingRequest("/grouping error handling"))
        assertTrue(isSummariesRequest("/summaries data flow"))
        assertTrue(isExplainRequest("/explain src/Main.kt"))
    }

    @Test fun `requires a command boundary and preserves argument restrictions`() {
        listOf("/topicsExtra", "/groupingExtra", "/summariesExtra", "/explainExtra", "explain", "topics architecture",
            "grouping error handling", "/topic-summaries extra", "/grouping-summaries extra",
            "/summaries\nextra", "Please explain this code").forEach {
            assertNull(it, ReviewCommand.parse(it))
        }
        assertFalse(isTopicsRequest("/topicsExtra"))
        assertFalse(isGroupingRequest("/groupingExtra"))
        assertFalse(isSummariesRequest("/summariesExtra"))
        assertFalse(isExplainRequest("/explainExtra"))
    }

    @Test fun `topic summary routing and progress use the same normalization`() {
        val question = "  /TOPIC-SUMMARIES  "
        assertEquals(ReviewCommand.TOPIC_SUMMARIES, ReviewCommand.parse(question))
        assertEquals(ReviewCommand.GROUPING_SUMMARIES, ReviewCommand.parse("  /GROUPING-SUMMARIES  "))
        assertEquals("Generating code summaries", reviewProgressTitle(question, "code"))
        assertEquals("Generating change summaries", reviewProgressTitle(question, "change"))
        assertEquals("Generating change summaries", reviewProgressTitle("/grouping-summaries", "change"))
        assertEquals("Generating code topics", reviewProgressTitle("/topics", "code"))
        assertEquals("Generating change grouping", reviewProgressTitle("/grouping", "change"))
        assertEquals("Explaining the change", reviewProgressTitle("/explain file.kt", "change"))
        assertEquals("Insight code review chat", reviewProgressTitle("What does this do?", "code"))
    }

    @Test fun `code reviews do not recognize grouping`() {
        listOf("/grouping", "grouping", "/grouping-summaries", "/grouping errors").forEach {
            assertNull(it, ReviewCommand.parse(it, ReviewCommand.code))
        }
        assertEquals(ReviewCommand.TOPICS, ReviewCommand.parse("/topics", ReviewCommand.code))
        assertEquals("Insight code review chat", reviewProgressTitle("/grouping", "code", ReviewCommand.code))
    }
}
