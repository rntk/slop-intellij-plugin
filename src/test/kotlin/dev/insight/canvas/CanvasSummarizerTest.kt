package dev.insight.canvas

import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.FinishReason
import dev.insight.llm.LlmRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class CanvasSummarizerTest {
    private val input = CanvasSummaryInput(CardKind.SELECTION, null, "src/App.kt", 9, "store.save()\nlog()", language = "Kotlin",
        chain = "Main.kt → run → selection")

    @Test
    fun `the prompt names the place and numbers the code`() {
        val (system, user) = canvasSummaryMessages(input)
        assertEquals(LlmRole.SYSTEM, system.role)
        assertTrue(system.content.contains("selected code"))
        assertTrue(user.content.contains("Location: src/App.kt:10-11"))
        assertTrue(user.content.contains("Reached from: Main.kt → run → selection"))
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
        assertEquals("Main.kt → run", derived.chain)
        assertEquals("FUNCTION", derived.symbolKind)
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
