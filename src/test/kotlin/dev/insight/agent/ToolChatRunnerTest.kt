package dev.insight.agent

import dev.insight.chat.LlmChatBackend
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.FinishReason
import dev.insight.llm.LlmApiException
import dev.insight.llm.LlmMessage
import dev.insight.llm.LlmProtocolException
import dev.insight.llm.LlmRole
import dev.insight.llm.LlmTool
import dev.insight.llm.LlmToolCall
import dev.insight.llm.ToolChoice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException

class ToolChatRunnerTest {
    private fun args(json: String) = JsonParser.parseString(json).asJsonObject
    private fun call(name: String, json: String = "{}", id: String? = null) = LlmToolCall(id, name, args(json))

    private class EchoTool : ChatTool {
        val received = mutableListOf<JsonObject>()
        override val definition = LlmTool("echo", "Echo text", JsonParser.parseString("""{"type":"object"}""").asJsonObject)
        override suspend fun call(arguments: JsonObject): String {
            received += arguments
            val text = arguments["text"]?.asString ?: throw ToolInputException("text is required")
            if (text == "boom") throw IllegalStateException("exploded")
            return "echo: $text"
        }
    }

    /** Replies with [script] in order and records each request. */
    private class FakeModel(vararg script: Completion) {
        private val remaining = ArrayDeque(script.toList())
        val requests = mutableListOf<CompletionRequest>()
        fun complete(request: CompletionRequest): Completion {
            requests += request
            return remaining.removeFirst()
        }
    }

    @Test fun `follow-ups continue the turn a bounded number of times`() = runBlocking {
        val model = FakeModel(Completion("First."), Completion(""), Completion("Third."))
        var checks = 0
        val result = ToolChatRunner(model::complete, listOf(EchoTool()), followUp = { checks++; "Not done yet." })
            .run(listOf(LlmMessage.user("Hi")))
        assertEquals("Third.", result.reply)
        assertEquals(2, checks)
        assertEquals(listOf(LlmRole.USER, LlmRole.ASSISTANT, LlmRole.USER, LlmRole.USER), model.requests.last().messages.map { it.role })
    }

    @Test fun `executes tool calls and returns the final answer`() = runBlocking {
        val tool = EchoTool()
        val model = FakeModel(
            Completion("Let me check.", reasoning = "think", toolCalls = listOf(call("echo", """{"text":"a"}""", "x"), call("echo", """{"text":"b"}""")), finishReason = FinishReason.TOOL_CALLS),
            Completion("  Done.  ", finishReason = FinishReason.COMPLETE),
        )
        val events = mutableListOf<ToolCallEvent>()
        val result = ToolChatRunner(model::complete, listOf(tool)).run(listOf(LlmMessage.user("Hi"))) { events += it }

        assertEquals("Done.", result.reply)
        assertEquals(FinishReason.COMPLETE, result.finishReason)
        assertEquals(2, result.requestCount)
        assertEquals(listOf("echo: a", "echo: b"), events.map { it.result })
        assertTrue(events.none { it.failed })
        val second = model.requests[1].messages
        assertEquals(listOf(LlmRole.USER, LlmRole.ASSISTANT, LlmRole.TOOL, LlmRole.TOOL), second.map { it.role })
        // Reasoning is replayed to the model but kept out of the transcript; missing IDs are generated.
        assertEquals("think", second[1].reasoning)
        assertEquals(listOf("x", "call_1_2"), second[1].toolCalls.map { it.id })
        assertEquals(listOf("x", "call_1_2"), second.drop(2).map { it.toolCallId })
        assertNull(result.transcript[0].reasoning)
        assertEquals(3, result.transcript.size)
        assertEquals(listOf("echo"), model.requests[0].tools.map { it.name })
        assertNull(model.requests[0].toolChoice)
    }

    @Test fun `reports unknown tools, invalid arguments and tool failures to the model`() = runBlocking {
        val model = FakeModel(
            Completion("", toolCalls = listOf(call("missing"), call("echo"), call("echo", """{"text":"boom"}"""))),
            Completion("Sorry."),
        )
        val events = mutableListOf<ToolCallEvent>()
        val result = ToolChatRunner(model::complete, listOf(EchoTool())).run(listOf(LlmMessage.user("Hi"))) { events += it }
        assertEquals("Sorry.", result.reply)
        assertTrue(events.all { it.failed })
        val results = model.requests[1].messages.filter { it.role == LlmRole.TOOL }.map { it.content }
        assertEquals("Error: unknown tool 'missing'. Available tools: echo.", results[0])
        assertEquals("Error: text is required", results[1])
        assertEquals("Error: the tool failed (exploded).", results[2])
    }

