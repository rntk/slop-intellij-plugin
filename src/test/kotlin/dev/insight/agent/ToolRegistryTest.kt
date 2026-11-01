package dev.insight.agent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.FinishReason
import dev.insight.llm.LlmMessage
import dev.insight.llm.LlmTool
import dev.insight.llm.LlmToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ToolRegistryTest {
    private fun args(json: String) = JsonParser.parseString(json).asJsonObject

    private class NamedTool(name: String, description: String) : ChatTool {
        var calls = 0
        override val definition = LlmTool(name, description, JsonParser.parseString("""{"type":"object"}""").asJsonObject)
        override suspend fun call(arguments: JsonObject): String {
            calls++
            return "${definition.name} ran"
        }
    }

    private val readFile = NamedTool("read_file", "Read numbered lines of a project file.")
    private val searchText = NamedTool("search_text", "Search project files for text.")
    private val openCard = NamedTool("open_card", "Open a card on the canvas. Changes the canvas.")
    private val showCard = NamedTool("show_card", "Scroll to an open card and highlight it.")
    private val registry = ToolRegistry()
        .register("reading project files", readFile, searchText)
        .register("showing code on the canvas", openCard, showCard)

    private fun ToolSet.search(json: String) = runBlocking { get(SEARCH_TOOLS)!!.call(args(json)) }

    @Test fun `search ranks names above descriptions, matches word forms and skips excluded tools`() {
        assertEquals(listOf("open_card", "show_card"), registry.search("opening cards").map { it.definition.name })
        assertEquals(listOf("show_card"), registry.search("highlighted card", exclude = setOf("open_card")).map { it.definition.name })
        assertEquals(setOf("show_card", "read_file"), registry.search("show_card, read_file", limit = 2).map { it.definition.name }.toSet())
        assertEquals(4, registry.search("").size)
        assertTrue(registry.search("compile").isEmpty())
    }

    @Test fun `exact name queries never fill unused results with related tools`() {
        assertEquals(listOf(showCard), registry.search("show_card"))
        assertEquals(listOf(showCard), registry.search(" show_card, "))
        assertEquals(listOf(readFile, showCard), registry.search("show_card, read_file"))
        assertTrue(registry.search("show_card", exclude = setOf("show_card")).isEmpty())
        assertTrue(registry.search("the tools").isEmpty())
    }

    @Test fun `descriptive search filters weak matches from a shared topic`() {
        val related = ToolRegistry().register("navigation: opening and highlighting cards", openCard, showCard)
        assertEquals(listOf(showCard), related.search("highlight a card"))
        assertEquals(listOf(openCard, showCard), related.search("navigation"))
    }

    @Test fun `registered names must be unique`() {
        assertThrows(IllegalArgumentException::class.java) { ToolRegistry().register("a", readFile).register("b", readFile) }
        assertThrows(IllegalArgumentException::class.java) { registry.select("missing") }
    }

    @Test fun `search_tools loads only tools the turn does not have and lists the remaining topics`() {
        val tools = ToolSet(registry.select("read_file", "search_text"), registry)
        assertEquals(setOf("read_file", "search_text", SEARCH_TOOLS), tools.names)
        val description = tools[SEARCH_TOOLS]!!.definition.description
        assertTrue(description, "showing code on the canvas" in description && "reading project files" !in description)

        // read_file is active already, so a search naming it does not offer it again.
        val result = tools.search("""{"query":"read_file highlight"}""")
        assertEquals("Loaded 1 tool; call it directly:\n- show_card: Scroll to an open card and highlight it.", result)
        assertSame(showCard, tools["show_card"])
        assertTrue(tools.search("""{"query":"read file"}""").startsWith("No tool you don't already have matches 'read file'."))
        assertTrue(tools.search("{}").contains("open_card"))
        assertEquals("No more tools to load; you already have every tool.", tools.search("{}"))
    }

    @Test fun `a set offers no search when the registry holds nothing new, and initial tools shadow registered ones`() {
        assertEquals(setOf("read_file", "search_text", "open_card", "show_card"), ToolSet(registry.tools, registry).names)
        val local = NamedTool("read_file", "A local reader.")
        val tools = ToolSet(listOf(local), registry)
        tools.search("""{"query":"read"}""")
        assertSame(local, tools["read_file"])
        assertThrows(IllegalArgumentException::class.java) { ToolSet(listOf(readFile, readFile)) }
    }

    @Test fun `the runner advertises loaded tools from the next request and loads a registered tool called early`() = runBlocking {
        val requests = mutableListOf<CompletionRequest>()
        val script = ArrayDeque(listOf(
            Completion("", toolCalls = listOf(LlmToolCall("1", SEARCH_TOOLS, args("""{"query":"open a card"}"""))), finishReason = FinishReason.TOOL_CALLS),
            Completion("", toolCalls = listOf(LlmToolCall("2", "open_card", JsonObject()), LlmToolCall("3", "show_card", JsonObject())),
                finishReason = FinishReason.TOOL_CALLS),
            Completion("Done."),
        ))
        val events = mutableListOf<ToolCallEvent>()
        val result = ToolChatRunner({ requests += it; script.removeFirst() }, registry.select("read_file"), registry = registry)
            .run(listOf(LlmMessage.user("Show it"))) { events += it }

        assertEquals("Done.", result.reply)
        assertEquals(listOf(
            listOf("read_file", SEARCH_TOOLS),
            listOf("read_file", SEARCH_TOOLS, "open_card", "show_card"),
            listOf("read_file", SEARCH_TOOLS, "open_card", "show_card"),
        ), requests.map { request -> request.tools.map { it.name } })
        assertEquals(listOf("open_card ran", "show_card ran"), events.filterNot { it.failed }.drop(1).map { it.result })

        // A call to a registered tool the turn has not loaded fails once, then works with its schema advertised.
        val early = ArrayDeque(listOf(
            Completion("", toolCalls = listOf(LlmToolCall("1", "show_card", JsonObject())), finishReason = FinishReason.TOOL_CALLS),
            Completion("", toolCalls = listOf(LlmToolCall("2", "show_card", JsonObject())), finishReason = FinishReason.TOOL_CALLS),
            Completion("Done."),
        ))
        val calls = mutableListOf<ToolCallEvent>()
        requests.clear()
        ToolChatRunner({ requests += it; early.removeFirst() }, emptyList(), registry = registry).run(listOf(LlmMessage.user("Hi"))) { calls += it }
        assertTrue(calls[0].failed && "It is loaded now" in calls[0].result)
        assertEquals("show_card ran", calls[1].result)
        assertEquals(listOf(SEARCH_TOOLS, "show_card"), requests[1].tools.map { it.name })
    }

    @Test fun `tools loaded by search cannot execute in the same batch`() = runBlocking {
        assertUnadvertisedBatch(listOf(
            LlmToolCall("search", SEARCH_TOOLS, args("""{"query":"open_card"}""")),
            LlmToolCall("early", "open_card", JsonObject()),
        ), failedCalls = 1)
    }

    @Test fun `repeated early calls cannot execute until their schema is advertised`() = runBlocking {
        assertUnadvertisedBatch(listOf(
            LlmToolCall("early1", "open_card", JsonObject()),
            LlmToolCall("early2", "open_card", JsonObject()),
        ), failedCalls = 2)
    }

    private suspend fun assertUnadvertisedBatch(batch: List<LlmToolCall>, failedCalls: Int) {
        val requests = mutableListOf<CompletionRequest>()
        val script = ArrayDeque(listOf(
            Completion("", toolCalls = batch, finishReason = FinishReason.TOOL_CALLS),
            Completion("", toolCalls = listOf(LlmToolCall("retry", "open_card", JsonObject())), finishReason = FinishReason.TOOL_CALLS),
            Completion("Done."),
        ))
        val events = mutableListOf<ToolCallEvent>()
        ToolChatRunner({ request ->
            requests += request
            // No canvas mutation may occur until the completion receiving the schema has responded.
            if (requests.size <= 2) assertEquals(0, openCard.calls)
            script.removeFirst()
        }, emptyList(), registry = registry).run(listOf(LlmMessage.user("Open a card"))) { events += it }

        assertEquals(listOf(SEARCH_TOOLS), requests.first().tools.map { it.name })
        assertEquals(listOf(SEARCH_TOOLS, "open_card"), requests[1].tools.map { it.name })
        assertEquals(failedCalls, events.count { it.failed })
        assertTrue(events.filter { it.failed }.all { "not advertised" in it.result })
        assertEquals(1, openCard.calls)
        assertEquals("open_card ran", events.last().result)
    }
}
