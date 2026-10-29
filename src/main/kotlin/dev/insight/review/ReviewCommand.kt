package dev.insight.review

/** Commands shared by code and diff reviews. Recognition is shared by routing and progress titles. */
internal enum class ReviewCommand(
    val command: String,
    private val bareAlias: Boolean = false,
    private val arguments: Boolean = false,
    private val multiline: Boolean = false,
) {
    TOPICS("/topics", bareAlias = true, arguments = true),
    SUMMARIES("/summaries", bareAlias = true, arguments = true),
    TOPIC_SUMMARIES("/topic-summaries"),
    EXPLAIN("/explain", arguments = true, multiline = true);

    private fun matches(text: String): Boolean = text == command ||
        (bareAlias && text == command.drop(1)) ||
        (arguments && text.startsWith("$command ")) ||
        (multiline && text.startsWith("$command\n"))

    companion object {
        fun parse(text: String): ReviewCommand? {
            val normalized = text.trim().lowercase()
            return entries.firstOrNull { it.matches(normalized) }
        }
    }
}

/** [subject] is the evidence vocabulary used by the tab: "code" or "change". */
internal fun reviewProgressTitle(text: String, subject: String): String = when (ReviewCommand.parse(text)) {
    ReviewCommand.TOPICS -> "Generating $subject topics"
    ReviewCommand.EXPLAIN -> "Explaining the $subject"
    ReviewCommand.SUMMARIES, ReviewCommand.TOPIC_SUMMARIES -> "Generating $subject summaries"
    null -> "Insight $subject review chat"
}
