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

class LlmConfigurationTest {
    @get:Rule val timeout = Timeout.seconds(30)

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val requests = mutableListOf<Pair<String, JsonObject>>()
    private val credentials = mutableListOf<String?>()

    @Before fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.path to JsonParser.parseString(exchange.requestBody.bufferedReader().readText()).asJsonObject
            credentials += exchange.requestHeaders.getFirst(if (exchange.requestURI.path.endsWith("/messages")) "x-api-key" else "Authorization")
            val body = if (exchange.requestURI.path.endsWith("/messages"))
                """{"stop_reason":"end_turn","content":[{"type":"text","text":"Hi"}]}"""
            else """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Hi"}}]}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After fun stopServer() = server.stop(0)

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

    @Test fun `explicit analysis budgets take precedence over the configured default`() = runBlocking {
        for (provider in LlmProvider.entries) {
            val client = LlmConfiguration(provider, baseUrl, "m", maxOutputTokens = 123).createClient("test-key")
            client.complete(CompletionRequest(listOf(LlmMessage.user("Hello")), maxTokens = 456))
            assertEquals(456, requests.last().second[if (provider == LlmProvider.OPENAI) "max_completion_tokens" else "max_tokens"].asInt)
        }
    }

    @Test fun `zero leaves the provider token default and llama credentials are optional`() = runBlocking {
        for (provider in LlmProvider.entries) {
            LlmConfiguration(provider, baseUrl, "m").createClient(if (provider == LlmProvider.LLAMA_CPP) null else "key")
                .complete(CompletionRequest(listOf(LlmMessage.user("Hello"))))
            val body = requests.last().second
            if (provider == LlmProvider.ANTHROPIC) assertEquals(8192, body["max_tokens"].asInt)
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

    @Test fun `blank URL uses the selected providers default endpoint`() {
        assertEquals(URI("https://api.openai.com/v1/chat/completions"), LlmConfiguration(LlmProvider.OPENAI).endpoint)
        assertEquals(URI("https://api.deepseek.com/chat/completions"), LlmConfiguration(LlmProvider.DEEPSEEK).endpoint)
        assertEquals(URI("https://api.anthropic.com/v1/messages"), LlmConfiguration(LlmProvider.ANTHROPIC).endpoint)
    }
}
