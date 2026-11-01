package dev.insight.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasSourceChainTest {
    private val entry = CanvasCard("entry", null, CardKind.SYMBOL, CardAnchor(10, 15, 4),
        SymbolRef("App.kt", "run", "FUNCTION", line = 9), "App.kt", 9, "fun run() {\n    save()\n}")
    private val selection = CanvasCard("selection", entry.id, CardKind.SELECTION, CardAnchor(12, 18, 10),
        null, "App.kt", 10, "    save()")
    private val save = CanvasCard("save", selection.id, CardKind.SYMBOL, CardAnchor(4, 8, 10),
        SymbolRef("Store.kt", "save", "FUNCTION", line = 20), "Store.kt", 20, "fun save() {\n    persist()\n}")
    private val unrelated = entry.copy(id = "other", filePath = "Other.kt")
    private val session = CanvasSession.new("Main.kt").copy(cards = listOf(entry, selection, save, unrelated))

    @Test fun `only the selected chain opens root first at each outgoing anchor and the leaf source range`() {
        val chain = canvasSourceChain(session, save.id)
        assertEquals(listOf("Main.kt", "App.kt", "App.kt", "Store.kt"), chain.map { it.path })
        assertEquals(listOf("Main.kt", "run", "Selection", "save"), chain.map { it.title })
        assertEquals(listOf(4, 10, 10, 20), chain.map { it.line })
        assertEquals(listOf(4, 10, 10, 22), chain.map { it.endLine })
        assertEquals(listOf(4, 9), canvasSourceChain(session, entry.id).map { it.line })
        assertTrue(canvasSourceChain(session, "missing").isEmpty())
    }

    @Test fun `expanded ancestors still point to child anchors and an expanded leaf keeps its symbol position`() {
        val model = CanvasSessionModel(session)
        model.expandToFile(entry.id, (0..30).joinToString("\n") { "line $it" }, 100)
        assertEquals(listOf(4, 10, 10, 20), canvasSourceChain(model.session, save.id).map { it.line })
        val expanded = canvasSourceChain(model.session, entry.id).last()
        assertEquals(9, expanded.line)
        assertEquals(9, expanded.endLine)
    }

    @Test fun `recursive visits and unavailable external leaves keep their place in the chain`() {
        val recursive = entry.copy(id = "recursive", parentId = save.id, anchor = CardAnchor(1, 4, 21))
        val external = save.copy(id = "external", parentId = recursive.id, filePath = "", text = "", firstLine = 0)
        val extended = session.copy(cards = session.cards + recursive + external)
        assertEquals(listOf("Main.kt", "App.kt", "App.kt", "Store.kt", "App.kt", ""),
            canvasSourceChain(extended, external.id).map { it.path })
    }

    @Test fun `generated summaries belong to each visit even when cards show the same file`() {
        val summarized = session.copy(root = session.root.copy(summary = "Starts the app."), cards = listOf(
            entry.copy(summary = "Runs the flow."), selection, save.copy(summary = "Saves the result."),
            unrelated.copy(summary = "A different branch.")))
        assertEquals(listOf("Starts the app.", "Runs the flow.", null, "Saves the result."),
            canvasSourceChain(summarized, save.id).map { it.summary })
    }
}
