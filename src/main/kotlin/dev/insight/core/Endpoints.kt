package dev.insight.core

import dev.insight.decisions.DecisionsClient
import java.net.http.HttpClient
import java.time.Duration

/**
 * Temporary hardcoded service hosts.
 *
 * Kept here — at the initialisation stage — instead of inside the clients, so
 * [dev.insight.llm.LlamaCppClient] and [dev.insight.decisions.DecisionsClient]
 * stay host-agnostic and their defaults remain unchanged. Call sites must use
 * these constants (or [decisionsClient]) rather than settings or per-call URLs
 * until the hosts become configurable again.
 */
object Endpoints {
    /** Base URL for LLM chat-completions requests. */
    const val LLM_BASE_URL = "http://192.168.0.147:8989"

    /** Decisions API endpoint. */
    val DECISIONS_ENDPOINT: java.net.URI = java.net.URI.create("http://192.168.0.147:18989/v1/systemone")
}

/**
 * Initialisation-stage factory for the Decisions API client, pinned to the
 * hardcoded [Endpoints.DECISIONS_ENDPOINT]. Supply credentials from the
 * caller's credential store. A supplied transport remains caller-owned.
 */
fun decisionsClient(
    apiKey: String,
    requestTimeout: Duration = Duration.ofSeconds(60),
    maxRetries: Int = 2,
    httpClient: HttpClient = sharedHttpClient,
    traffic: ApiTrafficLog? = null,
): DecisionsClient = DecisionsClient(apiKey, Endpoints.DECISIONS_ENDPOINT, requestTimeout, maxRetries, httpClient, traffic)

private val sharedHttpClient: HttpClient by lazy {
    HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
}
