package dev.insight.decisions

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.sun.net.httpserver.HttpServer
import dev.insight.core.ApiTrafficLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class DecisionsClientTest {
    @get:Rule val timeout = Timeout.seconds(30)

    private lateinit var server: HttpServer
    private val executor = Executors.newCachedThreadPool()
    private lateinit var endpoint: URI
    private val questions = linkedMapOf(
        "urgent" to DecisionQuestion.Noul("Is this urgent?", mapOf(true to JsonPrimitive("Time sensitive"))),
        "team" to DecisionQuestion.Choice("Which team?", linkedMapOf("billing" to null, "tech" to JsonPrimitive("Bugs"))),
        "severity" to DecisionQuestion.Score("How severe?", listOf(JsonPrimitive("Low"), JsonPrimitive("High"))),
    )
    private val request get() = DecisionRequest("Payment failed", questions)
    private val success = """{
        "model":"jev-test",
        "answers":{
            "urgent":{"type":"noul","noul":0.9},
            "team":{"type":"choice","choice":"billing","probabilities":{"billing":0.8,"tech":0.2},"confidence":0.7},
            "severity":{"type":"score","score":0.6,"legend":{"0":"Low","1":"High"},"probabilities":{"0":0.4,"1":0.6},"confidence":0.2}
        },
        "usage":{"input_tokens":120,"output_tokens":30},
        "future_field":true
    }"""

    @Before fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.start()
        endpoint = URI("http://127.0.0.1:${server.address.port}/v1/systemone")
    }

    @After fun stopServer() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun client(retries: Int = 0, timeout: Duration = Duration.ofSeconds(5)) =
        DecisionsClient("test-secret", endpoint, timeout, retries)

    private fun respond(status: Int, body: String, retryAfter: String? = null) {
        server.createContext("/v1/systemone") { exchange ->
            exchange.requestBody.readAllBytes()
            retryAfter?.let { exchange.responseHeaders.add("Retry-After", it) }
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    @Test fun `sends authenticated batched request and decodes all answer types`() = runBlocking {
        val received = CompletableDeferred<Pair<Map<String, List<String>>, String>>()
        server.createContext("/v1/systemone") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            received.complete(exchange.requestHeaders.toMap() to exchange.requestBody.bufferedReader().use { it.readText() })
            val bytes = success.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val state = JsonParser.parseString("""{"text":"Payment failed","items":[1,true,null]}""")
        val result = client().evaluate(DecisionRequest(state, questions, "custom-model"))
        val (headers, body) = withTimeout(5000) { received.await() }
        assertEquals(listOf("Bearer test-secret"), headers.entries.first { it.key.equals("Authorization", true) }.value)
        assertEquals(listOf("application/json"), headers.entries.first { it.key.equals("Content-Type", true) }.value)
        val json = JsonParser.parseString(body).asJsonObject
        assertEquals(state, json["state"])
        assertEquals("custom-model", json["model"].asString)
        val sent = json.getAsJsonObject("questions")
        assertEquals(questions.keys, sent.keySet())
        assertTrue(sent.getAsJsonObject("team").getAsJsonObject("criteria")["billing"].isJsonNull)
        assertEquals("Time sensitive", sent.getAsJsonObject("urgent").getAsJsonObject("criteria")["true"].asString)
        assertEquals("jev-test", result.model)
        assertEquals(DecisionAnswer.Noul(0.9), result.answers["urgent"])
        assertEquals("billing", (result.answers["team"] as DecisionAnswer.Choice).choice)
        assertEquals(0.8, (result.answers["team"] as DecisionAnswer.Choice).probabilities.getValue("billing"), 0.0)
        assertEquals(0.6, (result.answers["severity"] as DecisionAnswer.Score).score, 0.0)
        assertEquals(JsonPrimitive("High"), (result.answers["severity"] as DecisionAnswer.Score).legend["1"])
        assertEquals(DecisionUsage(120, 30), result.usage)
    }

    @Test fun `structured instructions and criteria are preserved`() {
        val instructions = JsonParser.parseString("""{"question":"Which?","context":["a","b"]}""")
        val description = JsonParser.parseString("""["Definition",{"example":"text"}]""")
        val encoded = JsonParser.parseString(DecisionJson.encode(DecisionRequest("text", mapOf(
            "choice" to DecisionQuestion.Choice(instructions, mapOf("a" to description)),
            "noul" to DecisionQuestion.Noul("True?"),
        )))).asJsonObject.getAsJsonObject("questions")
        assertEquals(instructions, encoded.getAsJsonObject("choice")["instructions"])
        assertEquals(description, encoded.getAsJsonObject("choice").getAsJsonObject("criteria")["a"])
        assertFalse(encoded.getAsJsonObject("noul").has("criteria"))
    }

    @Test fun `invalid requests fail before network work`() = runBlocking {
        val invalid = listOf(
            DecisionRequest("text", emptyMap()),
            DecisionRequest(JsonPrimitive(42), questions),
            DecisionRequest("text", mapOf("x" to DecisionQuestion.Choice("Choose", emptyMap()))),
            DecisionRequest("text", mapOf("x" to DecisionQuestion.Score("Rate", listOf(JsonPrimitive("Only"))))),
            DecisionRequest("text", questions, ""),
        )
        for (input in invalid) assertThrows(IllegalArgumentException::class.java) { runBlocking { client().evaluate(input) } }
    }

    @Test fun `HTTP failures retain details without putting them in exception messages`() = runBlocking {
        for (status in listOf(401, 422, 429, 529)) {
            server.removeContextIfPresent()
            respond(status, """{"detail":"test-secret validation detail"}""", "3")
            val error = assertThrows(DecisionApiException::class.java) { runBlocking { client().evaluate(request) } }
            assertEquals(status, error.statusCode)
            assertEquals("3", error.retryAfter)
            assertTrue(error.responseBody.contains("validation detail"))
            assertFalse(error.toString().contains("test-secret"))
        }
    }

    @Test fun `retries rate limits and overloads then succeeds`() = runBlocking {
        val calls = AtomicInteger()
        server.createContext("/v1/systemone") { exchange ->
            exchange.requestBody.readAllBytes()
            val attempt = calls.incrementAndGet()
            val bytes = (if (attempt < 3) "busy" else success).toByteArray()
            exchange.responseHeaders.add("Retry-After", "0")
            exchange.sendResponseHeaders(if (attempt == 1) 429 else if (attempt == 2) 529 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        assertEquals("jev-test", client(retries = 2).evaluate(request).model)
        assertEquals(3, calls.get())
    }

    @Test fun `retries are bounded and authentication failures are not retried`() = runBlocking {
        for (status in listOf(429, 529, 401, 422)) {
            server.removeContextIfPresent()
            val calls = AtomicInteger()
            server.createContext("/v1/systemone") { exchange ->
                calls.incrementAndGet()
                exchange.requestBody.readAllBytes()
                exchange.responseHeaders.add("Retry-After", "0")
                exchange.sendResponseHeaders(status, 0)
                exchange.close()
            }
            assertThrows(DecisionApiException::class.java) { runBlocking { client(retries = 2).evaluate(request) } }
            assertEquals(if (status == 429 || status == 529) 3 else 1, calls.get())
        }
    }

    @Test fun `malformed responses and mismatched answers fail explicitly`() = runBlocking {
        val malformed = listOf("not json", "{}", "[\"test-secret\"]", success.replace("\"noul\":0.9", "\"noul\":2"),
            success.replace("\"type\":\"noul\"", "\"type\":\"choice\""),
            success.replace("\"urgent\":", "\"other\":"), success.replace("\"choice\":\"billing\"", "\"choice\":\"missing\""))
        for (body in malformed) {
            server.removeContextIfPresent()
            respond(200, body)
            val error = assertThrows(DecisionProtocolException::class.java) { runBlocking { client().evaluate(request) } }
            assertFalse(error.stackTraceToString().contains("test-secret"))
        }
    }

    @Test fun `request timeout is propagated`() = runBlocking {
        server.createContext("/v1/systemone") { exchange ->
            exchange.requestBody.readAllBytes()
            Thread.sleep(1000)
            exchange.close()
        }
        assertThrows(HttpTimeoutException::class.java) {
            runBlocking { client(timeout = Duration.ofMillis(100)).evaluate(request) }
        }
        Unit
    }

    @Test fun `timeout then malformed answer then server error recover in one evaluation`() = runBlocking {
        val calls = AtomicInteger()
        server.createContext("/v1/systemone") { exchange ->
            exchange.requestBody.readAllBytes()
            val attempt = calls.incrementAndGet()
            if (attempt == 1) {
                Thread.sleep(1000)
                exchange.close()
            } else {
                val bytes = (if (attempt == 4) success else "{}").toByteArray()
                exchange.responseHeaders.add("Retry-After", "0")
                exchange.sendResponseHeaders(if (attempt == 3) 503 else 200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        assertEquals("jev-test", client(retries = 3, timeout = Duration.ofMillis(300)).evaluate(request).model)
        assertEquals(4, calls.get())
    }

    @Test fun `in flight request and retry wait can be cancelled promptly`() = runBlocking {
        for (retrying in listOf(false, true)) {
            server.removeContextIfPresent()
            val received = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            server.createContext("/v1/systemone") { exchange ->
                calls.incrementAndGet()
                exchange.requestBody.readAllBytes()
                if (retrying) {
                    exchange.responseHeaders.add("Retry-After", "60")
                    exchange.sendResponseHeaders(429, 0)
                    exchange.close()
                }
                received.complete(Unit)
            }
            val job = async { client(retries = 2).evaluate(request) }
            withTimeout(5000) { received.await() }
            if (retrying) delay(100) // Allow the client to enter the Retry-After wait.
            withTimeout(1000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertEquals(1, calls.get())
        }
    }

    @Test fun `traffic log pairs each attempt with its raw response and redacts the key`() = runBlocking {
        val calls = AtomicInteger()
        server.createContext("/v1/systemone") { exchange ->
            exchange.requestBody.readAllBytes()
            val bytes = (if (calls.incrementAndGet() == 1) "busy" else success).toByteArray()
            exchange.responseHeaders.add("Retry-After", "0")
            exchange.sendResponseHeaders(if (calls.get() == 1) 429 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val traffic = ApiTrafficLog("Decisions")
        DecisionsClient("test-secret", endpoint, Duration.ofSeconds(5), 2, traffic = traffic).evaluate(request)
        val (busy, ok) = traffic.entries()
        assertEquals(listOf(0, 1), listOf(busy.attempt, ok.attempt))
        assertEquals(429, busy.status)
        assertEquals("busy", busy.responseBody)
        assertEquals(200, ok.status)
        assertEquals(success, ok.responseBody)
        assertEquals(DecisionJson.encode(request), ok.requestBody)
        assertFalse(traffic.entries().toString().contains("test-secret"))
        assertEquals("Bearer <redacted>", ok.requestHeaders.entries.first { it.key.equals("Authorization", true) }.value)
    }

    @Test fun `traffic log records rejected responses and transport failures`() = runBlocking {
        val traffic = ApiTrafficLog("Decisions")
        respond(200, "not json")
        assertThrows(DecisionProtocolException::class.java) {
            runBlocking { DecisionsClient("k", endpoint, maxRetries = 0, traffic = traffic).evaluate(request) }
        }
        server.removeContextIfPresent()
        server.createContext("/v1/systemone") { exchange ->
            exchange.requestBody.readAllBytes()
            Thread.sleep(1000)
            exchange.close()
        }
        assertThrows(HttpTimeoutException::class.java) {
            runBlocking { DecisionsClient("k", endpoint, Duration.ofMillis(100), maxRetries = 0, traffic = traffic).evaluate(request) }
        }
        val (rejected, timedOut) = traffic.entries()
        assertEquals("not json", rejected.responseBody)
        assertTrue(rejected.error!!.startsWith("DecisionProtocolException"))
        assertNull(timedOut.status)
        assertTrue(timedOut.error!!.startsWith("HttpTimeoutException"))
        assertFalse(timedOut.pending)
    }

    private fun HttpServer.removeContextIfPresent() {
        runCatching { removeContext("/v1/systemone") }
    }
}
