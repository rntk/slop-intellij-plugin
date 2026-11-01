package dev.insight.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasChainContextTest {
    private val run = CanvasCard("a", null, CardKind.SYMBOL, CardAnchor(10, 13, 4), SymbolRef("src/App.kt", "run", "FUNCTION", "App"),
        "src/App.kt", 9, "fun run() {\n    store.save()\n}", summary = "Runs the app.")
    private val selection = CanvasCard("b", "a", CardKind.SELECTION, CardAnchor(16, 28, 10), null, "src/App.kt", 10, "    store.save()")
    private val save = CanvasCard("c", "b", CardKind.SYMBOL, CardAnchor(10, 14, 10), SymbolRef("src/Store.kt", "save", "FUNCTION", "Store"),
        "src/Store.kt", 2, "fun save() {}")
    private val unrelated = CanvasCard("d", null, CardKind.SYMBOL, CardAnchor(0, 4, 0), SymbolRef("src/Main.kt", "main", "FUNCTION"),
        "src/Main.kt", 0, "fun main() {}")
    private val root = CanvasSource("fun main() {}\n\n\n\n    app.run()", emptyList(), 0, "src/Main.kt", null)

    @Test
    fun `the chain names each card, where it was opened, its summary and numbered source, and leaves other chains out`() {
        val session = CanvasSession("s", "src/Main.kt", "Main", 0, 0, listOf(run, selection, save, unrelated))
        assertEquals("""
            Root file: src/Main.kt (card "root"; its source is not repeated here)
            Chain from the root file down to the current card; each card was opened from the source of the one above it.

            Step 1 [card a]: function run in App at src/App.kt:10-12
            Opened from: the root file, line 5: `app.run()`
            Summary (model-written): Runs the app.
            Source (lines 10-12):
            10: fun run() {
            11:     store.save()
            12: }

            Current card [card b]: selection at src/App.kt:11
            Opened from: card a, line 11: `store.save()`
            Selected text (lines 11-11):
            11:     store.save()

            Cards opened from the current card: save [card c] from line 11

            1 other card is open on other chains of the canvas and left out here; canvas_overview lists them.
        """.trimIndent(), canvasChainContext(session, "b", root))
        assertFalse(canvasChainContext(session, "b", root).contains("fun main"))
    }

    @Test
    fun `the root file's chat gets the file's numbered source and the cards opened from it`() {
        val session = CanvasSession("s", "src/Main.kt", "Main", 0, 0, listOf(run, selection, unrelated))
        assertEquals("""
            Root file: src/Main.kt (card "root")
            This chat belongs to the root file itself, so "current" names the root file; there is no chain above it.

            Current card [root]: file Main.kt at src/Main.kt
            File source (lines 1-5):
            1: fun main() {}
            2:
            3:
            4:
            5:     app.run()

            Cards opened from the root file: run [card a] from line 5; main [card d] from line 1

            1 other card is open further down the canvas and left out here; canvas_overview lists them.
        """.trimIndent(), canvasChainContext(session, null, root).lines().joinToString("\n") { it.trimEnd() })
        assertTrue(canvasChainContext(session, null).contains("[File source: not shown yet; use read_file path src/Main.kt]"))
        val long = CanvasSource((1..500).joinToString("\n") { "line $it" }, emptyList(), 0, "src/Main.kt", null)
        assertTrue(canvasRootContext("src/Main.kt", long, CanvasContextBudget(current = 300))
            .contains("[truncated; use read_file path src/Main.kt from line"))
        assertEquals("Ask about the file src/Main.kt. I read the project to answer and can open cards on the canvas to show " +
            "you code.", canvasRootWelcome("src/Main.kt"))
    }

    @Test
    fun `the current card gets the larger budget and cut text says where to read on`() {
        val long = (1..200).joinToString("\n") { "line $it of the body" }
        val ancestor = run.copy(text = long)
        val current = selection.copy(text = long)
        val text = canvasChainContext("src/Main.kt", listOf(ancestor, current), CanvasContextBudget(current = 1_000, ancestor = 200, total = 1_150))
        val (first, second) = text.split("Current card")
        assertTrue(first, first.contains("[truncated; use read_file path src/App.kt from line"))
        assertTrue(second.contains("[truncated; use read_file path src/App.kt from line"))
        assertTrue(second.length > first.length)
        assertTrue(second.contains("11: line 1 of the body"))

        val starved = canvasChainContext("src/Main.kt", listOf(ancestor, current), CanvasContextBudget(current = 1_000, ancestor = 200, total = 1_000))
        assertTrue(starved, starved.contains("[Source omitted; use read_file path src/App.kt from line 10]"))
        assertTrue(canvasChainContext("src/Main.kt", listOf(selection.copy(textTruncated = true)))
            .contains("[truncated; use read_file path src/App.kt from line 12]"))
    }

    @Test
    fun `welcome names the card and the prompt explains the canvas and its tools`() {
        assertEquals("Ask about run (function at src/App.kt:10-12). I read the project to answer and can open cards on the canvas " +
            "to show you code.", canvasWelcome(run))
        assertTrue(canvasWelcome(selection).startsWith("Ask about the selected code at src/App.kt:11."))
        for (word in listOf("chain", "root file", "open_symbol", "open_declaration", "open_file", "canvas_overview", "list_symbols"))
            assertTrue(word, canvasChatPrompt.contains(word))
    }
}
