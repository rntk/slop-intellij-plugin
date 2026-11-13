package dev.insight.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardTextTest {
    private val board = fullBoard()

    @Test
    fun describeStartsWithTitleAndListsBlocksInLayoutOrder() {
        val lines = describeBoard(board).lines()
        assertEquals("How requests flow", lines[0])
        val ids = lines.mapNotNull { Regex("^\\s*\\[(\\w+)]").find(it)?.groupValues?.get(1) }
        // t2 and s2 are in the group and follow it, indented.
        assertEquals(listOf("h1", "t1", "c1", "s1", "ch1", "st1", "tb1", "g1", "t2", "s2"), ids)
        assertTrue(lines.single { it.contains("[t2]") }.startsWith("  [t2]"))
        assertTrue(lines.single { it.contains("[s2]") }.startsWith("  [s2]"))
        assertFalse(lines.single { it.contains("[g1]") }.startsWith(" "))
    }

    @Test
    fun edgesAreListedAtTheEnd() {
        val lines = describeBoard(board).lines()
        assertEquals("  t1 -> c1 (explains)", lines[lines.size - 2])
        assertEquals("  s1 -> ch1", lines.last())
        assertEquals("Edges:", lines[lines.size - 3])
        assertFalse(describeBoard(boardOf(text("a"))).contains("Edges"))
    }

    @Test
    fun codeBlockShowsAnchorLinesAndAnnotationsButNotSource() {
        val code = describeBlock(board, board.block("c1")!!)
        assertEquals("[c1] code · full · Handler · src/A.kt:5-9 · 5 lines · diff · 2 annotations · (evidence)", code)
        val plain = CodeBlock("c", anchor(1, 3), "SECRET_SOURCE")
        val line = describeBlock(board, plain)
        assertFalse(line.contains("SECRET_SOURCE"))
        assertFalse(line.contains("annotations"))
        assertTrue(line.contains("3 lines"))
    }

    @Test
    fun interpretationAndEvidenceAreMarked() {
        assertTrue(describeBlock(board, board.block("t1")!!).endsWith("(interpretation)"))
        assertTrue(describeBlock(board, board.block("h1")!!).endsWith("(interpretation)"))
        assertTrue(describeBlock(board, board.block("st1")!!).endsWith("(interpretation)"))
        assertTrue(describeBlock(board, board.block("tb1")!!).endsWith("(interpretation)"))
        assertTrue(describeBlock(board, board.block("g1")!!).endsWith("(interpretation)"))
        assertTrue(describeBlock(board, board.block("s1")!!).endsWith("(evidence)"))
        assertTrue(describeBlock(board, board.block("ch1")!!).endsWith("(evidence)"))
    }

    @Test
    fun textLineHasIdKindWidthTitleToneAndExcerpt() {
        assertEquals("[t1] text · wide · Intro · insight · \"First paragraph. Second paragraph.\" · (interpretation)",
            describeBlock(board, board.block("t1")!!))
    }

    @Test
    fun excerptIsTruncated() {
        val long = TextBlock("t", "x".repeat(500))
        val line = describeBlock(board, long, maxTextChars = 20)
        assertTrue(line.contains("\"" + "x".repeat(20) + "…\""))
        assertFalse(line.contains("x".repeat(21)))
    }

    @Test
    fun otherKinds() {
        assertEquals("[h1] heading · full · Overview · h2 · (interpretation)", describeBlock(board, board.block("h1")!!))
        assertEquals("[s1] symbol · narrow · Service.run · Kotlin · method · src/B.kt:3-4 (before) · \"Runs it\" · (evidence)",
            describeBlock(board, board.block("s1")!!))
        assertEquals("[ch1] chain · wide · Path · main -> run · cut off · (evidence)", describeBlock(board, board.block("ch1")!!))
        assertEquals("[st1] steps · wide · Walkthrough · 2 steps · \"Parse\" · (interpretation)", describeBlock(board, board.block("st1")!!))
        assertEquals("[tb1] table · wide · Table · Name, Where · 2 rows · (interpretation)", describeBlock(board, board.block("tb1")!!))
        assertEquals("[g1] group · full · Group title · warning · children: t2, s2 · (interpretation)", describeBlock(board, board.block("g1")!!))
    }

    @Test
    fun emptyBoard() {
        assertEquals("Empty\n(empty board)", describeBoard(Board.new("Empty", id = "e")))
    }

    @Test
    fun markdownStructure() {
        val md = boardMarkdown(board)
        assertTrue(md.startsWith("# How requests flow\n\n"))
        assertTrue(md.contains("\n### Overview\n"))
        assertTrue(md.contains("> **INSIGHT:** **Intro**\n>\n> First paragraph.\n>\n> Second paragraph."))
        assertTrue(md.contains("**Handler** · `src/A.kt:5-9`\n\n```diff\na\nb\nc\nd\ne\n```"))
        assertTrue(md.contains("- line 6 (warning): Checks input\n- line 7: Fine"))
        assertTrue(md.contains("- **Service.run** (Kotlin · method) — `src/B.kt:3-4 (before)`\n  Runs it"))
        assertTrue(md.contains("**Path**\n\n- **main** — `src/A.kt:1-2`"))
        assertTrue(md.contains("- **run**: does work\n- … (path cut off)"))
        assertTrue(md.contains("**Walkthrough**\n\n1. Parse\n2. Run (`src/A.kt:8-8`)"))
        assertTrue(md.contains("| Name | Where |\n| --- | --- |\n| a \\| b | here (`src/A.kt:1-1`) |\n| c | d |"))
        assertTrue(md.endsWith("\n"))
    }

    @Test
    fun markdownGroupsAreSectionsWithTheirChildren() {
        val md = boardMarkdown(board)
        val group = md.indexOf("## Group title")
        assertTrue(group > 0)
        assertTrue(md.indexOf("Inside group") > group)
        assertTrue(md.indexOf("- **Inner**") > group)
        // grouped children are not repeated at the top level
        assertEquals(1, Regex("Inside group").findAll(md).count())
    }

    @Test
    fun markdownProseAndCalloutsAndFences() {
        val b = boardOf(
            TextBlock("p", "Plain.\n\nSecond."),
            TextBlock("w", "Careful", tone = Tone.WARNING),
            CodeBlock("c", anchor(1, 1, "x/Y.kt"), "val s = \"```\""),
            GroupBlock("g", emptyList()),
        )
        val md = boardMarkdown(b)
        assertTrue(md.contains("\n\nPlain.\n\nSecond.\n\n"))
        assertTrue(md.contains("> **WARNING:** Careful"))
        assertTrue(md.contains("````kotlin\nval s = \"```\"\n````"))
        assertTrue(md.contains("## Group"))
    }

    @Test
    fun markdownOfEmptyBoardIsJustTheTitle() {
        assertEquals("# T\n", boardMarkdown(Board.new("T", id = "e")))
    }
}