    @Test fun `disables new tool calls after the round limit`() = runBlocking {
        val looping = Completion("", toolCalls = listOf(call("echo", """{"text":"again"}""")))
        val model = FakeModel(looping, looping, Completion("Final answer."))
        val result = ToolChatRunner(model::complete, listOf(EchoTool()), maxToolRounds = 2).run(listOf(LlmMessage.user("Hi")))
        assertEquals("Final answer.", result.reply)
        assertEquals(3, model.requests.size)
        val last = model.requests.last()
        assertEquals(ToolChoice.None, last.toolChoice)
        assertEquals(listOf("echo"), last.tools.map { it.name })
        assertEquals(LlmRole.TOOL, last.messages.last().role)
        assertEquals(listOf(LlmMessage.user("Hi")), last.messages.filter { it.role == LlmRole.USER })
        assertEquals(listOf(LlmMessage.user("Hi")) + result.transcript, last.messages)
    }

    @Test fun `default permits one hundred tool rounds before requesting a final answer`() = runBlocking {
        val tool = EchoTool()
        val looping = Completion("", toolCalls = listOf(call("echo", """{"text":"again"}""")))
        val model = FakeModel(*(List(100) { looping } + Completion("Done.")).toTypedArray())
        val result = ToolChatRunner(model::complete, listOf(tool)).run(listOf(LlmMessage.user("Hi")))

        assertEquals("Done.", result.reply)
        assertEquals(100, tool.received.size)
        assertEquals(101, result.requestCount)
        assertTrue(model.requests.dropLast(1).all { it.toolChoice == null })
        assertEquals(ToolChoice.None, model.requests.last().toolChoice)
        assertTrue(model.requests.all { request ->
            request.messages.filter { it.role == LlmRole.USER } == listOf(LlmMessage.user("Hi"))
        })
    }

    @Test fun `retries with a user note when the server ignores tool choice`() = runBlocking {
        val tool = EchoTool()
        val looping = Completion("", toolCalls = listOf(call("echo", """{"text":"again"}""")))
        val model = FakeModel(looping, looping, Completion("Done."))
        val result = ToolChatRunner(model::complete, listOf(tool), maxToolRounds = 1)
            .run(listOf(LlmMessage.system("SYSTEM"), LlmMessage.user("Hi")))
        assertEquals("Done.", result.reply)
        assertEquals(3, result.requestCount)
        assertEquals(1, tool.received.size)
        assertEquals(2, result.transcript.size)
        val retry = model.requests.last()
        assertEquals(ToolChoice.None, retry.toolChoice)
        assertEquals(model.requests[1].tools, retry.tools)
        assertEquals(model.requests[1].messages, retry.messages.dropLast(1))
        assertEquals(LlmRole.USER, retry.messages.last().role)
        assertTrue(retry.messages.last().content.contains("Answer now"))
    }

    @Test fun `retries with a user note when the reply without tools is empty`() = runBlocking {
        val looping = Completion("", toolCalls = listOf(call("echo", """{"text":"again"}""")))
        val replies = ArrayDeque(listOf(looping, null, Completion("Done.")))
        val requests = mutableListOf<CompletionRequest>()
        // The decoder throws on an empty reply, as llama.cpp returns for a call dropped under tool choice none.
        val complete: suspend (CompletionRequest) -> Completion = {
            requests += it
            replies.removeFirst() ?: throw LlmProtocolException("Empty LLM response")
        }
        val result = ToolChatRunner(complete, listOf(EchoTool()), maxToolRounds = 1).run(listOf(LlmMessage.user("Hi")))
        assertEquals("Done.", result.reply)
        assertEquals(3, requests.size)
        assertTrue(requests.last().messages.last().content.contains("Answer now"))
    }

    @Test fun `backend forwards an explicit larger tool round budget`() = runBlocking {
        val tool = EchoTool()
        val looping = Completion("", toolCalls = listOf(call("echo", """{"text":"again"}""")))
        val model = FakeModel(*(List(100) { looping } + Completion("Done.")).toTypedArray())
        val backend = LlmChatBackend(model::complete, listOf(tool), "SYSTEM", maxToolRounds = 100)
        assertEquals("Done.", backend.reply(listOf(ChatMessage(ChatRole.USER, "Hi"))))
        assertEquals(100, tool.received.size)
        assertEquals(101, model.requests.size)
        assertEquals(ToolChoice.None, model.requests.last().toolChoice)
    }

