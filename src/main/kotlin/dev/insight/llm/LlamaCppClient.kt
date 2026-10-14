package dev.insight.llm

import dev.insight.core.ApiTrafficLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
fun chatCompletionsUri(baseUrl: String): URI {
    val trimmed = baseUrl.trim().trimEnd('/')
    require(trimmed.isNotEmpty()) { "Server URL is required" }
    val url = when {
        trimmed.endsWith("/chat/completions") -> trimmed
        Regex("/v\\d+$").containsMatchIn(trimmed) -> "$trimmed/chat/completions"
        else -> "$trimmed/v1/chat/completions"
    }
    val uri = try {
        URI(url)
    } catch (e: URISyntaxException) {
        throw IllegalArgumentException("Server URL is not a valid URL", e)
    }
    require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null &&
        uri.query == null && uri.fragment == null) { "Server URL must be an HTTP(S) URL without credentials or query" }
    return uri
}

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
    private val apiKey: String? = null,
    private val cachePrompt: Boolean = true,
    private val requestTimeout: Duration = Duration.ofSeconds(120),
    private val maxRetries: Int = 2,
    private val httpClient: HttpClient = sharedHttpClient,
    private val traffic: ApiTrafficLog? = null,
) {
    val endpoint: URI = chatCompletionsUri(baseUrl)

    init {
        apiKey?.let { require(it.isNotBlank() && '\r' !in it && '\n' !in it) { "API key must be a single non-blank line" } }
        require(apiKey == null || endpoint.scheme == "https" || endpoint.host in localHosts) {
            "Refusing to send an API key to a non-HTTPS server URL"
        }
        require(!requestTimeout.isNegative && !requestTimeout.isZero) { "Request timeout must be positive" }
        require(maxRetries in 0..10) { "Max retries must be between 0 and 10" }
        require(httpClient.followRedirects() == HttpClient.Redirect.NEVER) { "Transport must disable redirects" }
    }

    /**
     * One non-streaming completion. Retries timeouts (408), rate limits (429), and server errors (5xx;
     * llama-server answers 503 while it loads a model). Other HTTP failures throw [LlmApiException];
     * unusable responses throw [LlmProtocolException]; network errors propagate as IO exceptions.
     * A [FinishReason.TRUNCATED] completion is returned as-is so callers can keep the partial text.
     */
    suspend fun complete(request: CompletionRequest): Completion {
        val body = LlmJson.encode(request, model, cachePrompt)
        val httpRequest = HttpRequest.newBuilder(endpoint)
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .apply { apiKey?.let { header("Authorization", "Bearer $it") } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        var attempt = 0
        while (true) {
            val call = traffic?.begin(httpRequest, body, attempt)
            val response = try {
                send(httpRequest)
            } catch (e: Throwable) {
                call?.fail(e)
                throw e
            }
            val status = response.statusCode()
            if (status in 200..299) {
                return try {
                    LlmJson.decode(response.body()).also { call?.respond(response) }
                } catch (e: Throwable) {
                    call?.respond(response, e)
                    throw e
                }
            }
            call?.respond(response)
            val retryAfter = response.headers().firstValue("Retry-After").orElse(null)
            if (!(status == 408 || status == 429 || status in 500..599) || attempt >= maxRetries) {
                throw LlmApiException(status, response.body(), retryAfter)
            }
            delay(retryDelay(retryAfter, attempt++))
        }
    }

    private suspend fun send(request: HttpRequest): HttpResponse<String> = suspendCancellableCoroutine { continuation ->
        val future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        continuation.invokeOnCancellation { future.cancel(true) }
        future.whenComplete { response, error ->
            if (error == null) continuation.resume(response)
            else continuation.resumeWithException(if (error is CompletionException) error.cause ?: error else error)
        }
    }

    private fun retryDelay(header: String?, attempt: Int): Long {
        val fromHeader = header?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { (it * 1000).toLong() }
            ?: header?.let {
                runCatching {
                    Duration.between(ZonedDateTime.now(), ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME))
                        .toMillis().coerceAtLeast(0)
                }.getOrNull()
            }
        return maxOf(fromHeader ?: 0, 1000L shl attempt).coerceAtMost(60_000)
    }

    private companion object {
        val localHosts = setOf("localhost", "127.0.0.1", "[::1]", "::1")
        // llama-server speaks HTTP/1.1; skipping the h2c upgrade attempt avoids confusing plain-HTTP servers.
        val sharedHttpClient: HttpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }
}
