package dev.insight.entrypoints

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportRawProgress
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.insight.agent.ProjectFiles
import dev.insight.agent.ToolInputException
import dev.insight.agent.describeChatFailure
import dev.insight.callgraph.APPLICATION_GRAPH_TOPIC
import dev.insight.callgraph.ApplicationGraphListener
import dev.insight.core.runCatchingReported
import dev.insight.graph.CallGraphResult
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import kotlinx.coroutines.*

private val LOG = logger<EntryPointsPanel>()

/** Independent searches and retained results let users compare both APIs side by side. */
internal class EntryPointsPanel(
    private val project: Project,
) : JPanel(BorderLayout(0, JBUI.scale(8))), Disposable {
    private val service = project.service<EntryPointService>()
    private var disposed = false
    /** The latest application graph, mirrored from the graph panel via the Application Graph topic. */
    private var latestGraph: CallGraphResult? = null
    private val llm = ResultPane(EntryPointApi.LLM)
    private val decisions = ResultPane(EntryPointApi.DECISIONS)

    init {
        border = JBUI.Borders.empty(8)
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, llm, decisions).apply {
            resizeWeight = 0.5
            setDividerLocation(0.5)
        }, BorderLayout.CENTER)
        add(JLabel("Double-click or Enter: open the entry point in the editor"), BorderLayout.SOUTH)
        project.messageBus.connect(this).subscribe(APPLICATION_GRAPH_TOPIC, object : ApplicationGraphListener {
            override fun graphBuilt(result: CallGraphResult) { latestGraph = result }
        })
    }

    private inner class ResultPane(private val api: EntryPointApi) : JPanel(BorderLayout(0, 8)) {
        private var job: Job? = null
        private val findLabel = "Find with ${api.label}"
        private val button = JButton(findLabel)
        private val status = JLabel("Click $findLabel to start.")
        private val model = DefaultListModel<EntryPoint>()
        private val list = JBList(model).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            emptyText.text = "No ${api.label} results yet"
            cellRenderer = Renderer()
        }
        private val details = JLabel()

        init {
            border = JBUI.Borders.empty(4)
            button.addActionListener { if (job?.isActive == true) job?.cancel() else start() }
            add(JPanel(BorderLayout(0, 4)).apply {
                add(JLabel(api.label), BorderLayout.NORTH)
                add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    add(button)
                    add(JButton("Clear ${api.label} cache").apply {
                        addActionListener {
                            when (api) {
                                EntryPointApi.LLM -> service.cache.clear()
                                EntryPointApi.DECISIONS -> service.decisionsCache.clear()
                            }
                            status.text = "${api.label} cache cleared."
                        }
                    })
                }, BorderLayout.CENTER)
                add(status, BorderLayout.SOUTH)
            }, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(details, BorderLayout.SOUTH)
            list.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) open() }
            })
            list.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) { if (e.keyCode == KeyEvent.VK_ENTER) open() }
            })
        }

        private fun start() {
            if (disposed || job?.isActive == true) return
            job = service.scope.launch(Dispatchers.EDT) {
                button.text = "Cancel ${api.label}"
                status.text = "Finding entry points…"
                status.toolTipText = null
                try {
                    runCatchingReported(LOG,"Entry point search failed") {
                        val result = withBackgroundProgress(project, "Finding entry points with ${api.label}") {
                            reportRawProgress { reporter ->
                                val running = currentCoroutineContext()[Job]
                                service.find(latestGraph, { message ->
                                    reporter.text(message)
                                    SwingUtilities.invokeLater {
                                        if (!disposed && running?.isActive == true) status.text = message
                                    }
                                }, api)
                            }
                        }
                        ensureActive()
                        show(result)
                    }.onFailure {
                        if (!disposed) {
                            val message = "${api.label} failed: ${describeChatFailure(it)}"
                            status.text = message
                            status.toolTipText = message
                        }
                    }
                } catch (e: CancellationException) {
                    if (!disposed) status.text = "Search cancelled. Previous results retained."
                    throw e
                } finally {
                    if (!disposed) button.text = findLabel
                }
            }
        }

        private fun show(result: EntryPointResult) {
            model.clear()
            result.entryPoints.forEach(model::addElement)
            if (!model.isEmpty) list.selectedIndex = 0
            list.emptyText.text = "No entry points found with ${api.label}"
            status.text = "${result.entryPoints.size} entry points · " + if (result.cached) "cached result" else
                "${result.filesRead.size} files read · ${result.requestCount} API requests"
            details.text = "<html><body style='width: 360px'>${StringUtil.escapeXmlEntities(result.summary)}</body></html>"
        }

        private fun open() {
            val entry = list.selectedValue ?: return
            // resolve() queries the project file index, which requires a read action even on the EDT.
            val file = runReadAction {
                try { ProjectFiles(project).resolve(entry.path) } catch (_: ToolInputException) { null }
            } ?: return
            OpenFileDescriptor(project, file, entry.line - 1, 0).navigate(true)
        }

        fun cancel() { job?.cancel() }
    }

    private class Renderer : ColoredListCellRenderer<EntryPoint>() {
        override fun customizeCellRenderer(list: JList<out EntryPoint>, value: EntryPoint?, index: Int, selected: Boolean, focused: Boolean) {
            value ?: return
            append(value.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            append("  ${value.kind}", SimpleTextAttributes.GRAY_ATTRIBUTES)
            append("  ${value.path}:${value.line}", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES)
            if (value.symbol.isNotEmpty()) append("  ${value.symbol}", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            append("  ${(value.confidence * 100).toInt()}%", SimpleTextAttributes.GRAY_SMALL_ATTRIBUTES)
            toolTipText = value.description.takeIf { it.isNotEmpty() }
        }
    }

    override fun dispose() { disposed = true; llm.cancel(); decisions.cancel() }
}
