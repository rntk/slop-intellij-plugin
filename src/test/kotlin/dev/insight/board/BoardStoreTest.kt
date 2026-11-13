package dev.insight.board

import dev.insight.agent.ChatMessage
import dev.insight.agent.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class BoardStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val directory: Path get() = folder.root.toPath().resolve("boards")

    private val diff = BoardOrigin("diff", "main..feature", listOf("src/A.kt"))
    private val code = BoardOrigin("code", "Project root")

    private fun board(id: String, updated: Long, origin: BoardOrigin? = diff) =
        fullBoard().copy(id = id, title = "Board $id", origin = origin, created = 1, updated = updated)

    @Test
    fun `boards round trip and are listed newest first`() {
        val store = BoardStore(directory)
        assertTrue(store.save(board("old", 10)))
        assertTrue(store.save(board("new", 20)))
        assertTrue(store.save(board("other", 30, code)))
        assertEquals(board("old", 10), store.load("old")?.board)
        assertEquals(listOf("other", "new", "old"), store.list().map { it.id })
        assertEquals(listOf("new", "old"), store.list(boardOriginKey(diff)).map { it.id })
        assertEquals(listOf("other"), store.list(boardOriginKey(code)).map { it.id })
        val entry = store.list(boardOriginKey(code)).single()
        assertEquals(BoardEntry("other", "Board other", code, 1, 30, fullBoard().blocks.size), entry)
    }

    @Test
    fun `chat questions and answers are saved beside the board, notices and tool lines are not`() {
        val store = BoardStore(directory)
        val chat = listOf(ChatMessage(ChatRole.NOTICE, "Welcome"), ChatMessage(ChatRole.USER, "Explain the retry"),
            ChatMessage(ChatRole.TOOL, "add_code(path=src/A.kt)"), ChatMessage(ChatRole.ASSISTANT, "It retries twice."))
        store.save(board("b1", 5), chat)
        assertEquals(listOf(ChatMessage(ChatRole.USER, "Explain the retry"), ChatMessage(ChatRole.ASSISTANT, "It retries twice.")),
            store.load("b1")?.chat)
        // The board part stays readable by the plain codec.
        assertEquals(board("b1", 5), BoardJson.fromString(Files.readString(directory.resolve("b1.json"))))
    }

    @Test
    fun `saving replaces the earlier file and delete removes it`() {
        val store = BoardStore(directory)
        store.save(board("b1", 5))
        store.save(board("b1", 6).copy(title = "Renamed"))
        assertEquals("Renamed", store.load("b1")?.board?.title)
        assertEquals(1, store.list().size)
        assertTrue(store.delete("b1"))
        assertFalse(store.delete("b1"))
        assertNull(store.load("b1"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `damaged, newer and foreign files are skipped and invalid ids are refused`() {
        val store = BoardStore(directory)
        store.save(board("good", 5))
        Files.writeString(directory.resolve("broken.json"), "{ not json")
        Files.writeString(directory.resolve("newer.json"), """{"version": 99, "id": "newer", "title": "From the future"}""")
        Files.writeString(directory.resolve("untitled.json"), """{"version": 1, "id": "untitled"}""")
        assertEquals(listOf("good"), store.list().map { it.id })
        assertNull(store.load("newer"))
        assertFalse(store.save(board("../escape", 5)))
        assertNull(store.load("../good"))
        assertTrue(Files.exists(directory.resolve(".gitignore")))
    }

    @Test
    fun `a missing directory lists nothing`() {
        assertTrue(BoardStore(directory).list().isEmpty())
        assertNull(BoardStore(directory).load("b1"))
    }

    @Test
    fun `board files tolerate malformed chats`() {
        val text = encodeBoardFile(SavedBoard(board("b1", 5))).replace("\"chats\": []",
            "\"chats\": [{\"messages\": [{\"role\": \"system\", \"text\": \"x\"}, {\"role\": \"USER\"}, 3, {\"role\": \"user\", \"text\": \"hi\"}]}, 7]")
        assertEquals(SavedBoard(board("b1", 5), listOf(ChatMessage(ChatRole.USER, "hi"))), decodeBoardFile(text))
        assertEquals(board("b1", 5), decodeBoardFile(BoardJson.toString(board("b1", 5)))?.board)
        assertNull(decodeBoardFile("[]"))
    }
}
