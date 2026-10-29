package dev.insight.chat

import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.logger
import dev.insight.llm.Completion
import dev.insight.llm.CompletionFn
import dev.insight.llm.CompletionRequest
import dev.insight.llm.FinishReason
import dev.insight.llm.LlmApiException
import dev.insight.llm.LlmMessage
import dev.insight.llm.LlmProtocolException
import dev.insight.llm.LlmTool
import dev.insight.llm.ToolChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext

/** A function the model may call during a chat turn. Implementations validate their own arguments. */
interface ChatTool {
    val definition: LlmTool

    /** Returns the text sent back to the model. Throw [ToolInputException] for arguments the model should fix. */
    suspend fun call(arguments: JsonObject): String
}

/** Invalid tool arguments. The message goes back to the model so it can correct the call. */
class ToolInputException(message: String) : IllegalArgumentException(message)

/** One executed tool call, reported while the turn is still running. */
data class ToolCallEvent(val name: String, val arguments: JsonObject, val result: String, val failed: Boolean)

/** [transcript] holds the turn's tool calls and results, without provider reasoning. */
data class ToolChatResult(
    val reply: String,
    val finishReason: FinishReason,
    val transcript: List<LlmMessage>,
    val requestCount: Int,
)

/**
 * Runs the assistant/tool loop for one turn: request a completion, execute any tool calls, send the
 * results back, and repeat until the model answers in text. Tool failures are reported to the model
 * rather than failing the turn. After [maxToolRounds] rounds of calls, one final request disables new
 * calls through the API's tool choice. If the server ignores it or the reply is empty, retry once with an answer-now note.
 * A [followUp] can reject an answer, e.g. when required work is missing, and continue the turn.
 */
