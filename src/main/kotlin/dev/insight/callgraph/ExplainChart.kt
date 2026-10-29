package dev.insight.callgraph

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import dev.insight.agent.ChatTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The Flow Chart's tools for a review chat turn, with the explained functions as JSON; see [FlowChartPanel.explainSession]. */
class ExplainChart(val tools: List<ChatTool>, val functions: String, val open: () -> Unit)

/** A chart for a turn, or why there is none. */
sealed interface ExplainChartResult {
    class Ready(val chart: ExplainChart) : ExplainChartResult
    class Unavailable(val reason: String) : ExplainChartResult
}

/**
 * Prepares the Flow Chart for an /explain turn about the files at [paths] (absolute). With [wait], creates the Application
 * Graph's content, which builds the graph on first use, and waits for a finished graph; without, uses the chart only if ready.
 * Follow-ups belong to the review chat, so the chart's suggest_followups is left out.
 */
suspend fun explainChart(project: Project, paths: Set<String>, wait: Boolean): ExplainChartResult {
    val panel = withContext(Dispatchers.EDT) {
        val window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return@withContext null
        // Reading the content manager creates the content, which starts the graph build, without showing the window:
        // it shares a side with the review windows, and showing it would hide the chat that streams the reply.
        if (!wait && window.contentManagerIfCreated == null) null
        else window.contentManager.contents.firstNotNullOfOrNull { it.component as? FlowChartPanel }
    } ?: return ExplainChartResult.Unavailable("the Application Graph has not been opened.")
    if (!withContext(Dispatchers.EDT) { panel.chartReady }) {
        if (!wait) return ExplainChartResult.Unavailable("the application graph is still building.")
        withTimeoutOrNull(GRAPH_WAIT_MS) {
            while (!withContext(Dispatchers.EDT) { panel.chartReady }) delay(GRAPH_POLL_MS)
        } ?: return ExplainChartResult.Unavailable("the application graph did not finish building in time.")
    }
    return withContext(Dispatchers.EDT) {
        if (!panel.chartReady) return@withContext ExplainChartResult.Unavailable("the application graph is rebuilding.")
        val (context, functions) = panel.explainSession(paths)
        ExplainChartResult.Ready(ExplainChart(chartTools(context).filter { it.definition.name != "suggest_followups" }, functions) {
            val window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
            window?.activate {
                window.contentManager.contents.firstOrNull { it.component === panel }?.let {
                    window.contentManager.setSelectedContent(it)
                }
            }
        })
    }
}

private const val GRAPH_WAIT_MS = 120_000L
private const val GRAPH_POLL_MS = 500L
