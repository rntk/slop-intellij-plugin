package dev.insight.llm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import dev.insight.core.ApiTrafficLog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ProviderClientsTest {
    @get:Rule val timeout = Timeout.seconds(30)

    private lateinit var server: HttpServer
    private val executor = Executors.newCachedThreadPool()
    private lateinit var baseUrl: String
    private val requests = CopyOnWriteArrayList<Pair<Map<String, List<String>>, JsonObject>>()
    private val weatherTool = LlmTool(
        "get_weather", "Weather for a city",
        json("""{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""),
    )
    private val parisCall = LlmToolCall("call_1", "get_weather", json("""{"city":"Paris"}"""))
    private val toolLoop = listOf(
        LlmMessage.system("Be brief."),
        LlmMessage.user("Weather in Paris?"),
        LlmMessage.assistant("", listOf(parisCall), reasoning = "Need the tool."),
        LlmMessage.tool("call_1", "18C, sunny"),
    )

    @Before fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After fun stopServer() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun json(text: String) = JsonParser.parseString(text).asJsonObject

    private fun respond(path: String, vararg replies: Pair<Int, String>) {
        val calls = AtomicInteger()
        server.createContext(path) { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            requests += exchange.requestHeaders.toMap() to json(body)
            val (status, text) = replies[minOf(calls.getAndIncrement(), replies.size - 1)]
            val bytes = text.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun header(name: String) = requests.single().first.entries.first { it.key.equals(name, true) }.value.single()

    private fun chatReply(message: String, finishReason: String = "stop", extra: String = "") =
        """{"model":"m","choices":[{"index":0,"finish_reason":"$finishReason","message":$message}]$extra}"""

    // --- OpenAI ---

    @Test fun `OpenAI sends bearer auth, max_completion_tokens and OpenAI options`() = runBlocking {
        respond("/v1/chat/completions", 200 to chatReply("""{"role":"assistant","content":"Sunny."}""",
            extra = ""","usage":{"prompt_tokens":50,"completion_tokens":20,"total_tokens":70,
                "prompt_tokens_details":{"cached_tokens":32},"completion_tokens_details":{"reasoning_tokens":12}}"""))
        val result = OpenAiClient("sk-test", "gpt-5", baseUrl, serviceTier = "flex", promptCacheKey = "insight")
            .complete(CompletionRequest(toolLoop, tools = listOf(weatherTool), toolChoice = ToolChoice.Required,
                parallelToolCalls = false, temperature = 0.3, maxTokens = 300))
        assertEquals("Sunny.", result.content)
        assertEquals(LlmUsage(50, 20, 70, 32, reasoningTokens = 12), result.usage)
        assertEquals("Bearer sk-test", header("Authorization"))
        val body = requests.single().second
        assertEquals("gpt-5", body["model"].asString)
        assertEquals(300, body["max_completion_tokens"].asInt)
        assertFalse(body.has("max_tokens"))
        assertFalse(body.has("cache_prompt"))
        assertEquals("flex", body["service_tier"].asString)
        assertEquals("insight", body["prompt_cache_key"].asString)
        assertEquals("required", body["tool_choice"].asString)
        assertFalse(body["parallel_tool_calls"].asBoolean)
        val messages = body.getAsJsonArray("messages").map { it.asJsonObject }
        assertEquals(listOf("system", "user", "assistant", "tool"), messages.map { it["role"].asString })
        assertFalse(messages[2].has("reasoning_content"))
        assertEquals("""{"city":"Paris"}""", messages[2].getAsJsonArray("tool_calls")[0].asJsonObject
            .getAsJsonObject("function")["arguments"].asString)
    }

    @Test fun `OpenAI names an unnamed json_schema response format without mutating the caller's`() = runBlocking {
        respond("/v1/chat/completions", 200 to chatReply("""{"role":"assistant","content":"{}"}"""))
        val format = json("""{"type":"json_schema","json_schema":{"schema":{"type":"object"}}}""")
        OpenAiClient("sk", "gpt-5", baseUrl).complete(CompletionRequest(listOf(LlmMessage.user("Hi")), responseFormat = format))
        assertEquals("response", requests.single().second.getAsJsonObject("response_format")
            .getAsJsonObject("json_schema")["name"].asString)
        assertFalse(format.getAsJsonObject("json_schema").has("name"))
        val named = json("""{"type":"json_schema","json_schema":{"name":"x","schema":{}}}""")
        assertSame(named, OpenAiClient.namedSchema(named))
        val plain = json("""{"type":"json_object"}""")
        assertSame(plain, OpenAiClient.namedSchema(plain))
    }

    @Test fun `OpenAI defaults to the public API and validates its configuration`() {
        assertEquals(URI("https://api.openai.com/v1/chat/completions"), OpenAiClient("sk", "gpt-5").endpoint)
        assertThrows(IllegalArgumentException::class.java) { OpenAiClient("sk", " ") }
        assertThrows(IllegalArgumentException::class.java) { OpenAiClient("", "gpt-5") }
        assertThrows(IllegalArgumentException::class.java) { OpenAiClient("sk", "gpt-5", "http://example.com/v1") }
    }

    // --- DeepSeek ---

    @Test fun `DeepSeek echoes reasoning only on tool requests and decodes cache hits`() = runBlocking {
        respond("/chat/completions", 200 to chatReply(
            """{"role":"assistant","content":"Done.","reasoning_content":"Thought."}""",
            extra = ""","usage":{"prompt_tokens":100,"completion_tokens":10,"total_tokens":110,
                "prompt_cache_hit_tokens":64,"prompt_cache_miss_tokens":36}""",
        ))
        val client = DeepSeekClient("sk-ds", "deepseek-reasoner", "$baseUrl/chat/completions")
        val withTools = client.complete(CompletionRequest(toolLoop, tools = listOf(weatherTool), maxTokens = 50))
        assertEquals("Thought.", withTools.reasoning)
        assertEquals(LlmUsage(100, 10, 110, 64), withTools.usage)
        client.complete(CompletionRequest(toolLoop))
        val (toolRequest, plainRequest) = requests.map { it.second }
        fun assistantOf(body: JsonObject) = body.getAsJsonArray("messages")[2].asJsonObject
        assertEquals("Need the tool.", assistantOf(toolRequest)["reasoning_content"].asString)
        assertFalse(assistantOf(plainRequest).has("reasoning_content"))
        assertEquals(50, toolRequest["max_tokens"].asInt)
        assertEquals("Bearer sk-ds", requests[0].first.entries.first { it.key.equals("Authorization", true) }.value.single())
    }

    @Test fun `DeepSeek downgrades json_schema to json_object and keeps json_object`() = runBlocking {
        respond("/chat/completions", 200 to chatReply("""{"role":"assistant","content":"{}"}"""))
        val client = DeepSeekClient("sk", "deepseek-chat", "$baseUrl/chat/completions")
        client.complete(CompletionRequest(listOf(LlmMessage.user("JSON please")),
            responseFormat = json("""{"type":"json_schema","json_schema":{"schema":{"type":"object"}}}""")))
        client.complete(CompletionRequest(listOf(LlmMessage.user("JSON please")), responseFormat = json("""{"type":"json_object"}""")))
        requests.forEach { assertEquals(json("""{"type":"json_object"}"""), it.second["response_format"]) }
    }

    @Test fun `DeepSeek defaults to the public API`() {
        assertEquals(URI("https://api.deepseek.com/chat/completions"), DeepSeekClient("sk", "deepseek-chat").endpoint)
    }

    // --- Anthropic ---

    @Test fun `Anthropic translates system, tool calls and grouped tool results`() = runBlocking {
        respond("/v1/messages", 200 to """{"model":"claude-sonnet-4-5","stop_reason":"end_turn",
            "content":[{"type":"text","text":"Sunny."}],"usage":{"input_tokens":10,"output_tokens":5}}""")
        val thinking = json("""{"type":"thinking","thinking":"Plan.","signature":"sig"}""")
        val romeCall = LlmToolCall("call_2", "get_weather", json("""{"city":"Rome"}"""))
        val messages = listOf(
            LlmMessage.system("Be brief."),
            LlmMessage.system("Use tools."),
            LlmMessage.user("Weather in Paris and Rome?"),
            LlmMessage.assistant("Checking.", listOf(parisCall, romeCall), thinkingBlocks = listOf(thinking)),
            LlmMessage.tool("call_1", "18C"),
            LlmMessage.tool("call_2", "24C"),
            LlmMessage.user("Answer now."),
        )
        val result = AnthropicClient("sk-ant", "claude-sonnet-4-5", baseUrl, serviceTier = "standard_only").complete(
            CompletionRequest(messages, tools = listOf(weatherTool), toolChoice = ToolChoice.Function("get_weather"),
                parallelToolCalls = false, temperature = 0.2),
        )
        assertEquals("Sunny.", result.content)
        assertEquals("sk-ant", header("x-api-key"))
        assertEquals(AnthropicClient.API_VERSION, header("anthropic-version"))
        assertTrue(requests.single().first.keys.none { it.equals("Authorization", true) })
        val body = requests.single().second
        assertEquals("claude-sonnet-4-5", body["model"].asString)
        assertEquals(8192, body["max_tokens"].asInt)
        assertEquals(0.2, body["temperature"].asDouble, 0.0)
        assertEquals("standard_only", body["service_tier"].asString)
        assertEquals(json("""{"type":"text","text":"Be brief.\n\nUse tools.","cache_control":{"type":"ephemeral"}}"""),
            body.getAsJsonArray("system").single())
        assertEquals(json("""{"type":"tool","name":"get_weather","disable_parallel_tool_use":true}"""), body["tool_choice"])
        val tool = body.getAsJsonArray("tools")[0].asJsonObject
        assertEquals(weatherTool.parameters, tool["input_schema"])
        assertFalse(tool.has("type"))
        val sent = body.getAsJsonArray("messages").map { it.asJsonObject }
        assertEquals(listOf("user", "assistant", "user"), sent.map { it["role"].asString })
        val assistant = sent[1].getAsJsonArray("content").map { it.asJsonObject }
        assertEquals(listOf("thinking", "text", "tool_use", "tool_use"), assistant.map { it["type"].asString })
        assertEquals(thinking, assistant[0])
        assertEquals(json("""{"type":"tool_use","id":"call_1","name":"get_weather","input":{"city":"Paris"}}"""), assistant[2])
        val results = sent[2].getAsJsonArray("content").map { it.asJsonObject }
        assertEquals(listOf("tool_result", "tool_result", "text"), results.map { it["type"].asString })
        assertEquals(listOf("call_1", "call_2"), results.take(2).map { it["tool_use_id"].asString })
        assertEquals("Answer now.", results[2]["text"].asString)
    }

    @Test fun `Anthropic marks only the policy prefix for caching and decodes cache usage`() = runBlocking {
        respond("/v1/messages",
            200 to """{"stop_reason":"end_turn","content":[{"type":"text","text":"First"}],
                "usage":{"input_tokens":20,"cache_creation_input_tokens":1100,"cache_read_input_tokens":0,"output_tokens":1}}""",
            200 to """{"stop_reason":"end_turn","content":[{"type":"text","text":"Second"}],
                "usage":{"input_tokens":30,"cache_creation_input_tokens":0,"cache_read_input_tokens":1100,"output_tokens":1}}""")
        val client = AnthropicClient("sk", "claude-sonnet-4-5", baseUrl)
        val policy = LlmMessage.system("Describe the supplied file.")
        val results = listOf("File one", "File two").map { evidence ->
            client.complete(CompletionRequest(listOf(policy, LlmMessage.user(evidence)), tools = listOf(weatherTool)))
        }
        val (first, second) = requests.map { it.second }
        assertEquals(first["system"], second["system"])
        assertEquals(first["tools"], second["tools"])
        assertEquals(json("""{"type":"ephemeral"}"""),
            first.getAsJsonArray("system").single().asJsonObject["cache_control"])
        listOf(first, second).forEach { body ->
            assertFalse(body.has("cache_control"))
            assertEquals(1, Regex("cache_control").findAll(body.toString()).count())
        }
        assertEquals(listOf("File one", "File two"), listOf(first, second).map {
            it.getAsJsonArray("messages").single().asJsonObject["content"].asString
        })
        assertEquals(LlmUsage(1120, 1, 1121, cachedInputTokens = 0, cacheWriteTokens = 1100), results[0].usage)
        assertEquals(LlmUsage(1130, 1, 1131, cachedInputTokens = 1100, cacheWriteTokens = 0), results[1].usage)
    }

    @Test fun `Anthropic maps tool choices and omits unset or unsupported options`() = runBlocking {
        val ok = """{"stop_reason":"end_turn","content":[{"type":"text","text":"Hi"}]}"""
        respond("/v1/messages", 200 to ok)
        val client = AnthropicClient("sk", "claude-opus-4-7", baseUrl, defaultMaxTokens = 1000)
        val user = listOf(LlmMessage.user("Hi"))
        client.complete(CompletionRequest(user, temperature = 0.5, maxTokens = 77,
            responseFormat = json("""{"type":"json_object"}"""), toolChoice = ToolChoice.Required))
        client.complete(CompletionRequest(user, tools = listOf(weatherTool), toolChoice = ToolChoice.Required))
        client.complete(CompletionRequest(user, tools = listOf(weatherTool), toolChoice = ToolChoice.None, parallelToolCalls = false))
        client.complete(CompletionRequest(user, tools = listOf(weatherTool), parallelToolCalls = false))
        client.complete(CompletionRequest(user, tools = listOf(weatherTool)))
        val bodies = requests.map { it.second }
        // No temperature for 4.7+, no response_format, and no tool options without tools.
        assertEquals(setOf("model", "max_tokens", "messages"), bodies[0].keySet())
        assertEquals(77, bodies[0]["max_tokens"].asInt)
        assertEquals(json("""{"type":"any"}"""), bodies[1]["tool_choice"])
        assertEquals(json("""{"type":"none"}"""), bodies[2]["tool_choice"])
        assertEquals(json("""{"type":"auto","disable_parallel_tool_use":true}"""), bodies[3]["tool_choice"])
        assertFalse(bodies[4].has("tool_choice"))
        assertEquals(1000, bodies[4]["max_tokens"].asInt)
    }

    @Test fun `Anthropic skips empty messages and requires tool call IDs`() = runBlocking {
        respond("/v1/messages", 200 to """{"stop_reason":"end_turn","content":[{"type":"text","text":"Hi"}]}""")
        val client = AnthropicClient("sk", "claude-sonnet-4-5", baseUrl)
        client.complete(CompletionRequest(listOf(LlmMessage.user("Hi"), LlmMessage.assistant(""), LlmMessage.user("Again"))))
        val sent = requests.single().second.getAsJsonArray("messages").map { it.asJsonObject["content"].asString }
        assertEquals(listOf("Hi", "Again"), sent)
        val anonymous = LlmMessage.assistant("", listOf(parisCall.copy(id = null)))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.complete(CompletionRequest(listOf(LlmMessage.user("Hi"), anonymous))) }
        }
        assertEquals(1, requests.size)
    }

    @Test fun `Anthropic temperature support follows the model version`() {
        assertTrue(AnthropicJson.rejectsTemperature("claude-opus-4-7"))
        assertTrue(AnthropicJson.rejectsTemperature("claude-sonnet-5-5"))
        assertTrue(AnthropicJson.rejectsTemperature("claude-haiku-5-5-20260101"))
        assertTrue(AnthropicJson.rejectsTemperature("claude-opus-5"))
        assertFalse(AnthropicJson.rejectsTemperature("claude-sonnet-4-20250514"))
        assertFalse(AnthropicJson.rejectsTemperature("claude-sonnet-4-5"))
        assertFalse(AnthropicJson.rejectsTemperature("claude-3-7-sonnet-latest"))
        assertFalse(AnthropicJson.rejectsTemperature("my-proxy-model"))
    }

    @Test fun `Anthropic decodes tool use, thinking blocks and cache-aware usage`() = runBlocking {
        respond("/v1/messages", 200 to """{"model":"claude-sonnet-4-5","stop_reason":"tool_use","content":[
            {"type":"thinking","thinking":"Look it up.","signature":"s1"},
            {"type":"redacted_thinking","data":"opaque"},
            {"type":"text","text":"Checking."},
            {"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"Oslo"}}],
            "usage":{"input_tokens":20,"cache_read_input_tokens":100,"cache_creation_input_tokens":30,"output_tokens":15}}""")
        val result = AnthropicClient("sk", "claude-sonnet-4-5", baseUrl)
            .complete(CompletionRequest(listOf(LlmMessage.user("Weather?")), tools = listOf(weatherTool)))
        assertEquals("Checking.", result.content)
        assertEquals("Look it up.", result.reasoning)
        assertEquals(FinishReason.TOOL_CALLS, result.finishReason)
        assertEquals("claude-sonnet-4-5", result.model)
        assertEquals(listOf("thinking", "redacted_thinking"), result.thinkingBlocks.map { it["type"].asString })
        assertEquals(LlmToolCall("toolu_1", "get_weather", json("""{"city":"Oslo"}""")), result.toolCalls.single())
        assertEquals(LlmUsage(150, 15, 165, cachedInputTokens = 100, cacheWriteTokens = 30), result.usage)
    }

    @Test fun `Anthropic rejects empty, truncated-before-text and malformed responses`() {
        val cases = mapOf(
            """{"stop_reason":"max_tokens","content":[{"type":"thinking","thinking":"..."}]}""" to "truncated",
            """{"stop_reason":"end_turn","content":[]}""" to "Empty LLM response",
            """{"stop_reason":"end_turn"}""" to "Response has no content",
            "not json" to "Malformed Anthropic messages response",
        )
        for ((body, message) in cases) {
            runCatching { server.removeContext("/v1/messages") }
            respond("/v1/messages", 200 to body)
            val error = assertThrows(body, LlmProtocolException::class.java) {
                runBlocking { AnthropicClient("sk", "claude-sonnet-4-5", baseUrl, maxRetries = 0).complete(CompletionRequest(listOf(LlmMessage.user("Hi")))) }
            }
            assertTrue("${error.message} should mention $message", message in error.message.orEmpty())
        }
    }

    @Test fun `Anthropic retries overload, and the traffic log redacts the API key`() = runBlocking {
        respond("/v1/messages",
            529 to """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
            200 to """{"stop_reason":"end_turn","content":[{"type":"text","text":"Hi"}]}""")
        val traffic = ApiTrafficLog("LLM")
        val result = AnthropicClient("sk-ant-secret", "claude-sonnet-4-5", baseUrl, maxRetries = 1, traffic = traffic)
            .complete(CompletionRequest(listOf(LlmMessage.user("Hello"))))
        assertEquals("Hi", result.content)
        assertEquals(listOf(529, 200), traffic.entries().map { it.status })
        assertEquals("<redacted>", traffic.entries()[0].requestHeaders.entries.first { it.key.equals("x-api-key", true) }.value)
        assertFalse(traffic.entries().toString().contains("sk-ant-secret"))
    }

    @Test fun `Anthropic surfaces client errors without retrying`() = runBlocking {
        val body = """{"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long: 210000 tokens > 200000 maximum"}}"""
        respond("/v1/messages", 400 to body)
        val error = assertThrows(LlmApiException::class.java) {
            runBlocking { AnthropicClient("sk", "claude-sonnet-4-5", baseUrl, maxRetries = 2).complete(CompletionRequest(listOf(LlmMessage.user("Hi")))) }
        }
        assertEquals(400 to body, error.statusCode to error.responseBody)
        assertEquals(1, requests.size)
    }

    @Test fun `Anthropic builds its endpoint and validates configuration`() {
        assertEquals(URI("https://api.anthropic.com/v1/messages"), AnthropicClient("sk", "claude-sonnet-4-5").endpoint)
        assertEquals(URI("https://proxy.example/v1/messages"), AnthropicClient("sk", "m", "https://proxy.example/v1").endpoint)
        assertThrows(IllegalArgumentException::class.java) { AnthropicClient("sk", "") }
        assertThrows(IllegalArgumentException::class.java) { AnthropicClient("sk", "m", defaultMaxTokens = 0) }
        assertThrows(IllegalArgumentException::class.java) { AnthropicClient("sk", "m", "http://example.com") }
    }

    @Test fun `every client rejects invalid requests before network work`() = runBlocking {
        respond("/v1/chat/completions", 200 to chatReply("""{"role":"assistant","content":"Hi"}"""))
        respond("/v1/messages", 200 to """{"content":[{"type":"text","text":"Hi"}]}""")
        val clients: List<LlmClient> = listOf(
            OpenAiClient("sk", "gpt-5", baseUrl),
            DeepSeekClient("sk", "deepseek-chat", baseUrl),
            AnthropicClient("sk", "claude-sonnet-4-5", baseUrl),
        )
        val invalid = listOf(
            CompletionRequest(emptyList()),
            CompletionRequest(listOf(LlmMessage.user("Hi")), tools = listOf(weatherTool.copy(name = "bad name"))),
            CompletionRequest(listOf(LlmMessage.user("Hi")), maxTokens = 0),
            CompletionRequest(listOf(LlmMessage(LlmRole.TOOL, "result"))),
        )
        for (client in clients) for (request in invalid) {
            assertThrows("$client $request", IllegalArgumentException::class.java) { runBlocking { client.complete(request) } }
        }
        assertTrue(requests.isEmpty())
    }
}
