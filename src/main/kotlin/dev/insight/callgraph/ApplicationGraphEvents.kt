package dev.insight.callgraph

import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.intellij.openapi.wm.ToolWindowManager
import dev.insight.graph.CallGraphResult
import dev.insight.graph.FlowStart
import java.util.EventListener
import javax.swing.JComponent

/**
 * Project-level events that coordinate the panels of the Application Graph tool window. Panels subscribe to this
 * topic instead of holding references to each other, so the tool window factory only creates the panels.
 */
val APPLICATION_GRAPH_TOPIC = Topic(ApplicationGraphListener::class.java, Topic.BroadcastDirection.TO_CHILDREN)

interface ApplicationGraphListener : EventListener {
    /** A rebuild of the application graph was scheduled, including any debounce delay. */
    fun graphBuilding() {}

    /** A new application graph was built and replaces any cached one. */
    fun graphBuilt(result: CallGraphResult) {}

    /** A rebuild failed; [message] is what the graph panel shows. */
    fun graphBuildFailed(message: String) {}

    /** Enrichment finished; lenses and the report action need a refresh. */
    fun enrichmentChanged() {}

    /** The graph panel's status line changed. */
    fun statusChanged(text: String) {}

    /** The graph panel started or finished a long-running action (building or enriching). */
    fun busyChanged(busy: Boolean) {}

    /** Commands routed to whichever panel owns them. */
    fun rebuildRequested() {}
    fun enrichRequested() {}
    fun reportRequested() {}
    fun showInGraph(source: Int) {}
    fun addToChart(start: FlowStart) {}
    fun openInCanvas(source: Int) {}
}

/** Publishes [block] on [APPLICATION_GRAPH_TOPIC]; call on the EDT. */
internal fun Project.publishGraphEvent(block: ApplicationGraphListener.() -> Unit) {
    messageBus.syncPublisher(APPLICATION_GRAPH_TOPIC).block()
}

/** Selects the tab showing [component] synchronously, without opening the window over an active review chat. */
internal fun Project.activateGraphTab(component: JComponent) {
    val window = ToolWindowManager.getInstance(this).getToolWindow(TOOL_WINDOW_ID) ?: return
    val content = window.contentManager.contents.firstOrNull { it.component === component } ?: return
    window.contentManager.setSelectedContent(content)
}
