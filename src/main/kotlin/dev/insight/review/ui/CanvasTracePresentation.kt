package dev.insight.review.ui

import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import javax.swing.*

/** Hover and search updates keep the live components and both viewports intact. */
internal fun updateCanvasTracePresentation(card: JPanel, state: SummaryTraceState) {
    val results = state.resultTrace
    descendantsOf(card).forEach { component ->
        if (component is javax.swing.text.JTextComponent) updateWordTracing(component)
        if (component is JComponent && results == null) resetTraceDecoration(component)
    }
}

/** Scrolls a traced match into view: its card in a hierarchy, else its outline row. */
internal fun revealCanvasTrace(body: Container, target: String) {
    val hierarchy = descendantsOf(body).filterIsInstance<HierarchyColumnsPanel>().firstOrNull()
    val row = hierarchy?.anchors?.get(target)
        ?: descendantsOf(body).filterIsInstance<JComponent>().firstOrNull { it.name == "summary:$target" } ?: return
    row.scrollRectToVisible(Rectangle(0, 0, row.width, row.height))
}

private fun descendantsOf(component: Container): List<Component> =
    component.components.flatMap { listOf(it) + ((it as? Container)?.let(::descendantsOf) ?: emptyList()) }
