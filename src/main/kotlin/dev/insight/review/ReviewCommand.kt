package dev.insight.review

/** Commands shared by code and diff reviews. Recognition is shared by routing and progress titles. */
internal enum class ReviewCommand(
    val command: String,
    private val bareAlias: Boolean = false,
    private val arguments: Boolean = false,
    private val multiline: Boolean = false,
) {
    TOPICS("/topics", bareAlias = true, arguments = true),
    GROUPING("/grouping", bareAlias = true, arguments = true),
    SUMMARIES("/summaries", bareAlias = true, arguments = true),
    TOPIC_SUMMARIES("/topic-summaries"),
    GROUPING_SUMMARIES("/grouping-summaries"),
    EXPLAIN("/explain", arguments = true, multiline = true);

    private fun matches(text: String): Boolean = text == command ||
        (bareAlias && text == command.drop(1)) ||
        (arguments && text.startsWith("$command ")) ||
        (multiline && text.startsWith("$command\n"))

    companion object {
        /** Grouping reads a diff, so code reviews do not offer it; their text then stays an ordinary question. */
        val code: List<ReviewCommand> = listOf(TOPICS, SUMMARIES, TOPIC_SUMMARIES, EXPLAIN)

        /** The command [text] names among [supported], which are all commands by default. */
        fun parse(text: String, supported: Collection<ReviewCommand> = entries): ReviewCommand? {
            val normalized = text.trim().lowercase()
            return supported.firstOrNull { it.matches(normalized) }
        }
    }
}

/** [subject] is the evidence vocabulary used by the tab: "code" or "change". */
internal fun reviewProgressTitle(text: String, subject: String, supported: Collection<ReviewCommand> = ReviewCommand.entries): String =
    when (ReviewCommand.parse(text, supported)) {
    ReviewCommand.TOPICS -> "Generating $subject topics"
    ReviewCommand.GROUPING -> "Generating $subject grouping"
    ReviewCommand.EXPLAIN -> "Explaining the $subject"
    ReviewCommand.SUMMARIES, ReviewCommand.TOPIC_SUMMARIES, ReviewCommand.GROUPING_SUMMARIES -> "Generating $subject summaries"
    null -> "Insight $subject review chat"
}
