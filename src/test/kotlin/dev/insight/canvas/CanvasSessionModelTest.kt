package dev.insight.canvas

import dev.insight.agent.ChatMessage
import dev.insight.agent.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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
        val first = model.open(CANVAS_ROOT, CardKind.SYMBOL, CardAnchor(5, 9, 2), "src/Store.kt", 3, "fun save() {}", save)
        val again = model.open(CANVAS_ROOT, CardKind.SYMBOL, CardAnchor(5, 9, 2), "src/Store.kt", 3, "fun save() {}", save)
        val other = model.open(CANVAS_ROOT, CardKind.SELECTION, CardAnchor(5, 9, 2), "src/Main.kt", 2, "save()")
        assertSame(first, again)
        assertEquals(listOf(CANVAS_ROOT, "c0", "c1"), model.cards.map { it.id })
        assertEquals(listOf(CanvasChange.CardOpened(first), CanvasChange.CardOpened(other)), changes)
        assertEquals(1L, model.session.created)
        assertTrue(model.session.updated >= 100)
    }

    @Test
    fun `opening attribution belongs to each card and survives revisiting in either direction`() {
        val model = model()
        val manual = model.open(CANVAS_ROOT, CardKind.SYMBOL, CardAnchor(5, 9, 2), "src/Store.kt", 3, "fun save() {}", save)
        val revisitedByLlm = model.open(CANVAS_ROOT, CardKind.SYMBOL, manual.anchor, manual.filePath, manual.firstLine,
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
        assertEquals(4, model.cards.size)
    }

    @Test
    fun `summary chat restore and expand preserve opening attribution`() {
        val model = model()
        val card = model.open(CANVAS_ROOT, CardKind.SYMBOL, CardAnchor(5, 9, 2), "src/Store.kt", 3, "fun save() {}", save,
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
        val a = model.open(CANVAS_ROOT, CardKind.SYMBOL, CardAnchor(0, 4, 0), "src/Store.kt", 3, "a", save)
        val b = model.open(a.id, CardKind.SELECTION, CardAnchor(0, 1, 3), "src/Store.kt", 3, "a")
        val c = model.open(b.id, CardKind.SYMBOL, CardAnchor(0, 1, 3), "src/Store.kt", 3, "a", save.copy(name = "load"))
        val d = model.open(CANVAS_ROOT, CardKind.SELECTION, CardAnchor(1, 2, 0), "src/Main.kt", 0, "b")
        assertEquals(listOf(model.session.root, a, b, c), model.chain(c.id))
        assertEquals(listOf(a, d), model.children(CANVAS_ROOT))
        assertEquals(setOf(b.id, c.id), model.remove(b.id))
        assertEquals(listOf(CANVAS_ROOT, a.id, d.id), model.cards.map { it.id })
        assertTrue("The root card stays", model.remove(CANVAS_ROOT).isEmpty())
        assertTrue(model.remove("missing").isEmpty())
    }

    @Test
    fun `card state changes notify only when something changed`() {
        val model = model()
        val card = model.open(CANVAS_ROOT, CardKind.SELECTION, CardAnchor(0, 1, 0), "src/Main.kt", 0, "x")
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
        val card = CanvasCard("c", CANVAS_ROOT, CardKind.SELECTION, CardAnchor(0, 1, 0), null, "a.kt", 9, "x\ny\nz")
        assertEquals(11, card.lastLine)
        assertEquals("a.kt:10-12", card.location)
    }

    @Test
    fun `chat tabs keep independent histories and drafts and choose an adjacent tab on close`() {
        val model = model()
        val card = model.open(CANVAS_ROOT, CardKind.SELECTION, CardAnchor(0, 1, 0), "a", 0, "x")
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
    fun `the root card has its own chat tabs, drafts and folding, apart from the cards`() {
        val model = model()
        val card = model.open(CANVAS_ROOT, CardKind.SELECTION, CardAnchor(0, 1, 0), "a", 0, "x")
        val changes = mutableListOf<CanvasChange>()
        model.subscribe { changes += it }
        val first = model.session.root.activeChatId
        model.setDraft(CANVAS_ROOT, "about the file")
        val second = model.addChat(CANVAS_ROOT)!!
        assertEquals("Chat 2", second.title)
        val reply = listOf(SavedMessage(ChatRole.ASSISTANT, "background reply"))
        model.setMessages(CANVAS_ROOT, reply, first)
        assertTrue(model.session.root.messages.isEmpty())
        model.selectChat(CANVAS_ROOT, first)
        assertEquals(reply, model.session.root.messages)
        assertEquals("about the file", model.session.root.draft)
        assertEquals(true, model.toggleFolded(CANVAS_ROOT, CardSection.CHAT))
        model.removeChat(CANVAS_ROOT, first)
        model.removeChat(CANVAS_ROOT, second.id)
        assertEquals(listOf(second), model.session.root.chats)
        assertEquals(setOf(CardSection.CHAT), model.session.root.folded)

        assertEquals(listOf(CanvasChat()), model.card(card.id)!!.chats)
        assertTrue(changes.all { it is CanvasChange.CardChanged && it.card.id == CANVAS_ROOT })
        assertEquals(model.session.root, (changes.last() as CanvasChange.CardChanged).card)
        val count = changes.size
        model.selectChat(CANVAS_ROOT, "missing")
        assertEquals("An unchanged root notifies nobody", count, changes.size)
    }

    @Test
    fun `tool calls are not saved and saved messages become chat messages`() {
        val saved = savedMessages(listOf(ChatMessage(ChatRole.NOTICE, "Welcome"), ChatMessage(ChatRole.USER, "Q"),
            ChatMessage(ChatRole.TOOL, "read_file(path=a)"), ChatMessage(ChatRole.ASSISTANT, "A")))
        assertEquals(listOf(SavedMessage(ChatRole.NOTICE, "Welcome"), SavedMessage(ChatRole.USER, "Q"), SavedMessage(ChatRole.ASSISTANT, "A")), saved)
        assertEquals(ChatMessage(ChatRole.USER, "Q"), saved.toChatMessages()[1])
    }

    @Test
    fun `every session starts with its one root card, which heads every chain and cannot be closed`() {
        val model = model()
        val root = model.session.root
        assertEquals(CardKind.ROOT, root.kind)
        assertEquals(CANVAS_ROOT, root.id)
        assertEquals("Main.kt", root.title)
        assertEquals("src/Main.kt", root.location)
        assertEquals("file", model.session.rootKind)
        assertEquals("directory", CanvasSession.new("src", directory = true).rootKind)
        val card = model.open(CANVAS_ROOT, CardKind.SELECTION, CardAnchor(0, 1, 0), "src/Main.kt", 0, "x")
        assertEquals(listOf(root, card), model.chain(card.id))
        assertEquals(listOf(root), model.chain(CANVAS_ROOT))
        assertTrue(model.remove(CANVAS_ROOT).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            model.open(CANVAS_ROOT, CardKind.ROOT, CardAnchor(0, 0, 0), "src/Main.kt", 0, "")
        }
        assertThrows(IllegalArgumentException::class.java) { CanvasSession("s", "src/Main.kt", "Main", 0, 0, listOf(card)) }
        assertThrows(IllegalArgumentException::class.java) { CanvasSession("s", "src/Main.kt", "Main", 0, 0, emptyList()) }
    }

    @Test
    fun `resizing sets each dimension within bounds and resetting makes the card automatic`() {
        val model = model()
        val card = model.open(CANVAS_ROOT, CardKind.SELECTION, CardAnchor(0, 1, 0), "src/Main.kt", 0, "x")
        val changes = mutableListOf<CanvasChange>()
        model.subscribe { changes += it }
        model.resize(card.id, CardSize(width = 500))
        assertEquals(CardSize(width = 500), model.card(card.id)!!.size)
        model.resize(card.id, CardSize(width = 500))
        assertEquals(1, changes.size)
        model.resize(card.id, CardSize(10, 100_000))
        assertEquals(CardSize(CARD_MIN_WIDTH, CARD_MAX_HEIGHT), model.card(card.id)!!.size)
        model.resize(CANVAS_ROOT, CardSize(height = 400))
        assertEquals(CardSize(height = 400), model.session.root.size)
        model.resize(card.id, CardSize())
        assertNull(model.card(card.id)!!.size)
        model.resize(CANVAS_ROOT, null)
        assertNull(model.session.root.size)
        assertEquals(listOf(card.id, card.id, CANVAS_ROOT, card.id, CANVAS_ROOT),
            changes.map { (it as CanvasChange.CardChanged).card.id })
    }

    @Test
    fun `appendCards adds non-duplicate cards in one CardsOpened notification and updates directoryCursor`() {
        val model = model()
        val changes = mutableListOf<CanvasChange>()
        model.subscribe { changes += it }
        val c1 = CanvasCard("c1", CANVAS_ROOT, CardKind.SYMBOL, CardAnchor(0, 1, 0),
            SymbolRef("A.kt", "A.kt", "FILE"), "A.kt", 0, "class A", wholeFile = true, openedBy = CardOpenedBy.DIRECTORY)
        val c2 = CanvasCard("c2", "c1", CardKind.SYMBOL, CardAnchor(0, 1, 0),
            SymbolRef("B.kt", "B.kt", "FILE"), "B.kt", 0, "class B", wholeFile = true, openedBy = CardOpenedBy.DIRECTORY)
        model.initializeDirectory(listOf(c1), directoryCursor = "A.kt")
        assertEquals("A.kt", model.session.directoryCursor)
        assertTrue(changes.isEmpty())

        model.appendCards(listOf(c1, c2), directoryCursor = "B.kt")
        assertEquals(listOf(CANVAS_ROOT, "c1", "c2"), model.cards.map { it.id })
        assertEquals("B.kt", model.session.directoryCursor)
        assertEquals(listOf(CanvasChange.CardsOpened(listOf(c2))), changes)

        model.appendCards(emptyList(), directoryCursor = null)
        assertNull(model.session.directoryCursor)
        assertEquals(1, changes.size)
    }

    @Test
    fun `appendCards updates pendingFiles with and without opening cards`() {
        val model = model()
        val p1 = CanvasPendingFile("A.kt")
        val p2 = CanvasPendingFile("B.kt", "A.kt", 12)
        val withPending = CanvasSessionModel(model.session.copy(pendingFiles = listOf(p1, p2)))
        val changes = mutableListOf<CanvasChange>()
        withPending.subscribe { changes += it }

        val c1 = CanvasCard("c1", CANVAS_ROOT, CardKind.SYMBOL, CardAnchor(0, 1, 0),
            SymbolRef("A.kt", "A.kt", "FILE"), "A.kt", 0, "class A", wholeFile = true)
        withPending.appendCards(listOf(c1), pendingFiles = listOf(p2))
        assertEquals(listOf(p2), withPending.session.pendingFiles)
        assertEquals(listOf(CanvasChange.CardsOpened(listOf(c1))), changes)

        withPending.appendCards(emptyList(), pendingFiles = emptyList())
        assertTrue(withPending.session.pendingFiles.isEmpty())
        assertEquals("Updating pendingFiles alone emits no CardsOpened", 1, changes.size)
    }
}
