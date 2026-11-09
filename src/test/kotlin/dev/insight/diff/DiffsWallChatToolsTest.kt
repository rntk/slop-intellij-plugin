package dev.insight.diff

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.insight.agent.ChatMessage
import dev.insight.agent.ChatRole
import dev.insight.agent.SourceSide
import dev.insight.agent.ToolInputException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffsWallChatToolsTest {
    private val a = ReviewFile("src/a.kt", "/p/src/a.kt", null, "modified",
        "@@ -1,3 +1,3 @@ class A\n fun keep() = loadUser()\n-fun old() = loadUser(1)\n+fun new() = loadUser(2)\n", "a",
        before = "fun keep() = loadUser()\nfun old() = loadUser(1)\nfun tail() = 0\n",
        after = "fun keep() = loadUser()\nfun new() = loadUser(2)\nfun tail() = 0\n")
    private val b = ReviewFile("src/b.kt", "/p/src/b.kt", null, "added", "@@ -0,0 +1,2 @@\n+val user = loadUser()\n+    indented()\n", "b",
        after = "val user = loadUser()\n    indented()\n")

    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    @Test
    fun searchFindsTextAcrossTheChangeWithBothSidesNumbered() {
        val result = json(diffSearchResult(listOf(a, b), DiffSearch("LOADUSER")))
        assertEquals(4, result["total_matches"].asInt)
        val matches = result.getAsJsonArray("matches").map { it.asJsonObject }
        assertEquals(listOf("src/a.kt", "src/a.kt", "src/a.kt", "src/b.kt"), matches.map { it["path"].asString })
        // Patch lines are numbered as read_diff_files numbers them; the hunk header is line 1.
        assertEquals(listOf(2, 3, 4, 2), matches.map { it["line"].asInt })
        assertEquals(listOf("context", "removed", "added", "added"), matches.map { it["kind"].asString })
        assertEquals(2, matches[1]["before_line"].asInt)
        assertFalse(matches[1].has("after_line"))
        assertEquals(2, matches[2]["after_line"].asInt)
        assertEquals("fun old() = loadUser(1)", matches[1]["text"].asString)
    }

    @Test
    fun searchFiltersByLineKindPathAndRegexAndPages() {
        assertEquals(listOf("added"), json(diffSearchResult(listOf(a, b), DiffSearch("loadUser", paths = listOf("src/a.kt"), lines = DiffSearchLines.ADDED)))
            .getAsJsonArray("matches").map { it.asJsonObject["kind"].asString })
        assertEquals(2, json(diffSearchResult(listOf(a), DiffSearch("loadUser\\(\\d\\)", regex = true)))["total_matches"].asInt)
        // Markers and hunk headers are not file text.
        assertEquals(0, json(diffSearchResult(listOf(a), DiffSearch("+fun")))["total_matches"].asInt)
        assertEquals(0, json(diffSearchResult(listOf(a), DiffSearch("class A")))["total_matches"].asInt)

        val many = ReviewFile("m.kt", "/m.kt", null, "modified", "@@ -0,0 +1,120 @@\n" + (1..120).joinToString("\n") { "+hit $it" } + "\n", "m")
        val first = json(diffSearchResult(listOf(many), DiffSearch("hit")))
        assertEquals(SEARCH_PAGE, first.getAsJsonArray("matches").size())
        val next = first["next_offset"].asInt
        val last = json(diffSearchResult(listOf(many), DiffSearch("hit", offset = next + SEARCH_PAGE)))
        assertEquals(120 - next - SEARCH_PAGE, last.getAsJsonArray("matches").size())
        assertFalse(last.has("next_offset"))

        assertThrows(ToolInputException::class.java) { diffSearchResult(listOf(a), DiffSearch("x", paths = listOf("missing.kt"))) }
        assertThrows(ToolInputException::class.java) { diffSearchResult(listOf(a), DiffSearch("(", regex = true)) }
    }

    @Test
    fun sourceReadsEitherSideBeyondTheHunksAndSaysWhereToContinue() {
        val before = json(diffSourceResult(a, SourceSide.BEFORE, 2, 3))
        assertEquals("2|fun old() = loadUser(1)\n3|fun tail() = 0", before["source"].asString)
        assertEquals(3, before["total_lines"].asInt)
        assertFalse(before.has("next_start_line"))
        val after = json(diffSourceResult(a, SourceSide.AFTER, 1, 1))
        assertEquals("1|fun keep() = loadUser()", after["source"].asString)
        assertEquals(2, after["next_start_line"].asInt)
        // Indentation is source, not whitespace to tidy.
        assertEquals("2|    indented()", json(diffSourceResult(b, SourceSide.AFTER, 2, null))["source"].asString)

        val missing = assertThrows(ToolInputException::class.java) { diffSourceResult(b, SourceSide.BEFORE, 1, null) }
        assertTrue(missing.message!!.contains("new"))
        assertThrows(ToolInputException::class.java) { diffSourceResult(a, SourceSide.AFTER, 9, null) }
        assertThrows(ToolInputException::class.java) { diffSourceResult(a, SourceSide.AFTER, 3, 2) }
    }

    @Test
    fun promptPinsTheFileItsSummaryAndANumberedDiff() {
        val prompt = wallChatPrompt(a, "Renames old to new.", "Working tree vs HEAD")
        assertTrue(prompt.contains("path: src/a.kt"))
        assertTrue(prompt.contains("comparison: Working tree vs HEAD"))
        assertTrue(prompt.contains("Renames old to new."))
        assertTrue(prompt.contains("1|@@ -1,3 +1,3 @@ class A\n2| fun keep() = loadUser()\n3|-fun old()"))
        assertFalse(prompt.contains("The diff continues"))

        val cut = wallChatPrompt(a, null, "", patchChars = 30)
        assertFalse(cut.contains("+fun new()"))
        assertTrue(cut.contains("call read_diff_files with path src/a.kt and offset"))
        assertFalse(cut.contains("Generated summary"))
    }

    @Test
    fun toolsReadAndSearchTheChangeButLeaveTheCanvasAlone() = runBlocking {
        val searched = mutableListOf<DiffSearch>()
        val context = object : DiffReviewContext {
            override suspend fun state(offset: Int, patchBudget: Int) = "{}"
            override suspend fun read(paths: List<String>, offset: Int) = "{}"
            override suspend fun searchDiffs(search: DiffSearch): String { searched += search; return "{}" }
            override suspend fun readSource(path: String, side: SourceSide, start: Int, end: Int?) = "$path $side $start $end"
            override suspend fun addNotes(notes: List<dev.insight.review.ReviewNote>) = error("not offered")
            override suspend fun showSource(range: dev.insight.review.ReviewSourceRange) = error("not offered")
        }
        val tools = wallChatTools(context).associateBy { it.definition.name }
        assertEquals(setOf("point_to_code", "get_current_diff", "read_diff_files", "search_diff", "read_diff_source", "get_change_summaries"), tools.keys)
        tools.getValue("search_diff").call(json("""{"query":"user","lines":"removed","paths":["src/a.kt"]}"""))
        assertEquals(DiffSearch("user", paths = listOf("src/a.kt"), lines = DiffSearchLines.REMOVED), searched.single())
        assertEquals("src/a.kt BEFORE 4 null", tools.getValue("read_diff_source").call(json("""{"path":"src/a.kt","side":"before","start_line":4}""")))
        assertThrows(ToolInputException::class.java) { runBlocking { tools.getValue("search_diff").call(json("""{"query":"x","lines":"some"}""")) } }
        Unit
    }

    @Test
    fun theWallsOwnChatPinsEveryDiffWithinItsBudget() {
        val prompt = wallCanvasChatPrompt(listOf(a, b), "Working tree vs HEAD")
        assertTrue(prompt.contains("- src/a.kt (modified)"))
        assertTrue(prompt.contains("- src/b.kt (added)"))
        assertTrue(prompt.contains("comparison: Working tree vs HEAD"))
        assertTrue(prompt.contains("Diff of src/a.kt"))
        assertTrue(prompt.contains("+fun new() = loadUser(2)"))
        assertTrue(prompt.contains("+val user = loadUser()"))
        assertFalse(prompt.contains("continues"))
        assertFalse(prompt.contains("Not pinned"))

        // The diff that runs past the budget is cut at a line and says where to read on; later ones are listed.
        val cut = wallCanvasChatPrompt(listOf(a, b), "", patchChars = 30)
        assertTrue(cut.contains("call read_diff_files with path src/a.kt and offset"))
        assertFalse(cut.contains("Diff of src/b.kt"))
        assertTrue(cut.contains("read these diffs with read_diff_files when a question needs them: src/b.kt."))
        assertTrue(cut.contains("- src/b.kt (added)"))
    }

    @Test
    fun aChatIsNamedByItsFirstQuestion() {
        assertEquals("New chat", wallChatTitle(listOf(ChatMessage(ChatRole.NOTICE, "Ask about a.kt"))))
        assertEquals("Why is old removed?", wallChatTitle(listOf(ChatMessage(ChatRole.USER, "Why is old removed?"))))
        assertEquals("Why does this ch…", wallChatTitle(listOf(ChatMessage(ChatRole.USER, "Why does this\n change the loader?")), max = 17))
    }
}
