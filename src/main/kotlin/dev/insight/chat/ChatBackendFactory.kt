package dev.insight.chat

import com.intellij.openapi.project.Project

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

/** Chart backend policy: chart actions plus the project tools useful for source evidence. */
internal class ChartChatBackendFactory(
    private val project: Project,
    private val chart: ChartChatContext,
) : ChatBackendFactory {
    private val sourcePoints = SourcePoints(project, "Chat")

    override fun chartContext(): ChartChatContext = chart

    override fun create(suggestFollowups: (List<String>) -> Unit): ChatBackend {
        val client = ChatSettings.getInstance().createClient()
        val tools = chartTools(chart, suggestFollowups) + projectTools(project, sourcePoints)
            .filter { it.definition.name in SOURCE_TOOLS }
        return LlmChatBackend(client::complete, tools,
            chartChatSystemPrompt(tools, references = chart is ChartReferences, scoped = chart.scoped),
            maxToolRounds = 100, context = chart::snapshot)
    }

    override fun clear() = sourcePoints.clear()
    override fun dispose() = sourcePoints.dispose()

    private companion object {
        val SOURCE_TOOLS = setOf("read_file", "list_files", "search_text", "point_to_code")
    }
}
