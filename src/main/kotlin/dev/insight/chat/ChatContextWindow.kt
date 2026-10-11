package dev.insight.chat

import dev.insight.llm.CompletionRequest
import dev.insight.llm.LlmApiException
import dev.insight.llm.LlmJson
import dev.insight.llm.LlmMessage
import dev.insight.llm.LlmRole

/**
 * A character-based moving window. Evicts complete older historical turns, then complete older tool
 * rounds, and only then the latest historical turn, which usually gives the current question its
 * meaning. The system messages, current question and latest tool round are protected, even if they
 * alone exceed the budget. The server remains the authority on token limits.
 */
internal class ChatContextWindow(
    messages: List<LlmMessage>,
    private var maxRequestChars: Int,
    maxHistoryChars: Int,
    maxHistoryMessages: Int = 20,
) {
    private val system = messages.takeWhile { it.role == LlmRole.SYSTEM }
    private val question = messages.last()
    private val history: MutableList<List<LlmMessage>>
    private val rounds = mutableListOf<List<LlmMessage>>()
    private var omittedTurns = 0
    private var omittedRounds = 0
    private val omittedCalls = ArrayDeque<String>()
    private var forgottenCalls = 0

    init {
        require(question.role == LlmRole.USER) { "The conversation must end with a user message" }
        val turns = historyTurns(messages.drop(system.size).dropLast(1))
        history = recentTurns(turns, maxHistoryChars, maxHistoryMessages) { rememberCalls(it); omittedTurns++ }.toMutableList()
    }

    fun addRound(messages: List<LlmMessage>) {
        // The caller appends a round only after all calls have their corresponding results.
        rounds += messages.toList()
    }

    fun fit(request: (List<LlmMessage>) -> CompletionRequest): CompletionRequest {
        var candidate = request(messages())
        while (size(candidate) > maxRequestChars && dropOldest()) candidate = request(messages())
        return candidate
    }

    /**
     * Retry only if we can actually remove data. The budget drops just below the rejected size, so
     * the next [fit] evicts at least one item without discarding most of the context; the smaller
     * budget is kept for subsequent rounds.
     */
    fun shrink(rejected: CompletionRequest): Boolean {
        if (history.isEmpty() && rounds.size <= 1) return false
        maxRequestChars = minOf(maxRequestChars, (size(rejected) * RETRY_BUDGET_FRACTION).toInt().coerceAtLeast(1))
        return true
    }

    private fun messages(): List<LlmMessage> {
        val notice = if (omittedTurns + omittedRounds == 0) "" else buildString {
            append("\n\n[Context omitted to fit the window: $omittedTurns earlier conversation turns, $omittedRounds earlier tool rounds.")
            if (omittedCalls.isNotEmpty()) {
                append(" Omitted tool calls (results unavailable): ")
                append(omittedCalls.joinToString("; "))
                append('.')
            }
            if (forgottenCalls > 0) append(" $forgottenCalls older call summaries omitted.")
            append(']')
        }
        // Attach the single notice to the current user message: no extra system messages or orphan
        // tool messages, and templates requiring alternating user/assistant turns still work.
        return system + history.flatten() + question.copy(content = question.content + notice) + rounds.flatten()
    }

    private fun dropOldest(): Boolean = when {
        history.size > 1 -> { rememberCalls(history.removeAt(0)); omittedTurns++; true }
        rounds.size > 1 -> { rememberCalls(rounds.removeAt(0)); omittedRounds++; true }
        history.isNotEmpty() -> { rememberCalls(history.removeAt(0)); omittedTurns++; true }
        else -> false
    }

    private fun rememberCalls(messages: List<LlmMessage>) {
        for (call in messages.flatMap { it.toolCalls }) {
            // Keep identifiers and arguments, never result bodies or reasoning. Summaries are plain
            // text, so clipping large argument objects cannot create malformed protocol messages.
            val arguments = if (call.arguments.size() == 0) "" else call.arguments.toString().let {
                if (it.length <= MAX_ARGUMENT_CHARS) it else it.take(MAX_ARGUMENT_CHARS - 1) + "…"
            }
            omittedCalls.addLast("${call.name.take(64)}($arguments)")
            while (omittedCalls.sumOf { it.length + 2 } > MAX_CALL_SUMMARY_CHARS) {
                omittedCalls.removeFirst()
                forgottenCalls++
            }
        }
    }

    private fun size(request: CompletionRequest): Int = LlmJson.encode(request, "", true).length

    private companion object {
        const val MAX_ARGUMENT_CHARS = 192
        const val MAX_CALL_SUMMARY_CHARS = 1024
        const val RETRY_BUDGET_FRACTION = 0.85
    }
}

/**
 * Keeps the most recent complete turns within the budgets. The latest turn is kept even when it
 * alone is over [maxChars], clipped instead, so a long paste or answer does not erase the context of
 * a follow-up question. A zero budget keeps nothing. Dropped turns are passed to [onDrop], oldest first.
 */
internal fun recentTurns(
    turns: List<List<LlmMessage>>,
    maxChars: Int,
    maxMessages: Int,
    onDrop: (List<LlmMessage>) -> Unit = {},
): List<List<LlmMessage>> {
    if (maxChars <= 0 || maxMessages <= 0) return emptyList<List<LlmMessage>>().also { turns.forEach(onDrop) }
    val kept = turns.toMutableList()
    fun chars() = kept.sumOf { turn -> turn.sumOf { it.content.length.toLong() } }
    while (kept.size > 1 && (chars() > maxChars || kept.sumOf { it.size } > maxMessages)) onDrop(kept.removeAt(0))
    if (kept.isNotEmpty() && chars() > maxChars) kept[0] = clipTurn(kept[0], maxChars)
    return kept
}

/**
 * Clips message text so [turn] roughly fits [maxChars], keeping the start of each message. Later
 * messages are served first, but every message keeps up to [MIN_CLIPPED_CHARS] so none becomes empty.
 */
private fun clipTurn(turn: List<LlmMessage>, maxChars: Int): List<LlmMessage> {
    var remaining = maxChars
    return turn.asReversed().map { message ->
        val keep = maxOf(remaining, minOf(message.content.length, MIN_CLIPPED_CHARS))
        remaining = (remaining - minOf(keep, message.content.length)).coerceAtLeast(0)
        if (message.content.length <= keep) message
        else message.copy(content = message.content.take(keep) + "\n[Truncated ${message.content.length - keep} characters.]")
    }.asReversed()
}

private const val MIN_CLIPPED_CHARS = 200

/** Historical user turns include all following assistant/tool messages up to the next user. */
internal fun historyTurns(messages: List<LlmMessage>): List<List<LlmMessage>> {
    val turns = mutableListOf<MutableList<LlmMessage>>()
    for (message in messages) {
        if (message.role == LlmRole.USER || turns.isEmpty()) turns += mutableListOf<LlmMessage>()
        turns.last() += message
    }
    return turns
}

/** Recognize explicit context failures, without retrying unrelated invalid requests. */
internal fun LlmApiException.isContextOverflow(): Boolean {
    if (statusCode !in setOf(400, 413, 422)) return false
    val detail = responseBody.lowercase()
    return listOf(
        "context_length_exceeded", "model_context_window_exceeded", "exceeds the available context size",
        "maximum context length", "context window exceeded", "context size exceeded",
        "exceeds the context", "too many tokens",
    ).any { it in detail }
}
