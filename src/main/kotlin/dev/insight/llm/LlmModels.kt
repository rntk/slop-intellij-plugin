package dev.insight.llm

import com.google.gson.JsonObject

enum class LlmRole(val wire: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant"), TOOL("tool") }

/** A model-issued function call. [arguments] is always a decoded JSON object, never a JSON string. */
data class LlmToolCall(val id: String?, val name: String, val arguments: JsonObject)

/**
 * One message in the provider-neutral shape. Clients serialize it to their wire format.
 * [reasoning] is replayed on assistant tool-call messages; some servers need it to continue a tool loop.
 * [thinkingBlocks] are an Anthropic response's raw `thinking`/`redacted_thinking` blocks, replayed verbatim on
 * assistant tool-call messages; other clients ignore them.
 */
data class LlmMessage(
    val role: LlmRole,
    val content: String = "",
    val reasoning: String? = null,
    val toolCalls: List<LlmToolCall> = emptyList(),
    val toolCallId: String? = null,
    val thinkingBlocks: List<JsonObject> = emptyList(),
) {
    companion object {
        fun system(content: String) = LlmMessage(LlmRole.SYSTEM, content)
        fun user(content: String) = LlmMessage(LlmRole.USER, content)
        fun assistant(
            content: String,
            toolCalls: List<LlmToolCall> = emptyList(),
            reasoning: String? = null,
            thinkingBlocks: List<JsonObject> = emptyList(),
        ) = LlmMessage(LlmRole.ASSISTANT, content, reasoning, toolCalls, thinkingBlocks = thinkingBlocks)
        fun tool(toolCallId: String, content: String) = LlmMessage(LlmRole.TOOL, content, toolCallId = toolCallId)
    }
}

/** A function tool. [parameters] is a JSON Schema object describing the arguments. */
data class LlmTool(val name: String, val description: String, val parameters: JsonObject)

sealed interface ToolChoice {
    data object Auto : ToolChoice
    data object None : ToolChoice
    data object Required : ToolChoice
    data class Function(val name: String) : ToolChoice
}

/**
 * Request options shared by all completion clients. Unset values are left out of the request,
 * so the server's own defaults apply.
 */
data class CompletionRequest(
    val messages: List<LlmMessage>,
    val tools: List<LlmTool> = emptyList(),
    val toolChoice: ToolChoice? = null,
    val parallelToolCalls: Boolean? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    /**
     * OpenAI `response_format`, for example `{"type":"json_object"}` or
     * `{"type":"json_schema","json_schema":{"schema":{...}}}`; llama.cpp enforces it with a grammar.
     */
    val responseFormat: JsonObject? = null,
)

/** Provider finish reasons mapped to one vocabulary. UNKNOWN is not treated as truncation: some servers omit it. */
enum class FinishReason {
    COMPLETE, TRUNCATED, TOOL_CALLS, CONTENT_FILTER, ERROR, UNKNOWN;

    companion object {
        fun fromWire(raw: String?): FinishReason = when (raw?.trim()?.lowercase()) {
            "length", "max_tokens", "model_context_window_exceeded" -> TRUNCATED
            "stop", "end_turn", "stop_sequence", "pause_turn" -> COMPLETE
            "tool_calls", "function_call", "tool_use" -> TOOL_CALLS
            "content_filter", "refusal" -> CONTENT_FILTER
            "error" -> ERROR
            else -> UNKNOWN
        }
    }
}

/**
 * Token counts; null when the server did not report a metric. [inputTokens] includes cached tokens.
 * llama.cpp reports cache hits as `timings.cache_n` and newly evaluated prompt tokens as `timings.prompt_n`.
 * [cacheWriteTokens] are input tokens written to the prompt cache (Anthropic); [reasoningTokens] are part of
 * [outputTokens].
 */
data class LlmUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val cachedInputTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    val reasoningTokens: Int? = null,
)

data class Completion(
    val content: String,
    val reasoning: String? = null,
    val toolCalls: List<LlmToolCall> = emptyList(),
    val finishReason: FinishReason = FinishReason.UNKNOWN,
    val model: String? = null,
    val usage: LlmUsage? = null,
    /** Anthropic thinking blocks; pass them to [LlmMessage.assistant] when continuing a tool loop. */
    val thinkingBlocks: List<JsonObject> = emptyList(),
)

/** Injectable completion port for workflows and request decorators. */
typealias CompletionFn = suspend (CompletionRequest) -> Completion
