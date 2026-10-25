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

class DemoChatBackend : ChatBackend {
    override suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit): String {
        val prompt = messages.lastOrNull()?.text.orEmpty().lowercase()
        return when {
            "chart" in prompt || "dashboard" in prompt || "graph" in prompt ->
                "Open the dashboard above to explore the sample activity chart and module graph. Choose a time range or select a module to see its details. These are demo datasets, not project measurements."
            "architecture" in prompt || "module" in prompt ->
                "The scaffold separates the chat backend, project conversation state, and dashboard views. A project service owns the conversation; an editor provider hosts the visualizations. You can add analysis providers behind these boundaries."
            else -> "Demo response: I received your message. This local assistant demonstrates the chat flow. Connect a llama.cpp server in Settings → Tools → Insight Chat to enable project-aware conversations. Try asking about the dashboard or architecture."
        }
    }
}
