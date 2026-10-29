package dev.insight.core.http

import java.net.http.HttpClient
import java.time.Duration

internal object Transports {
    private val defaultClient by lazy { create(false) }
    private val http1Client by lazy { create(true) }

    fun shared(http1: Boolean = false): HttpClient = if (http1) http1Client else defaultClient

    private fun create(http1: Boolean): HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        // Avoid h2c upgrades against plain-HTTP llama-server.
        .apply { if (http1) version(HttpClient.Version.HTTP_1_1) }
        .build()
}
