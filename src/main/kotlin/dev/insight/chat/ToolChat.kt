package dev.insight.chat

import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.logger
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.FinishReason
import dev.insight.llm.LlmMessage
import dev.insight.llm.LlmProtocolException
import dev.insight.llm.LlmTool
import dev.insight.llm.ToolChoice
import kotlinx.coroutines.CancellationException

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
 * calls so the model answers with what it has.
 */
class ToolChatRunner(
    private val complete: suspend (CompletionRequest) -> Completion,
    tools: List<ChatTool>,
    private val maxToolRounds: Int = 12,
    private val maxToolResultChars: Int = 16_000,
) {
    private val tools = tools.associateBy { it.definition.name }

    init {
        require(this.tools.size == tools.size) { "Tool names must be unique" }
        require(maxToolRounds >= 0) { "Max tool rounds must not be negative" }
        require(maxToolResultChars > 0) { "Max tool result size must be positive" }
    }

    suspend fun run(messages: List<LlmMessage>, onToolCall: suspend (ToolCallEvent) -> Unit = {}): ToolChatResult {
        require(messages.isNotEmpty()) { "At least one message is required" }
        val conversation = messages.toMutableList()
        val transcript = mutableListOf<LlmMessage>()
        // Schemas stay in every request, including the last one, so earlier calls remain interpretable.
        val definitions = tools.values.map { it.definition }
        for (round in 0..maxToolRounds) {
            val exhausted = round == maxToolRounds && definitions.isNotEmpty()
            val completion = complete(CompletionRequest(
                // A user note rather than a system message: several chat templates require system messages first.
                messages = if (exhausted) conversation + LlmMessage.user(TOOL_ROUNDS_EXHAUSTED) else conversation.toList(),
                tools = definitions,
                toolChoice = if (exhausted) ToolChoice.None else null,
            ))
            if (completion.toolCalls.isEmpty()) {
                val reply = completion.content.trim()
                if (reply.isEmpty()) throw LlmProtocolException("The model returned an empty reply")
                return ToolChatResult(reply, completion.finishReason, transcript, round + 1)
            }
            if (round == maxToolRounds) throw LlmProtocolException("The model exceeded the tool-call round limit")
            val calls = completion.toolCalls.mapIndexed { index, call ->
                if (call.id.isNullOrBlank()) call.copy(id = "call_${round + 1}_${index + 1}") else call
            }
            val assistant = LlmMessage.assistant(completion.content, calls, completion.reasoning)
            conversation += assistant
            transcript += assistant.copy(reasoning = null)
            for (call in calls) {
                val (result, failed) = execute(call.name, call.arguments)
                val toolMessage = LlmMessage.tool(call.id!!, result)
                conversation += toolMessage
                transcript += toolMessage
                onToolCall(ToolCallEvent(call.name, call.arguments, result, failed))
            }
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

    private companion object {
        val LOG = logger<ToolChatRunner>()
        const val TOOL_ROUNDS_EXHAUSTED =
            "The tool-call limit for this turn is reached. Do not call tools. Answer now using the information you have."
    }
}

/**
 * Keeps the most recent user-visible turns within a character budget. Tool events and notices are
 * not replayed: earlier tool output multiplies token use and is rarely relevant to the next question.
 */
fun compactHistory(history: List<ChatMessage>, maxChars: Int = 24_000, maxMessages: Int = 20): List<LlmMessage> {
    val kept = mutableListOf<LlmMessage>()
    var remaining = maxChars
    for (message in history.asReversed()) {
        if (kept.size >= maxMessages || remaining <= 0) break
        val content = message.text.trim().take(remaining)
        if (content.isEmpty()) continue
        kept += when (message.role) {
            ChatMessage.USER -> LlmMessage.user(content)
            ChatMessage.ASSISTANT -> LlmMessage.assistant(content)
            else -> continue
        }
        remaining -= content.length
    }
    return kept.asReversed()
}
