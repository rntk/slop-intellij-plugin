package dev.insight.llm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import dev.insight.core.ApiTrafficLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class LlamaCppClientTest {
    @get:Rule val timeout = Timeout.seconds(30)

    private lateinit var server: HttpServer
    private val executor = Executors.newCachedThreadPool()
    private lateinit var baseUrl: String
    private val requests = CopyOnWriteArrayList<Pair<Map<String, List<String>>, JsonObject>>()
    private val weatherTool = LlmTool(
        "get_weather", "Weather for a city",
        JsonParser.parseString("""{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}""").asJsonObject,
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

    private fun client(model: String = "", apiKey: String? = null, retries: Int = 0) =
        LlamaCppClient(baseUrl, model, apiKey, requestTimeout = Duration.ofSeconds(5), maxRetries = retries)

    @Test fun `empty and malformed successful responses recover automatically`() = runBlocking {
        respond(200 to "{}", 200 to reply("{\"content\":\"\"}"), 200 to reply("{\"content\":\"Recovered\"}"))
        assertEquals("Recovered", client(retries = 2).complete(CompletionRequest(listOf(LlmMessage.user("Hi")))).content)
        assertEquals(3, requests.size)
    }

    @Test fun `transport timeout recovers on the next attempt`() = runBlocking {
        val calls = AtomicInteger()
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.readAllBytes()
            if (calls.incrementAndGet() == 1) {
                Thread.sleep(1000)
                exchange.close()
            } else {
                val bytes = reply("{\"content\":\"Recovered\"}").toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        val client = LlamaCppClient(baseUrl, requestTimeout = Duration.ofMillis(300), maxRetries = 1)
        assertEquals("Recovered", client.complete(CompletionRequest(listOf(LlmMessage.user("Hi")))).content)
        assertEquals(2, calls.get())
    }

    private fun respond(vararg replies: Pair<Int, String>) {
        val calls = AtomicInteger()
        server.createContext("/v1/chat/completions") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            requests += exchange.requestHeaders.toMap() to JsonParser.parseString(body).asJsonObject
            val (status, text) = replies[minOf(calls.getAndIncrement(), replies.size - 1)]
            val bytes = text.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun reply(message: String, finishReason: String = "stop", extra: String = "") =
        """{"model":"qwen","choices":[{"index":0,"finish_reason":"$finishReason","message":$message}]$extra}"""

    @Test fun `builds the chat completions URL from common base forms`() {
        assertEquals(URI("http://localhost:8080/v1/chat/completions"), chatCompletionsUri(" http://localhost:8080/ "))
        assertEquals(URI("http://localhost:8080/v1/chat/completions"), chatCompletionsUri("http://localhost:8080/v1"))
        assertEquals(URI("https://h/api/v2/chat/completions"), chatCompletionsUri("https://h/api/v2/chat/completions"))
        for (invalid in listOf("", "localhost:8080", "ftp://h", "http://user:pw@h", "http://h/?x=1", "http://h /")) {
            assertThrows(invalid, IllegalArgumentException::class.java) { chatCompletionsUri(invalid) }
        }
    }

    @Test fun `refuses to send an API key over plain HTTP to a remote host`() {
        assertThrows(IllegalArgumentException::class.java) { LlamaCppClient("http://example.com", apiKey = "secret") }
        LlamaCppClient("https://example.com", apiKey = "secret")
        LlamaCppClient("http://localhost:8080", apiKey = "secret")
        LlamaCppClient("http://example.com")
    }

    @Test fun `sends messages, tools and llama cpp options in the OpenAI-compatible shape`() = runBlocking {
        respond(200 to reply("""{"role":"assistant","content":"Sunny."}"""))
        val call = LlmToolCall("call_1", "get_weather", JsonParser.parseString("""{"city":"Paris"}""").asJsonObject)
        val result = client(model = "qwen", apiKey = "secret").complete(CompletionRequest(
            messages = listOf(
                LlmMessage.system("Be brief."),
                LlmMessage.user("Weather in Paris?"),
                LlmMessage.assistant("", listOf(call), reasoning = "Need the tool."),
                LlmMessage.tool("call_1", "18C, sunny"),
            ),
            tools = listOf(weatherTool),
            toolChoice = ToolChoice.Function("get_weather"),
            parallelToolCalls = false,
            temperature = 0.2,
            maxTokens = 256,
        ))
        assertEquals("Sunny.", result.content)
        assertEquals(FinishReason.COMPLETE, result.finishReason)
        val (headers, body) = requests.single()
        assertEquals(listOf("Bearer secret"), headers.entries.first { it.key.equals("Authorization", true) }.value)
        assertEquals("qwen", body["model"].asString)
        assertTrue(body["cache_prompt"].asBoolean)
        assertEquals(0.2, body["temperature"].asDouble, 0.0)
        assertEquals(256, body["max_tokens"].asInt)
        assertFalse(body["parallel_tool_calls"].asBoolean)
        assertEquals("get_weather", body.getAsJsonObject("tool_choice").getAsJsonObject("function")["name"].asString)
        val tool = body.getAsJsonArray("tools")[0].asJsonObject
        assertEquals("function", tool["type"].asString)
        assertEquals(weatherTool.parameters, tool.getAsJsonObject("function")["parameters"])
        val messages = body.getAsJsonArray("messages").map { it.asJsonObject }
        assertEquals(listOf("system", "user", "assistant", "tool"), messages.map { it["role"].asString })
        val assistant = messages[2]
        assertEquals("Need the tool.", assistant["reasoning_content"].asString)
        val sentCall = assistant.getAsJsonArray("tool_calls")[0].asJsonObject
        assertEquals("call_1", sentCall["id"].asString)
        // Arguments go over the wire as a JSON string.
        assertEquals("""{"city":"Paris"}""", sentCall.getAsJsonObject("function")["arguments"].asString)
        assertEquals("call_1", messages[3]["tool_call_id"].asString)
    }

    @Test fun `omits unset options, and tool options when there are no tools`() = runBlocking {
        respond(200 to reply("""{"role":"assistant","content":"Hi"}"""))
        LlamaCppClient(baseUrl, cachePrompt = false).complete(CompletionRequest(
            listOf(LlmMessage.user("Hi")), toolChoice = ToolChoice.None, parallelToolCalls = true,
        ))
        val (headers, body) = requests.single()
        assertEquals(setOf("messages"), body.keySet())
        assertTrue(headers.keys.none { it.equals("Authorization", true) })
    }

    @Test fun `sends the response format as given`() = runBlocking {
        respond(200 to reply("""{"role":"assistant","content":"{}"}"""))
        val format = JsonParser.parseString("""{"type":"json_schema","json_schema":{"schema":{"type":"object"}}}""").asJsonObject
        client().complete(CompletionRequest(listOf(LlmMessage.user("Hi")), responseFormat = format))
        assertEquals(format, requests.single().second["response_format"])
    }

    @Test fun `decodes tool calls, reasoning, think tags and llama cpp usage`() = runBlocking {
        respond(200 to reply(
            """{"role":"assistant","content":"<think>Plan it.</think>Checking.","reasoning_content":"Look up weather.",
                "tool_calls":[
                    {"id":"a","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Oslo\"}"}},
                    {"call_id":"b","function":{"name":"get_weather","arguments":{"city":"Rome"}}},
                    {"function":{"name":"get_weather","arguments":""}}
                ]}""",
            finishReason = "tool_calls",
            extra = ""","timings":{"cache_n":100,"prompt_n":20,"predicted_n":7}""",
        ))
        val result = client().complete(CompletionRequest(listOf(LlmMessage.user("Weather?")), tools = listOf(weatherTool)))
        assertEquals("Checking.", result.content)
        assertEquals("Look up weather.\n\nPlan it.", result.reasoning)
        assertEquals(FinishReason.TOOL_CALLS, result.finishReason)
        assertEquals("qwen", result.model)
        assertEquals(listOf("a", "b", null), result.toolCalls.map { it.id })
        assertEquals("Oslo", result.toolCalls[0].arguments["city"].asString)
        assertEquals("Rome", result.toolCalls[1].arguments["city"].asString)
        assertEquals(JsonObject(), result.toolCalls[2].arguments)
        assertEquals(LlmUsage(inputTokens = 120, outputTokens = 7, totalTokens = 127, cachedInputTokens = 100), result.usage)
    }

    @Test fun `prefers reported usage over timings`() = runBlocking {
        respond(200 to reply("""{"role":"assistant","content":"Hi"}""", extra = ""","usage":{"prompt_tokens":50,"completion_tokens":5,"total_tokens":55,"prompt_tokens_details":{"cached_tokens":40}}"""))
        assertEquals(LlmUsage(50, 5, 55, 40), client().complete(CompletionRequest(listOf(LlmMessage.user("Hi")))).usage)
    }

    @Test fun `keeps truncated text and rejects empty or malformed responses`() = runBlocking {
        respond(200 to reply("""{"role":"assistant","content":"Partial"}""", finishReason = "length"))
        assertEquals(FinishReason.TRUNCATED, client().complete(CompletionRequest(listOf(LlmMessage.user("Hi")))).finishReason)
        val invalid = listOf(
            reply("""{"role":"assistant","content":"<think>only thinking</think>"}""", finishReason = "length"),
            reply("""{"role":"assistant","content":""}"""),
            reply("""{"role":"assistant","tool_calls":[{"function":{"name":"x","arguments":"{not json"}}]}"""),
            reply("""{"role":"assistant","tool_calls":[{"function":{"name":"x","arguments":"[1]"}}]}"""),
            """{"choices":[]}""",
            "not json",
        )
        for (body in invalid) {
            server.removeContext("/v1/chat/completions")
            respond(200 to body)
            assertThrows(body, LlmProtocolException::class.java) {
                runBlocking { client().complete(CompletionRequest(listOf(LlmMessage.user("Hi")))) }
            }
        }
        assertThrows(LlmProtocolException::class.java) {
            runBlocking { client().complete(CompletionRequest(listOf(LlmMessage.user("Hi")))) }
        }.also { assertEquals("Malformed chat completion response", it.message) }
        Unit
    }

    @Test fun `retries server errors such as a loading model, then succeeds`() = runBlocking {
        respond(503 to """{"error":{"message":"Loading model"}}""", 200 to reply("""{"role":"assistant","content":"Ready"}"""))
        assertEquals("Ready", client(retries = 1).complete(CompletionRequest(listOf(LlmMessage.user("Hi")))).content)
        assertEquals(2, requests.size)
    }

    @Test fun `does not retry client errors and exposes the server message`() = runBlocking {
        val body = """{"error":{"code":400,"message":"the request exceeds the available context size"}}"""
        respond(400 to body)
        val error = assertThrows(LlmApiException::class.java) {
            runBlocking { client(retries = 2).complete(CompletionRequest(listOf(LlmMessage.user("Hi")))) }
        }
        assertEquals(400, error.statusCode)
        assertEquals(body, error.responseBody)
        assertEquals(1, requests.size)
    }

    @Test fun `invalid requests fail before network work`() = runBlocking {
        respond(200 to reply("""{"role":"assistant","content":"Hi"}"""))
        val user = listOf(LlmMessage.user("Hi"))
        val invalid = listOf(
            CompletionRequest(emptyList()),
            CompletionRequest(user, tools = listOf(weatherTool, weatherTool)),
            CompletionRequest(user, tools = listOf(weatherTool.copy(name = "bad name"))),
            CompletionRequest(user, temperature = Double.NaN),
            CompletionRequest(user, maxTokens = 0),
            CompletionRequest(listOf(LlmMessage(LlmRole.TOOL, "result"))),
        )
        for (request in invalid) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { client().complete(request) } }
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun `cancellation aborts the in-flight request`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        server.createContext("/v1/chat/completions") { exchange ->
            started.complete(Unit)
            Thread.sleep(10_000)
            exchange.close()
        }
        withTimeout(5_000) {
            val job = async { client().complete(CompletionRequest(listOf(LlmMessage.user("Hi")))) }
            started.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }
    }

    @Test fun `traffic log records each attempt with raw bodies and without the API key`() = runBlocking {
        respond(503 to """{"error":"loading model"}""", 200 to reply("""{"role":"assistant","content":"Hi"}"""))
        val traffic = ApiTrafficLog("LLM")
        LlamaCppClient(baseUrl, apiKey = "secret", maxRetries = 1, traffic = traffic)
            .complete(CompletionRequest(listOf(LlmMessage.user("Hello"))))
        val (loading, ok) = traffic.entries()
        assertEquals(503 to 200, loading.status to ok.status)
        assertEquals("""{"error":"loading model"}""", loading.responseBody)
        assertTrue(ok.responseBody!!.contains("\"Hi\""))
        assertEquals("Hello", JsonParser.parseString(ok.requestBody).asJsonObject.getAsJsonArray("messages")[0].asJsonObject["content"].asString)
        assertEquals(listOf(0, 1), traffic.entries().map { it.attempt })
        assertFalse(traffic.entries().toString().contains("secret"))
    }
}
