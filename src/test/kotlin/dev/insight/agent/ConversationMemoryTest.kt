package dev.insight.agent

import com.google.gson.JsonObject
import dev.insight.llm.LlmRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMemoryTest {
    private fun user(text: String) = ChatMessage(ChatRole.USER, text)
    private fun assistant(text: String) = ChatMessage(ChatRole.ASSISTANT, text)
    private fun tool(name: String, path: String, failed: Boolean = false) = ChatMessage(ChatRole.TOOL, "$name($path)",
        toolCall = ToolCallEvent(name, JsonObject().apply { addProperty("path", path) }, "result", failed))
    private fun turn(index: Int, size: Int = 10) = listOf(user("Question $index " + "q".repeat(size)), assistant("Answer $index " + "a".repeat(size)))

    @Test fun `recent turns are kept whole and in order`() {
        val history = conversationHistory((1..3).flatMap { turn(it) }, historyChars = 10_000)
        assertNull(history.digest)
        assertEquals(0, history.foldedTurns)
        assertEquals(listOf(LlmRole.USER, LlmRole.ASSISTANT, LlmRole.USER, LlmRole.ASSISTANT, LlmRole.USER, LlmRole.ASSISTANT), history.turns.map { it.role })
        assertTrue(history.turns[0].content.startsWith("Question 1"))
        assertTrue(history.turns[5].content.startsWith("Answer 3"))
    }

    @Test fun `older turns fold into a digest that keeps the first turn fullest`() {
        val messages = listOf(user("Review the payment refactoring for thread safety issues"), assistant("The refactoring moves locking into PaymentService. " + "x".repeat(900))) +
            (2..6).flatMap { turn(it, 400) }
        val history = conversationHistory(messages, historyChars = 3_000)
        assertTrue(history.foldedTurns in 1..5)
        val digest = history.digest!!
        assertTrue(digest.startsWith("Earlier in this conversation (${history.foldedTurns} turn"))
        assertTrue(digest.contains("1. Asked: Review the payment refactoring for thread safety issues"))
        assertTrue(digest.contains("Answered: The refactoring moves locking into PaymentService."))
        // Kept turns follow the folded ones, oldest first, and are the newest of the conversation.
        assertTrue(history.turns.last().content.startsWith("Answer 6"))
        assertEquals(history.turns.size / 2, 6 - history.foldedTurns)
        assertTrue(history.turns.sumOf { it.content.length } + digest.length <= 3_000)
    }

    @Test fun `a turn without a reply gets a placeholder so roles keep alternating`() {
        val history = conversationHistory(listOf(user("First"), ChatMessage(ChatRole.NOTICE, "The response failed"), user("Second"), assistant("Reply")), 10_000)
        assertEquals(listOf("First", "[No reply was produced for this message.]", "Second", "Reply"), history.turns.map { it.content })
    }

    @Test fun `tool events and notices are not replayed but folded turns name their tools`() {
        val messages = listOf(user("Where is the lock taken?"), tool("read_file", "a/PaymentService.kt"), tool("search_text", "lock"),
            tool("read_file", "missing.kt", failed = true), assistant("In PaymentService.charge.")) + turn(2, 200)
        val kept = conversationHistory(messages, 10_000)
        assertTrue(kept.turns.none { it.role == LlmRole.TOOL })
        val folded = conversationHistory(messages, 1_000)
        assertEquals(1, folded.foldedTurns)
        assertTrue(folded.digest!!.contains("Read: read_file(a/PaymentService.kt), search_text(lock)"))
        assertFalse(folded.digest!!.contains("missing.kt"))
    }

    @Test fun `the digest drops middle turns first and then shrinks texts to stay within its share`() {
        val messages = (1..40).flatMap { turn(it, 300) }
        val history = conversationHistory(messages, historyChars = 2_000)
        val digest = history.digest!!
        assertTrue(digest.length <= 2_000)
        assertTrue(digest.contains("1. Asked: Question 1"))
        assertTrue(digest.contains("turns omitted)"))
        assertTrue(digest.contains("${history.foldedTurns}. Asked: Question ${history.foldedTurns}"))
    }

    @Test fun `the newest turn is clipped rather than dropped`() {
        val history = conversationHistory(listOf(user("Short"), assistant("x".repeat(5_000))), historyChars = 2_000)
        assertEquals(0, history.foldedTurns)
        assertNull(history.digest)
        assertTrue(history.turns[1].content.length < 2_000)
        assertTrue(history.turns[1].content.contains("[Truncated"))
    }

    @Test fun `a zero budget keeps nothing and blank questions are ignored`() {
        assertEquals(ConversationHistory(null, emptyList(), 2), conversationHistory(turn(1) + turn(2), 0))
        assertEquals(ConversationHistory.EMPTY, conversationHistory(listOf(user("  "), assistant("Orphan")), 1_000))
        assertEquals(ConversationHistory.EMPTY, conversationHistory(emptyList(), 1_000))
    }

    @Test fun `the message cap folds turns too`() {
        val history = conversationHistory((1..30).flatMap { turn(it) }, historyChars = 100_000, maxMessages = 10)
        assertEquals(10, history.turns.size)
        assertEquals(25, history.foldedTurns)
        assertTrue(history.turns.first().content.startsWith("Question 26"))
    }
}
