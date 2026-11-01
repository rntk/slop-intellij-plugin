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
    fun `opening attribution belongs to each card and survives revisiting in either direction`() {
        val model = model()
        val manual = model.open(null, CardKind.SYMBOL, CardAnchor(5, 9, 2), "src/Store.kt", 3, "fun save() {}", save)
        val revisitedByLlm = model.open(null, CardKind.SYMBOL, manual.anchor, manual.filePath, manual.firstLine,
            manual.text, save, openedBy = CardOpenedBy.LLM)
        assertSame(manual, revisitedByLlm)
        assertEquals(CardOpenedBy.USER, revisitedByLlm.openedBy)

        val llm = model.open(manual.id, CardKind.SELECTION, CardAnchor(0, 3, 3), "src/Store.kt", 3, "fun",
            openedBy = CardOpenedBy.LLM)
        val revisitedByUser = model.open(manual.id, CardKind.SELECTION, llm.anchor, llm.filePath, llm.firstLine, llm.text)
        assertSame(llm, revisitedByUser)
        assertEquals(CardOpenedBy.LLM, revisitedByUser.openedBy)

        val manualChild = model.open(llm.id, CardKind.SELECTION, CardAnchor(0, 1, 3), "src/Store.kt", 3, "f")
        assertEquals(CardOpenedBy.USER, manualChild.openedBy)
        assertEquals(3, model.cards.size)
    }

    @Test
    fun `summary chat restore and expand preserve opening attribution`() {
        val model = model()
        val card = model.open(null, CardKind.SYMBOL, CardAnchor(5, 9, 2), "src/Store.kt", 3, "fun save() {}", save,
            openedBy = CardOpenedBy.LLM)
        val child = model.open(card.id, CardKind.SELECTION, CardAnchor(0, 3, 3), card.filePath, 3, "fun",
            openedBy = CardOpenedBy.LLM)
        model.setSummary(card.id, "Saves it.")
        model.setFolded(card.id, CardSection.SUMMARY, true)
        model.addChat(card.id)
        model.setDraft(card.id, "why?")
        model.restoreSnapshot(card.id, card.anchor.copy(line = 4), 4, "fun save() { write() }")
        model.expandToFile(card.id, "package sample\nfun save() { write() }", 15)
        assertEquals(CardOpenedBy.LLM, model.card(card.id)!!.openedBy)
        assertEquals(CardOpenedBy.LLM, model.card(child.id)!!.openedBy)
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
    fun `chat tabs keep independent histories and drafts and choose an adjacent tab on close`() {
        val model = model()
        val card = model.open(null, CardKind.SELECTION, CardAnchor(0, 1, 0), "a", 0, "x")
        val first = card.activeChatId
        model.setDraft(card.id, "first draft")
        val second = model.addChat(card.id)!!
        model.setDraft(card.id, "second draft")
        val reply = listOf(SavedMessage(ChatRole.ASSISTANT, "background reply"))
        model.setMessages(card.id, reply, first)
        assertTrue(model.card(card.id)!!.messages.isEmpty())
        assertEquals("second draft", model.card(card.id)!!.draft)
        model.selectChat(card.id, first)
        assertEquals(reply, model.card(card.id)!!.messages)
        assertEquals("first draft", model.card(card.id)!!.draft)
        model.selectChat(card.id, "missing")
        assertEquals(first, model.card(card.id)!!.activeChatId)
        model.removeChat(card.id, first)
        assertEquals(second.id, model.card(card.id)!!.activeChatId)
        model.setMessages(card.id, reply, first)
        assertTrue(model.card(card.id)!!.messages.isEmpty())
        model.removeChat(card.id, second.id)
        assertEquals(listOf(second.copy(draft = "second draft")), model.card(card.id)!!.chats)
        assertNull(model.addChat("missing"))
    }

    @Test
    fun `the root file has its own chat tabs, drafts and folding, apart from the cards`() {
        val model = model()
        val card = model.open(null, CardKind.SELECTION, CardAnchor(0, 1, 0), "a", 0, "x")
        val changes = mutableListOf<CanvasChange>()
        model.subscribe { changes += it }
        val first = model.session.root.activeChatId
        model.setDraft(null, "about the file")
        val second = model.addChat(null)!!
        assertEquals("Chat 2", second.title)
        val reply = listOf(SavedMessage(ChatRole.ASSISTANT, "background reply"))
        model.setMessages(null, reply, first)
        assertTrue(model.session.root.messages.isEmpty())
        model.selectChat(null, first)
        assertEquals(reply, model.session.root.messages)
        assertEquals("about the file", model.session.root.draft)
        assertEquals(true, model.toggleFolded(null, CardSection.CHAT))
        model.removeChat(null, first)
        model.removeChat(null, second.id)
        assertEquals(listOf(second), model.session.root.chats)
        assertEquals(setOf(CardSection.CHAT), model.session.root.folded)

        assertEquals(listOf(CanvasChat()), model.card(card.id)!!.chats)
        assertTrue(changes.all { it is CanvasChange.RootChanged })
        assertEquals(model.session.root, (changes.last() as CanvasChange.RootChanged).root)
        val count = changes.size
        model.selectChat(null, "missing")
        assertEquals("An unchanged root notifies nobody", count, changes.size)
    }

    @Test
    fun `tool calls are not saved and saved messages become chat messages`() {
        val saved = savedMessages(listOf(ChatMessage(ChatRole.NOTICE, "Welcome"), ChatMessage(ChatRole.USER, "Q"),
            ChatMessage(ChatRole.TOOL, "read_file(path=a)"), ChatMessage(ChatRole.ASSISTANT, "A")))
        assertEquals(listOf(SavedMessage(ChatRole.NOTICE, "Welcome"), SavedMessage(ChatRole.USER, "Q"), SavedMessage(ChatRole.ASSISTANT, "A")), saved)
        assertEquals(ChatMessage(ChatRole.USER, "Q"), saved.toChatMessages()[1])
    }
}
