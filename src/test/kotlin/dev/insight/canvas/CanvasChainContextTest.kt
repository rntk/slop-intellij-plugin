package dev.insight.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasChainContextTest {
    private val run = CanvasCard("a", null, CardKind.SYMBOL, CardAnchor(10, 13, 4), SymbolRef("src/App.kt", "run", "FUNCTION", "App"),
        "src/App.kt", 9, "fun run() {\n    store.save()\n}", summary = "Runs the app.")
    private val selection = CanvasCard("b", "a", CardKind.SELECTION, CardAnchor(16, 28, 10), null, "src/App.kt", 10, "    store.save()")

    @Test
    fun `the chain lists files, steps, summaries and numbered source`() {
        val session = CanvasSession("s", "src/Main.kt", "Main", 0, 0, listOf(run, selection))
        assertEquals("""
            Canvas chain, from the root file down to the current card. Each card was opened from the source of the one above it.
            Files: src/Main.kt, src/App.kt
            Root file: src/Main.kt (open on the canvas; read it with read_file)

            Card 1: function run in App at src/App.kt:10-12
            Opened from: src/Main.kt line 5
            Summary: Runs the app.
            Source (lines 10-12):
            10: fun run() {
            11:     store.save()
            12: }

            Current card: selection at src/App.kt:11
            Opened from: src/App.kt line 11
            Selected text (lines 11-11):
            11:     store.save()
        """.trimIndent(), canvasChainContext(session, "b"))
    }

    @Test
    fun `the current card gets the larger budget and cut text says where to read on`() {
        val long = (1..200).joinToString("\n") { "line $it of the body" }
        val ancestor = run.copy(text = long)
        val current = selection.copy(text = long)
        val text = canvasChainContext("src/Main.kt", listOf(ancestor, current), CanvasContextBudget(current = 1_000, ancestor = 200, total = 1_150))
        val (first, second) = text.split("Current card:")
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
    fun `welcome names the card`() {
        assertEquals("Ask about run (function at src/App.kt:10-12). I can read and search the project to answer.", canvasWelcome(run))
        assertTrue(canvasWelcome(selection).startsWith("Ask about the selected code at src/App.kt:11."))
        assertFalse(canvasChatPrompt.isBlank())
    }
}
