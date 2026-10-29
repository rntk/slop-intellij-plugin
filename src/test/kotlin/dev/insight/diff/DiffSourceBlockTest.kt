package dev.insight.diff

import com.google.gson.JsonParser
import dev.insight.chat.SourceSide
import dev.insight.chat.ToolInputException
import dev.insight.review.ReviewNote
import dev.insight.review.ReviewSourceRange
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiffSourceBlockTest {
    private val file = ReviewFile("src/a.kt", "/project/src/a.kt", null, "modified", "patch", "1",
        before = "old\r\n  removed\r\n", after = "first\n\tval x = 1\nlast")
    private val snapshot = ReviewSnapshot(listOf(file), emptyList())

    @Test fun `inclusive ranges preserve whitespace and choose the snapshot side`() {
        assertEquals("\tval x = 1\nlast", resolveSourceBlock(snapshot, ReviewSourceRange(file.path, 2, 3)).text)
        assertEquals("  removed", resolveSourceBlock(snapshot, ReviewSourceRange(file.path, 2, 2, SourceSide.BEFORE)).text)
        for (range in listOf(ReviewSourceRange(file.path, 0, 1), ReviewSourceRange(file.path, 3, 2),
            ReviewSourceRange(file.path, 1, 4), ReviewSourceRange(file.path, 1, Int.MAX_VALUE),
            ReviewSourceRange(file.path, 1, 1, SourceSide.CURRENT), ReviewSourceRange("other.kt", 1, 1))) {
            rejected { resolveSourceBlock(snapshot, range) }
        }
        rejected { resolveSourceBlock(snapshot.copy(files = listOf(file.copy(after = null))), ReviewSourceRange(file.path, 1, 1)) }
        rejected { resolveSourceBlock(snapshot.copy(files = listOf(file.copy(after = ""))), ReviewSourceRange(file.path, 1, 1)) }
        rejected { resolveSourceBlock(snapshot.copy(files = listOf(file.copy(after = "x".repeat(20_001)))), ReviewSourceRange(file.path, 1, 1)) }
    }

    @Test fun `source cards coexist with notes deduplicate and expire with their evidence`() {
        val state = DiffReviewState()
        state.update(snapshot)
        val range = ReviewSourceRange(file.path, 1, 2)
        state.showSource(state.revision, range)
        state.showSource(state.revision, range)
        assertEquals(1, state.sources.size)
        state.addNote(state.revision, listOf(file.path), "Explanation")
        state.showSource(state.revision, range.copy(side = SourceSide.BEFORE))
        assertEquals(2, state.sources.size)
        val revision = state.revision
        state.update(snapshot.copy(files = snapshot.files + file.copy(path = "b.kt")))
        assertEquals(2, state.sources.size)
        rejected { state.showSource(revision, range) }
        state.removeSource(range)
        assertEquals(SourceSide.BEFORE, state.sources.single().range.side)
        assertEquals(1, state.notes.size)
        state.update(snapshot.copy(files = listOf(file.copy(fingerprint = "2"))))
        assertTrue(state.sources.isEmpty())
        state.showSource(state.revision, range)
        state.clearNotes()
        assertTrue(state.sources.isEmpty())
    }

    @Test fun `tool validates coordinates before dispatch and defaults to after`() = runBlocking {
        val seen = mutableListOf<ReviewSourceRange>()
        val context = object : DiffReviewContext {
            override suspend fun state(offset: Int, patchBudget: Int) = ""
            override suspend fun read(paths: List<String>, offset: Int) = ""
            override suspend fun addNotes(notes: List<ReviewNote>) = ""
            override suspend fun showSource(range: ReviewSourceRange): String { seen += range; return "shown" }
        }
        val tool = diffReviewTools(context).single { it.definition.name == "show_diff_source" }
        tool.call(JsonParser.parseString("""{"path":"src/a.kt","start_line":2,"end_line":3}""").asJsonObject)
        assertEquals(listOf(ReviewSourceRange(file.path, 2, 3)), seen)
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
