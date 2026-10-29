package dev.insight.decisions

import dev.insight.core.ApiRequestLimits
import dev.insight.core.http.JsonPostTransport
import dev.insight.core.http.RetryPolicy
import dev.insight.core.http.Transports
import dev.insight.core.ApiTrafficLog
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

/** HTTP failure. Inspect body explicitly for API validation details; messages omit sensitive content. */
class DecisionApiException(
    val statusCode: Int,
    val responseBody: String,
    val retryAfter: String?,
) : IOException("Decisions API returned HTTP $statusCode")

/**
 * Reusable TypeSafe System One client. No IntelliJ service, settings, or UI dependencies.
 * Calls suspend without blocking the EDT; cancellation cancels the in-flight HTTP future.
 * Supply credentials at construction time from the caller's credential store.
 * The default HTTP transport is shared. A supplied transport remains caller-owned.
 * When [traffic] is set, every HTTP attempt is recorded there with its raw request and response.
 */
class DecisionsClient(
    private val apiKey: String,
    private val endpoint: URI = URI.create("https://api.typesafe.ai/v1/systemone"),
    private val requestTimeout: Duration = Duration.ofSeconds(600),
    private val maxRetries: Int = 6,
    private val httpClient: HttpClient = Transports.shared(),
    private val traffic: ApiTrafficLog? = null,
) {
    init {
        require(apiKey.isNotBlank() && '\r' !in apiKey && '\n' !in apiKey) { "A valid API key is required" }
        require(endpoint.scheme in setOf("http", "https") && endpoint.host != null &&
            endpoint.userInfo == null && endpoint.fragment == null) { "Endpoint must be an HTTP(S) URI" }
        require(!requestTimeout.isNegative && !requestTimeout.isZero) { "Request timeout must be positive" }
        require(maxRetries in 0..100) { "Max retries must be between 0 and 100" }
        require(httpClient.followRedirects() == HttpClient.Redirect.NEVER) { "Transport must disable redirects" }
    }

    /** One answer per question ID. Retries transient HTTP, transport, and invalid-response failures. */
    suspend fun evaluate(request: DecisionRequest): DecisionResponse {
        val body = DecisionJson.encode(request)
        // Snapshot the schema so caller mutation during suspension cannot alter response validation.
        val questions = request.questions.mapValues { (_, question) ->
            when (question) {
                is DecisionQuestion.Noul -> question.copy(criteria = question.criteria?.toMap())
                is DecisionQuestion.Choice -> question.copy(criteria = question.criteria.toMap())
                is DecisionQuestion.Score -> question.copy(criteria = question.criteria.toList())
            }
        }
        return transport.post(body, { DecisionJson.decode(it, questions) }) { it is DecisionProtocolException }
    }

    private val transport = JsonPostTransport(
        endpoint, mapOf("Authorization" to "Bearer $apiKey"), requestTimeout, httpClient,
        ApiRequestLimits.decisions, RetryPolicy(maxRetries, 500, retryAfterIsMinimum = false),
        traffic, ::DecisionApiException,
    )
}
