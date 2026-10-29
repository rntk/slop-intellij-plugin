package dev.insight.llm

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException

/** A successful HTTP response was not a usable chat completion. */
open class LlmProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The output-token limit cut the answer short: a plain retry fails the same way, a larger budget or smaller request may not. */
class LlmTruncatedException(message: String) : LlmProtocolException(message)

/**
 * OpenAI-compatible `/chat/completions` wire format, as served by llama.cpp's `llama-server`, OpenAI, and DeepSeek.
 * Outbound encoding accepts only the internal shape; tolerance for server quirks (JSON-string
 * arguments, `call_id`, `<think>` tags, reasoning fields) is applied only when decoding responses.
 */
internal object LlmJson {
    private val toolName = Regex("[A-Za-z0-9_-]{1,64}")
    private val thinkTag = Regex("<think\\b[^>]*>([\\s\\S]*?)</think>", RegexOption.IGNORE_CASE)

    /** Checks shared by every provider's encoder, so invalid requests fail before network work. */
    fun validate(request: CompletionRequest) {
        require(request.messages.isNotEmpty()) { "At least one message is required" }
        require(request.tools.map { it.name }.toSet().size == request.tools.size) { "Tool names must be unique" }
        request.tools.forEach { require(toolName.matches(it.name)) { "Invalid tool name: ${it.name}" } }
        request.temperature?.let { require(it.isFinite() && it >= 0) { "Temperature must be a non-negative number" } }
        request.maxTokens?.let { require(it > 0) { "Max tokens must be positive" } }
        request.messages.filter { it.role == LlmRole.TOOL }
            .forEach { require(!it.toolCallId.isNullOrBlank()) { "Tool messages require a tool call ID" } }
    }

    /**
     * [maxTokensKey] is `max_tokens` for most servers; OpenAI reasoning models accept only `max_completion_tokens`.
     * [replayReasoning] echoes [LlmMessage.reasoning] as `reasoning_content`. [extra] adds provider-specific fields.
     */
    fun encode(
        request: CompletionRequest,
        model: String,
        cachePrompt: Boolean,
        maxTokensKey: String = "max_tokens",
        replayReasoning: Boolean = true,
        extra: JsonObject.() -> Unit = {},
    ): String {
        validate(request)
        val body = JsonObject()
        if (model.isNotBlank()) body.addProperty("model", model)
        body.add("messages", JsonArray().apply { request.messages.forEach { add(message(it, replayReasoning)) } })
        request.temperature?.let { body.addProperty("temperature", it) }
        request.maxTokens?.let { body.addProperty(maxTokensKey, it) }
        request.responseFormat?.let { body.add("response_format", it) }
        // llama.cpp reuses the KV cache for the shared prompt prefix; other servers ignore the flag.
        if (cachePrompt) body.addProperty("cache_prompt", true)
        // tool_choice and parallel_tool_calls are rejected by some servers when no tools are sent.
        if (request.tools.isNotEmpty()) {
            body.add("tools", JsonArray().apply { request.tools.forEach { add(tool(it)) } })
            request.toolChoice?.let { body.add("tool_choice", toolChoice(it)) }
            request.parallelToolCalls?.let { body.addProperty("parallel_tool_calls", it) }
        }
        body.extra()
        return body.toString()
    }

    private fun message(message: LlmMessage, replayReasoning: Boolean) = JsonObject().apply {
        addProperty("role", message.role.wire)
        addProperty("content", message.content)
        if (message.role == LlmRole.TOOL) addProperty("tool_call_id", message.toolCallId)
        if (replayReasoning && !message.reasoning.isNullOrEmpty()) addProperty("reasoning_content", message.reasoning)
        if (message.toolCalls.isNotEmpty()) {
            add("tool_calls", JsonArray().apply {
                message.toolCalls.forEach { call ->
                    add(JsonObject().apply {
                        call.id?.let { addProperty("id", it) }
                        addProperty("type", "function")
                        add("function", JsonObject().apply {
                            addProperty("name", call.name)
                            addProperty("arguments", call.arguments.toString())
                        })
                    })
                }
            })
        }
    }

    private fun tool(tool: LlmTool) = JsonObject().apply {
        addProperty("type", "function")
        add("function", JsonObject().apply {
            addProperty("name", tool.name)
            addProperty("description", tool.description)
            add("parameters", tool.parameters)
        })
    }

