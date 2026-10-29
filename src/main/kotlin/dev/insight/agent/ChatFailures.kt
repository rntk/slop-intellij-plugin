package dev.insight.agent

import com.google.gson.JsonParser
import dev.insight.llm.LlmApiException
import java.net.ConnectException
import java.net.http.HttpTimeoutException

/** A short, user-facing explanation of a failed reply. */
fun describeChatFailure(error: Throwable): String = when (error) {
    is LlmApiException -> {
        // llama.cpp and OpenAI-compatible servers explain failures in {"error":{"message":…}}.
        val detail = runCatching {
            JsonParser.parseString(error.responseBody).asJsonObject.getAsJsonObject("error").get("message").asString
        }.getOrNull() ?: error.responseBody
        "the LLM server returned HTTP ${error.statusCode}" + detail.trim().take(300).let { if (it.isEmpty()) "." else ": $it" }
    }
    is HttpTimeoutException -> "the LLM server did not answer in time. Increase the timeout in Settings → Tools → Insight Chat."
    is ConnectException -> "could not connect to the LLM server. Check that it is running and the URL in Settings → Tools → Insight Chat."
    else -> error.message ?: error.javaClass.simpleName
}
