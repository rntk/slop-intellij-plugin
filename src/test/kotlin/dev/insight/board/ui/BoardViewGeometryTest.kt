package dev.insight.board.ui

import dev.insight.board.Annotation
import dev.insight.board.BoardAnchor
import dev.insight.board.BoardRect
import dev.insight.board.BoardSide
import dev.insight.board.CodeBlock
import dev.insight.board.CodeKind
import dev.insight.board.Step
import dev.insight.board.StepsBlock
import dev.insight.board.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardViewGeometryTest {
    @Test
    fun sourceLinesAreNumberedFromTheAnchor() {
        val block = CodeBlock("c", BoardAnchor("a.kt", 7, 9), "a\nb\nc\n", annotations = listOf(Annotation(8, "note")))
        val rows = codeRows(block)
        assertEquals(listOf(7, 8, 9), rows.map { it.number })
        assertEquals(listOf("note"), rows[1].annotations.map { it.text })
    }

    @Test
    fun patchLinesAreNumberedOnTheAnchorSide() {
        val text = "@@ -10,2 +20,2 @@\n-old\n+new\n same"
        val after = codeRows(CodeBlock("d", BoardAnchor("a.kt", 1, 30), text, kind = CodeKind.DIFF))
        assertEquals(listOf(null, null, 20, 21), after.map { it.number })
        assertEquals(listOf(CodeRowKind.HUNK, CodeRowKind.REMOVED, CodeRowKind.ADDED, CodeRowKind.CONTEXT), after.map { it.kind })
        val before = codeRows(CodeBlock("d", BoardAnchor("a.kt", 1, 30, BoardSide.BEFORE), text, kind = CodeKind.DIFF,
            annotations = listOf(Annotation(30, "elsewhere"))))
        assertEquals(listOf(null, 10, null, 11), before.map { it.number })
        assertEquals(listOf("elsewhere"), before.last().annotations.map { it.text })
    }

    @Test
    fun textWrapsAtWordsAndKeepsParagraphs() {
        val measure = { s: String -> s.length }
        assertEquals(listOf("one two", "three", "", "four"), wrapLines("one two three\n\nfour", 8, measure))
        assertEquals(listOf("abcd", "efgh", "ij"), wrapLines("abcdefghij", 4, measure))
    }

    @Test
    fun flowWrapsItems() {
        val rects = flowLayout(listOf(40, 40, 40), 10, 100, 5, 3)
        assertEquals(listOf(BoardRect(0, 0, 40, 10), BoardRect(45, 0, 40, 10), BoardRect(0, 13, 40, 10)), rects)
    }

    @Test
    fun arrowsRunBetweenRectangleEdges() {
        val (a, b) = edgeSegment(BoardRect(0, 0, 100, 50), BoardRect(200, 0, 100, 50))!!
        assertEquals(BoardPoint(100.0, 25.0), a)
        assertEquals(BoardPoint(200.0, 25.0), b)
        assertNull(edgeSegment(BoardRect(0, 0, 300, 300), BoardRect(10, 10, 50, 50)))
    }

    @Test
    fun primaryAnchorPrefersTheBlocksOwnCode() {
        val anchor = BoardAnchor("a.kt", 3, 4)
        assertEquals(anchor, primaryAnchor(StepsBlock("s", listOf(Step("x"), Step("y", anchor)))))
        assertNull(primaryAnchor(TextBlock("t", "x")))
    }
}
