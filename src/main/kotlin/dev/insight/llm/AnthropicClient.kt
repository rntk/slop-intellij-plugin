package dev.insight.llm

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.insight.core.ApiTrafficLog
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

/**
 * Anthropic Messages API client (`/v1/messages`), including function tools.
 *
 * System messages are hoisted to the top-level `system` field, tool calls and results become `tool_use` and
 * `tool_result` blocks, and the [thinking blocks][Completion.thinkingBlocks] of a tool-calling turn must be
 * passed back on the assistant message: the API rejects a tool loop that drops them.
 *
 * The API requires `max_tokens`; [defaultMaxTokens] applies when the request sets none. `temperature` is left
 * out for models that reject it (Claude 4.7 and later). [CompletionRequest.responseFormat] is not sent:
 * Anthropic's structured outputs require strict schemas, so JSON output relies on the prompt instead.
 * [serviceTier] is sent as given (`auto` or `standard_only`).
 * With [cachePrompt] and a system policy, a single explicit 5-minute cache breakpoint covers that policy
 * and preceding tool definitions.
 * Per-request evidence is left uncached to avoid paying for cache writes on changing file content.
 * See [LlamaCppClient] for transport, retry, and traffic-log behaviour; 529 (overloaded) is retried.
 */
class AnthropicClient(
    apiKey: String,
    private val model: String,
    baseUrl: String = DEFAULT_BASE_URL,
    private val defaultMaxTokens: Int = 8192,
    private val serviceTier: String? = null,
    requestTimeout: Duration = Duration.ofSeconds(600),
    maxRetries: Int = 6,
    httpClient: HttpClient = LlmHttp.sharedHttpClient,
    traffic: ApiTrafficLog? = null,
    private val cachePrompt: Boolean = true,
) : LlmClient {
    init {
        require(model.isNotBlank()) { "Model is required" }
        require(defaultMaxTokens > 0) { "Default max tokens must be positive" }
    }

    private val http = LlmHttp(
        apiUri(baseUrl, "/messages"), apiKey, mapOf("x-api-key" to apiKey, "anthropic-version" to API_VERSION),
        requestTimeout, maxRetries, httpClient, traffic,
    )
    override val endpoint: URI get() = http.endpoint

    override suspend fun complete(request: CompletionRequest): Completion =
        http.post(AnthropicJson.encode(request, model, defaultMaxTokens, serviceTier, cachePrompt), AnthropicJson::decode)

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        const val API_VERSION = "2023-06-01"
    }
}

/** Anthropic Messages wire format. Like [LlmJson], encoding accepts only the internal shape. */
internal object AnthropicJson {
    // A minor version has at most two digits, so a dated ID such as `claude-sonnet-4-20250514` reads as 4.0.
    private val modelVersion = Regex("^claude-(?:opus|sonnet|haiku)-(\\d{1,2})(?:-(\\d{1,2}))?(?!\\d)")

    fun encode(request: CompletionRequest, model: String, defaultMaxTokens: Int, serviceTier: String?, cachePrompt: Boolean): String {
        LlmJson.validate(request)
        // A tool_use block without an ID cannot be matched to its tool_result.
        request.messages.flatMap { it.toolCalls }
            .forEach { require(!it.id.isNullOrBlank()) { "Tool calls require an ID: ${it.name}" } }
        val body = JsonObject()
        body.addProperty("model", model)
        body.addProperty("max_tokens", request.maxTokens ?: defaultMaxTokens)
        val system = request.messages.filter { it.role == LlmRole.SYSTEM && it.content.isNotEmpty() }
            .joinToString("\n\n") { it.content }
        if (system.isNotEmpty() && cachePrompt) body.add("system", JsonArray().apply {
            add(textBlock(system).apply {
                // Explicitly cache the reusable prefix, not the varying messages. Automatic caching
                // would instead write the last message on every one-shot classification request.
                add("cache_control", JsonObject().apply { addProperty("type", "ephemeral") })
            })
        }) else if (system.isNotEmpty()) body.addProperty("system", system)
        body.add("messages", messages(request.messages))
        if (request.temperature != null && !rejectsTemperature(model)) body.addProperty("temperature", request.temperature)
        serviceTier?.takeIf { it.isNotBlank() }?.let { body.addProperty("service_tier", it) }
        // tool_choice without tools is a 400 on the Messages API.
        if (request.tools.isNotEmpty()) {
            body.add("tools", JsonArray().apply {
                request.tools.forEach { tool ->
                    add(JsonObject().apply {
                        addProperty("name", tool.name)
                        addProperty("description", tool.description)
                        add("input_schema", tool.parameters)
                    })
                }
            })
            toolChoice(request.toolChoice, request.parallelToolCalls)?.let { body.add("tool_choice", it) }
        }
        return body.toString()
    }

