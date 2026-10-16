package dev.insight.llm

import dev.insight.core.ApiTrafficLog
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

/** HTTP failure. Inspect [responseBody] for server details (llama.cpp explains context overflows there). */
class LlmApiException(
    val statusCode: Int,
    val responseBody: String,
    val retryAfter: String?,
) : IOException("LLM server returned HTTP $statusCode")

/**
 * Builds the chat-completions URL from a user-supplied base. Accepts a host (`http://127.0.0.1:8080`),
 * a versioned base (`…/v1`), or a full `…/chat/completions` URL.
 */
fun chatCompletionsUri(baseUrl: String): URI = apiUri(baseUrl, "/chat/completions")

/**
 * Coroutine client for llama.cpp's OpenAI-compatible `llama-server` (`/v1/chat/completions`), including
 * function tools. It also works with other OpenAI-compatible servers; `cachePrompt` is llama.cpp-specific
 * and ignored elsewhere. No IntelliJ service, settings, or UI dependencies.
 *
 * Calls suspend without blocking the EDT; cancellation cancels the in-flight HTTP future or retry wait.
 * [model] is optional: llama-server answers with its loaded model when it is empty. [apiKey] matches
 * `llama-server --api-key` and is sent only over HTTPS or to localhost.
 * The default HTTP transport is shared. A supplied transport remains caller-owned.
 * When [traffic] is set, every HTTP attempt is recorded there with its raw request and response.
 */
class LlamaCppClient(
    baseUrl: String,
    private val model: String = "",
    apiKey: String? = null,
    private val cachePrompt: Boolean = true,
    requestTimeout: Duration = Duration.ofSeconds(120),
    maxRetries: Int = 2,
    httpClient: HttpClient = LlmHttp.sharedHttpClient,
    traffic: ApiTrafficLog? = null,
) : LlmClient {
    private val http = LlmHttp(
        chatCompletionsUri(baseUrl), apiKey, apiKey?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty(),
        requestTimeout, maxRetries, httpClient, traffic,
    )
    override val endpoint: URI get() = http.endpoint

    /** See [LlmClient.complete]. llama-server answers 503 while it loads a model; that is retried. */
    override suspend fun complete(request: CompletionRequest): Completion =
        http.post(LlmJson.encode(request, model, cachePrompt), LlmJson::decode)
}
