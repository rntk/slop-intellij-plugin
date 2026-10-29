package dev.insight.chat

import org.junit.Assert.*
import org.junit.Test
import dev.insight.agent.ProjectFiles

class ChatFileSearchTest {
    @Test fun `file names rank above directory matches and results are bounded`() {
        val paths = listOf("Main.kt/Other.txt", "src/NotMain.kt", "src/Main.kt", "test/MainTest.kt")
        val found = rankChatFiles("main", paths)
        assertEquals("src/Main.kt", found.first())
        assertTrue(found.indexOf("src/NotMain.kt") < found.indexOf("Main.kt/Other.txt"))
        assertEquals(listOf("src/Main.kt"), rankChatFiles("MAIN.KT", paths, limit = 1))
        assertTrue(rankChatFiles("no-such-file", paths).isEmpty())
    }

    @Test fun `matches partial names camel humps and relative paths`() {
        val paths = listOf("src/chat/ChatPanel.kt", "test/chat/ChatPanelTest.kt", "src/tools/ProjectFiles.kt")
        assertEquals(paths.take(2), rankChatFiles("hatPan", paths))
        assertTrue(rankChatFiles("CP", paths).contains("src/chat/ChatPanel.kt"))
        assertEquals(listOf("src/chat/ChatPanel.kt"), rankChatFiles("SRC\\CHAT", paths))
        assertEquals(paths.take(2), rankChatFiles("", paths, limit = 2))
    }

    @Test fun `large searches check cancellation and return only the best results`() {
        val paths = (1..10000).map { "src/File$it.kt" }
        var checks = 0
        assertEquals(50, rankChatFiles("File", paths) { checks++ }.size)
        assertTrue(checks > 50)
        assertThrows(IllegalStateException::class.java) {
            rankChatFiles("File", paths) { throw IllegalStateException("cancelled") }
        }
    }
}
