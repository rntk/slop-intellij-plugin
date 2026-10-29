package dev.insight.chat

import com.sun.net.httpserver.HttpServer
import dev.insight.decisions.DEFAULT_DECISIONS_URL
import dev.insight.decisions.DecisionApiException
import dev.insight.decisions.DecisionQuestion
import dev.insight.decisions.DecisionRequest
import dev.insight.llm.LlmConfiguration
import dev.insight.llm.LlmProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.Collections

class DefaultEndpointsTest {
    @Test fun `llama cpp defaults to a local server`() {
        assertEquals("http://localhost:8080", LlmProvider.LLAMA_CPP.defaultBaseUrl)
        assertEquals(URI.create("http://localhost:8080/v1/chat/completions"), LlmConfiguration(LlmProvider.LLAMA_CPP).endpoint)
    }

    @Test fun `decisions defaults to a local server`() {
        assertEquals("http://localhost:18989", DEFAULT_DECISIONS_URL)
        assertEquals("", ChatSettings.State().decisionsUrl.orEmpty())
        ChatSettings.decisionsClient("", null)
        ChatSettings.decisionsClient("localhost:18989", "secret")
    }

    @Test fun `decisions client rejects invalid URLs and keys`() {
        assertThrows(IllegalArgumentException::class.java) { ChatSettings.decisionsClient("ftp://decisions.example", null) }
        assertThrows(IllegalArgumentException::class.java) { ChatSettings.decisionsClient("", "bad\nkey") }
        assertThrows(IllegalArgumentException::class.java) { ChatSettings.decisionsClient("http://decisions.example", "secret") }
        assertThrows(IllegalArgumentException::class.java) { ChatSettings.decisionsClient("decisions.example:18989", "secret") }
        assertThrows(IllegalArgumentException::class.java) { ChatSettings.decisionsClient("http://decisions.example/v1/systemone", "secret") }
        ChatSettings.decisionsClient("http://decisions.example", null)
        ChatSettings.decisionsClient("https://decisions.example", "secret")
    }

    @Test fun `decisions client adds the systemone path and sends a placeholder token when the key is blank`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val received = Collections.synchronizedList(mutableListOf<String>())
        server.createContext("/v1/systemone") { exchange ->
            exchange.requestBody.readAllBytes()
            received += exchange.requestHeaders.getFirst("Authorization")
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        server.start()
        try {
            val hostPort = "127.0.0.1:${server.address.port}"
            val request = DecisionRequest("state", mapOf("q" to DecisionQuestion.Noul("Question?")))
            fun post(url: String, key: String?) {
                val client = ChatSettings.decisionsClient(url, key, Duration.ofSeconds(5), maxRetries = 0)
                assertThrows(DecisionApiException::class.java) { runBlocking { client.evaluate(request) } }
            }
            for (key in listOf(null, " ", "secret")) post(" http://$hostPort ", key)
            assertEquals(listOf("Bearer local", "Bearer local", "Bearer secret"), received)

            received.clear()
            post(hostPort, null)
            post("http://$hostPort/v1/systemone", null)
            post("http://$hostPort/v1", null)
            assertEquals(listOf("Bearer local", "Bearer local", "Bearer local"), received)
        } finally { server.stop(0) }
    }
}