class ToolChatRunner(
    private val complete: CompletionFn,
    tools: List<ChatTool>,
    private val maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS,
    private val maxToolResultChars: Int = DEFAULT_MAX_TOOL_RESULT_CHARS,
    private val maxContextChars: Int = 64_000,
    private val maxHistoryChars: Int = 24_000,
    private val maxContextRetries: Int = 3,
    private val evictionTarget: Double = 1.0,
    /** Checked when the model answers; a non-null note is sent back as a user message and the turn continues. */
    private val followUp: (suspend () -> String?)? = null,
    private val maxFollowUps: Int = 2,
) {
    private val tools = tools.associateBy { it.definition.name }

    init {
        require(this.tools.size == tools.size) { "Tool names must be unique" }
        require(maxToolRounds >= 0) { "Max tool rounds must not be negative" }
        require(maxToolResultChars > 0) { "Max tool result size must be positive" }
        require(maxContextChars > 0) { "Max context size must be positive" }
        require(maxHistoryChars >= 0) { "Max history size must not be negative" }
        require(maxContextRetries >= 0) { "Max context retries must not be negative" }
        require(maxFollowUps >= 0) { "Max follow-ups must not be negative" }
    }

    suspend fun run(messages: List<LlmMessage>, onToolCall: suspend (ToolCallEvent) -> Unit = {}): ToolChatResult {
        require(messages.isNotEmpty()) { "At least one message is required" }
        val window = ChatContextWindow(messages, maxContextChars, maxHistoryChars, evictionTarget = evictionTarget)
        val transcript = mutableListOf<LlmMessage>()
        var requestCount = 0
        var followUps = 0
        // Schemas stay in every request, including the last one, so earlier calls remain interpretable.
        val definitions = tools.values.map { it.definition }
        for (round in 0..maxToolRounds) {
            val exhausted = round == maxToolRounds && definitions.isNotEmpty()
            var answerNow = false
            fun request(context: List<LlmMessage>) = CompletionRequest(
                // A user note: several chat templates require system messages first.
                messages = if (answerNow) context + LlmMessage.user(TOOL_ROUNDS_EXHAUSTED) else context,
                tools = definitions,
                toolChoice = if (exhausted) ToolChoice.None else null,
            )
            suspend fun completeWithWindow(): Completion {
                var retries = 0
                while (true) {
                    val candidate = window.fit(::request)
                    requestCount++
                    try {
                        return complete(candidate)
                    } catch (error: LlmApiException) {
                        if (!error.isContextOverflow() || retries >= maxContextRetries || !window.shrink(candidate)) throw error
                        retries++
                    }
                }
            }
            val first = try {
                completeWithWindow()
            } catch (e: LlmProtocolException) {
                // Some servers (llama.cpp with gpt-oss) drop a call made despite tool choice none and return nothing.
                if (!exhausted) throw e
                null
            }
            val completion = if (exhausted && (first == null || first.toolCalls.isNotEmpty() || first.content.isBlank())) {
                // Discard the rejected completion: its calls must never execute or enter the transcript.
                answerNow = true
                completeWithWindow()
            } else first!!
            if (completion.toolCalls.isEmpty()) {
                val reply = completion.content.trim()
                val note = if (round < maxToolRounds && followUps < maxFollowUps) followUp?.invoke() else null
                if (note != null) {
                    followUps++
                    // Some providers reject empty assistant messages; a user note may follow a tool result directly.
                    val nudge = listOfNotNull(
                        LlmMessage.assistant(completion.content, reasoning = completion.reasoning, thinkingBlocks = completion.thinkingBlocks)
                            .takeIf { reply.isNotEmpty() },
                        LlmMessage.user(note),
                    )
                    transcript += nudge.map { it.copy(reasoning = null, thinkingBlocks = emptyList()) }
                    window.addRound(nudge)
                    continue
                }
                if (reply.isEmpty()) throw LlmProtocolException("The model returned an empty reply")
                return ToolChatResult(reply, completion.finishReason, transcript, requestCount)
            }
            if (round == maxToolRounds) throw LlmProtocolException("The model exceeded the tool-call round limit")
            val calls = completion.toolCalls.mapIndexed { index, call ->
                if (call.id.isNullOrBlank()) call.copy(id = "call_${round + 1}_${index + 1}") else call
            }
            val assistant = LlmMessage.assistant(completion.content, calls, completion.reasoning, completion.thinkingBlocks)
            val roundMessages = mutableListOf(assistant)
            transcript += assistant.copy(reasoning = null, thinkingBlocks = emptyList())
            for (call in calls) {
                currentCoroutineContext()[FileTraceContext]?.trace?.requestTool(call.name, call.arguments)
                val (result, failed) = execute(call.name, call.arguments)
                val toolMessage = LlmMessage.tool(call.id!!, result)
                roundMessages += toolMessage
                transcript += toolMessage
                onToolCall(ToolCallEvent(call.name, call.arguments, result, failed))
            }
            window.addRound(roundMessages)
        }
        error("unreachable")
    }

    private suspend fun execute(name: String, arguments: JsonObject): Pair<String, Boolean> {
        val tool = tools[name]
            ?: return "Error: unknown tool '${name.ifBlank { "(missing name)" }}'. Available tools: ${tools.keys.joinToString()}." to true
        return try {
            limit(tool.call(arguments)) to false
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolInputException) {
            "Error: ${e.message}" to true
        } catch (e: Exception) {
            LOG.warn("Chat tool '$name' failed", e)
            "Error: the tool failed (${e.message ?: e.javaClass.simpleName})." to true
        }
    }

    private fun limit(result: String): String =
        if (result.length <= maxToolResultChars) result
        else result.take(maxToolResultChars) + "\n[Truncated ${result.length - maxToolResultChars} characters.]"

    companion object {
        const val DEFAULT_MAX_TOOL_RESULT_CHARS = 16_000
        const val DEFAULT_MAX_TOOL_ROUNDS = 100
        private const val TOOL_ROUNDS_EXHAUSTED =
            "The tool-call limit for this turn is reached. Do not call tools. Answer now using the information you have."
        private val LOG = logger<ToolChatRunner>()
    }
}

/**
 * Keeps complete recent user-visible turns within a character budget, clipping the latest turn rather
 * than dropping it (see [recentTurns]). Tool events and notices are
 * not replayed: earlier tool output multiplies token use and is rarely relevant to the next question.
 */
fun compactHistory(history: List<ChatMessage>, maxChars: Int = 24_000, maxMessages: Int = 20): List<LlmMessage> {
    require(maxChars >= 0 && maxMessages >= 0) { "History budgets must not be negative" }
    val messages = history.mapNotNull { message ->
        val content = message.text.trim()
        if (content.isEmpty()) return@mapNotNull null
        when (message.role) {
            ChatRole.USER -> LlmMessage.user(content)
            ChatRole.ASSISTANT -> LlmMessage.assistant(content)
            else -> null
        }
    }
    return recentTurns(historyTurns(messages), maxChars, maxMessages).flatten()
}
