package dev.insight.llm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.net.InetSocketAddress
import java.net.URI
import java.io.IOException

class LlmConfigurationTest {
    @get:Rule val timeout = Timeout.seconds(30)

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val requests = mutableListOf<Pair<String, JsonObject>>()
    private val credentials = mutableListOf<String?>()
    private var completionText = "Hi"
    private var truncated = false

    @Before fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.path to JsonParser.parseString(exchange.requestBody.bufferedReader().readText()).asJsonObject
            credentials += exchange.requestHeaders.getFirst(if (exchange.requestURI.path.endsWith("/messages")) "x-api-key" else "Authorization")
            val body = if (exchange.requestURI.path.endsWith("/messages"))
                """{"stop_reason":"${if (truncated) "max_tokens" else "end_turn"}","content":[{"type":"text","text":"$completionText"}]}"""
            else """{"choices":[{"finish_reason":"${if (truncated) "length" else "stop"}","message":{"role":"assistant","content":"$completionText"}}]}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After fun stopServer() = server.stop(0)

    @Test fun `context budget reserves explicit output and rejects oversized input before HTTP`() = runBlocking {
        for (provider in LlmProvider.entries) {
            val client = LlmConfiguration(provider, baseUrl, "m", maxOutputTokens = 100,
                contextWindowTokens = 1000).createClient("key")
            client.complete(CompletionRequest(listOf(LlmMessage.user("Hello"))))
            val sent = requests.size
            for (request in listOf(
                CompletionRequest(listOf(LlmMessage.user("x".repeat(5000)))),
                CompletionRequest(listOf(LlmMessage.user("Hello")), maxTokens = 1000),
                CompletionRequest(listOf(LlmMessage.user("x".repeat(1000))), maxTokens = 800),
            )) {
                val error = runCatching { client.complete(request) }.exceptionOrNull()
                assertTrue(error is LlmContextLimitException)
                assertTrue(error!!.message!!.contains("configured context window"))
                assertEquals(sent, requests.size)
            }
            // Explicit analysis budgets may be smaller than the configured default.
            client.complete(CompletionRequest(listOf(LlmMessage.user("x".repeat(3400))), maxTokens = 10))
            assertEquals(sent + 1, requests.size)
        }
    }

    @Test fun `zero context preserves unlimited client input and invalid context output combinations fail`() = runBlocking {
        LlmConfiguration(LlmProvider.LLAMA_CPP, baseUrl, contextWindowTokens = 0).createClient(null)
            .complete(CompletionRequest(listOf(LlmMessage.user("x".repeat(100_000)))))
        for (config in listOf(
            LlmConfiguration(LlmProvider.LLAMA_CPP, contextWindowTokens = -1),
            LlmConfiguration(LlmProvider.LLAMA_CPP, maxOutputTokens = 1000, contextWindowTokens = 1000),
            LlmConfiguration(LlmProvider.ANTHROPIC, model = "m", maxOutputTokens = 0, contextWindowTokens = 1000),
        )) assertTrue(runCatching { config.createClient("key") }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(3600, contextInputChars(1000, 100))
        assertEquals(48_000, contextInputChars(0, 100, fallback = 48_000))
        assertEquals(Int.MAX_VALUE, contextInputChars(Int.MAX_VALUE))
    }

    @Test fun `factory selects each provider and applies default output tokens`() = runBlocking {
        for (provider in LlmProvider.entries) {
            val client = LlmConfiguration(provider, baseUrl, "test-model", maxOutputTokens = 123).createClient("test-key")
            assertEquals("Hi", client.complete(CompletionRequest(listOf(LlmMessage.user("Hello")))).content)
            val (path, body) = requests.last()
            assertEquals(if (provider == LlmProvider.ANTHROPIC) "/v1/messages" else "/v1/chat/completions", path)
            assertEquals("test-model", body["model"].asString)
            assertEquals(123, body[if (provider == LlmProvider.OPENAI) "max_completion_tokens" else "max_tokens"].asInt)
            assertEquals(if (provider == LlmProvider.ANTHROPIC) "test-key" else "Bearer test-key", credentials.last())
        }
    }

    @Test fun `JSON completion fits a small window for every provider`() = runBlocking {
        completionText = "{}"
        for (provider in LlmProvider.entries) {
            val client = LlmConfiguration(provider, baseUrl, "m", maxOutputTokens = 2000,
                contextWindowTokens = 8192).createClient("key")
            assertEquals(JsonObject(), completeJson(client::complete, "s", "u", null, maxTokens = 2048))
            val key = if (provider == LlmProvider.OPENAI) "max_completion_tokens" else "max_tokens"
            assertTrue(requests.last().second[key].asInt in 1..8191)
        }
    }

    @Test fun `JSON output fitting accounts for serialized schema and input`() = runBlocking {
        completionText = "{}"
        for (provider in LlmProvider.entries) {
            val client = LlmConfiguration(provider, baseUrl, "m", maxOutputTokens = 900,
                contextWindowTokens = 1000).createClient("key")
            val schema = JsonSchemas.obj("value" to JsonSchemas.string(description = "x".repeat(1000)))
            completeJson(client::complete, "s", "u".repeat(1000), schema, maxTokens = 1000)
            val body = requests.last().second
            val key = if (provider == LlmProvider.OPENAI) "max_completion_tokens" else "max_tokens"
            val outputTokens = body[key].asInt
            assertTrue(outputTokens in 1..899)
            assertTrue(body.toString().length.toLong() + outputTokens * 4L <= 4000)
        }
    }

    @Test fun `zero output default still lets JSON output fit its configured window`() = runBlocking {
        completionText = "{}"
        val client = LlmConfiguration(LlmProvider.LLAMA_CPP, baseUrl, "m", maxOutputTokens = 0,
            contextWindowTokens = 8192).createClient(null)
        completeJson(client::complete, "s", "u", null, maxTokens = 2048)
        assertTrue(requests.single().second["max_tokens"].asInt in 1..8191)
    }

    @Test fun `JSON truncation retries stay within the configured window and fail as IOException`() = runBlocking {
        completionText = "{"
        truncated = true
        val client = LlmConfiguration(LlmProvider.LLAMA_CPP, baseUrl, "m",
            contextWindowTokens = 32_768).createClient(null)
        val error = try {
            completeJson(client::complete, "s", "u", null, maxTokens = 1000)
            null
        } catch (e: IOException) { e }
        assertTrue(error!!.cause is LlmTruncatedException)
        val budgets = requests.map { it.second["max_tokens"].asInt }
        assertEquals(listOf(10_000, 20_000), budgets.take(2))
        assertTrue(budgets[2] in 20_001..32_767)
        assertEquals(budgets[2], budgets[3])
    }

    @Test fun `explicit analysis budgets take precedence over the configured default`() = runBlocking {
        for (provider in LlmProvider.entries) {
            val client = LlmConfiguration(provider, baseUrl, "m", maxOutputTokens = 123).createClient("test-key")
            client.complete(CompletionRequest(listOf(LlmMessage.user("Hello")), maxTokens = 456))
            assertEquals(456, requests.last().second[if (provider == LlmProvider.OPENAI) "max_completion_tokens" else "max_tokens"].asInt)
        }
    }

    @Test fun `default configuration sends 10000 output tokens for each provider`() = runBlocking {
        for (provider in LlmProvider.entries) {
            LlmConfiguration(provider, baseUrl, "m").createClient("key")
                .complete(CompletionRequest(listOf(LlmMessage.user("Hello"))))
            val key = if (provider == LlmProvider.OPENAI) "max_completion_tokens" else "max_tokens"
            assertEquals(10_000, requests.last().second[key].asInt)
        }
    }

    @Test fun `zero leaves the provider token default and llama credentials are optional`() = runBlocking {
        for (provider in LlmProvider.entries) {
            LlmConfiguration(provider, baseUrl, "m", maxOutputTokens = 0).createClient(if (provider == LlmProvider.LLAMA_CPP) null else "key")
                .complete(CompletionRequest(listOf(LlmMessage.user("Hello"))))
            val body = requests.last().second
            if (provider == LlmProvider.ANTHROPIC) assertEquals(10_000, body["max_tokens"].asInt)
            else assertFalse(body.has(if (provider == LlmProvider.OPENAI) "max_completion_tokens" else "max_tokens"))
        }
        assertNull(credentials.first())
    }

    @Test fun `provider options are sent to the selected client`() = runBlocking {
        LlmConfiguration(LlmProvider.LLAMA_CPP, baseUrl, cachePrompt = false).createClient(null)
            .complete(CompletionRequest(listOf(LlmMessage.user("Hi"))))
        assertFalse(requests.last().second.has("cache_prompt"))
        LlmConfiguration(LlmProvider.OPENAI, baseUrl, "m", serviceTier = "flex", promptCacheKey = "project").createClient("key")
            .complete(CompletionRequest(listOf(LlmMessage.user("Hi"))))
        assertEquals("flex", requests.last().second["service_tier"].asString)
        assertEquals("project", requests.last().second["prompt_cache_key"].asString)
        LlmConfiguration(LlmProvider.ANTHROPIC, baseUrl, "m", serviceTier = "standard_only").createClient("key")
            .complete(CompletionRequest(listOf(LlmMessage.user("Hi"))))
        assertEquals("standard_only", requests.last().second["service_tier"].asString)
    }

    @Test fun `configuration validates required fields and transport before sending`() {
        for (provider in LlmProvider.entries.filter { it != LlmProvider.LLAMA_CPP }) {
            assertThrows(IllegalArgumentException::class.java) { LlmConfiguration(provider, baseUrl, "m").createClient(null) }
            assertThrows(IllegalArgumentException::class.java) { LlmConfiguration(provider, baseUrl).createClient("key") }
        }
        for (configuration in listOf(
            LlmConfiguration(LlmProvider.LLAMA_CPP, "ftp://localhost"),
            LlmConfiguration(LlmProvider.LLAMA_CPP, "http://remote.example"),
            LlmConfiguration(LlmProvider.OPENAI, maxOutputTokens = -1),
            LlmConfiguration(LlmProvider.OPENAI, requestTimeoutSeconds = 0),
            LlmConfiguration(LlmProvider.OPENAI, maxRetries = 101),
        )) assertThrows(IllegalArgumentException::class.java) { configuration.createClient("key") }
        assertTrue(requests.isEmpty())
    }

    @Test fun `Anthropic configuration enables or disables the policy cache breakpoint`() = runBlocking {
        val request = CompletionRequest(listOf(LlmMessage.system("Review the supplied code."), LlmMessage.user("file content")))
        for (enabled in listOf(true, false)) {
            LlmConfiguration(LlmProvider.ANTHROPIC, baseUrl, "m", cachePrompt = enabled).createClient("key").complete(request)
            val body = requests.last().second
            assertFalse(body.has("cache_control"))
            if (enabled) {
                val system = body.getAsJsonArray("system").single().asJsonObject
                assertEquals("Review the supplied code.", system["text"].asString)
                assertEquals("ephemeral", system.getAsJsonObject("cache_control")["type"].asString)
            } else {
                assertEquals("Review the supplied code.", body["system"].asString)
                assertFalse(body.toString().contains("cache_control"))
            }
        }
    }

    @Test fun `blank URL uses the selected providers default endpoint`() {
        assertEquals(URI("https://api.openai.com/v1/chat/completions"), LlmConfiguration(LlmProvider.OPENAI).endpoint)
        assertEquals(URI("https://api.deepseek.com/chat/completions"), LlmConfiguration(LlmProvider.DEEPSEEK).endpoint)
        assertEquals(URI("https://api.anthropic.com/v1/messages"), LlmConfiguration(LlmProvider.ANTHROPIC).endpoint)
    }
}
