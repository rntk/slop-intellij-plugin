package dev.insight.review

import com.google.gson.JsonObject
import dev.insight.agent.ChatTool
import dev.insight.agent.ToolRegistry
import dev.insight.graph.RelationKind
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.FinishReason
import dev.insight.llm.LlmProtocolException
import dev.insight.llm.LlmRole
import dev.insight.llm.LlmTool
import dev.insight.llm.LlmToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TreeConnectionExplainerTest {
    @Test fun `request includes directed resolved occurrences and numbered snippets`() = runBlocking {
        var request: CompletionRequest? = null
        val site = TreeConnectionSite(12, 4, listOf(
            TreeConnectionOccurrence(RelationKind.CALL, 12, 4,
                CodeSnippet(11, 12, listOf("fun run() {", "  service.execute()", "}")),
                CodeSnippet(4, 4, listOf("fun execute() = work()"))),
            TreeConnectionOccurrence(RelationKind.IMPORT, 2, null),
        ), totalOccurrences = 7)
        val explainer = TreeConnectionExplainer({ request = it; Completion("  Calls `Service.execute` at `src/A.kt:12`.  ") }, emptyList())

        assertEquals("Calls `Service.execute` at `src/A.kt:12`.", explainer.explain("src/A.kt", "src/Service.kt", site))
        val messages = request!!.messages
        assertEquals(listOf(LlmRole.SYSTEM, LlmRole.USER), messages.map { it.role })
        val evidence = messages.last().content
        assertTrue(evidence.contains("Source file: src/A.kt"))
        assertTrue(evidence.contains("Target file: src/Service.kt"))
        assertTrue(evidence.contains("CALL: src/A.kt:12 → src/Service.kt:4"))
        assertTrue(evidence.contains("IMPORT: src/A.kt:2 → src/Service.kt"))
        assertTrue(evidence.contains("Total resolved occurrences: 7"))
        assertTrue(evidence.contains("Supplied occurrences: 2"))
        assertTrue(evidence.contains("11| fun run() {\n12|   service.execute()\n13| }"))
        assertTrue(evidence.contains("4| fun execute() = work()"))
    }

    @Test fun `model can read more source and continue with tool result`() = runBlocking {
        val requests = mutableListOf<CompletionRequest>()
        val arguments = JsonObject().apply { addProperty("path", "src/A.kt") }
        var seenPath: String? = null
        val readFile = object : ChatTool {
            override val definition = LlmTool("read_file", "Read source", JsonObject())
            override suspend fun call(arguments: JsonObject): String {
                seenPath = arguments["path"].asString
                return "12| service.execute()"
            }
        }
        val searchText = object : ChatTool {
            override val definition = LlmTool("search_text", "Search source", JsonObject())
            override suspend fun call(arguments: JsonObject) = "found"
        }
        val script = ArrayDeque(listOf(
            Completion("", toolCalls = listOf(LlmToolCall("read", "read_file", arguments)), finishReason = FinishReason.TOOL_CALLS),
            Completion("The call at `src/A.kt:12` invokes `execute`."),
        ))
        val registry = ToolRegistry().register("searching source", searchText)
        val explainer = TreeConnectionExplainer({ requests += it; script.removeFirst() }, listOf(readFile), registry)

        assertEquals("The call at `src/A.kt:12` invokes `execute`.", explainer.explain("src/A.kt", "src/B.kt", TreeConnectionSite(12, null)))
        assertEquals("src/A.kt", seenPath)
        assertEquals(setOf("read_file", "search_tools"), requests.first().tools.map { it.name }.toSet())
        assertTrue(requests.last().messages.any { it.role == LlmRole.TOOL && it.content == "12| service.execute()" })
    }

    @Test fun `blank model response fails rather than returning an explanation`() = runBlocking {
        val explainer = TreeConnectionExplainer({ Completion("  \n ") }, emptyList())
        try {
            explainer.explain("a", "b", TreeConnectionSite(null, null))
            fail("Expected a protocol error")
        } catch (expected: LlmProtocolException) {
            assertTrue(expected.message!!.contains("empty", ignoreCase = true))
        }
    }
}
