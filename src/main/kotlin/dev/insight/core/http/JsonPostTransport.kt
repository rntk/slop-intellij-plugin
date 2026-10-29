package dev.insight.core.http

import dev.insight.core.ApiCall
import dev.insight.core.RequestLimiter
import dev.insight.core.ApiTrafficLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared JSON POST lifecycle, with provider-specific response handling. */
internal class JsonPostTransport(
    private val endpoint: URI,
    private val headers: Map<String, String>,
    private val requestTimeout: Duration,
    private val httpClient: HttpClient,
    private val limiter: RequestLimiter,
    private val retryPolicy: RetryPolicy,
    private val traffic: ApiTrafficLog?,
    private val apiException: (Int, String, String?) -> IOException,
) {
    private val logger = com.intellij.openapi.diagnostic.Logger.getInstance(JsonPostTransport::class.java)

    suspend fun <T> post(body: String, decode: (String) -> T, isRetryableDecodeError: (Throwable) -> Boolean): T {
        val httpRequest = HttpRequest.newBuilder(endpoint)
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        var attempt = 0
        while (true) {
            var call: ApiCall? = null
            val response = try {
                limiter.withPermit {
                    call = traffic?.begin(httpRequest, body, attempt)
                    try {
                        send(httpRequest)
                    } catch (e: Throwable) {
                        call?.fail(e)
                        throw e
                    }
                }
            } catch (e: IOException) {
                if (attempt >= retryPolicy.maxRetries) throw e
                logger.info("JSON POST request failed (attempt ${attempt + 1}); retrying", e)
                delay(retryPolicy.delayMillis(null, attempt++))
                continue
            }
            val status = response.statusCode()
            if (status in 200..299) {
                return try {
                    decode(response.body()).also { call?.respond(response) }
                } catch (e: Throwable) {
                    call?.respond(response, e)
                    if (!isRetryableDecodeError(e) || attempt >= retryPolicy.maxRetries) throw e
                    delay(retryPolicy.delayMillis(null, attempt++))
                    continue
                }
            }
            call?.respond(response)
            val retryAfter = response.headers().firstValue("Retry-After").orElse(null)
            if (!(status == 408 || status == 429 || status in 500..599) || attempt >= retryPolicy.maxRetries) {
                logger.warn("JSON POST request to ${httpRequest.uri().host} failed with HTTP $status")
                throw apiException(status, response.body(), retryAfter)
            }
            delay(retryPolicy.delayMillis(retryAfter, attempt++))
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

}
