package dev.insight.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBudgetTest {
    @Test fun `shares of the window leave room for the policy question and tool rounds`() {
        for (profile in ChatProfile.values()) for (context in listOf(8_000, 64_000, 160_000, 800_000)) {
            val budget = ChatBudget.of(profile, context)
            assertEquals(context, budget.contextChars)
            assertTrue(budget.historyChars + budget.pinnedChars + budget.snapshotChars <= context * 4 / 5)
            assertTrue(budget.pinnedChars > budget.snapshotChars)
            assertTrue(budget.toolResultChars <= ChatBudget.MAX_TOOL_RESULT_CHARS && budget.toolResultChars <= context / 4)
        }
    }

    @Test fun `profiles differ in fallback window rounds and eviction only`() {
        val focused = ChatBudget.of(ChatProfile.FOCUSED)
        val review = ChatBudget.of(ChatProfile.REVIEW)
        assertEquals(64_000, focused.contextChars)
        assertEquals(160_000, review.contextChars)
        assertTrue(review.toolRounds > focused.toolRounds)
        assertTrue(review.evictionTarget < focused.evictionTarget)
        assertEquals(ChatBudget.of(ChatProfile.ASSISTANT, 64_000).pinnedChars, focused.pinnedChars)
    }

    @Test fun `a configured window replaces the fallback`() {
        val budget = ChatBudget.of(ChatProfile.FOCUSED, 400_000)
        assertEquals(400_000, budget.contextChars)
        assertEquals(140_000, budget.pinnedChars)
        assertEquals(120_000, budget.historyChars)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `shares exceeding the context are rejected`() {
        ChatBudget.of(ChatProfile.FOCUSED, 1_000).copy(historyChars = 900)
    }
}
