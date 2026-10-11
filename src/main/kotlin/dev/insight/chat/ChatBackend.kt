package dev.insight.chat

data class ChatMessage(val role: String, val text: String)

/** Replace this boundary with a provider; never perform network work on the EDT. */
fun interface ChatBackend {
    suspend fun reply(messages: List<ChatMessage>): String
}

class DemoChatBackend : ChatBackend {
    override suspend fun reply(messages: List<ChatMessage>): String {
        val prompt = messages.lastOrNull()?.text.orEmpty().lowercase()
        return when {
            "chart" in prompt || "dashboard" in prompt || "graph" in prompt ->
                "Open the dashboard above to explore the sample activity chart and module graph. Choose a time range or select a module to see its details. These are demo datasets, not project measurements."
            "architecture" in prompt || "module" in prompt ->
                "The scaffold separates the chat backend, project conversation state, and dashboard views. A project service owns the conversation; an editor provider hosts the visualizations. You can add analysis providers behind these boundaries."
            else -> "Demo response: I received your message. This local assistant demonstrates the chat flow. Connect a real provider in ChatBackend to enable project-aware conversations. Try asking about the dashboard or architecture."
        }
    }
}
