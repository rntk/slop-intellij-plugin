package dev.insight.llm

import dev.insight.core.ApiRequestLimits
import dev.insight.core.http.JsonPostTransport
import dev.insight.core.http.RetryPolicy
import dev.insight.core.http.Transports
import dev.insight.core.ApiTrafficLog
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

/** A chat model behind an HTTP API. Implementations translate [CompletionRequest] to their provider's wire format. */
interface LlmClient {
    val endpoint: URI

    /**
     * One non-streaming completion. Retries transport failures, timeouts (408), rate limits (429),
     * server errors (5xx), and malformed or empty responses within the configured retry budget.
     * Other HTTP failures throw [LlmApiException]; unusable responses throw [LlmProtocolException];
     * network errors propagate as IO exceptions. A [FinishReason.TRUNCATED] completion is returned as-is
     * so callers can keep the partial text.
     */
    suspend fun complete(request: CompletionRequest): Completion
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
        require(maxRetries in 0..100) { "Max retries must be between 0 and 100" }
        require(httpClient.followRedirects() == HttpClient.Redirect.NEVER) { "Transport must disable redirects" }
    }

    private val transport = JsonPostTransport(
        endpoint, headers, requestTimeout, httpClient, ApiRequestLimits.llm,
        RetryPolicy(maxRetries, 1000, retryAfterIsMinimum = true), traffic, ::LlmApiException,
    )

    suspend fun post(body: String, decode: (String) -> Completion): Completion =
        transport.post(body, decode) { it is LlmProtocolException && "truncated" !in it.message.orEmpty() }

    companion object {
        private val localHosts = setOf("localhost", "127.0.0.1", "[::1]", "::1")
        val sharedHttpClient: HttpClient = Transports.shared(http1 = true)
    }
}
