package dev.insight.entrypoints

import com.google.gson.JsonPrimitive
import dev.insight.decisions.*
import org.junit.Assert.*
import org.junit.Test

class EntryPointDecisionCacheTest {
    @Test fun `request identity includes state model instructions and criteria`() {
        val cache = EntryPointDecisionCache()
        val request = DecisionRequest("source", mapOf("entry" to DecisionQuestion.Noul("Is this an entry point?")))
        val key = cache.key(request)
        assertEquals(key, cache.key(request.copy()))
        assertNotEquals(key, cache.key(request.copy(state = JsonPrimitive("changed source"))))
        assertNotEquals(key, cache.key(request.copy(model = "other-model")))
        assertNotEquals(key, cache.key(request.copy(questions = mapOf("entry" to DecisionQuestion.Noul("New instructions")))))
        assertNotEquals(key, cache.key(request.copy(questions = mapOf("entry" to
            DecisionQuestion.Noul("Is this an entry point?", mapOf(true to JsonPrimitive("registered callback")))))))
    }

    @Test fun `cache evicts least recently used judgments and can be cleared`() {
        val cache = EntryPointDecisionCache(maxRequests = 2)
        val response = DecisionResponse("test", mapOf("entry" to DecisionAnswer.Noul(0.9)), DecisionUsage(1, 1))
        cache.putResponse("first", response)
        cache.putResponse("second", response)
        assertEquals(response, cache.response("first"))
        cache.putResponse("third", response)
        assertNull(cache.response("second"))
        assertEquals(response, cache.response("first"))
        assertEquals(response, cache.response("third"))
        cache.clear()
        assertNull(cache.response("first"))
        assertNull(cache.response("third"))
    }
}
