package dev.insight.enrichment

import com.google.gson.JsonParser
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.generativeCacheKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CachedJsonTest {
    private val requests = mutableListOf<CompletionRequest>()

    private fun replies(vararg replies: () -> Completion): suspend (CompletionRequest) -> Completion = { request ->
        requests += request
        replies[minOf(requests.size - 1, replies.size - 1)]()
    }

    private val schema = JsonParser.parseString("""{"type":"object"}""").asJsonObject

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
