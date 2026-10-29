package dev.insight.enrichment

import com.google.gson.JsonObject
import dev.insight.llm.CompletionFn
import dev.insight.llm.parseJsonObjectLeniently
import dev.insight.llm.completeJson
import dev.insight.llm.generativeCacheKey
import kotlinx.coroutines.CancellationException

/**
 * Answers one request from [cache] or by [completeJson], storing fresh answers. Returns null (and does not cache)
 * when the model fails, so one bad batch never fails a run. [onRequest] is called once per model request made.
 */
internal suspend fun cachedJson(
    cache: EnrichmentCache,
    kind: String,
    complete: CompletionFn,
    system: String,
    user: String,
    schema: JsonObject,
    maxTokens: Int,
    onRequest: () -> Unit,
): JsonObject? {
    val key = generativeCacheKey(kind, system, user, schema.toString())
    cache.text(key)?.let(::parseJsonObjectLeniently)?.let { return it }
    val counted: CompletionFn = { onRequest(); complete(it) }
    return try {
        completeJson(counted, system, user, schema, maxTokens).also { cache.putText(key, it.toString()) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        com.intellij.openapi.diagnostic.Logger.getInstance("dev.insight.enrichment.LlmJsonOutput").warn("Generative request '$kind' failed", e)
        null
    }
}
