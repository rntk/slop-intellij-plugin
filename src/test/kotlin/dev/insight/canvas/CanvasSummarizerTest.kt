package dev.insight.canvas

import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.FinishReason
import dev.insight.llm.LlmRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class CanvasSummarizerTest {
    private val input = CanvasSummaryInput(CardKind.SELECTION, null, "src/App.kt", 9, "store.save()\nlog()", language = "Kotlin",
        chain = "1. src/Main.kt, the file the developer opened")

    @Test
    fun `the prompt names the place and numbers the code`() {
        val (system, user) = canvasSummaryMessages(input)
        assertEquals(LlmRole.SYSTEM, system.role)
        assertTrue(system.content.contains("selected code"))
        assertTrue(user.content.contains("Location: src/App.kt:10-11"))
        assertTrue(user.content.contains("How the developer reached this code (one path to it; it may have other callers):\n1. src/Main.kt"))
        assertTrue(user.content.contains("10: store.save()\n11: log()"))
        val long = canvasSummaryMessages(input.copy(text = "x\n".repeat(CANVAS_SUMMARY_TEXT_LIMIT)))[1].content
        assertTrue(long.contains("[The code continues beyond line"))
    }

    @Test
    fun `the summary is trimmed and the card input is derived from its chain`() = runBlocking {
        val requests = mutableListOf<CompletionRequest>()
        val progress = mutableListOf<String>()
        val summary = CanvasSummarizer({ requests += it; Completion("  Saves the store.\n", finishReason = FinishReason.COMPLETE) })
            .summarize(input, progress::add)
        assertEquals("Saves the store.", summary)
        assertEquals(1, requests.size)
        assertEquals(listOf("Summarizing selection…"), progress)

        val card = CanvasCard("a", null, CardKind.SYMBOL, CardAnchor(0, 1, 0), SymbolRef("src/App.kt", "run", "FUNCTION", "App"), "src/App.kt", 9, "fun run() {}")
        val derived = CanvasSummaryInput.of(card, "src/Main.kt", listOf(card))
        assertEquals("1. src/Main.kt, the file the developer opened\n2. this function, opened from a reference at src/Main.kt:1", derived.chain)
        assertEquals("FUNCTION", derived.symbolKind)
    }

    @Test
    fun `the reading path quotes each reference and is left out for a card opened at its own declaration`() {
        val run = CanvasCard("a", null, CardKind.SYMBOL, CardAnchor(0, 3, 4), SymbolRef("src/App.kt", "run", "FUNCTION", "App"),
            "src/App.kt", 9, "fun run() {\n    store.save()\n}")
        val save = CanvasCard("b", "a", CardKind.SYMBOL, CardAnchor(16, 20, 10), SymbolRef("src/Store.kt", "save", "FUNCTION", "Store"),
            "src/Store.kt", 2, "fun save() {\n    persist()\n}")
        val selection = CanvasCard("c", "b", CardKind.SELECTION, CardAnchor(17, 26, 3), null, "src/Store.kt", 3, "    persist()")
        assertEquals("""
            1. src/Main.kt, the file the developer opened
            2. function `run` in App (src/App.kt:10-12), opened from a reference at src/Main.kt:5
            3. function `save` in Store (src/Store.kt:3-5), opened from a reference at src/App.kt:11: `store.save()`
            4. this selection, selected in the code of step 3
        """.trimIndent(), canvasReadingPath("src/Main.kt", listOf(run, save, selection)))

        val declared = run.copy(filePath = "src/Main.kt", anchor = CardAnchor(0, 3, 9))
        assertEquals("", canvasReadingPath("src/Main.kt", listOf(declared)))
        assertEquals("", CanvasSummaryInput.of(declared, "src/Main.kt", listOf(declared)).chain)
        assertFalse(canvasSummaryMessages(CanvasSummaryInput.of(declared, "src/Main.kt", listOf(declared)))[1].content.contains("reached"))
    }

    @Test
    fun `summary reads source through injected tools`() = runBlocking {
        var reads = 0
        val tool = object : dev.insight.agent.ChatTool {
            override val definition = dev.insight.llm.LlmTool("read_file", "Read source", com.google.gson.JsonObject())
            override suspend fun call(arguments: com.google.gson.JsonObject): String {
                reads++
                return "fun save() = persist()"
            }
        }
        var requests = 0
        val summary = CanvasSummarizer({ request ->
            assertEquals(listOf("read_file"), request.tools.map { it.name })
            if (requests++ == 0) Completion("", toolCalls = listOf(
                dev.insight.llm.LlmToolCall("read-1", "read_file", com.google.gson.JsonObject())))
            else {
                assertTrue(request.messages.any { it.content == "fun save() = persist()" })
                Completion("Persists the store.", finishReason = FinishReason.COMPLETE)
            }
        }, tools = listOf(tool)).summarize(input)
        assertEquals("Persists the store.", summary)
        assertEquals(1, reads)
        assertEquals(2, requests)
    }

    @Test
    fun `failures are explained and cancellation propagates`() = runBlocking {
        suspend fun failure(complete: suspend (CompletionRequest) -> Completion): String = try {
            CanvasSummarizer(complete).summarize(input)
            fail("Expected a failure"); ""
        } catch (e: CanvasSummaryException) { e.message!! }
        assertEquals("The summary failed: offline", failure { throw IOException("offline") })
        assertEquals("The summary was cut off; try again.", failure { Completion("Half", finishReason = FinishReason.TRUNCATED) })
        assertEquals("The model returned an empty summary.", failure { Completion(" ") })
        try {
            CanvasSummarizer({ throw CancellationException("stop") }).summarize(input)
            fail("Expected cancellation")
        } catch (_: CancellationException) {
        }
    }
}
