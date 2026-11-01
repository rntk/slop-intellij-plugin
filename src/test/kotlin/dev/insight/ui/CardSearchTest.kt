package dev.insight.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardSearchTest {
    private val cards = listOf(
        CardCandidate(1, "loadRoot", context = listOf("CanvasSessionController")),
        CardCandidate(2, "rootLoaded", text = "Called once the file is shown."),
        CardCandidate(3, "reveal", context = listOf("CanvasSessionPanel", "CanvasSessionPanel.kt"), text = "Scrolls to a card."),
    )

    private fun search(query: String) = searchCards(cards, query).map { it.key }

    @Test fun `empty query keeps every card in order`() {
        assertEquals(listOf(1, 2, 3), search("  "))
    }

    @Test fun `name starts rank before name parts, then context, then text`() {
        assertEquals(listOf(2, 1), search("root"))
        assertEquals(listOf(1), search("LR"))
        assertEquals(listOf(3), search("CSP"))
        assertEquals(listOf(3), search("scrolls card"))
        assertTrue(search("unrelated").isEmpty())
    }
}
