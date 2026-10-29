package dev.insight.chat

import com.intellij.openapi.project.Project
import dev.insight.agent.ChatBackend
import dev.insight.settings.ChatSettings

/** Creates a backend for each turn on IO; owned UI resources are cleared/disposed on the EDT. */
fun interface ChatBackendFactory {
    fun create(suggestFollowups: (List<String>) -> Unit): ChatBackend

    /** The chart used by this backend and its session UI; null for conversations without a chart. */
    fun chartContext(): ChartChatContext? = null

    fun clear() {}
    fun dispose() {}
}

/** Sidebar backend policy and its conversation's source viewer. */
internal class ProjectChatBackendFactory(private val project: Project) : ChatBackendFactory {
    private val sourcePoints = SourcePoints(project, "Chat")

    override fun create(suggestFollowups: (List<String>) -> Unit): ChatBackend {
        val client = ChatSettings.getInstance().createClient()
        return LlmChatBackend(client::complete, projectTools(project, sourcePoints), projectChatSystemPrompt())
    }

    override fun clear() = sourcePoints.clear()
    override fun dispose() = sourcePoints.dispose()
}
