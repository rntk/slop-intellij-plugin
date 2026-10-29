package dev.insight.diff

import dev.insight.callgraph.ExplainChartResult
import dev.insight.agent.ChatMessage
import dev.insight.agent.ChatRole
import dev.insight.review.ExplainResults
import org.junit.Assert.*
import org.junit.Test

class ExplainResultsTest {
    @Test fun `completion publishes the answer and chart status only once`() {
        val results = ExplainResults()
        val request = ChatMessage(ChatRole.USER, "/explain src/app")
        assertTrue(results.update(listOf(request), busy = true))
        assertTrue(results.result!!.running)
        val chart = ExplainChartResult.Unavailable("the graph is rebuilding.")
        results.chartPrepared(chart)
        val progress = listOf(request, ChatMessage(ChatRole.TOOL, "read_code"))
        assertFalse(results.update(progress, busy = true))
        val finished = progress + ChatMessage(ChatRole.ASSISTANT, "The cache avoids repeated reads.")
        // File-trace notifications can arrive before the session has finished publishing the reply.
        assertFalse(results.update(finished, busy = true))
        assertTrue(results.update(finished, busy = false))
        assertEquals("The cache avoids repeated reads.", results.result!!.text)
        assertFalse(results.result!!.running)
        assertSame(chart, results.result!!.chart)
        assertFalse(results.update(finished, busy = false))
        assertFalse(results.update(finished + ChatMessage(ChatRole.USER, "Another question"), busy = true))
        assertEquals("The cache avoids repeated reads.", results.result!!.text)
    }

    @Test fun `failures and cancellation replace the progress message`() {
        listOf("The response failed: connection refused", "Stopped.").forEach { notice ->
            val results = ExplainResults()
            val request = ChatMessage(ChatRole.USER, "/explain")
            results.update(listOf(request), busy = true)
            assertTrue(results.update(listOf(request, ChatMessage(ChatRole.NOTICE, notice)), busy = false))
            assertEquals(notice, results.result!!.text)
            assertFalse(results.result!!.running)
        }
    }

    @Test fun `repeating a command starts a new result and a new chat forgets it`() {
        val results = ExplainResults()
        val request = ChatMessage(ChatRole.USER, "/explain")
        val history = listOf(request, ChatMessage(ChatRole.ASSISTANT, "First answer"))
        results.update(listOf(request), busy = true)
        results.chartPrepared(ExplainChartResult.Unavailable("Old chart status"))
        results.update(history, busy = false)
        val repeated = history + request.copy()
        assertTrue(results.update(repeated, busy = true))
        assertTrue(results.result!!.running)
        assertNull(results.result!!.chart)
        assertTrue(results.update(repeated + ChatMessage(ChatRole.ASSISTANT, "Second answer"), busy = false))
        assertEquals("Second answer", results.result!!.text)
        assertNull(results.result!!.chart)
        assertTrue(results.update(emptyList(), busy = false))
        assertNull(results.result)
        assertFalse(results.update(emptyList(), busy = false))
    }

    @Test fun `an empty answer leaves a visible explanation instead of a blank result`() {
        val results = ExplainResults()
        val request = ChatMessage(ChatRole.USER, "/explain")
        results.update(listOf(request), busy = true)
        results.update(listOf(request, ChatMessage(ChatRole.ASSISTANT, "")), busy = false)
        assertTrue(results.result!!.text.contains("No explanation was returned"))
    }
}
