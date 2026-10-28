package dev.insight.decisions

import dev.insight.core.ApiCall
import dev.insight.core.ApiRequestLimits
import dev.insight.core.ApiTrafficLog
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
 * When [traffic] is set, every HTTP attempt is recorded there with its raw request and response.
 */
class DecisionsClient(
    private val apiKey: String,
    private val endpoint: URI = URI.create("https://api.typesafe.ai/v1/systemone"),
    private val requestTimeout: Duration = Duration.ofSeconds(600),
    private val maxRetries: Int = 6,
    private val httpClient: HttpClient = sharedHttpClient,
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
        val httpRequest = HttpRequest.newBuilder(endpoint)
            .timeout(requestTimeout)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        var attempt = 0
        while (true) {
            var call: ApiCall? = null
            val response = try {
                ApiRequestLimits.decisions.withPermit {
                    call = traffic?.begin(httpRequest, body, attempt)
                    try {
                        send(httpRequest)
                    } catch (e: Throwable) {
                        call?.fail(e)
                        throw e
                    }
                }
            } catch (e: IOException) {
                if (attempt >= maxRetries) throw e
                com.intellij.openapi.diagnostic.Logger.getInstance(DecisionsClient::class.java).info("Decisions request failed (attempt ${attempt + 1}); retrying", e)
                delay(retryDelay(null, attempt++))
                continue
            }
            if (response.statusCode() in 200..299) {
                return try {
                    DecisionJson.decode(response.body(), questions).also { call?.respond(response) }
                } catch (e: Throwable) {
                    call?.respond(response, e)
                    if (e !is DecisionProtocolException || attempt >= maxRetries) throw e
                    delay(retryDelay(null, attempt++))
                    continue
                }
            }
            call?.respond(response)
            val retryAfter = response.headers().firstValue("Retry-After").orElse(null)
            if (!(response.statusCode() == 408 || response.statusCode() == 429 || response.statusCode() in 500..599) || attempt >= maxRetries) {
                com.intellij.openapi.diagnostic.Logger.getInstance(DecisionsClient::class.java).warn("Decisions request failed with HTTP ${response.statusCode()}")
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
        return fromHeader ?: (500L shl attempt.coerceAtMost(7)).coerceAtMost(60_000)
    }

    private companion object {
        val sharedHttpClient: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }
}
