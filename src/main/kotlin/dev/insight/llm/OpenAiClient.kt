package dev.insight.llm

import com.google.gson.JsonObject
import dev.insight.core.ApiTrafficLog
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

/**
 * OpenAI Chat Completions client (`/v1/chat/completions`), including function tools.
 * [baseUrl] can point at another OpenAI-hosted gateway; for local servers use [LlamaCppClient].
 *
 * [maxTokens][CompletionRequest.maxTokens] is sent as `max_completion_tokens`, the only form reasoning
 * models accept. An unset temperature is left out, which is how models that reject it are served.
 * [serviceTier] is sent as given (`auto`, `default`, `flex`, `priority`). [promptCacheKey] groups requests
 * that share a prompt prefix so OpenAI routes them to the same cache.
 * See [LlamaCppClient] for transport, retry, and traffic-log behaviour.
 */
class OpenAiClient(
    apiKey: String,
    private val model: String,
    baseUrl: String = DEFAULT_BASE_URL,
    private val serviceTier: String? = null,
    private val promptCacheKey: String? = null,
    requestTimeout: Duration = Duration.ofSeconds(600),
    maxRetries: Int = 6,
    httpClient: HttpClient = LlmHttp.sharedHttpClient,
    traffic: ApiTrafficLog? = null,
) : LlmClient {
    init {
        require(model.isNotBlank()) { "Model is required" }
    }

    private val http = LlmHttp(
        chatCompletionsUri(baseUrl), apiKey, mapOf("Authorization" to "Bearer $apiKey"),
        requestTimeout, maxRetries, httpClient, traffic,
    )
    override val endpoint: URI get() = http.endpoint

    override suspend fun complete(request: CompletionRequest): Completion {
        val body = LlmJson.encode(
            request.copy(responseFormat = request.responseFormat?.let(::namedSchema)), model, cachePrompt = false,
            maxTokensKey = "max_completion_tokens",
            // Chat Completions never returns reasoning text, so there is nothing to echo back.
            replayReasoning = false,
        ) {
            serviceTier?.takeIf { it.isNotBlank() }?.let { addProperty("service_tier", it) }
            promptCacheKey?.takeIf { it.isNotBlank() }?.let { addProperty("prompt_cache_key", it) }
        }
        return http.post(body, LlmJson::decode)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

        /** OpenAI rejects a `json_schema` response format without a `name`; other servers do not need one. */
        internal fun namedSchema(format: JsonObject): JsonObject {
            val schema = format.objectOrNull("json_schema")
            if (format.string("type") != "json_schema" || schema == null || schema.has("name")) return format
            return format.deepCopy().apply { getAsJsonObject("json_schema").addProperty("name", "response") }
        }
    }
}
