package dev.insight.llm

import dev.insight.core.ApiTrafficLog
import dev.insight.core.Endpoints
import java.net.URI
import java.time.Duration

enum class LlmProvider(val displayName: String, val defaultBaseUrl: String) {
    LLAMA_CPP("llama.cpp / OpenAI-compatible", Endpoints.LLM_BASE_URL),
    OPENAI("OpenAI", OpenAiClient.DEFAULT_BASE_URL),
    DEEPSEEK("DeepSeek", DeepSeekClient.DEFAULT_BASE_URL),
    ANTHROPIC("Anthropic", AnthropicClient.DEFAULT_BASE_URL);

    override fun toString(): String = displayName
}

/** A snapshot of the selected provider's options. Credentials stay in the IDE password safe. */
data class LlmConfiguration(
    val provider: LlmProvider,
    val serverUrl: String = "",
    val model: String = "",
    val requestTimeoutSeconds: Int = 120,
    val maxRetries: Int = 2,
    val maxOutputTokens: Int = 0,
    val cachePrompt: Boolean = true,
    val serviceTier: String = "",
    val promptCacheKey: String = "",
) {
    val baseUrl: String get() = serverUrl.trim().ifEmpty { provider.defaultBaseUrl }
    val endpoint: URI get() = if (provider == LlmProvider.ANTHROPIC) apiUri(baseUrl, "/messages") else chatCompletionsUri(baseUrl)
    val label: String get() = "${provider.displayName} · ${model.trim().ifEmpty { baseUrl }}"

    /** Validates configuration without making a request. Also used by the settings dialog on Apply. */
    fun createClient(apiKey: String?, traffic: ApiTrafficLog? = null): LlmClient {
        require(requestTimeoutSeconds in 10..3600) { "Request timeout must be between 10 and 3600 seconds" }
        require(maxRetries in 0..10) { "Max retries must be between 0 and 10" }
        require(maxOutputTokens >= 0) { "Max output tokens must not be negative" }
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        require(provider == LlmProvider.LLAMA_CPP || key != null) { "API key is required for ${provider.displayName}" }
        val timeout = Duration.ofSeconds(requestTimeoutSeconds.toLong())
        val client = when (provider) {
            LlmProvider.LLAMA_CPP -> LlamaCppClient(baseUrl, model.trim(), key, cachePrompt, timeout, maxRetries, traffic = traffic)
            LlmProvider.OPENAI -> OpenAiClient(key!!, model.trim(), baseUrl, serviceTier, promptCacheKey, timeout, maxRetries, traffic = traffic)
            LlmProvider.DEEPSEEK -> DeepSeekClient(key!!, model.trim(), baseUrl, timeout, maxRetries, traffic = traffic)
            LlmProvider.ANTHROPIC -> AnthropicClient(key!!, model.trim(), baseUrl, serviceTier = serviceTier,
                requestTimeout = timeout, maxRetries = maxRetries, traffic = traffic)
        }
        if (maxOutputTokens == 0) return client
        return object : LlmClient {
            override val endpoint: URI get() = client.endpoint
            override suspend fun complete(request: CompletionRequest): Completion =
                client.complete(if (request.maxTokens == null) request.copy(maxTokens = maxOutputTokens) else request)
        }
    }
}
