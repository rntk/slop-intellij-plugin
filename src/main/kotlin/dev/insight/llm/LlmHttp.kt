package dev.insight.llm

import dev.insight.core.ApiTrafficLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
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

/** A chat model behind an HTTP API. Implementations translate [CompletionRequest] to their provider's wire format. */
interface LlmClient {
    val endpoint: URI

    /**
     * One non-streaming completion. Retries timeouts (408), rate limits (429), and server errors (5xx).
     * Other HTTP failures throw [LlmApiException]; unusable responses throw [LlmProtocolException];
     * network errors propagate as IO exceptions. A [FinishReason.TRUNCATED] completion is returned as-is
     * so callers can keep the partial text.
     */
    suspend fun complete(request: CompletionRequest): Completion
}

/**
 * Builds an API URL from a user-supplied base. Accepts a host (`https://api.example.com`), a versioned
 * base (`…/v1`), or a full URL already ending in [path].
 */
internal fun apiUri(baseUrl: String, path: String): URI {
    val trimmed = baseUrl.trim().trimEnd('/')
    require(trimmed.isNotEmpty()) { "Server URL is required" }
    val url = when {
        trimmed.endsWith(path) -> trimmed
        Regex("/v\\d+$").containsMatchIn(trimmed) -> "$trimmed$path"
        else -> "$trimmed/v1$path"
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
 * JSON POST with retries, cancellation, and traffic logging, shared by every [LlmClient].
 * [headers] typically carry credentials; [apiKey] is validated against [endpoint] so it only travels over
 * HTTPS or to localhost. A supplied [httpClient] remains caller-owned.
 */
internal class LlmHttp(
    val endpoint: URI,
    apiKey: String?,
    private val headers: Map<String, String>,
    private val requestTimeout: Duration,
    private val maxRetries: Int,
    private val httpClient: HttpClient,
    private val traffic: ApiTrafficLog?,
) {
    init {
        apiKey?.let { require(it.isNotBlank() && '\r' !in it && '\n' !in it) { "API key must be a single non-blank line" } }
        require(apiKey == null || endpoint.scheme == "https" || endpoint.host in localHosts) {
            "Refusing to send an API key to a non-HTTPS server URL"
        }
        require(!requestTimeout.isNegative && !requestTimeout.isZero) { "Request timeout must be positive" }
        require(maxRetries in 0..10) { "Max retries must be between 0 and 10" }
        require(httpClient.followRedirects() == HttpClient.Redirect.NEVER) { "Transport must disable redirects" }
    }

    suspend fun post(body: String, decode: (String) -> Completion): Completion {
        val httpRequest = HttpRequest.newBuilder(endpoint)
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
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
                    decode(response.body()).also { call?.respond(response) }
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

    companion object {
        private val localHosts = setOf("localhost", "127.0.0.1", "[::1]", "::1")
        // llama-server speaks HTTP/1.1; skipping the h2c upgrade attempt avoids confusing plain-HTTP servers.
        val sharedHttpClient: HttpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }
}
