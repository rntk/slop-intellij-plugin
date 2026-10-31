package dev.insight.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasRestoreTest {
    private val parent = "fun run() {\n    save(1)\n    load()\n    save(2)\n}"
    private val links = listOf(CanvasLink(4..6, listOf(1)), CanvasLink(16..19, listOf(2)), CanvasLink(28..31, listOf(3)),
        CanvasLink(40..43, listOf(2)))

    private fun card(kind: CardKind, start: Int, end: Int, text: String) = CanvasCard("c", null, kind, CardAnchor(start, end, 0),
        if (kind == CardKind.SYMBOL) SymbolRef("A.kt", "save", "FUNCTION") else null, "A.kt", 0, text)

    @Test
    fun `a restored symbol moves to the nearest link to it`() {
        assertEquals(CardAnchor(40, 44, 13), restoredAnchor(parent, 10, links, 2, CardAnchor(38, 42, 12)))
        assertEquals(CardAnchor(16, 20, 11), restoredAnchor(parent, 10, links, 2, CardAnchor(0, 3, 10)))
        // No link leads to the target any more: the saved anchor stays, within the text.
        assertEquals(CardAnchor(parent.length, parent.length, 14), restoredAnchor(parent, 10, links, 9, CardAnchor(500, 600, 3)))
    }

    @Test
    fun `a selection keeps its parent's links while its text still matches`() {
        val start = parent.indexOf("save(1)")
        val (kept, changed) = restoredSelectionLinks(parent, links, card(CardKind.SELECTION, start, start + 7, "save(1)"))
        assertFalse(changed)
        assertEquals(listOf(CanvasLink(0..3, listOf(2))), kept)
        val (none, moved) = restoredSelectionLinks(parent, links, card(CardKind.SELECTION, start, start + 7, "save(9)"))
        assertTrue(moved)
        assertTrue(none.isEmpty())
        assertTrue(restoredSelectionLinks(parent, links, card(CardKind.SELECTION, 90, 99, "x")).second)
    }

    @Test
    fun `changes are judged on the bounded text that was saved`() {
        val long = "x\n".repeat(CANVAS_CARD_TEXT_LIMIT)
        val saved = card(CardKind.SYMBOL, 0, 1, boundCardText(long).first)
        assertFalse(sourceChanged(saved, long))
        assertTrue(sourceChanged(saved, "y$long"))
        assertEquals(listOf(1, 2, 3), linkTargets(links))
    }

    @Test
    fun `restoring a snapshot refreshes the card but keeps its chat`() {
        val model = CanvasSessionModel(CanvasSession.new("A.kt", now = 1), clock = { 2 }, ids = { "c1" })
        val opened = model.open(null, CardKind.SYMBOL, CardAnchor(0, 3, 0), "A.kt", 0, "old", SymbolRef("A.kt", "save", "FUNCTION"))
        model.setMessages(opened.id, listOf(SavedMessage(dev.insight.agent.ChatRole.USER, "hi")))
        val changes = mutableListOf<CanvasChange>()
        model.subscribe { changes += it }
        model.restoreSnapshot(opened.id, CardAnchor(4, 7, 1), 3, "new\ntext")
        val card = model.card(opened.id)!!
        assertEquals("new\ntext", card.text)
        assertEquals(3, card.firstLine)
        assertEquals(CardAnchor(4, 7, 1), card.anchor)
        assertEquals(1, card.messages.size)
        assertEquals(listOf(CanvasChange.CardChanged(card)), changes)
    }
}
