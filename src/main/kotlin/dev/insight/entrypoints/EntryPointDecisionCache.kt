package dev.insight.entrypoints

import dev.insight.decisions.DecisionJson
import dev.insight.decisions.DecisionRequest
import dev.insight.decisions.DecisionResponse

/** Bounded, session-only judgments keyed by all evidence, questions, criteria and the requested model. */
class EntryPointDecisionCache(private val maxRequests: Int = 1024) {
    init { require(maxRequests > 0) }

    private val responses = object : LinkedHashMap<String, DecisionResponse>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DecisionResponse>?) = size > maxRequests
    }

    internal fun key(request: DecisionRequest): String = EntryPointFinder.digest(DecisionJson.encode(request))
    internal fun response(key: String): DecisionResponse? = synchronized(responses) { responses[key] }
    internal fun putResponse(key: String, response: DecisionResponse) {
        synchronized(responses) { responses[key] = response }
    }

    fun clear() { synchronized(responses) { responses.clear() } }
}
