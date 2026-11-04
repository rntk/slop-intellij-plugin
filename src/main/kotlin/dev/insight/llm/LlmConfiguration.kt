package dev.insight.llm

import dev.insight.core.ApiTrafficLog
import dev.insight.core.http.apiUri
import java.net.URI
import java.time.Duration

const val DEFAULT_OUTPUT_TOKENS = 10_000

/** Default llama-server address. */
const val LLAMA_CPP_DEFAULT_BASE_URL = "http://localhost:8080"

enum class LlmProvider(val displayName: String, val defaultBaseUrl: String) {
    LLAMA_CPP("llama.cpp / OpenAI-compatible", LLAMA_CPP_DEFAULT_BASE_URL),
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
    val requestTimeoutSeconds: Int = 600,
    val maxRetries: Int = 6,
    val maxOutputTokens: Int = DEFAULT_OUTPUT_TOKENS,
    val cachePrompt: Boolean = true,
    val serviceTier: String = "",
    val promptCacheKey: String = "",
    /** Total input and output capacity; zero keeps legacy workflow budgets. */
    val contextWindowTokens: Int = 0,
) {
    val baseUrl: String get() = serverUrl.trim().ifEmpty { provider.defaultBaseUrl }
    val endpoint: URI get() = if (provider == LlmProvider.ANTHROPIC) apiUri(baseUrl, "/messages") else chatCompletionsUri(baseUrl)
    val label: String get() = "${provider.displayName} · ${model.trim().ifEmpty { baseUrl }}"

    val defaultOutputTokens: Int get() = if (maxOutputTokens == 0 && provider == LlmProvider.ANTHROPIC) DEFAULT_OUTPUT_TOKENS else maxOutputTokens

    /** Validates configuration without making a request. Also used by the settings dialog on Apply. */
    fun createClient(apiKey: String?, traffic: ApiTrafficLog? = null): LlmClient {
        require(requestTimeoutSeconds in 10..86400) { "Request timeout must be between 10 and 86400 seconds" }
        require(maxRetries in 0..100) { "Max retries must be between 0 and 100" }
        require(contextWindowTokens >= 0) { "Context window must not be negative" }
        require(contextWindowTokens == 0 || defaultOutputTokens < contextWindowTokens) { "Context window must be larger than max output tokens" }
        require(maxOutputTokens >= 0) { "Max output tokens must not be negative" }
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        require(provider == LlmProvider.LLAMA_CPP || key != null) { "API key is required for ${provider.displayName}" }
        val timeout = Duration.ofSeconds(requestTimeoutSeconds.toLong())
        val client = when (provider) {
            LlmProvider.LLAMA_CPP -> LlamaCppClient(baseUrl, model.trim(), key, cachePrompt, timeout, maxRetries, traffic = traffic)
            LlmProvider.OPENAI -> OpenAiClient(key!!, model.trim(), baseUrl, serviceTier, promptCacheKey, timeout, maxRetries, traffic = traffic)
            LlmProvider.DEEPSEEK -> DeepSeekClient(key!!, model.trim(), baseUrl, timeout, maxRetries, traffic = traffic)
            LlmProvider.ANTHROPIC -> AnthropicClient(key!!, model.trim(), baseUrl, serviceTier = serviceTier,
                requestTimeout = timeout, maxRetries = maxRetries, traffic = traffic, cachePrompt = cachePrompt)
        }
        if (maxOutputTokens == 0 && contextWindowTokens == 0) return client
        return object : LlmClient {
            override val endpoint: URI get() = client.endpoint
            override suspend fun complete(request: CompletionRequest): Completion {
                var configured = if (request.maxTokens == null && maxOutputTokens > 0) request.copy(maxTokens = maxOutputTokens) else request
                if (contextWindowTokens > 0 && configured.fitOutputToContext && configured.maxTokens != null) {
                    // Estimate the full serialized input, including the schema, before allocating output. Using
                    // the original budget here is conservative: a smaller budget has no more serialized digits.
                    val inputTokens = (requestChars(configured).toLong() + 3) / 4
                    val available = contextWindowTokens.toLong() - inputTokens
                    if (available <= 0) throw contextLimitException()
                    configured = configured.copy(maxTokens = minOf(configured.maxTokens!!, available.toInt()))
                }
                val outputTokens = configured.maxTokens ?: if (provider == LlmProvider.ANTHROPIC) DEFAULT_OUTPUT_TOKENS else 0
                if (contextWindowTokens > 0 && (outputTokens >= contextWindowTokens ||
                        requestChars(configured) > contextInputChars(contextWindowTokens, outputTokens))) {
                    throw contextLimitException()
                }
                return client.complete(configured)
            }
        }
    }

    private fun contextLimitException() = LlmContextLimitException(
        "Estimated request plus output exceeds the configured context window ($contextWindowTokens tokens). Increase the context window in Insight Chat settings or reduce the input/output budget.")

    private fun requestChars(original: CompletionRequest): Int {
        val request = original.forContextEstimate()
        return when (provider) {
            // Includes replayed thinking blocks, which the OpenAI-compatible representation omits.
            LlmProvider.ANTHROPIC -> AnthropicJson.encode(request, model, DEFAULT_OUTPUT_TOKENS, serviceTier, cachePrompt).length
            LlmProvider.OPENAI -> LlmJson.encode(
                request.copy(responseFormat = request.responseFormat?.let(OpenAiClient::namedSchema)),
                model, cachePrompt = false, maxTokensKey = "max_completion_tokens", replayReasoning = false,
            ) {
                serviceTier.takeIf { it.isNotBlank() }?.let { addProperty("service_tier", it) }
                promptCacheKey.takeIf { it.isNotBlank() }?.let { addProperty("prompt_cache_key", it) }
            }.length
            else -> LlmJson.encode(request, model, cachePrompt).length
        }
    }
}

/** Approximate input budget: four serialized characters per token, reserving the requested output. */
fun contextInputChars(contextWindowTokens: Int, outputTokens: Int = 0, fallback: Int = 64_000): Int {
    require(contextWindowTokens >= 0 && outputTokens >= 0)
    if (contextWindowTokens == 0) return fallback
    return ((contextWindowTokens.toLong() - outputTokens).coerceAtLeast(1) * 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
