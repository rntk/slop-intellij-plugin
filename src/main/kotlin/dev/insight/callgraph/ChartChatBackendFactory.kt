package dev.insight.callgraph

import com.intellij.openapi.project.Project
import dev.insight.agent.ChatBackend
import dev.insight.chat.projectTools
import dev.insight.chat.ChartChatContext
import dev.insight.chat.ChartReferences
import dev.insight.chat.ChatBackendFactory
import dev.insight.chat.LlmChatBackend
import dev.insight.chat.SourcePoints
import dev.insight.settings.ChatSettings

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
