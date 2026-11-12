package dev.insight.agent

/**
 * How a chat spends its context window. Every interactive chat takes one of the [ChatProfile]s instead of its own constants,
 * so pinned data, history and the per-turn snapshot are shares of the same window and always leave room for the system
 * policy, the question and tool rounds. All sizes are characters; the server remains the authority on tokens.
 */
data class ChatBudget(
    /** The whole request, as [ChatContextWindow] measures it. */
    val contextChars: Int,
    /** Earlier turns, including the digest of folded ones (see [conversationHistory]). */
    val historyChars: Int,
    /** Earlier messages kept whole; older turns fold into the digest. */
    val historyMessages: Int,
    /** Data pinned in the system prompt: a file's diff or source, or as many of a wall's files as fit. */
    val pinnedChars: Int,
    /** The fresh snapshot attached to each question, which is sent anew every turn. */
    val snapshotChars: Int,
    /** One tool result; longer results are cut with a note. */
    val toolResultChars: Int,
    /** Tool rounds in one turn before the model must answer. */
    val toolRounds: Int,
    /** The share of [contextChars] an in-turn eviction frees down to, so later rounds keep a cacheable prefix. */
    val evictionTarget: Double,
) {
    init {
        require(contextChars > 0) { "Context size must be positive" }
        require(historyChars >= 0 && pinnedChars >= 0 && snapshotChars >= 0) { "Budget shares must not be negative" }
        require(historyMessages >= 0 && toolRounds >= 0) { "Budget counts must not be negative" }
        require(toolResultChars > 0) { "Tool result size must be positive" }
        require(historyChars + pinnedChars + snapshotChars <= contextChars) { "Budget shares exceed the context" }
        require(evictionTarget > 0 && evictionTarget <= 1) { "Eviction target must be in (0, 1]" }
    }

    companion object {
        /** Shares of the window: together they leave at least a fifth for the policy, the question and the first tool rounds. */
        const val HISTORY_SHARE = 0.30
        const val PINNED_SHARE = 0.35
        const val SNAPSHOT_SHARE = 0.15
        const val MAX_TOOL_RESULT_CHARS = 16_000

        /** The budget of [profile] over a window of [contextChars]; the profile's fallback window when the provider's is unknown. */
        fun of(profile: ChatProfile, contextChars: Int = profile.fallbackContextChars): ChatBudget = ChatBudget(
            contextChars = contextChars,
            historyChars = (contextChars * HISTORY_SHARE).toInt(),
            historyMessages = profile.historyMessages,
            pinnedChars = (contextChars * PINNED_SHARE).toInt(),
            snapshotChars = (contextChars * SNAPSHOT_SHARE).toInt(),
            toolResultChars = minOf(MAX_TOOL_RESULT_CHARS, contextChars / 4).coerceAtLeast(1),
            toolRounds = profile.toolRounds,
            evictionTarget = profile.evictionTarget,
        )
    }
}

/** The kinds of interactive chat, by how long they run and how much evidence they gather in one turn. */
enum class ChatProfile(
    /** The window assumed when the provider's context size is not configured. */
    val fallbackContextChars: Int,
    val historyMessages: Int,
    val toolRounds: Int,
    val evictionTarget: Double,
) {
    /** A chat about one file or a wall of files, whose data is pinned: short turns, few reads. */
    FOCUSED(fallbackContextChars = 64_000, historyMessages = 40, toolRounds = 40, evictionTarget = 0.85),
    /** The project and chart assistants: tools find the evidence, nothing is pinned. */
    ASSISTANT(fallbackContextChars = 64_000, historyMessages = 40, toolRounds = 100, evictionTarget = 0.85),
    /** Review and schematic chats, which accumulate evidence over many rounds; evict in chunks so the prefix stays cacheable. */
    REVIEW(fallbackContextChars = 160_000, historyMessages = 40, toolRounds = 100, evictionTarget = 0.7),
}
