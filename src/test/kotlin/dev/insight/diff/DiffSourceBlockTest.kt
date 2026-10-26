package dev.insight.diff

import com.google.gson.JsonParser
import dev.insight.chat.ToolInputException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiffSourceBlockTest {
    private val file = ReviewFile("src/a.kt", "/project/src/a.kt", null, "modified", "patch", "1",
        before = "old\r\n  removed\r\n", after = "first\n\tval x = 1\nlast")
    private val snapshot = ReviewSnapshot(listOf(file), emptyList())

    @Test fun `inclusive ranges preserve whitespace and choose the snapshot side`() {
        assertEquals("\tval x = 1\nlast", resolveSourceBlock(snapshot, DiffSourceRange(file.path, 2, 3)).text)
        assertEquals("  removed", resolveSourceBlock(snapshot, DiffSourceRange(file.path, 2, 2, "before")).text)
        for (range in listOf(DiffSourceRange(file.path, 0, 1), DiffSourceRange(file.path, 3, 2),
            DiffSourceRange(file.path, 1, 4), DiffSourceRange(file.path, 1, Int.MAX_VALUE),
            DiffSourceRange(file.path, 1, 1, "current"), DiffSourceRange("other.kt", 1, 1))) {
            rejected { resolveSourceBlock(snapshot, range) }
        }
        rejected { resolveSourceBlock(snapshot.copy(files = listOf(file.copy(after = null))), DiffSourceRange(file.path, 1, 1)) }
        rejected { resolveSourceBlock(snapshot.copy(files = listOf(file.copy(after = ""))), DiffSourceRange(file.path, 1, 1)) }
        rejected { resolveSourceBlock(snapshot.copy(files = listOf(file.copy(after = "x".repeat(20_001)))), DiffSourceRange(file.path, 1, 1)) }
    }

    @Test fun `source cards coexist with notes deduplicate and expire with their evidence`() {
        val state = DiffReviewState()
        state.update(snapshot)
        val range = DiffSourceRange(file.path, 1, 2)
        state.showSource(state.revision, range)
        state.showSource(state.revision, range)
        assertEquals(1, state.sources.size)
        state.addNote(state.revision, listOf(file.path), "Explanation")
        state.showSource(state.revision, range.copy(side = "before"))
        assertEquals(2, state.sources.size)
        val revision = state.revision
        state.update(snapshot.copy(files = snapshot.files + file.copy(path = "b.kt")))
        assertEquals(2, state.sources.size)
        rejected { state.showSource(revision, range) }
        state.removeSource(range)
        assertEquals("before", state.sources.single().range.side)
        assertEquals(1, state.notes.size)
        state.update(snapshot.copy(files = listOf(file.copy(fingerprint = "2"))))
        assertTrue(state.sources.isEmpty())
        state.showSource(state.revision, range)
        state.clearNotes()
        assertTrue(state.sources.isEmpty())
    }

    @Test fun `tool validates coordinates before dispatch and defaults to after`() = runBlocking {
        val seen = mutableListOf<DiffSourceRange>()
        val context = object : DiffReviewContext {
            override suspend fun state(offset: Int, patchBudget: Int) = ""
            override suspend fun read(paths: List<String>, offset: Int) = ""
            override suspend fun addNotes(notes: List<DiffNote>) = ""
            override suspend fun showSource(range: DiffSourceRange): String { seen += range; return "shown" }
        }
        val tool = diffReviewTools(context).single { it.definition.name == "show_diff_source" }
        tool.call(JsonParser.parseString("""{"path":"src/a.kt","start_line":2,"end_line":3}""").asJsonObject)
        assertEquals(listOf(DiffSourceRange(file.path, 2, 3)), seen)
        for (args in listOf("{}", """{"path":"a","start_line":1}""",
            """{"path":"a","start_line":0,"end_line":2}""",
            """{"path":"a","start_line":1.5,"end_line":2}""",
            """{"path":"a","start_line":"1","end_line":2}""",
            """{"path":"a","start_line":1,"end_line":201}""",
            """{"path":"a","start_line":1,"end_line":2,"side":null}""")) {
            try { tool.call(JsonParser.parseString(args).asJsonObject); fail(args) } catch (_: ToolInputException) { }
        }
        assertEquals(1, seen.size)
    }

    private fun rejected(action: () -> Unit) {
        try { action(); fail("Expected rejected source range") } catch (_: ToolInputException) { }
    }
}
