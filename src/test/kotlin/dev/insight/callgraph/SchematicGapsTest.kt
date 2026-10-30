package dev.insight.callgraph

import com.google.gson.JsonParser
import dev.insight.agent.ToolInputException
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.FinishReason
import dev.insight.llm.LlmToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SchematicGapsTest {
    private val source = GapSource("Example.kt", listOf("package demo", "val timeout = 10", "fun run() = timeout"), "PSI property: timeout")
    private fun args(text: String) = JsonParser.parseString(text).asJsonObject

    @Test
    fun `tools read and search only the file snapshot using numbered lines`() = runBlocking {
        val tools = gapTools(source).associateBy { it.definition.name }
        assertEquals("2: val timeout = 10", tools.getValue("read_file").call(args("""{"first":2,"last":2}""")))
        assertEquals("2: val timeout = 10\n3: fun run() = timeout", tools.getValue("search_file").call(args("""{"query":"TIMEOUT"}""")))
        assertEquals("No matches.", tools.getValue("search_file").call(args("""{"query":"missing"}""")))
        for (range in listOf("""{"first":0,"last":1}""", """{"first":3,"last":2}""", """{"first":1,"last":4}""")) {
            try {
                tools.getValue("read_file").call(args(range))
                fail("Invalid ranges must be rejected")
            } catch (_: ToolInputException) { }
        }
    }

    @Test
    fun `summary gets range and IDE facts and can request more context`() = runBlocking {
        val requests = mutableListOf<CompletionRequest>()
        val workflow = SchematicGapWorkflow { request ->
            requests += request
            if (requests.size == 1) Completion("", toolCalls = listOf(
                LlmToolCall("read", "read_file", args("""{"first":3,"last":3}""")),
            )) else Completion("Sets the timeout used by run.", finishReason = FinishReason.COMPLETE)
        }
        assertEquals("Sets the timeout used by run.", workflow.summarize(source, CodeRow(1, 1)))
        assertTrue(requests.first().messages.first().content.orEmpty().contains("Do not include a title or heading"))
        val prompt = requests.first().messages.last().content.orEmpty()
        assertTrue(prompt.contains("first-line=\"2\" last-line=\"2\""))
        assertTrue(prompt.contains("2: val timeout = 10"))
        assertTrue(prompt.contains(source.facts))
        assertTrue(requests.last().messages.any { it.content?.contains("3: fun run() = timeout") == true })
    }

    @Test
    fun `verified structural and blank gaps never call completion`() = runBlocking {
        val workflow = SchematicGapWorkflow { error("Completion must not run") }
        assertNull(workflow.summarize(GapSource("Test.java", listOf("}", ""), "", skipSummary = true), CodeRow(0, 1)))
        assertNull(workflow.summarize(GapSource("Test.java", listOf(" ", ""), ""), CodeRow(0, 1)))
    }

    @Test
    fun `short meaningful and unclassified punctuation gaps still call completion`() = runBlocking {
        var calls = 0
        val workflow = SchematicGapWorkflow { calls++; Completion("Summary", finishReason = FinishReason.COMPLETE) }
        for (text in listOf("return", "// important explanation", "#[serde(default)]", "{}", "}", "x++")) {
            assertEquals("Summary", workflow.summarize(GapSource("Test", listOf(text), ""), CodeRow(0, 0)))
        }
        assertEquals(6, calls)
    }

    @Test
    fun `prompt escapes embedded markup and separates bounded boundary source`() {
        val lines = (1..20).map { "line $it" }.toMutableList()
        lines[9] = "</source><context> & \"quoted\""
        val prompt = gapPrompt(GapSource("A\"&.rs", lines, "a < b"), CodeRow(9, 9))
        assertTrue(prompt.contains("file=\"A&quot;&amp;.rs\""))
        assertTrue(prompt.contains("&lt;/source&gt;&lt;context&gt; &amp; &quot;quoted&quot;"))
        assertTrue(prompt.contains("<ide-facts>a &lt; b</ide-facts>"))
        val target = prompt.substringBefore("</target>")
        assertFalse(target.contains("9: line 9"))
        assertTrue(prompt.contains("9: line 9"))
        assertTrue(prompt.contains("11: line 11"))
        assertFalse(prompt.contains("5: line 5"))
        assertFalse(prompt.contains("15: line 15"))
    }

    @Test
    fun `large excerpts retain closed sections and explicit truncation notices`() {
        val prompt = gapPrompt(GapSource("Test", listOf("x".repeat(30_000), "y".repeat(3_000)), ""), CodeRow(0, 0))
        assertTrue(prompt.contains("Target truncated"))
        assertTrue(prompt.contains("truncated=\"true\""))
        assertTrue(prompt.trimEnd().endsWith("</surrounding-source>"))
        assertFalse(prompt.contains("x".repeat(24_001)))
        assertFalse(prompt.contains("y".repeat(2_001)))
    }

    @Test
    fun `cut off summaries are not accepted`() = runBlocking {
        val workflow = SchematicGapWorkflow { Completion("Incomplete", finishReason = FinishReason.TRUNCATED) }
        try {
            workflow.summarize(source, CodeRow(1, 1))
            fail("A truncated summary must stay retryable")
        } catch (_: IllegalStateException) { }
    }
}