    private fun toolChoice(choice: ToolChoice): JsonElement = when (choice) {
        ToolChoice.Auto -> JsonParser.parseString("\"auto\"")
        ToolChoice.None -> JsonParser.parseString("\"none\"")
        ToolChoice.Required -> JsonParser.parseString("\"required\"")
        is ToolChoice.Function -> JsonObject().apply {
            addProperty("type", "function")
            add("function", JsonObject().apply { addProperty("name", choice.name) })
        }
    }

    fun decode(body: String): Completion = try {
        val root = JsonParser.parseString(body).asJsonObject
        val choice = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
            ?: throw LlmProtocolException("Response has no choices")
        val message = choice.getAsJsonObject("message") ?: throw LlmProtocolException("Response has no message")
        val rawContent = message.string("content").orEmpty()
        val reasoning = (listOf("reasoning_content", "reasoning", "thinking").mapNotNull { message.string(it) } +
            thinkTag.findAll(rawContent).map { it.groupValues[1] })
            .map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n").ifEmpty { null }
        val content = rawContent.replace(thinkTag, "").trim()
        val toolCalls = message.getAsJsonArray("tool_calls")?.map { toolCall(it.asJsonObject) }.orEmpty()
        val finishReason = FinishReason.fromWire(choice.string("finish_reason"))
        if (content.isEmpty() && toolCalls.isEmpty()) {
            // Reasoning tokens count toward the output limit, so a reply can stop before any text.
            if (finishReason == FinishReason.TRUNCATED) throw LlmTruncatedException("LLM response was truncated at the output-token limit")
            throw LlmProtocolException("Empty LLM response")
        }
        Completion(content, reasoning, toolCalls, finishReason, root.string("model"), usage(root))
    } catch (e: LlmProtocolException) {
        throw e
    } catch (e: RuntimeException) {
        throw LlmProtocolException("Malformed chat completion response", e)
    }

    private fun toolCall(call: JsonObject): LlmToolCall {
        val function = call.getAsJsonObject("function") ?: call
        val name = function.string("name").orEmpty()
        return LlmToolCall(call.string("id") ?: call.string("call_id"), name, arguments(function.get("arguments"), name))
    }

    private fun arguments(value: JsonElement?, name: String): JsonObject {
        if (value == null || value.isJsonNull) return JsonObject()
        if (value.isJsonObject) return value.asJsonObject
        val text = value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: throw LlmProtocolException("Arguments for tool call '$name' must be a JSON object")
        if (text.isBlank()) return JsonObject()
        val parsed = try {
            JsonParser.parseString(text)
        } catch (e: RuntimeException) {
            throw LlmProtocolException("Invalid arguments JSON for tool call '$name'", e)
        }
        if (!parsed.isJsonObject) throw LlmProtocolException("Arguments for tool call '$name' must be a JSON object")
        return parsed.asJsonObject
    }

    private fun usage(root: JsonObject): LlmUsage? {
        val usage = root.objectOrNull("usage")
        val timings = root.objectOrNull("timings")
        val cacheN = timings?.int("cache_n")
        val promptN = timings?.int("prompt_n")
        val input = usage?.int("prompt_tokens")
            ?: if (cacheN != null || promptN != null) (cacheN ?: 0) + (promptN ?: 0) else null
        val output = usage?.int("completion_tokens") ?: timings?.int("predicted_n")
        val total = usage?.int("total_tokens") ?: if (input != null || output != null) (input ?: 0) + (output ?: 0) else null
        // DeepSeek reports cache hits at the top level of `usage`; OpenAI nests them under prompt_tokens_details.
        val cached = usage?.int("prompt_cache_hit_tokens")
            ?: usage?.objectOrNull("prompt_tokens_details")?.int("cached_tokens") ?: cacheN
        val reasoning = usage?.objectOrNull("completion_tokens_details")?.int("reasoning_tokens")
        return LlmUsage(input, output, total, cached, reasoningTokens = reasoning)
            .takeIf { input != null || output != null || cached != null }
    }
}

internal fun JsonObject.objectOrNull(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
internal fun JsonObject.string(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
internal fun JsonObject.int(key: String): Int? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
        ?.takeIf { it.isFinite() && it >= 0 && it <= Int.MAX_VALUE }?.toInt()
