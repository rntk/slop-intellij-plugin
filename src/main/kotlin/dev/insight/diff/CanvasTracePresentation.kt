package dev.insight.diff

import com.intellij.openapi.util.Key
import com.intellij.ui.ClientProperty
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.*

private val TRACE_PRESENTATION_UPDATE = Key.create<() -> Unit>("insight.note.tracePresentationUpdate")

/** Hover and search updates keep the live components and both viewports intact. */
internal fun updateCanvasTracePresentation(card: JPanel, state: SummaryTraceState) {
    val results = state.resultTrace
    descendantsOf(card).forEach { component ->
        if (component is javax.swing.text.JTextComponent) updateWordTracing(component)
        if (component is JComponent && results == null) resetTraceDecoration(component)
        ClientProperty.get(component, TRACE_PRESENTATION_UPDATE)?.invoke()
    }
}

/** Scrolls a traced match into view: its card in a hierarchy, else its outline row. */
internal fun revealCanvasTrace(body: Container, target: String) {
    val hierarchy = descendantsOf(body).filterIsInstance<HierarchyColumnsPanel>().firstOrNull()
    val row = hierarchy?.anchors?.get(target)
        ?: descendantsOf(body).filterIsInstance<JComponent>().firstOrNull { it.name == "summary:$target" } ?: return
    row.scrollRectToVisible(Rectangle(0, 0, row.width, row.height))
}

/**
 * Two fixed-height status lines: changing hover queries never changes the reading area's geometry. [where] tells what
 * became of a search's matches.
 */
internal fun canvasTraceStatusLines(
    state: SummaryTraceState, session: SummaryTraceSession, width: Int, selection: Boolean = false,
    where: String = "highlighted in the view.",
): List<JComponent> {
    fun line(text: String, name: String, lineWidth: Int = width) = JLabel(text).apply {
        this.name = name
        isOpaque = false
        font = JBFont.small().zoomed(1.0); foreground = UIUtil.getContextHelpForeground()
        toolTipText = text.takeIf { it.isNotBlank() }
        preferredSize = Dimension(lineWidth.coerceAtLeast(1), getFontMetrics(font).height)
    }
    return listOf(line(tracingHint(selection), "tracing-hint"), JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        isOpaque = false
        add(line(" ", "trace-status", width - JBUI.scale(56)).apply {
            val update = {
                val tracing = state.tracing
                text = tracing?.let { traceStatus(it, where) } ?: " "
                toolTipText = text.takeIf { it.isNotBlank() }
                foreground = if (tracing?.trace?.related.isNullOrEmpty()) UIUtil.getContextHelpForeground() else TraceStyle.MATCH_TEXT
            }
            ClientProperty.put(this, TRACE_PRESENTATION_UPDATE, update)
            update()
        }, BorderLayout.CENTER)
        add(ActionLink("Clear") { session.clear() }.apply {
            name = "trace-clear"; font = JBFont.small().zoomed(1.0); isEnabled = state.tracing != null || state.resultTrace != null
            ClientProperty.put(this, TRACE_PRESENTATION_UPDATE) { isEnabled = state.tracing != null || state.resultTrace != null }
        }, BorderLayout.EAST)
    })
}

private fun descendantsOf(component: Container): List<Component> =
    component.components.flatMap { listOf(it) + ((it as? Container)?.let(::descendantsOf) ?: emptyList()) }
