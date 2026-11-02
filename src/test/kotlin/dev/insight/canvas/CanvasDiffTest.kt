package dev.insight.canvas

import dev.insight.canvas.CanvasDiffRow.Kind.ADDED
import dev.insight.canvas.CanvasDiffRow.Kind.CONTEXT
import dev.insight.canvas.CanvasDiffRow.Kind.GAP
import dev.insight.canvas.CanvasDiffRow.Kind.REMOVED
import org.junit.Assert.assertEquals
import org.junit.Test

class CanvasDiffTest {
    private val patch = """
        @@ -1,3 +1,3 @@
         a
        -b
        +B
         c
        @@ -10,3 +10,2 @@
         j
        -k
         l
        \ No newline at end of file
    """.trimIndent() + "\n"

    @Test fun `numbers both sides and separates hunks with a gap`() {
        assertEquals(listOf(
            CanvasDiffRow(CONTEXT, "a", 1, 1), CanvasDiffRow(REMOVED, "b", old = 2), CanvasDiffRow(ADDED, "B", new = 2),
            CanvasDiffRow(CONTEXT, "c", 3, 3), CanvasDiffRow(GAP, "⋯"),
            CanvasDiffRow(CONTEXT, "j", 10, 10), CanvasDiffRow(REMOVED, "k", old = 11), CanvasDiffRow(CONTEXT, "l", 12, 11),
        ), canvasDiffRows(patch))
    }

    @Test fun `keeps only hunks that change the given lines, ignoring their context`() {
        assertEquals(listOf("a", "b", "B", "c"), canvasDiffRows(patch, 2..2).map { it.text })
        assertEquals(emptyList<CanvasDiffRow>(), canvasDiffRows(patch, 3..9))
        // The removal sits between new lines 10 and 11, so it needs both.
        assertEquals(listOf("j", "k", "l"), canvasDiffRows(patch, 10..11).map { it.text })
        assertEquals(emptyList<CanvasDiffRow>(), canvasDiffRows(patch, 11..20))
    }

    @Test fun `text without hunks has no rows`() {
        assertEquals(emptyList<CanvasDiffRow>(), canvasDiffRows("[Diff content unavailable: binary.]"))
        assertEquals("Diff · +1 −2", canvasDiffTitle(canvasDiffRows(patch)))
    }
}
