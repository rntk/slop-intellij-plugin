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

class CanvasCardSearchTest {
    private fun symbol(id: String, name: String, path: String, container: String = "", summary: String? = null) =
        CanvasCard(id, null, CardKind.SYMBOL, CardAnchor(0, 1, 0), SymbolRef(path, name, "FUNCTION", container), path, 0,
            "fun $name() {}", summary = summary)

    private val cards = listOf(
        symbol("load", "loadRoot", "src/CanvasSessionController.kt", "CanvasSessionController"),
        symbol("show", "showCanvasSessions", "src/Sessions.kt", summary = "Lists saved sessions in the side panel."),
        symbol("reveal", "reveal", "src/CanvasSessionPanel.kt", "CanvasSessionPanel"),
        CanvasCard("sel", null, CardKind.SELECTION, CardAnchor(0, 1, 0), null, "src/Main.kt", 3, "\n  val answer = 42\n}"),
    )

    @Test fun `empty search lists every card in opening order`() {
        assertEquals(cards, searchCanvasCards(cards, " "))
    }

    @Test fun `names starting with the query rank before names containing it, then containers and files`() {
        assertEquals(listOf("show", "sel", "load", "reveal"), searchCanvasCards(cards, "s").map { it.id })
        assertEquals(listOf("load"), searchCanvasCards(cards, "root").map { it.id })
        assertEquals("show", searchCanvasCards(cards, "sCS").first().id)
    }

    @Test fun `containers and file names match when the name does not`() {
        assertEquals(listOf("reveal"), searchCanvasCards(cards, "SessionPanel").map { it.id })
        assertEquals(listOf("sel"), searchCanvasCards(cards, "main").map { it.id })
    }

    @Test fun `summaries and selected text match by every word`() {
        assertEquals(listOf("show"), searchCanvasCards(cards, "side panel").map { it.id })
        assertEquals(listOf("sel"), searchCanvasCards(cards, "answer 42").map { it.id })
        assertTrue(searchCanvasCards(cards, "unrelated words").isEmpty())
    }

    @Test fun `a selection is named by its first non-blank line`() {
        assertEquals("val answer = 42", canvasCardName(cards[3]))
        assertEquals("reveal", canvasCardName(cards[2]))
    }
}
