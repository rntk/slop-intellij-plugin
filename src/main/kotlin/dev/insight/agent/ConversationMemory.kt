package dev.insight.agent

import dev.insight.llm.LlmMessage

/**
 * A conversation's earlier turns as the model sees them: the newest [turns] whole, in order, each a user message followed by
 * an assistant message, and a [digest] of the [foldedTurns] before them. The digest goes into the system prompt, so it
 * survives in-turn eviction and keeps user/assistant alternation intact.
 */
data class ConversationHistory(val digest: String?, val turns: List<LlmMessage>, val foldedTurns: Int) {
    companion object {
        val EMPTY = ConversationHistory(null, emptyList(), 0)
    }
}

/**
 * Assembles the history the model sees of [earlier], the conversation before the current question. Tool events and notices
 * are not replayed; a turn that got no reply (it failed or was stopped) gets a placeholder so the roles keep alternating.
 * The newest turns are kept whole while they fit [historyChars] and [maxMessages]; the newest is clipped rather than
 * dropped. Older turns fold into a bounded digest, oldest first, that keeps the first turn fullest since it usually sets
 * the task, and names the tools each turn used so the model can re-read precisely instead of broadly.
 */
fun conversationHistory(earlier: List<ChatMessage>, historyChars: Int, maxMessages: Int = 40): ConversationHistory {
    require(historyChars >= 0 && maxMessages >= 0) { "History budgets must not be negative" }
    val turns = conversationTurns(earlier)
    if (turns.isEmpty() || historyChars == 0 || maxMessages == 0) return ConversationHistory(null, emptyList(), turns.size)
    val digestChars = (historyChars * DIGEST_SHARE).toInt().coerceIn(minOf(MIN_DIGEST_CHARS, historyChars), MAX_DIGEST_CHARS)
    val keptChars = historyChars - digestChars
    val kept = recentTurns(turns.map { it.messages }, keptChars, maxMessages)
    val folded = turns.take(turns.size - kept.size)
    val digest = if (folded.isEmpty()) null else digest(folded, digestChars)
    return ConversationHistory(digest, kept.flatten(), folded.size)
}

/** One user question and what followed it, up to the next question. */
internal class ConversationTurn(val question: String, val answer: String?, val tools: List<ToolCallEvent>) {
    val messages: List<LlmMessage> get() = listOf(LlmMessage.user(question), LlmMessage.assistant(answer ?: NO_REPLY))
}

/** Groups [messages] into turns; messages before the first question, and blank questions, are ignored. */
internal fun conversationTurns(messages: List<ChatMessage>): List<ConversationTurn> {
    val turns = mutableListOf<ConversationTurn>()
    var question: String? = null
    val answers = mutableListOf<String>()
    val tools = mutableListOf<ToolCallEvent>()
    fun close() {
        val text = question?.trim().orEmpty()
        if (text.isNotEmpty()) turns += ConversationTurn(text, answers.joinToString("\n\n").trim().ifEmpty { null }, tools.toList())
        question = null
        answers.clear()
        tools.clear()
    }
    for (message in messages) {
        when (message.role) {
            ChatRole.USER -> { close(); question = message.text }
            ChatRole.ASSISTANT -> if (question != null && message.text.isNotBlank()) answers += message.text.trim()
            ChatRole.TOOL -> if (question != null) message.toolCall?.let { tools += it }
            ChatRole.NOTICE -> {}
        }
    }
    close()
    return turns
}

/**
 * The folded turns in [maxChars]: a numbered entry per turn with its question, the start of its answer and the tools it used.
 * When the entries run over, the middle ones go first, leaving the first turn and the most recent folded ones, and the texts
 * shrink before anything else is dropped.
 */
internal fun digest(turns: List<ConversationTurn>, maxChars: Int): String {
    fun render(entries: List<IndexedValue<ConversationTurn>>, scale: Double): String = buildString {
        append("Earlier in this conversation (").append(turns.size).append(if (turns.size == 1) " turn" else " turns")
        append(", condensed; the full text is gone, so ask or re-read files when a detail matters):")
        var previous = -1
        for ((index, turn) in entries) {
            if (index != previous + 1) append("\n(").append(index - previous - 1).append(" turns omitted)")
            previous = index
            val first = index == 0
            append('\n').append(index + 1).append(". Asked: ").append(clip(turn.question, ((if (first) FIRST_QUESTION_CHARS else QUESTION_CHARS) * scale).toInt()))
            turn.answer?.let { append("\n   Answered: ").append(clip(it, ((if (first) FIRST_ANSWER_CHARS else ANSWER_CHARS) * scale).toInt())) }
                ?: append("\n   (no reply)")
            val reads = turn.tools.filter { !it.failed }.map { toolLabel(it) }.distinct()
            if (reads.isNotEmpty()) append("\n   Read: ").append(reads.take(MAX_TOOLS_PER_TURN).joinToString(", "))
                .append(if (reads.size > MAX_TOOLS_PER_TURN) " and ${reads.size - MAX_TOOLS_PER_TURN} more" else "")
        }
    }
    var entries = turns.withIndex().toList()
    var text = render(entries, 1.0)
    while (text.length > maxChars && entries.size > 2) {
        entries = entries.filterIndexed { position, _ -> position != entries.size / 2 }
        text = render(entries, 1.0)
    }
    var scale = 1.0
    while (text.length > maxChars && scale > 0.1) {
        scale /= 2
        text = render(entries, scale)
    }
    return if (text.length <= maxChars) text else text.take((maxChars - 1).coerceAtLeast(0)) + "…"
}

private fun toolLabel(event: ToolCallEvent): String {
    val argument = event.arguments.entrySet().firstOrNull()?.value?.let { if (it.isJsonPrimitive) it.asString else it.toString() }
    return if (argument == null) event.name else "${event.name}(${clip(argument, ARGUMENT_CHARS)})"
}

/** The first [max] characters on one line, cut at a word, with an ellipsis when something was left out. */
private fun clip(text: String, max: Int): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    if (flat.length <= max) return flat
    val cut = flat.take(max.coerceAtLeast(1))
    val word = cut.lastIndexOf(' ')
    return (if (word > max / 2) cut.substring(0, word) else cut).trimEnd() + "…"
}

private const val NO_REPLY = "[No reply was produced for this message.]"
private const val DIGEST_SHARE = 0.2
private const val MIN_DIGEST_CHARS = 800
private const val MAX_DIGEST_CHARS = 6_000
private const val FIRST_QUESTION_CHARS = 600
private const val FIRST_ANSWER_CHARS = 600
private const val QUESTION_CHARS = 160
private const val ANSWER_CHARS = 240
private const val ARGUMENT_CHARS = 60
private const val MAX_TOOLS_PER_TURN = 6
