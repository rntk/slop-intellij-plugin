package dev.insight.core.http

import java.net.URI
import java.net.URISyntaxException

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

