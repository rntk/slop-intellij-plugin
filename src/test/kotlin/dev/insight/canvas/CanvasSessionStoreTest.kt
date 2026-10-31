package dev.insight.canvas

import dev.insight.agent.ChatRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class CanvasSessionStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val directory: Path get() = folder.root.toPath().resolve("canvas-sessions")

    private fun session(id: String, root: String, updated: Long): CanvasSession {
        val symbol = CanvasCard("a1", null, CardKind.SYMBOL, CardAnchor(3, 7, 1), SymbolRef("src/Store.kt", "save", "FUNCTION", "Store", 40, 3),
            "src/Store.kt", 3, "fun save() {\n  write()\n}", summary = "Saves.", folded = setOf(CardSection.SOURCE, CardSection.META),
            messages = listOf(SavedMessage(ChatRole.USER, "why?"), SavedMessage(ChatRole.ASSISTANT, "Because.")), draft = "and")
        val selection = CanvasCard("a2", "a1", CardKind.SELECTION, CardAnchor(13, 20, 4), null, "src/Store.kt", 4, "  write()",
            textTruncated = true, summaryError = "The summary failed: timeout", wholeFile = true)
        return CanvasSession(id, root, "Main", 1, updated, listOf(symbol, selection), CanvasView(1.25, -40, 300))
    }

    @Test
    fun `sessions round trip and are listed newest first`() {
        val store = CanvasSessionStore(directory)
        val old = session("old", "src/Main.kt", 10)
        val new = session("new", "src/Main.kt", 20)
        val other = session("other", "src/Other.kt", 30)
        listOf(old, new, other).forEach { assertTrue(store.save(it)) }
        assertEquals(old, store.load("old"))
        assertEquals(listOf("other", "new", "old"), store.list().map { it.id })
        assertEquals(listOf(CanvasSessionEntry("new", "src/Main.kt", "Main", 20, 2), CanvasSessionEntry("old", "src/Main.kt", "Main", 10, 2)),
            store.list("src/Main.kt"))
        assertEquals("*\n", Files.readString(directory.resolve(".gitignore")))
        assertTrue(store.delete("old"))
        assertFalse(store.delete("old"))
        assertNull(store.load("old"))
        assertTrue(Files.list(directory).use { files -> files.noneMatch { it.fileName.toString().endsWith(".tmp") } })
    }

    @Test
    fun `damaged, foreign and newer files are skipped without failing`() {
        val store = CanvasSessionStore(directory)
        store.save(session("good", "src/Main.kt", 5))
        Files.writeString(directory.resolve("broken.json"), "{not json")
        Files.writeString(directory.resolve("future.json"), """{"version":99,"session":{"id":"future","rootPath":"x"}}""")
        Files.writeString(directory.resolve("empty.json"), """{"version":1,"session":{}}""")
        assertEquals(listOf("good"), store.list().map { it.id })
        assertNull(store.load("broken"))
        assertNull(store.load("future"))
        assertNull(store.load("../escape"))
        assertFalse(store.save(session("../escape", "x", 1)))
        assertTrue(CanvasSessionStore(folder.root.toPath().resolve("missing")).list().isEmpty())
    }

    @Test
    fun `missing fields take defaults and orphans and unknown values are dropped`() {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("lean.json"), """
            {"version":1,"session":{"id":"lean","rootPath":"src/A.kt","cards":[
              {"id":"c1","kind":"SELECTION","text":"x","folded":["SOURCE","BOGUS"],"messages":[{"role":"USER","text":"q"},{"role":"ROBOT","text":"r"}]},
              {"id":"c2","parentId":"gone","kind":"SELECTION"},
              {"id":"c3","kind":"SYMBOL"},
              {"id":"c4","kind":"SHAPE"}
            ]}}
        """.trimIndent())
        val session = CanvasSessionStore(directory).load("lean")!!
        assertEquals("A.kt", session.title)
        assertEquals(CanvasView(), session.view)
        val card = session.cards.single()
        assertEquals(CanvasCard("c1", null, CardKind.SELECTION, CardAnchor(0, 0, 0), null, "", 0, "x", folded = setOf(CardSection.SOURCE),
            messages = listOf(SavedMessage(ChatRole.USER, "q"))), card)
    }

    @Test
    fun `autosave writes only the latest requested state, once`() = runBlocking {
        val store = CanvasSessionStore(directory)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val autosave = CanvasSessionAutosave(store, scope, delayMs = 200)
            autosave.request(session("s", "a", 1))
            autosave.request(session("s", "a", 2))
            autosave.request(session("s", "a", 3))
            assertTrue(autosave.dirty)
            autosave.join()
            assertFalse(autosave.dirty)
            assertEquals(3L, store.load("s")!!.updated)
            autosave.request(session("s", "a", 4))
            autosave.flush()
            assertEquals(4L, store.load("s")!!.updated)
            autosave.join()
            assertEquals(4L, store.load("s")!!.updated)
        } finally {
            scope.cancel()
        }
    }
}
