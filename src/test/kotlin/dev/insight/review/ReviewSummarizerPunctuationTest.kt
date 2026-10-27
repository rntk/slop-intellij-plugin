package dev.insight.review

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class ReviewSummarizerPunctuationTest {
    private fun decode(text: String, citation: String, includeClaim: Boolean = true): ReviewSummarizer.Companion.Decoded {
        fun segment(value: String, vararg sources: String) = JsonObject().apply {
            addProperty("text", value)
            add("from", JsonArray().apply { sources.forEach { add(it) } })
        }
        val answer = JsonObject().apply {
            add("summaries", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("id", 1)
                    add("segments", JsonArray().apply {
                        if (includeClaim) add(segment("Adds retries", citation))
                        add(segment(text))
                    })
                })
            })
        }
        val allowed = if (citation == "1.1") ReviewSummarizer.Citations.Children(listOf(listOf(0)))
            else ReviewSummarizer.Citations.Lines(1..3)
        return ReviewSummarizer.decode(answer, listOf(allowed))
    }

    @Test fun `uncited punctuation preserves cited leaf and parent summaries`() {
        for (citation in listOf("1-2", "1.1")) {
            for (punctuation in listOf(".", ", ", "; ", "!", "?", " … ", " — ", " \t ")) {
                val decoded = decode(punctuation, citation)
                assertEquals(punctuation, emptyMap<Int, String>(), decoded.problems)
                val summary = decoded.summaries.getValue(1)
                assertEquals(("Adds retries" + punctuation).trimEnd(), summary.text)
                val source = if (citation == "1.1") SummarySource.Claim(0, 0) else SummarySource.Rows(1, 2)
                assertEquals(listOf(source), summary.segments.first().sources)
                assertEquals(listOf(0), summary.claims)
                assertTrue(summary.segments.drop(1).all { it.sources.isEmpty() })
            }
        }
    }

    @Test fun `punctuation exception does not admit uncited claims or summaries without citations`() {
        for (text in listOf("Improves speed.", "42.", "é.", "and.")) {
            val decoded = decode(text, "1-2")
            assertTrue(text, decoded.summaries.isEmpty())
            assertTrue(text, decoded.problems.containsKey(1))
        }
        val punctuationOnly = decode(".", "1-2", includeClaim = false)
        assertTrue(punctuationOnly.summaries.isEmpty())
        assertTrue(punctuationOnly.problems.getValue(1).contains("no segment cites anything"))
    }
}
