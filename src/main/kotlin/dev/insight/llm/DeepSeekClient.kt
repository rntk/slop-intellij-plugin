package dev.insight.llm

import com.google.gson.JsonObject
import dev.insight.core.ApiTrafficLog
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

/**
 * DeepSeek client for its OpenAI-compatible `/chat/completions` API, including function tools.
 *
 * Thinking mode needs the assistant's `reasoning_content` echoed on tool follow-ups (HTTP 400 when missing),
 * so it is replayed only on requests that carry tools; DeepSeek ignores it elsewhere
 * (https://api-docs.deepseek.com/guides/thinking_mode). DeepSeek supports only the `json_object` response
 * format, so a `json_schema` format is sent as `json_object`; the prompt has to describe the shape.
 * See [LlamaCppClient] for transport, retry, and traffic-log behaviour.
 */
class DeepSeekClient(
    apiKey: String,
    private val model: String,
    baseUrl: String = DEFAULT_BASE_URL,
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
        val format = request.responseFormat?.let { if (it.string("type") == "json_schema") jsonObjectFormat else it }
        val body = LlmJson.encode(
            request.copy(responseFormat = format), model, cachePrompt = false,
            replayReasoning = request.tools.isNotEmpty(),
        )
        return http.post(body, LlmJson::decode)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/chat/completions"
        private val jsonObjectFormat = JsonObject().apply { addProperty("type", "json_object") }
    }
}
