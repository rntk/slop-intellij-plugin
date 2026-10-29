package dev.insight.review

import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import dev.insight.callgraph.ExplainChartResult
import dev.insight.agent.ChatMessage
import dev.insight.agent.ChatRole
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

/** The latest explanation stays in the review canvas, even when its chat or the Application Graph is hidden. */
internal data class ExplainResult(
    val command: String,
    val text: String,
    val running: Boolean,
    val chart: ExplainChartResult? = null,
)

/** Observes the owning chat on the EDT, including failures and cancellation, without changing its transcript. */
internal class ExplainResults {
    private var request: ChatMessage? = null
    private var chart: ExplainChartResult? = null
    var result: ExplainResult? = null
        private set

    fun chartPrepared(chart: ExplainChartResult?) { this.chart = chart }

    /** True only when the canvas needs an update; tool progress must not keep selecting the result tab. */
    fun update(messages: List<ChatMessage>, busy: Boolean): Boolean {
        val index = messages.indexOfLast { it.role == ChatRole.USER }
        if (index < 0) {
            request = null
            chart = null
            return (result != null).also { result = null }
        }
        val latest = messages[index]
        if (!isExplainRequest(latest.text)) return false
        if (latest !== request) {
            request = latest
            chart = null
            result = ExplainResult(latest.text, "Explaining… The result will appear here when finished.", running = true)
            return true
        }
        if (busy) return false
        val outcome = messages.drop(index + 1).lastOrNull { it.role == ChatRole.ASSISTANT || it.role == ChatRole.NOTICE }
        val next = ExplainResult(latest.text, outcome?.text?.takeIf { it.isNotBlank() }
            ?: "No explanation was returned. See the review chat for details or try Explain again.", running = false, chart)
        return (next != result).also { result = next }
    }
}

internal fun explanationCard(result: ExplainResult): JPanel = JPanel(BorderLayout(0, JBUI.scale(12))).apply {
    border = JBUI.Borders.empty(12)
    fun text(value: String) = JBTextArea(value).apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        columns = 80
        caretPosition = 0
    }
    add(text(result.command), BorderLayout.NORTH)
    add(text(result.text), BorderLayout.CENTER)
    when (val chart = result.chart) {
        is ExplainChartResult.Ready -> add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(JButton("Open Flow Chart").apply { addActionListener { chart.chart.open() } })
        }, BorderLayout.SOUTH)
        is ExplainChartResult.Unavailable -> add(text("Flow Chart unavailable: ${chart.reason}"), BorderLayout.SOUTH)
        null -> Unit
    }
}
