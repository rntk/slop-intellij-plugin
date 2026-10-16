package dev.insight.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.Optional
import javax.net.ssl.SSLSession

class ApiTrafficLogTest {
    @get:Rule val folder = TemporaryFolder()

    private val request = HttpRequest.newBuilder(URI("http://localhost/v1/x"))
        .header("Authorization", "Bearer s3cret").header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.noBody()).build()

    private fun response(status: Int, body: String) = object : HttpResponse<String> {
        override fun statusCode() = status
        override fun request() = request
        override fun previousResponse() = Optional.empty<HttpResponse<String>>()
        override fun headers() = HttpHeaders.of(mapOf("Content-Type" to listOf("application/json"))) { _, _ -> true }
        override fun body() = body
        override fun sslSession() = Optional.empty<SSLSession>()
        override fun uri() = request.uri()
        override fun version() = HttpClient.Version.HTTP_1_1
    }

    @Test fun `pairs requests with responses, persists them and reloads after restart`() {
        val file = folder.root.toPath().resolve("logs/llm.jsonl")
        val log = ApiTrafficLog("LLM", file)
        var notified = 0
        log.subscribe { notified++ }
        val first = log.begin(request, """{"q":1}""", 0)
        val second = log.begin(request, """{"q":2}""", 1)
        assertTrue(log.entries().all { it.pending })
        second.fail(IOException("refused"))
        first.respond(response(200, """{"a":1}"""))
        assertEquals(4, notified)

        val reloaded = ApiTrafficLog("LLM", file).entries()
        assertEquals(log.entries(), reloaded)
        val (ok, failed) = reloaded
        assertEquals("""{"q":1}""", ok.requestBody)
        assertEquals("""{"a":1}""", ok.responseBody)
        assertEquals(200, ok.status)
        assertEquals("IOException: refused", failed.error)
        assertEquals(1, failed.attempt)
        assertFalse(Files.readString(file).contains("s3cret"))
        // IDs continue after a restart so exported logs stay unambiguous.
        val restarted = ApiTrafficLog("LLM", file).apply { begin(request, "", 0) }
        assertEquals(3L, restarted.entries().last().id)
    }

    @Test fun `history is bounded in memory and on disk and can be cleared`() {
        val file = folder.root.toPath().resolve("d.jsonl")
        val log = ApiTrafficLog("Decisions", file, maxEntries = 3)
        repeat(10) { log.begin(request, "{\"n\":$it}", 0).respond(response(200, "{}")) }
        assertEquals(listOf("{\"n\":7}", "{\"n\":8}", "{\"n\":9}"), log.entries().map { it.requestBody })
        assertTrue(Files.readAllLines(file).size <= 6)
        assertEquals(log.entries().map { it.id }, ApiTrafficLog("Decisions", file, maxEntries = 3).entries().map { it.id })
        log.clear()
        assertTrue(log.entries().isEmpty())
        assertFalse(Files.exists(file))
    }

    @Test fun `cleared pending calls cannot recreate history but new calls are persisted`() {
        val file = folder.root.toPath().resolve("llm.jsonl")
        val log = ApiTrafficLog("LLM", file, maxEntries = 2)
        val evicted = log.begin(request, "evicted", 0)
        val pendingResponse = log.begin(request, "pending response", 0)
        val pendingFailure = log.begin(request, "pending failure", 0)
        log.clear()

        evicted.respond(response(200, "{}"))
        pendingResponse.respond(response(200, "{}"))
        pendingFailure.fail(IOException("refused"))
        assertTrue(log.entries().isEmpty())
        assertFalse(Files.exists(file))
        assertTrue(ApiTrafficLog("LLM", file).entries().isEmpty())

        val fresh = log.begin(request, "fresh", 0)
        fresh.respond(response(200, "{}"))
        pendingResponse.respond(response(200, "{}"))
        assertEquals(listOf("fresh"), log.entries().map { it.requestBody })
        assertEquals(log.entries(), ApiTrafficLog("LLM", file).entries())
    }

    @Test fun `reload retains newest requests when completions arrive out of order`() {
        val file = folder.root.toPath().resolve("llm.jsonl")
        val log = ApiTrafficLog("LLM", file, maxEntries = 2)
        val calls = (1..4).map { log.begin(request, "request $it", 0) }
        calls.reversed().forEach { it.respond(response(200, "{}")) }

        val reloaded = ApiTrafficLog("LLM", file, maxEntries = 2)
        assertEquals(listOf(3L, 4L), reloaded.entries().map { it.id })
        assertEquals(log.entries(), reloaded.entries())
        reloaded.begin(request, "next", 0)
        assertEquals(5L, reloaded.entries().last().id)
    }

    @Test fun `exports embed JSON bodies as JSON and keep other bodies as text`() {
        val log = ApiTrafficLog("LLM")
        log.begin(request, """{"messages":[]}""", 0).respond(response(500, "upstream down"))
        val exported = JsonParser.parseString(ApiTrafficLog.toPrettyJson(log.entries())).asJsonArray[0].asJsonObject
        assertTrue(exported.getAsJsonObject("request")["body"].isJsonObject)
        assertEquals("upstream down", exported.getAsJsonObject("response")["body"].asString)
        assertEquals(500, exported.getAsJsonObject("response")["status"].asInt)
        val text = ApiTrafficLog.toText(log.entries().single())
        assertTrue(text.contains("REQUEST POST http://localhost/v1/x"))
        assertTrue(text.contains("RESPONSE 500"))
        assertTrue(text.contains("Bearer <redacted>"))
    }

    @Test fun `redacts credentials without a scheme and in proxy and key headers`() {
        val credentials = HttpRequest.newBuilder(URI("http://localhost/v1/x"))
            .header("Authorization", "bare-s3cret").header("Proxy-Authorization", "Basic cHJveHk=")
            .header("x-api-key", "sk-ant-s3cret").header("api-key", "azure-s3cret")
            .POST(HttpRequest.BodyPublishers.noBody()).build()
        val log = ApiTrafficLog("LLM")
        log.begin(credentials, "", 0)
        val headers = log.entries().single().requestHeaders.mapKeys { it.key.lowercase() }
        assertEquals("<redacted>", headers["authorization"])
        assertEquals("Basic <redacted>", headers["proxy-authorization"])
        assertEquals("<redacted>", headers["x-api-key"])
        assertEquals("<redacted>", headers["api-key"])
        assertFalse(log.entries().toString().contains("s3cret"))
        assertFalse(log.entries().toString().contains("cHJveHk="))
    }
}
