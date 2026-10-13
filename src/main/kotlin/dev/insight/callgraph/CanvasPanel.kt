package dev.insight.callgraph

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportRawProgress
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.insight.enrichment.CanvasLine
import dev.insight.enrichment.EnrichmentProgress
import dev.insight.enrichment.EnrichmentService
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*

/** Independent reading state, sharing the application graph and its configured LLM service. */
internal class CanvasPanel(
    private val project: Project,
    private val activateCanvas: () -> Unit,
    private val refresh: () -> Unit,
    private val enrich: () -> Unit,
) : JPanel(BorderLayout(0, JBUI.scale(8))), Disposable {
    private val service = project.service<CallGraphService>()
    private val enrichment = project.service<EnrichmentService>()
    private var result: CallGraphResult? = null
    private var job: Job? = null
    private var disposed = false
    private var updating = false
    private var root: Int? = null
    private var enriching = false
    private var revision = 0
    private val scopeLabel = JLabel("Application functions")
    private val status = JLabel("Building application graph…")
    private val flowChoice = flowChoiceControl()
    private val generate = JButton("Generate canvas")
    private val enrichButton = JButton("Enrich")
    internal val readingView = CanvasReadingView(::navigate)
    private val mode = JCheckBox("Summary mode")
    private val depth = JComboBox(arrayOf(1, 2, 3, 4, 5, 6)).apply { selectedItem = 3 }

    init {
        border = JBUI.Borders.empty(8)
        add(JPanel(BorderLayout(0, 4)).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(JButton("Refresh").apply { addActionListener { refresh() } })
                add(enrichButton.apply { addActionListener { enrich() } })
                add(JLabel("Flow:")); add(flowChoice); add(scopeLabel); add(generate)
                add(JButton("Application scope").apply { addActionListener { root = null; flowChoice.selectedIndex = 0; scopeLabel.text = "Application functions"; invalidateReading() } })
                add(JLabel("Depth:")); add(depth); add(mode)
                add(zoomControl({ readingView.zoom }, { readingView.zoom = it }, readingView))
            }, BorderLayout.NORTH)
            add(status, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(JBScrollPane(readingView), BorderLayout.CENTER)
        add(JLabel("Click topics to focus · Double-click source or press Enter to open · Drag to pan · Ctrl/Cmd + wheel to zoom"), BorderLayout.SOUTH)
        generate.toolTipText = "Group source lines into topics and summarize them using the configured LLM."
        generate.addActionListener { if (job?.isActive == true) job?.cancel() else generateCanvas() }
        flowChoice.addActionListener {
            if (!updating) {
                root = null
                val flow = (flowChoice.selectedItem as? FlowChoice)?.flow
                scopeLabel.text = flow?.let { "${it.nodes.size} functions" } ?: "Application functions"
                scopeLabel.toolTipText = flow?.description
                invalidateReading()
            }
        }
        depth.addActionListener { readingView.depth = depth.selectedItem as Int }
        mode.addActionListener { readingView.summaryMode = mode.isSelected }
        generate.isEnabled = false
        refreshLens()
    }

    fun showResult(built: CallGraphResult) {
        val old = result
        val oldRoot = root?.let { id -> old?.graph?.nodes?.firstOrNull { it.id == id } }
        root = oldRoot?.let { node -> built.graph.nodes.firstOrNull {
            it.filePath == node.filePath && it.name == node.name && it.container == node.container && it.kind == node.kind
        }?.id }
        result = built
        invalidateReading()
        refreshLens()
        scopeLabel.text = root?.let { id -> built.graph.nodes.firstOrNull { it.id == id }?.let { "Call path: ${it.name}" } }
            ?: (flowChoice.selectedItem as? FlowChoice)?.flow?.let { "${it.nodes.size} functions" } ?: "Application functions"
        status.text = "Choose a scope and Generate canvas to read its topics."
        generate.isEnabled = true
    }

    fun refreshLens() {
        val selected = (flowChoice.selectedItem as? FlowChoice)?.flow?.id
        // Flow ids refer to one graph build, so stale enrichment must not become a scope for a new graph.
        val flows = enrichment.latest?.takeIf { it.graph === result?.graph }?.enrichment?.flows.orEmpty()
        updating = true
        try {
            val choices = listOf(FlowChoice(null)) + flows.map(::FlowChoice)
            flowChoice.model = DefaultComboBoxModel(choices.toTypedArray())
            flowChoice.selectedItem = choices.firstOrNull { it.flow?.id == selected } ?: choices.first()
            flowChoice.isEnabled = flows.isNotEmpty()
            enrichButton.isEnabled = result != null && !enriching
        } finally { updating = false }
    }

    fun setBusy(busy: Boolean) { enriching = busy; enrichButton.isEnabled = !busy && result != null }
    fun setStatus(message: String) { status.text = message }
    fun setBuilding() { invalidateReading(); generate.isEnabled = false; status.text = "Building application graph…" }

    fun openFunction(id: Int) {
        val node = result?.graph?.nodes?.firstOrNull { it.id == id } ?: return
        updating = true
        try { flowChoice.selectedIndex = 0 } finally { updating = false }
        root = id
        scopeLabel.text = "Call path: ${node.name}"
        invalidateReading()
        activateCanvas()
        status.text = "Generate canvas for ${node.name} and its callees."
    }

    private fun invalidateReading() {
        revision++
        job?.cancel()
        generate.text = "Generate canvas"
        readingView.data = null
        status.text = "Choose a scope and Generate canvas to read its topics."
    }

    private fun generateCanvas() {
        val built = result ?: return
        val requestRevision = ++revision
        val flow = (flowChoice.selectedItem as? FlowChoice)?.flow
        val ids = root?.let { start ->
            val calls = built.graph.edges.filter { it.kind == RelationKind.CALL }.groupBy({ it.caller }, { it.callee })
            val seen = linkedSetOf(start); val queue = ArrayDeque<Int>().apply { add(start) }
            while (queue.isNotEmpty()) calls[queue.removeFirst()].orEmpty().forEach { if (seen.add(it)) queue.add(it) }
            seen.toList()
        }
        job = service.scope.launch(Dispatchers.EDT) {
            generate.text = "Cancel"
            status.text = "Grouping source into topics…"
            try {
                val data = withBackgroundProgress(project, "Generating canvas") {
                    reportRawProgress { reporter ->
                        reporter.text("Grouping source into topics…")
                        enrichment.generateCanvas(built, nodeIds = ids, flow = flow,
                            progress = EnrichmentProgress { phase, done, total ->
                                reporter.text(phase)
                                reporter.details("$done / $total")
                                reporter.fraction(if (total > 0) done.toDouble() / total else null)
                                SwingUtilities.invokeLater {
                                    if (!disposed && revision == requestRevision && job?.isActive == true) status.text = "$phase · $done / $total"
                                }
                            })
                    }
                }
                ensureActive()
                if (!disposed && result === built && revision == requestRevision) {
                    readingView.data = data
                    status.text = if (data.document.lines.isEmpty()) "No function source is available in this scope." else "${data.topics.size} topics · ${data.document.lines.size} source lines" +
                        if (data.failures > 0) " · ${data.failures} model requests failed; source remains available. Generate again to retry." else ""
                }
            } catch (e: CancellationException) {
                if (!disposed && revision == requestRevision) status.text = "Canvas generation cancelled."
                throw e
            } catch (e: Exception) {
                if (!disposed && revision == requestRevision) status.text = "Canvas failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                if (!disposed && revision == requestRevision) generate.text = "Generate canvas"
            }
        }
    }

    private fun navigate(line: CanvasLine) {
        val file = line.filePath?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: return
        OpenFileDescriptor(project, file, (line.fileLine - 1).coerceAtLeast(0), 0).navigate(true)
    }

    override fun dispose() { disposed = true; job?.cancel() }
}
