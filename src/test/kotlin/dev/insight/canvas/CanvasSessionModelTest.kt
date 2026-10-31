package dev.insight.canvas

import dev.insight.agent.ChatMessage
import dev.insight.agent.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasSessionModelTest {
    private var now = 100L
    private var next = 0
    private fun model() = CanvasSessionModel(CanvasSession.new("src/Main.kt", now = 1, id = "s1"), clock = { now++ }, ids = { "c${next++}" })
    private val save = SymbolRef("src/Store.kt", "save", "FUNCTION", "Store", offset = 40, line = 3)

    @Test
    fun `opening the same place twice returns the open card`() {
        val model = model()
        val changes = mutableListOf<CanvasChange>()
        model.subscribe { changes += it }
        val first = model.open(null, CardKind.SYMBOL, CardAnchor(5, 9, 2), "src/Store.kt", 3, "fun save() {}", save)
        val again = model.open(null, CardKind.SYMBOL, CardAnchor(5, 9, 2), "src/Store.kt", 3, "fun save() {}", save)
        val other = model.open(null, CardKind.SELECTION, CardAnchor(5, 9, 2), "src/Main.kt", 2, "save()")
        assertSame(first, again)
        assertEquals(listOf("c0", "c1"), model.cards.map { it.id })
        assertEquals(listOf(CanvasChange.CardOpened(first), CanvasChange.CardOpened(other)), changes)
        assertEquals(1L, model.session.created)
        assertTrue(model.session.updated >= 100)
    }

    @Test
    fun `removing a card removes its subtree and chains run root first`() {
        val model = model()
        val a = model.open(null, CardKind.SYMBOL, CardAnchor(0, 4, 0), "src/Store.kt", 3, "a", save)
        val b = model.open(a.id, CardKind.SELECTION, CardAnchor(0, 1, 3), "src/Store.kt", 3, "a")
        val c = model.open(b.id, CardKind.SYMBOL, CardAnchor(0, 1, 3), "src/Store.kt", 3, "a", save.copy(name = "load"))
        val d = model.open(null, CardKind.SELECTION, CardAnchor(1, 2, 0), "src/Main.kt", 0, "b")
        assertEquals(listOf(a, b, c), model.chain(c.id))
        assertEquals(listOf(a, d), model.children(null))
        assertEquals(setOf(b.id, c.id), model.remove(b.id))
        assertEquals(listOf(a.id, d.id), model.cards.map { it.id })
        assertTrue(model.remove("missing").isEmpty())
    }

    @Test
    fun `card state changes notify only when something changed`() {
        val model = model()
        val card = model.open(null, CardKind.SELECTION, CardAnchor(0, 1, 0), "src/Main.kt", 0, "x")
        var changes = 0
        val subscription = model.subscribe { changes++ }
        model.setSummary(card.id, "  Saves it.  ")
        model.setSummary(card.id, "  Saves it.  ")
        assertEquals(true, model.toggleFolded(card.id, CardSection.SOURCE))
        assertEquals(false, model.toggleFolded(card.id, CardSection.SOURCE))
        model.setFolded(card.id, CardSection.CHAT, true)
        model.setMessages(card.id, listOf(SavedMessage(ChatRole.USER, "why?")))
        model.setDraft(card.id, "and")
        model.setView(CanvasView(1.5, 10, 20))
        model.setView(CanvasView(1.5, 10, 20))
        assertEquals(7, changes)
        subscription.dispose()
        model.setDraft(card.id, "and then")
        assertEquals(7, changes)
        val saved = model.card(card.id)!!
        assertEquals("  Saves it.  ", saved.summary)
        assertEquals(setOf(CardSection.CHAT), saved.folded)
        assertEquals("and then", saved.draft)
        assertNull(model.toggleFolded("missing", CardSection.META))
    }

    @Test
    fun `long text is bounded at a line and card places are 1-based`() {
        val (text, cut) = boundCardText("aaaa\nbbbb\ncccc", 11)
        assertEquals("aaaa\nbbbb", text)
        assertTrue(cut)
        assertFalse(boundCardText("short").second)
        val card = CanvasCard("c", null, CardKind.SELECTION, CardAnchor(0, 1, 0), null, "a.kt", 9, "x\ny\nz")
        assertEquals(11, card.lastLine)
        assertEquals("a.kt:10-12", card.location)
    }

    @Test
    fun `tool calls are not saved and saved messages become chat messages`() {
        val saved = savedMessages(listOf(ChatMessage(ChatRole.NOTICE, "Welcome"), ChatMessage(ChatRole.USER, "Q"),
            ChatMessage(ChatRole.TOOL, "read_file(path=a)"), ChatMessage(ChatRole.ASSISTANT, "A")))
        assertEquals(listOf(SavedMessage(ChatRole.NOTICE, "Welcome"), SavedMessage(ChatRole.USER, "Q"), SavedMessage(ChatRole.ASSISTANT, "A")), saved)
        assertEquals(ChatMessage(ChatRole.USER, "Q"), saved.toChatMessages()[1])
    }
}