    /** Models from Claude 4.7 on return 400 for a non-default temperature, so their default applies. */
    internal fun rejectsTemperature(model: String): Boolean {
        val match = modelVersion.find(model) ?: return false
        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].toIntOrNull() ?: 0
        return major > 4 || (major == 4 && minor >= 7)
    }

    /** Anthropic has no top-level parallel_tool_calls; disabling it lives inside tool_choice. */
    private fun toolChoice(choice: ToolChoice?, parallelToolCalls: Boolean?): JsonObject? {
        val result = when (choice) {
            null -> if (parallelToolCalls == false) JsonObject().apply { addProperty("type", "auto") } else return null
            ToolChoice.Auto -> JsonObject().apply { addProperty("type", "auto") }
            ToolChoice.None -> return JsonObject().apply { addProperty("type", "none") }
            ToolChoice.Required -> JsonObject().apply { addProperty("type", "any") }
            is ToolChoice.Function -> JsonObject().apply {
                addProperty("type", "tool")
                addProperty("name", choice.name)
            }
        }
        if (parallelToolCalls == false) result.addProperty("disable_parallel_tool_use", true)
        return result
    }

    /**
     * Consecutive tool results are grouped into one user message, as the API requires all results of a turn
     * together and first; a user message that follows them joins that message as a text block.
     */
    private fun messages(messages: List<LlmMessage>): JsonArray {
        val output = JsonArray()
        var openToolResults: JsonArray? = null
        for (message in messages) {
            when {
                message.role == LlmRole.SYSTEM -> continue
                message.role == LlmRole.TOOL -> {
                    val block = JsonObject().apply {
                        addProperty("type", "tool_result")
                        addProperty("tool_use_id", message.toolCallId)
                        addProperty("content", message.content)
                    }
                    openToolResults?.add(block) ?: run {
                        openToolResults = JsonArray().apply { add(block) }
                        output.add(JsonObject().apply {
                            addProperty("role", "user")
                            add("content", openToolResults)
                        })
                    }
                    continue
                }
                message.role == LlmRole.USER && openToolResults != null -> {
                    if (message.content.isNotEmpty()) openToolResults!!.add(textBlock(message.content))
                }
                message.role == LlmRole.ASSISTANT && (message.toolCalls.isNotEmpty() || message.thinkingBlocks.isNotEmpty()) ->
                    output.add(JsonObject().apply {
                        addProperty("role", "assistant")
                        add("content", JsonArray().apply {
                            // Thinking blocks go first and unmodified, signatures included.
                            message.thinkingBlocks.forEach { add(it) }
                            if (message.content.isNotBlank()) add(textBlock(message.content))
                            message.toolCalls.forEach { call ->
                                add(JsonObject().apply {
                                    addProperty("type", "tool_use")
                                    addProperty("id", call.id)
                                    addProperty("name", call.name)
                                    add("input", call.arguments)
                                })
                            }
                        })
                    })
                // The API rejects messages with empty content.
                message.content.isEmpty() -> continue
                else -> output.add(JsonObject().apply {
                    addProperty("role", message.role.wire)
                    addProperty("content", message.content)
                })
            }
            openToolResults = null
        }
        return output
    }

    private fun textBlock(text: String) = JsonObject().apply {
        addProperty("type", "text")
        addProperty("text", text)
    }

    fun decode(body: String): Completion = try {
        val root = JsonParser.parseString(body).asJsonObject
        val blocks = root.getAsJsonArray("content") ?: throw LlmProtocolException("Response has no content")
        val text = mutableListOf<String>()
        val reasoning = mutableListOf<String>()
        val thinkingBlocks = mutableListOf<JsonObject>()
        val toolCalls = mutableListOf<LlmToolCall>()
        for (element in blocks) {
            val block = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            when (block.string("type")) {
                "text" -> block.string("text")?.let(text::add)
                "thinking" -> {
                    thinkingBlocks += block
                    block.string("thinking")?.trim()?.takeIf { it.isNotEmpty() }?.let(reasoning::add)
                }
                "redacted_thinking" -> thinkingBlocks += block
                // Unlike OpenAI's JSON-string arguments, `input` arrives as an object.
                "tool_use" -> toolCalls += LlmToolCall(
                    block.string("id"), block.string("name").orEmpty(), block.objectOrNull("input") ?: JsonObject(),
                )
            }
        }
        val content = text.joinToString("\n").trim()
        val finishReason = FinishReason.fromWire(root.string("stop_reason"))
        if (content.isEmpty() && toolCalls.isEmpty()) {
            // Thinking tokens count toward max_tokens, so a response can stop before any text.
            throw LlmProtocolException(
                if (finishReason == FinishReason.TRUNCATED) "LLM response was truncated at the output-token limit"
                else "Empty LLM response"
            )
        }
        Completion(
            content, reasoning.joinToString("\n\n").ifEmpty { null }, toolCalls, finishReason,
            root.string("model"), usage(root.objectOrNull("usage")), thinkingBlocks,
        )
    } catch (e: LlmProtocolException) {
        throw e
    } catch (e: RuntimeException) {
        throw LlmProtocolException("Malformed Anthropic messages response", e)
    }

    /** `input_tokens` excludes cache reads and writes; [LlmUsage.inputTokens] includes them. */
    private fun usage(usage: JsonObject?): LlmUsage? {
        usage ?: return null
        val uncached = usage.int("input_tokens")
        val read = usage.int("cache_read_input_tokens")
        val written = usage.int("cache_creation_input_tokens")
        val output = usage.int("output_tokens")
        val input = if (uncached != null || read != null || written != null) (uncached ?: 0) + (read ?: 0) + (written ?: 0) else null
        if (input == null && output == null) return null
        return LlmUsage(input, output, (input ?: 0) + (output ?: 0), read, written)
    }
}
