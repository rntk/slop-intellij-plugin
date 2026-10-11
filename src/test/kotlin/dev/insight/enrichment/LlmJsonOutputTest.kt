package dev.insight.enrichment

import com.google.gson.JsonParser
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.FinishReason
import dev.insight.llm.LlmApiException
import dev.insight.llm.LlmProtocolException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LlmJsonOutputTest {
    private val requests = mutableListOf<CompletionRequest>()

    private fun replies(vararg replies: () -> Completion): suspend (CompletionRequest) -> Completion = { request ->
        requests += request
        replies[minOf(requests.size - 1, replies.size - 1)]()
    }

    private val schema = JsonParser.parseString("""{"type":"object"}""").asJsonObject

    @Test fun `parses fenced JSON and sends the schema as response format`() = runBlocking {
        val result = completeJson(replies({ Completion("Here:\n```json\n{\"a\": 1}\n```") }), "sys", "user", schema, 1000)
        assertEquals(1, result["a"].asInt)
        val request = requests.single()
        assertEquals("json_schema", request.responseFormat!!["type"].asString)
        assertEquals(schema, request.responseFormat!!.getAsJsonObject("json_schema")["schema"])
        assertEquals(listOf("sys", "user"), request.messages.map { it.content })
        assertEquals(1000, request.maxTokens)
    }

    @Test fun `asks for any JSON object without a schema`() = runBlocking {
        completeJson(replies({ Completion("{}") }), "s", "u", null, 100)
        assertEquals("""{"type":"json_object"}""", requests.single().responseFormat.toString())
    }

    @Test fun `retries garbage once and doubles the budget after truncation`() = runBlocking {
        assertEquals(2, completeJson(replies({ Completion("no json here") }, { Completion("{\"b\":2}") }), "s", "u", null, 500)["b"].asInt)
        requests.clear()
        completeJson(replies({ Completion("{\"b\":", finishReason = FinishReason.TRUNCATED) }, { Completion("{}") }), "s", "u", null, 500)
        assertEquals(listOf(500, 1000), requests.map { it.maxTokens })
        requests.clear()
        completeJson(replies({ throw LlmProtocolException("LLM response was truncated at the output-token limit") }, { Completion("{}") }),
            "s", "u", null, 500)
        assertEquals(listOf(500, 1000), requests.map { it.maxTokens })
    }

    @Test fun `fails clearly after the retry, and does not retry HTTP errors`() = runBlocking {
        val error = try {
            completeJson(replies({ Completion("[1, 2]") }), "s", "u", null, 100); null
        } catch (e: IOException) { e }
        assertTrue(error!!.message!!.contains("No JSON object after 2 attempts"))
        assertEquals(2, requests.size)
        requests.clear()
        assertThrows(LlmApiException::class.java) {
            runBlocking { completeJson(replies({ throw LlmApiException(400, "context", null) }), "s", "u", null, 100) }
        }
        assertEquals(1, requests.size)
    }

    @Test fun `lenient parsing ignores surrounding text and rejects non-objects`() {
        assertEquals(1, parseJsonObjectLeniently("Sure! {\"x\": {\"y\": 1}} done")!!.getAsJsonObject("x")["y"].asInt)
        assertNull(parseJsonObjectLeniently("nothing"))
        assertNull(parseJsonObjectLeniently("{\"x\": "))
        assertNull(parseJsonObjectLeniently("} {"))
    }

    @Test fun `cached answers are reused and failures are not cached`() = runBlocking {
        val cache = InMemoryEnrichmentCache()
        var counted = 0
        val first = cachedJson(cache, "k", replies({ Completion("{\"v\":1}") }), "s", "u", schema, 100) { counted++ }
        val second = cachedJson(cache, "k", replies({ Completion("{\"v\":2}") }), "s", "u", schema, 100) { counted++ }
        assertEquals(first, second)
        assertEquals(1, counted)
        assertNull(cachedJson(cache, "k", replies({ Completion("bad") }), "s", "other", schema, 100) { counted++ })
        assertNull(cache.text(generativeCacheKey("k", "s", "other", schema.toString())))
    }
}
