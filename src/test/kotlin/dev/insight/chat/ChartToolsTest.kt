package dev.insight.chat

import com.google.gson.JsonParser
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.FinishReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChartToolsTest {
    private class Context : ChartChatContext {
        var revision = 1
        val actions = mutableListOf<Triple<String, Int, List<Int>>>()
        override suspend fun state(offset: Int) = "revision=$revision offset=$offset"
        override suspend fun find(query: String, offset: Int) = "$query offset=$offset"
        override suspend fun apply(action: String, revision: Int, nodes: List<Int>): String {
            if (revision != this.revision) throw ToolInputException("Stale revision")
            actions += Triple(action, revision, nodes)
            return "ok"
        }
    }

    @Test fun `validates full batches and requires revision before dispatch`() = runBlocking {
        val context = Context()
        val tool = chartTools(context).single { it.definition.name == "add_chart_nodes" }
        for (json in listOf(
            """{"node_ids":[1]}""", """{"revision":1,"node_ids":[1,-2]}""",
            """{"revision":1,"node_ids":[1,{}]}""", """{"revision":1,"node_ids":[]}""",
            """{"revision":1,"node_ids":[1.5]}""", """{"revision":0,"node_ids":[1]}""",
            """{"revision":1,"node_ids":[${(1..21).joinToString()}]}""",
        )) {
            try { tool.call(JsonParser.parseString(json).asJsonObject); fail(json) }
            catch (_: ToolInputException) { }
        }
        assertTrue(context.actions.isEmpty())
        tool.call(JsonParser.parseString("""{"revision":1,"node_ids":[2,2,3]}""").asJsonObject)
        assertEquals(listOf(Triple("add_chart_nodes", 1, listOf(2, 3))), context.actions)
    }

    @Test fun `clear highlights and paginated discovery are available without project tools`() = runBlocking {
        val context = Context()
        val tools = chartTools(context).associateBy { it.definition.name }
        assertEquals(setOf("get_chart_state", "find_chart_nodes", "add_chart_nodes", "highlight_chart_nodes",
            "expand_chart_calls", "expand_chart_callers"), tools.keys)
        assertEquals("revision=1 offset=20", tools.getValue("get_chart_state").call(JsonParser.parseString("""{"offset":20}""").asJsonObject))
        assertEquals("save offset=0", tools.getValue("find_chart_nodes").call(JsonParser.parseString("""{"query":"save"}""").asJsonObject))
        tools.getValue("highlight_chart_nodes").call(JsonParser.parseString("""{"revision":1,"node_ids":[]}""").asJsonObject)
        assertTrue(context.actions.single().third.isEmpty())
    }

    @Test fun `fresh chart state is supplied on every turn without altering saved history`() = runBlocking {
        val context = Context()
        val requests = mutableListOf<CompletionRequest>()
        val backend = LlmChatBackend({ request ->
            requests += request
            Completion("Answer", finishReason = FinishReason.COMPLETE)
        }, chartTools(context), chartChatSystemPrompt("test"), context = context::snapshot)
        val history = listOf(ChatMessage(ChatMessage.USER, "Explain selection"))
        backend.reply(history)
        context.revision = 2
        backend.reply(history + ChatMessage(ChatMessage.ASSISTANT, "Answer") + ChatMessage(ChatMessage.USER, "Now?"))
        assertTrue(requests[0].messages.last().content.orEmpty().contains("revision=1"))
        assertTrue(requests[1].messages.last().content.orEmpty().contains("revision=2"))
        assertFalse(requests[1].messages.dropLast(1).any { it.content.orEmpty().contains("revision=1") })
        assertEquals("Explain selection", history.single().text)
    }
}
