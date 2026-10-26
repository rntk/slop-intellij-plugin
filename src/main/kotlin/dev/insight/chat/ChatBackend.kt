package dev.insight.chat

/**
 * [USER] and [ASSISTANT] messages are conversation; [TOOL] events and [NOTICE]s are shown but never sent to a model.
 * [revision] and [storyId] are the chart graph revision and active story ID an assistant reply's references belong to.
 */
data class ChatMessage(val role: String, val text: String, val revision: Int? = null, val storyId: Int? = null,
    val toolCall: ToolCallEvent? = null) {
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
        const val TOOL = "tool"
        const val NOTICE = "notice"
    }
}

/** Replace this boundary with a provider; never perform network work on the EDT. */
interface ChatBackend {
    /** [messages] ends with the new user message. [onToolCall] reports each tool call as it completes. */
    suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit = {}): String
}
