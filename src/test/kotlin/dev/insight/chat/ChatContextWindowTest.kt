package dev.insight.chat

import com.google.gson.JsonObject
import dev.insight.llm.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChatContextWindowTest {
    private val system = LlmMessage.system("System")
    private val question = LlmMessage.user("Current question")
    private val old = listOf(LlmMessage.user("Old question"), LlmMessage.assistant("Old answer"))
    private fun request(messages: List<LlmMessage>) = CompletionRequest(messages)
    private fun round(id: String, text: String = "result") = listOf(
        LlmMessage.assistant("checking", listOf(LlmToolCall(id, "echo", JsonObject())), "reasoning"),
        LlmMessage.tool(id, text),
    )
    private fun overflow() = LlmApiException(400, """{"error":{"code":"context_length_exceeded"}}""", null)

    @Test fun `history budget evicts whole turns and emits one cumulative notice`() {
        val recent = listOf(LlmMessage.user("New question"), LlmMessage.assistant("New answer"))
        val window = ChatContextWindow(listOf(system) + old + recent + question, 64_000, 22)
        val kept = window.fit(::request).messages
        assertEquals(listOf(system) + recent, kept.dropLast(1))
        assertTrue(kept.last().content.startsWith(question.content))
        assertTrue(kept.last().content.contains("1 earlier conversation turns"))
        assertTrue(window.shrink(request(kept)))
        val shrunk = window.fit(::request).messages
        assertEquals(system, shrunk.first())
        assertEquals(2, shrunk.size)
        assertTrue(shrunk.last().content.contains("2 earlier conversation turns"))
        assertEquals(1, Regex("Context omitted").findAll(shrunk.last().content).count())
        assertFalse(window.shrink(request(shrunk)))
    }

    @Test fun `parallel calls and all results are evicted as one round`() {
        val window = ChatContextWindow(listOf(system, question), 64_000, 24_000)
        val first = listOf(
            LlmMessage.assistant("", listOf(LlmToolCall("a", "echo", JsonObject()), LlmToolCall("b", "echo", JsonObject())), "old reasoning"),
            LlmMessage.tool("a", "A"), LlmMessage.tool("b", "B"),
        )
        val latest = round("c")
        window.addRound(first)
        window.addRound(latest)
        assertTrue(window.shrink(window.fit(::request)))
        val kept = window.fit(::request).messages
        assertEquals(latest, kept.takeLast(2))
        assertFalse(kept.any { it.toolCallId in listOf("a", "b") || it.reasoning == "old reasoning" })
        assertTrue(kept[1].content.contains("1 earlier tool rounds"))
        assertFalse(window.shrink(request(kept)))
    }

    @Test fun `request budget counts schemas arguments reasoning and results`() {
        val tool = LlmTool("echo", "schema ".repeat(100), JsonObject())
        fun withTools(messages: List<LlmMessage>) = CompletionRequest(messages, tools = listOf(tool))
        val largeRound = listOf(
            LlmMessage.assistant("", listOf(LlmToolCall("a", "echo", JsonObject().apply { addProperty("text", "x".repeat(2000)) })), "r".repeat(2000)),
            LlmMessage.tool("a", "t".repeat(2000)),
        )
        val latest = round("b")
        val window = ChatContextWindow(listOf(system) + old + question, 2500, 24_000)
        window.addRound(largeRound)
        window.addRound(latest)
        val kept = window.fit(::withTools)
        assertTrue(LlmJson.encode(kept, "", true).length <= 2500)
        assertEquals(listOf(tool), kept.tools)
        assertEquals(system, kept.messages.first())
        assertEquals(latest, kept.messages.takeLast(2))
        // The older tool round goes before the latest conversation turn.
        assertEquals(old, kept.messages.subList(1, 3))
        assertTrue(kept.messages[3].content.contains("0 earlier conversation turns, 1 earlier tool rounds"))

        // A large schema alone must be enough to evict otherwise-small history.
        val schemaWindow = ChatContextWindow(listOf(system) + old + question, 500, 24_000)
        val protected = schemaWindow.fit(::withTools).messages
        assertEquals(2, protected.size)
        assertTrue(protected.last().content.contains("1 earlier conversation turns"))
    }

    @Test fun `eviction target frees headroom so later rounds keep the same prefix`() {
        fun window(target: Double) = ChatContextWindow(listOf(system, question), 1500, 24_000, evictionTarget = target).apply {
            for (i in 1..6) addRound(round("r$i", "x".repeat(200)))
        }
        val exact = window(1.0)
        val chunked = window(0.5)
        val exactKept = exact.fit(::request)
        val chunkedKept = chunked.fit(::request)
        assertTrue(LlmJson.encode(exactKept, "", true).length <= 1500)
        assertTrue(LlmJson.encode(chunkedKept, "", true).length <= 750)
        assertTrue(chunkedKept.messages.size < exactKept.messages.size)
        // Under budget, nothing more is evicted, so the next request extends the previous one.
        chunked.addRound(round("next"))
        assertEquals(chunkedKept.messages, chunked.fit(::request).messages.dropLast(2))
    }

    @Test fun `older tool rounds are evicted before the latest conversation turn`() {
        val recent = listOf(LlmMessage.user("New question"), LlmMessage.assistant("New answer"))
        val window = ChatContextWindow(listOf(system) + old + recent + question, 64_000, 24_000)
        window.addRound(round("a"))
        window.addRound(round("b"))
        window.addRound(round("c"))
        do {
            val kept = window.fit(::request).messages
            val ids = kept.mapNotNull { it.toolCallId }
            if ("a" in ids || "b" in ids) assertTrue(recent.all { it in kept })
            if (old.any { it in kept }) assertTrue("a" in ids)
            assertTrue("c" in ids)
        } while (window.shrink(request(kept)))
        val kept = window.fit(::request).messages
        assertEquals(listOf(system), kept.take(1))
        assertTrue(kept[1].content.startsWith(question.content))
        assertTrue(kept[1].content.contains("2 earlier conversation turns, 2 earlier tool rounds"))
        assertEquals(round("c"), kept.drop(2))
    }

    @Test fun `an oversized latest turn is clipped instead of erasing all history`() {
        val paste = LlmMessage.user("trace ".repeat(10_000))
        val answer = LlmMessage.assistant("The first exception comes from Parser.kt:12.")
        val window = ChatContextWindow(listOf(system) + old + listOf(paste, answer) + question, 64_000, 24_000)
        val kept = window.fit(::request).messages
        assertEquals(4, kept.size)
        assertEquals(answer, kept[2])
        assertTrue(kept[1].content.startsWith("trace trace"))
        assertTrue(kept[1].content.length < 25_000)
        assertTrue(kept[1].content.contains("[Truncated"))
        assertTrue(kept.last().content.contains("1 earlier conversation turns"))
    }

    @Test fun `overflow shrink evicts a little rather than half the context`() {
        val window = ChatContextWindow(listOf(system, question), 64_000, 24_000)
        repeat(10) { window.addRound(round("$it", "t".repeat(1000))) }
        assertTrue(window.shrink(window.fit(::request)))
        val kept = window.fit(::request).messages
        assertTrue(kept.count { it.role == LlmRole.TOOL } >= 7)
    }

    @Test fun `oversized protected data remains intact`() {
        val hugeQuestion = LlmMessage.user("q".repeat(5000))
        val latest = round("a", "t".repeat(5000))
        val window = ChatContextWindow(listOf(system, hugeQuestion), 100, 24_000)
        window.addRound(latest)
        assertEquals(listOf(system, hugeQuestion) + latest, window.fit(::request).messages)
    }

    @Test fun `tombstone remembers all omitted parallel tool names and parameters`() {
        val window = ChatContextWindow(listOf(system, question), 64_000, 24_000)
        val calls = listOf(
            LlmToolCall("read", "read_file", JsonObject().apply {
                addProperty("path", "src/main.kt")
                addProperty("start_line", 10)
                addProperty("end_line", 30)
            }),
            LlmToolCall("search", "search", JsonObject().apply { addProperty("query", "context overflow") }),
            LlmToolCall("list", "list_files", JsonObject()),
        )
        window.addRound(listOf(LlmMessage.assistant("", calls, "private reasoning")) +
            calls.map { LlmMessage.tool(it.id!!, "private result") })
        window.addRound(round("latest"))
        assertTrue(window.shrink(window.fit(::request)))
        val kept = window.fit(::request).messages
        val notice = kept[1].content
        assertTrue(notice.contains("read_file({\"path\":\"src/main.kt\",\"start_line\":10,\"end_line\":30})"))
        assertTrue(notice.contains("search({\"query\":\"context overflow\"})"))
        assertTrue(notice.contains("list_files()"))
        assertTrue(notice.contains("results unavailable"))
        assertFalse(notice.contains("private result"))
        assertFalse(notice.contains("private reasoning"))
        assertEquals(listOf("latest"), kept.filter { it.role == LlmRole.TOOL }.map { it.toolCallId })
    }

    @Test fun `history eviction also remembers tools from the removed turn`() {
        val past = round("past").let { messages ->
            listOf(messages.first().copy(toolCalls = listOf(LlmToolCall("past", "read_file", JsonObject().apply {
                addProperty("path", "past.kt")
            }))), messages.last())
        }
        val window = ChatContextWindow(listOf(system, LlmMessage.user("Past question")) + past + question, 64_000, 0)
        val kept = window.fit(::request).messages
        assertEquals(2, kept.size)
        assertTrue(kept.last().content.contains("read_file({\"path\":\"past.kt\"})"))
    }

    @Test fun `call summaries stay bounded and retain the most recently omitted parameters`() {
        val window = ChatContextWindow(listOf(system, question), 8000, 24_000)
        repeat(20) { index ->
            window.addRound(listOf(
                LlmMessage.assistant("", listOf(LlmToolCall("$index", "search", JsonObject().apply {
                    addProperty("query", "query-$index-" + "x".repeat(5000))
                }))),
                LlmMessage.tool("$index", "result"),
            ))
        }
        val kept = window.fit(::request).messages
        val notice = kept[1].content
        assertTrue(notice.length < 1400)
        assertTrue(notice.contains("older call summaries omitted"))
        assertTrue(notice.contains("query-18-"))
        assertFalse(notice.contains("query-0-"))
        assertTrue(notice.contains("…"))
        assertEquals(listOf("19"), kept.filter { it.role == LlmRole.TOOL }.map { it.toolCallId })
        assertEquals(1, Regex("Context omitted").findAll(notice).count())
    }

    @Test fun `overflow retry removes whole history and counts requests`() = runBlocking {
        val requests = mutableListOf<CompletionRequest>()
        val runner = ToolChatRunner({ candidate ->
            requests += candidate
            if (requests.size == 1) throw overflow()
            Completion("Answer")
        }, emptyList())
        val result = runner.run(listOf(system) + old + question)
        assertEquals(2, result.requestCount)
        assertEquals(listOf(system) + old + question, requests[0].messages)
        assertEquals(2, requests[1].messages.size)
        assertTrue(requests[1].messages.last().content.contains("1 earlier conversation turns"))
    }

    @Test fun `overflow retry does not reexecute tools and preserves the transcript`() = runBlocking {
        var executions = 0
        val tool = object : ChatTool {
            override val definition = LlmTool("echo", "Echo", JsonObject())
            override suspend fun call(arguments: JsonObject): String { executions++; return "result" }
        }
        val requests = mutableListOf<CompletionRequest>()
        val events = mutableListOf<ToolCallEvent>()
        val runner = ToolChatRunner({ candidate ->
            requests += candidate
            when (requests.size) {
                1 -> Completion("", toolCalls = round("a").first().toolCalls)
                2 -> Completion("", toolCalls = round("b").first().toolCalls)
                3 -> throw overflow()
                else -> Completion("Answer")
            }
        }, listOf(tool))
        val result = runner.run(listOf(system, question)) { events += it }
        assertEquals(4, result.requestCount)
        assertEquals(2, executions)
        assertEquals(2, events.size)
        assertEquals(4, result.transcript.size)
        assertEquals(listOf("b"), requests.last().messages.filter { it.role == LlmRole.TOOL }.map { it.toolCallId })
        assertTrue(requests.last().messages[1].content.contains("1 earlier tool rounds"))
    }

    @Test fun `unrelated errors and overflow with no removable data are not retried`() {
        for (error in listOf(LlmApiException(400, "invalid schema", null), overflow())) {
            var requests = 0
            val runner = ToolChatRunner({ requests++; throw error }, emptyList())
            assertSame(error, assertThrows(LlmApiException::class.java) {
                runBlocking { runner.run(listOf(system, question)) }
            })
            assertEquals(1, requests)
        }
    }

    @Test fun `overflow retries are bounded`() {
        var requests = 0
        val error = overflow()
        val runner = ToolChatRunner({ requests++; throw error }, emptyList(), maxContextRetries = 1)
        assertSame(error, assertThrows(LlmApiException::class.java) {
            runBlocking { runner.run(listOf(system) + old + old + question) }
        })
        assertEquals(2, requests)
    }

    @Test fun `backend retains an omission notice when history is dropped`() = runBlocking {
        var sent: CompletionRequest? = null
        val backend = LlmChatBackend({ sent = it; Completion("Answer") }, emptyList(), "System", maxHistoryChars = 0)
        backend.reply(listOf(ChatMessage(ChatMessage.USER, "Old"), ChatMessage(ChatMessage.ASSISTANT, "Answer"), ChatMessage(ChatMessage.USER, "Current")))
        assertEquals(2, sent!!.messages.size)
        assertTrue(sent.messages.last().content.contains("1 earlier conversation turns"))
    }
}
