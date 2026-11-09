package dev.insight.codereview

import dev.insight.review.ReviewNote
import dev.insight.review.ReviewSourceRange
import dev.insight.review.wall.WallLine
import dev.insight.review.wall.WallScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Code wall blocks, chat prompts and tools, without the IDE. */
class CodeWallTest {
    private fun file(path: String, lines: Int, width: Int = 10) =
        CodeFile(path, "/project/$path", (1..lines).joinToString("") { "l$it".padEnd(width, 'x') + "\n" })

    @Test fun `blocks number the source, count its lines and cap long files`() {
        val block = codeWallBlock(CodeFile("src/A.kt", "/p/src/A.kt", "fun a() {\n\treturn\n}\n"))
        assertEquals("src/A.kt", block.path)
        assertEquals("src/A.kt", block.title)
        assertEquals("3 lines", block.stats)
        assertEquals(listOf(WallLine.source(1, "fun a() {"), WallLine.source(2, "    return"), WallLine.source(3, "}")), block.lines)
        assertEquals(0, block.hiddenLines)

        val capped = codeWallBlock(file("src/Long.kt", 10), maxLines = 4)
        assertEquals(listOf(1, 2, 3, 4), capped.lines.map { it.new })
        assertEquals(6, capped.hiddenLines)
        assertEquals("1 line", codeWallBlock(CodeFile("one.kt", "/one.kt", "x")).stats)
        val empty = codeWallBlock(CodeFile("empty.kt", "/empty.kt", ""))
        assertEquals("", empty.stats)
        assertTrue(empty.lines.isEmpty())

        val files = listOf(file("a.kt", 2), file("b.kt", 1))
        val wall = CodeWallContent(WallScope(null, "All"), files, mapOf("a.kt" to "Does a.")).toWall()
        assertEquals(listOf("a.kt", "b.kt"), wall.blocks.map { it.path })
        assertEquals(mapOf("a.kt" to "Does a."), wall.summaries)
    }

    @Test fun `a file chat pins the file and its summary, cutting a long one at a line`() {
        val small = file("src/Small.kt", 3)
        val prompt = codeWallChatPrompt(small, "Holds small things.", "the project root")
        assertTrue(prompt.contains("search_code"))
        assertTrue(prompt.contains("path: src/Small.kt"))
        assertTrue(prompt.contains("review scope: the project root"))
        assertTrue(prompt.contains("Holds small things."))
        assertTrue(prompt.contains("1|l1xxxxxxxx\n2|l2xxxxxxxx\n3|l3xxxxxxxx"))
        assertFalse(prompt.contains("continues"))

        // Each line is 11 characters with its break, so 35 characters hold three whole lines.
        val long = file("src/Long.kt", 10)
        assertEquals(3, codeWallPinnedLines(long, 35))
        val clipped = codeWallChatPrompt(long, null, "", sourceChars = 35)
        assertTrue(clipped.contains("3|l3xxxxxxxx"))
        assertFalse(clipped.contains("4|l4"))
        assertFalse(clipped.contains("Generated summary"))
        assertTrue(clipped.contains("[The source continues at line 4 of 10: call read_code with path src/Long.kt and start_line 4 to read on.]"))
    }

    @Test fun `the wall chat pins files while the budget lasts and lists the rest`() {
        val files = listOf(file("a.kt", 2), file("b.kt", 4), file("c.kt", 1), file("d.kt", 1))
        // a.kt takes 22 characters, so 22 more cut b.kt after two lines; c.kt and d.kt are only listed.
        val prompt = codeWallCanvasChatPrompt(files, "src", sourceChars = 44)
        assertTrue(prompt.contains("- a.kt (2 lines)\n- b.kt (4 lines)\n- c.kt (1 line)\n- d.kt (1 line)"))
        assertTrue(prompt.contains("Source of a.kt, with line numbers before \"|\":\n1|l1xxxxxxxx\n2|l2xxxxxxxx\n"))
        assertTrue(prompt.contains("2|l2xxxxxxxx\n[The source continues at line 3 of 4: call read_code with path b.kt and start_line 3"))
        assertFalse(prompt.contains("Source of c.kt"))
        assertTrue(prompt.endsWith("[Not pinned for size; read these files with read_code when a question needs them: c.kt, d.kt.]"))

        val whole = codeWallCanvasChatPrompt(files, "src")
        files.forEach { assertTrue(whole.contains("Source of ${it.path}")) }
        assertFalse(whole.contains("Not pinned"))
    }

    @Test fun `wall chats read the scope but cannot change the canvas`() {
        val snap = codeSnapshot(file("a.kt", 1))
        val context = object : CodeReviewContext {
            override val snapshot = snap
            override suspend fun overview() = ""
            override suspend fun listFiles(directory: String, glob: String?, offset: Int) = ""
            override suspend fun read(ranges: List<CodeRange>) = ""
            override suspend fun search(query: String, regex: Boolean, directory: String, glob: String?, offset: Int) = ""
            override suspend fun showSource(range: ReviewSourceRange) = ""
            override suspend fun addNotes(notes: List<ReviewNote>) = ""
            override suspend fun summaries(offset: Int, path: String?) = ""
        }
        assertEquals(listOf("point_to_code", "list_code_files", "read_code", "search_code", "get_code_summaries"),
            codeWallChatTools(context).map { it.definition.name })
    }
}
