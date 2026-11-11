package dev.insight.review.wall

import dev.insight.diff.wallLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.Color
import java.awt.Font

class WallHighlightingTest {
    private val keyword = WallStyle(Color.BLUE, Font.BOLD)
    private val comment = WallStyle(Color.GRAY, Font.ITALIC)

    /** Styles every occurrence of [words] in [text] as a keyword. */
    private fun keywords(text: String, vararg words: String): List<WallToken> = words.flatMap { word ->
        Regex("\\b$word\\b").findAll(text).map { WallToken(it.range.first, it.range.last + 1, keyword) }.toList()
    }

    @Test
    fun tokensAreCutAtLineBreaksAndTabsWidenColumns() {
        val text = "/* a\n\tb */ val x"
        val side = WallSideHighlight.of(text, listOf(WallToken(0, 10, comment)) + keywords(text, "val"))
        assertEquals(listOf("/* a", "    b */ val x"), side.lines)
        assertEquals(listOf(WallSpan(0, 4, comment)), side.spans[0])
        assertEquals(listOf(WallSpan(0, 8, comment), WallSpan(9, 12, keyword)), side.spans[1])
    }

    @Test
    fun sourceRowsTakeTheirLinesSpans() {
        val text = "val a = 1\n\tval b = 2\n"
        val side = WallSideHighlight.of(text, keywords(text, "val"))
        val rows = wallSourceLines(text)
        assertEquals(listOf(listOf(WallSpan(0, 3, keyword)), listOf(WallSpan(4, 7, keyword))), wallRowSpans(rows, null, side))
    }

    @Test
    fun diffRowsReadTheirSideAndShiftPastTheMarker() {
        val before = "fun a() {\n    /* old\n    note */\n    return 1\n}"
        val after = "fun a() {\n    /* old\n    note */\n    return 2\n}"
        val patch = "@@ -3,3 +3,3 @@\n     note */\n-    return 1\n+    return 2\n }\n\\ No newline at end of file"
        val commentEnd = before.indexOf("*/") + 2
        val old = WallSideHighlight.of(before, listOf(WallToken(before.indexOf("/*"), commentEnd, comment)) + keywords(before, "return"))
        val new = WallSideHighlight.of(after, listOf(WallToken(after.indexOf("/*"), commentEnd, comment)) + keywords(after, "return"))
        val spans = wallRowSpans(wallLines(patch), old, new)
        assertNull("hunk header", spans[0])
        // The comment opened before the hunk still colours its context row.
        assertEquals(listOf(WallSpan(1, 12, comment)), spans[1])
        assertEquals(listOf(WallSpan(5, 11, keyword)), spans[2])
        assertEquals(listOf(WallSpan(5, 11, keyword)), spans[3])
        assertNull("unstyled context", spans[4])
        assertNull("note", spans[5])
    }

    @Test
    fun rowsThatDoNotMatchTheirSideOrLackOneStayPlain() {
        val side = WallSideHighlight.of("val a", keywords("val a", "val"))
        val rows = listOf(WallLine(WallLine.Kind.ADDED, "+var a", new = 1), WallLine(WallLine.Kind.REMOVED, "-val a", old = 1),
            WallLine(WallLine.Kind.ADDED, "+val a", new = 7))
        assertEquals(listOf(null, null, null), wallRowSpans(rows, null, side))
    }

    @Test
    fun extentsAndCutTextBoundLexing() {
        val rows = wallLines("@@ -2,2 +2,3 @@\n a\n-b\n+c\n+d")
        assertEquals(3 to 4, wallSideExtents(rows))
        assertEquals("1\n2", wallTextThrough("1\r\n2\r\n3\r\n", 2))
        assertEquals("1\n2", wallTextThrough("1\n2", 5))
        assertEquals("", wallTextThrough("1\n2", 0))
    }

    @Test
    fun segmentsCoverTheRowAndClipBadSpans() {
        assertEquals(listOf("+val x" to null), wallSegments("+val x", null))
        assertEquals(listOf("+" to null, "val" to keyword, " x" to null),
            wallSegments("+val x", listOf(WallSpan(1, 4, keyword))))
        assertEquals(listOf("ab" to keyword, "c" to comment),
            wallSegments("abc", listOf(WallSpan(0, 2, keyword), WallSpan(1, 9, comment))))
    }
}