    @Test fun `fails when the model keeps calling tools after the limit`() {
        val looping = Completion("", toolCalls = listOf(call("echo", """{"text":"again"}""")))
        val model = FakeModel(looping, looping, looping)
        val tool = EchoTool()
        assertThrows(LlmProtocolException::class.java) {
            runBlocking { ToolChatRunner(model::complete, listOf(tool), maxToolRounds = 1).run(listOf(LlmMessage.user("Hi"))) }
        }
        assertEquals(1, tool.received.size)
    }

    @Test fun `truncates oversized tool results`() = runBlocking {
        val model = FakeModel(Completion("", toolCalls = listOf(call("echo", """{"text":"${"x".repeat(50)}"}"""))), Completion("ok"))
        ToolChatRunner(model::complete, listOf(EchoTool()), maxToolResultChars = 10).run(listOf(LlmMessage.user("Hi")))
        assertEquals("echo: xxxx\n[Truncated 46 characters.]", model.requests[1].messages.last().content)
    }

    @Test fun `history keeps recent conversation within the budget and drops tool events and notices`() {
        val history = listOf(
            ChatMessage(ChatRole.NOTICE, "Welcome"),
            ChatMessage(ChatRole.USER, "old question"),
            ChatMessage(ChatRole.ASSISTANT, "old answer"),
            ChatMessage(ChatRole.TOOL, "read_file(path=a)"),
            ChatMessage(ChatRole.USER, "  "),
            ChatMessage(ChatRole.USER, "new question"),
            ChatMessage(ChatRole.ASSISTANT, "new answer"),
        )
        assertEquals(
            listOf(LlmMessage.user("old question"), LlmMessage.assistant("old answer"), LlmMessage.user("new question"), LlmMessage.assistant("new answer")),
            compactHistory(history),
        )
        assertEquals(listOf(LlmMessage.user("new question"), LlmMessage.assistant("new answer")), compactHistory(history, maxMessages = 2))
        // The latest turn survives a small budget; short messages are not clipped to nothing.
        assertEquals(listOf(LlmMessage.user("new question"), LlmMessage.assistant("new answer")), compactHistory(history, maxChars = 12))
        assertEquals(emptyList<LlmMessage>(), compactHistory(history, maxChars = 0))
    }

    @Test fun `history clips an oversized latest turn and keeps the start of each message`() {
        val history = listOf(
            ChatMessage(ChatRole.USER, "old question"),
            ChatMessage(ChatRole.ASSISTANT, "old answer"),
            ChatMessage(ChatRole.USER, "x".repeat(1000)),
            ChatMessage(ChatRole.ASSISTANT, "short answer"),
        )
        val kept = compactHistory(history, maxChars = 500)
        assertEquals(2, kept.size)
        assertEquals("x".repeat(488) + "\n[Truncated 512 characters.]", kept[0].content)
        assertEquals("short answer", kept[1].content)
    }

    @Test fun `backend sends system prompt, history and question, and marks truncated replies`() = runBlocking {
        val model = FakeModel(Completion("Partial", finishReason = FinishReason.TRUNCATED))
        val backend = LlmChatBackend(model::complete, emptyList(), "SYSTEM")
        val reply = backend.reply(listOf(
            ChatMessage(ChatRole.USER, "first"), ChatMessage(ChatRole.ASSISTANT, "answer"), ChatMessage(ChatRole.USER, "second"),
        ))
        assertTrue(reply.startsWith("Partial\n\n[The reply was cut off"))
        assertEquals(
            listOf(LlmMessage.system("SYSTEM"), LlmMessage.user("first"), LlmMessage.assistant("answer"), LlmMessage.user("second")),
            model.requests.single().messages,
        )
    }

    @Test fun `failure descriptions surface server messages`() {
        assertEquals(
            "the LLM server returned HTTP 400: the request exceeds the available context size",
            describeChatFailure(LlmApiException(400, """{"error":{"message":"the request exceeds the available context size"}}""", null)),
        )
        assertEquals("the LLM server returned HTTP 502: Bad gateway", describeChatFailure(LlmApiException(502, "Bad gateway", null)))
        assertEquals("the LLM server returned HTTP 500.", describeChatFailure(LlmApiException(500, "", null)))
        assertTrue(describeChatFailure(ConnectException()).startsWith("could not connect"))
    }
}
