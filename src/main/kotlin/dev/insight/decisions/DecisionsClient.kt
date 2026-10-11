package dev.insight.decisions

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
 */
class DecisionsClient(
    private val apiKey: String,
    private val endpoint: URI = URI.create("https://api.typesafe.ai/v1/systemone"),
    private val requestTimeout: Duration = Duration.ofSeconds(60),
    private val maxRetries: Int = 2,
    private val httpClient: HttpClient = sharedHttpClient,
) {
    init {
        require(apiKey.isNotBlank() && '\r' !in apiKey && '\n' !in apiKey) { "A valid API key is required" }
        require(endpoint.scheme in setOf("http", "https") && endpoint.host != null &&
            endpoint.userInfo == null && endpoint.fragment == null) { "Endpoint must be an HTTP(S) URI" }
        require(!requestTimeout.isNegative && !requestTimeout.isZero) { "Request timeout must be positive" }
        require(maxRetries in 0..10) { "Max retries must be between 0 and 10" }
        require(httpClient.followRedirects() == HttpClient.Redirect.NEVER) { "Transport must disable redirects" }
    }

    /** One answer per question ID. Retries only documented rate-limit and overload statuses. */
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
        val httpRequest = HttpRequest.newBuilder(endpoint)
            .timeout(requestTimeout)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        var attempt = 0
        while (true) {
            val response = send(httpRequest)
            if (response.statusCode() in 200..299) return DecisionJson.decode(response.body(), questions)
            val retryAfter = response.headers().firstValue("Retry-After").orElse(null)
            if (response.statusCode() !in setOf(429, 529) || attempt >= maxRetries) {
                throw DecisionApiException(response.statusCode(), response.body(), retryAfter)
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
        return (fromHeader ?: (500L shl attempt)).coerceAtMost(60_000)
    }

    private companion object {
        val sharedHttpClient: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }
}
