package dev.insight.diff

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.pom.Navigatable
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.insight.callgraph.FlowChartCanvas
import dev.insight.core.runCatchingReported
import dev.insight.enrichment.EnrichmentService
import dev.insight.graph.FlowRegion
import dev.insight.graph.FlowRegionPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.beans.PropertyChangeListener
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu

private val LOG = logger<DiffFlowChartEditor>()

class DiffFlowChartEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is DiffFlowChartFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = DiffFlowChartEditor(project, file as DiffFlowChartFile)
    override fun getEditorTypeId() = "insight-diff-flowchart"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/**
 * A diff's flow chart: changed functions head the columns, calls between changes are open and the rest is closed
 * context. Compact boards read one step above source (conditions, loops, grouped calls and summaries); changed code is
 * never grouped away. Summarizing subflows names the grouped calls with a model; it stops once the source changes.
 */
private class DiffFlowChartEditor(private val project: Project, private val file: DiffFlowChartFile) : UserDataHolderBase(), FileEditor {
    private val chart = file.chart
    private val regionPlans = HashMap<String, List<FlowRegionPlan>>()
    private var source = chart.source(regionPlans)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    private var summaryJob: Job? = null
    private var disposed = false
    private val status = JLabel()
    private val selection = JLabel(HINT)
    private val compact = JCheckBox("Compact", true).apply {
        toolTipText = "Group calls into subflows and fold helpers; changed code always stays visible"
    }
    private val summarize = JButton("Summarize subflows").apply {
        toolTipText = "Name the grouped calls with the model; titles are interpretations, the calls stay source-derived"
    }
    private val canvas = FlowChartCanvas(::describe, ::navigate, ::menu, { updateControls() },
        "No changed functions or classes in ${file.title.substringBefore(" · ")} to chart. Open it on canvas to explore the source.",
        ::describeRegion)
    private val panel = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
        border = JBUI.Borders.empty(6)
        add(JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                add(JLabel(file.title))
                add(compact)
                add(summarize)
            }, BorderLayout.WEST)
            add(status, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(JBScrollPane(canvas), BorderLayout.CENTER)
        add(selection, BorderLayout.SOUTH)
    }

    init {
        canvas.setCompact(true)
        canvas.show(source)
        canvas.setBoard(chart.board(source))
        compact.addActionListener {
            canvas.setCompact(compact.isSelected)
            canvas.setBoard(expandRegionsHolding(canvas.board, source, chart.plan.open))
        }
        summarize.addActionListener { if (summaryJob?.isActive == true) summaryJob?.cancel() else summarizeSubflows() }
        updateControls()
    }

    private fun updateControls() {
        val shown = canvas.chart
        val changed = shown?.steps.orEmpty().count { it.node in chart.changes }
        status.text = listOfNotNull(
            shown?.let { "${it.lanes.size} columns · ${it.steps.size} steps · $changed changed · ${it.regions.size} subflows" },
            "${file.unavailable.size} files not analyzed".takeIf { file.unavailable.isNotEmpty() },
        ).joinToString(" · ")
        status.toolTipText = file.unavailable.takeIf { it.isNotEmpty() }
            ?.joinToString(", ", "Missing, unsupported, or different from the reviewed source: ")
        summarize.text = if (summaryJob?.isActive == true) "Stop summary" else "Summarize subflows"
        summarize.isEnabled = summaryJob?.isActive == true || (canvas.board.compact && candidates().isNotEmpty())
    }

    /** Structural subflows on the chart that have no interpretation yet. */
    private fun candidates() = canvas.chart?.regions.orEmpty().filter { !it.plan.inferred }.map { it.plan.evidence }
        .filter { it.key !in regionPlans }.distinctBy { it.key }

    private fun describe(id: Int?) {
        val node = id?.let { chart.graph.nodes.getOrNull(it) }
        selection.text = if (node == null) HINT else listOfNotNull("${node.kind.name.lowercase()} ${node.name}",
            node.location.ifEmpty { null }, chart.changes[node.id]?.label ?: "unchanged").joinToString(" · ")
        updateControls()
    }

    private fun describeRegion(region: FlowRegion?) {
        if (region == null) return
        selection.text = "${region.plan.title} · ${region.plan.evidence.sites.size} call occurrences in " +
            chart.lens.card(region.plan.evidence.owner).title
        updateControls()
    }

    private fun navigate(id: Int) {
        (file.result.pointers.getOrNull(id)?.element?.navigationElement as? Navigatable)?.takeIf { it.canNavigate() }?.navigate(true)
    }

    private fun menu(id: Int?): JPopupMenu? = JPopupMenu().apply {
        fun item(text: String, action: () -> Unit) = add(JMenuItem(text).apply { addActionListener { action() } })
        val board = canvas.board
        val step = id?.let { canvas.chart?.step(it) }
        if (id != null && file.result.pointers.getOrNull(id) != null) item("Open source") { navigate(id) }
        if (step != null && (step.calls > 0 || step.callers > 0)) {
            addSeparator()
            if (step.calls > 0) item(if (step.node in board.openCalls) "Hide calls" else "Show calls (${step.calls})") { canvas.toggleCalls(step.node) }
            if (step.callers > 0) item(if (step.node in board.openCallers) "Hide callers" else "Show callers (${step.callers})") { canvas.toggleCallers(step.node) }
        }
        if (id != null && board.starts.any { it.node == id }) {
            addSeparator()
            item("Remove column") { canvas.removeColumn(id) }
        }
    }.takeIf { it.componentCount > 0 }

    /** Whether the charted files still have the source this chart was read from. */
    private suspend fun sourceUnchanged(): Boolean = readAction {
        file.stamps.all { (path, stamp) ->
            val virtual = LocalFileSystem.getInstance().findFileByPath(path) ?: return@all false
            FileDocumentManager.getInstance().getDocument(virtual)?.modificationStamp == stamp
        }
    }

    private fun summarizeSubflows() {
        val batch = candidates().take(MAX_SUMMARY_BATCH)
        if (batch.isEmpty()) return
        val pending = candidates().size
        summaryJob = scope.launch {
            try {
                selection.text = "Summarizing ${batch.size} subflows…"
                updateControls()
                if (!sourceUnchanged()) {
                    selection.text = "Source changed since this flowchart opened. Reopen it from Diff Review to summarize."
                    return@launch
                }
                runCatchingReported(LOG, "Summarizing diff subflows failed") {
                    val summaries = withBackgroundProgress(project, "Summarizing subflows") {
                        project.service<EnrichmentService>().summarizeSubflows(batch, chart.facts, chart.lens.freshSummaries(chart.facts))
                    }
                    if (disposed) return@launch
                    if (!sourceUnchanged()) {
                        selection.text = "Source changed during the summary; stale interpretations were discarded."
                        return@launch
                    }
                    regionPlans += summaries.plans
                    source = chart.source(regionPlans)
                    canvas.show(source)
                    canvas.setBoard(expandRegionsHolding(canvas.board, source, chart.plan.open))
                    selection.text = "${summaries.plans.values.sumOf { it.size }} LLM subflows · ${summaries.failedBlocks} kept structural" +
                        (if (pending > batch.size) " · summarize again for the remaining subflows" else "")
                }.onFailure {
                    if (!disposed) selection.text = "Could not summarize; structural subflows remain available."
                }
            } catch (e: CancellationException) {
                if (!disposed) selection.text = "Summary stopped; existing subflows retained."
                throw e
            } finally {
                if (summaryJob === coroutineContext.job) summaryJob = null
                if (!disposed) updateControls()
            }
        }
        updateControls()
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = canvas
    override fun getName() = "Flowchart"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = false
    override fun isValid() = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() {
        disposed = true
        scope.cancel()
    }

    private companion object {
        const val MAX_SUMMARY_BATCH = 24
        const val HINT = "Amber dots changed in this diff, grey dots are context · double-click opens source · " +
            "Right/Space toggles calls · Left toggles callers"
    }
}
