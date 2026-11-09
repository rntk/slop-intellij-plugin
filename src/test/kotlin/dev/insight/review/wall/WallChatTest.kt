package dev.insight.review.wall

import dev.insight.agent.ChatMessage
import dev.insight.agent.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Test

class WallChatTest {
    @Test
    fun aChatIsNamedByItsFirstQuestion() {
        assertEquals("New chat", wallChatTitle(listOf(ChatMessage(ChatRole.NOTICE, "Ask about a.kt"))))
        assertEquals("Why is old removed?", wallChatTitle(listOf(ChatMessage(ChatRole.USER, "Why is old removed?"))))
        assertEquals("Why does this ch…", wallChatTitle(listOf(ChatMessage(ChatRole.USER, "Why does this\n change the loader?")), max = 17))
    }
}
