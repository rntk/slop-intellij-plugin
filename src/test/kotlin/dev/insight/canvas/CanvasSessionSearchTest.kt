package dev.insight.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasSessionSearchTest {
    private val entries = listOf(
        CanvasSessionEntry("recent", "src/CanvasSessionPanel.kt", "First exploration", 30, 2),
        CanvasSessionEntry("other", "src/CanvasSessionPanel.kt/Other.kt", "CanvasSessionPanel.kt", 20, 1),
        CanvasSessionEntry("older", "test/CanvasSessionPanelTest.kt", "Older exploration", 10, 4),
        CanvasSessionEntry("windows", "src\\Main.kt", "Main", 5, 0),
    )

    @Test fun `empty search lists every session in its original order`() {
        assertEquals(entries, filterCanvasSessions(entries, "  "))
    }

    @Test fun `fuzzy search accepts camel humps partial names and mixed case`() {
        val expected = listOf(entries[0], entries[2])
        assertEquals(expected, filterCanvasSessions(entries, "CSP"))
        assertEquals(expected, filterCanvasSessions(entries, " sessionpan "))
        assertEquals(expected, filterCanvasSessions(entries, "CANVASSESSIONPANEL"))
        assertTrue(filterCanvasSessions(entries, "unrelated").isEmpty())
    }

    @Test fun `only root filenames match never directory names or session titles`() {
        assertEquals(listOf(entries[0], entries[2]), filterCanvasSessions(entries, "CanvasSessionPanel"))
        assertTrue(filterCanvasSessions(entries, "exploration").isEmpty())
        assertTrue(filterCanvasSessions(entries, "src").isEmpty())
        assertEquals(listOf(entries[3]), filterCanvasSessions(entries, "main"))
    }

    @Test fun `separate sessions for the same filename are retained`() {
        val sameFile = entries[0].copy(id = "another", updated = 1)
        assertEquals(listOf(entries[0], sameFile), filterCanvasSessions(listOf(entries[0], sameFile), "CSP"))
    }
}
