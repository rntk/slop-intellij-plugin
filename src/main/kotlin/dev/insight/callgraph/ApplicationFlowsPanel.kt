package dev.insight.callgraph

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.pom.Navigatable
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.enrichment.EnrichmentService
import dev.insight.entrypoints.EntryPoint
import dev.insight.entrypoints.EntryPointService
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.MouseWheelEvent
import javax.swing.*
import kotlin.math.pow

/**
 * The Flows tab: each entrypoint is split into logical flows, and every flow is drawn as its own small graph instead
 * of a subset of the whole application graph. Flows of the same entrypoint are still drawn separately.
 */
internal class ApplicationFlowsPanel(
    private val project: Project,
    private val latest: () -> CallGraphResult?,
    private val building: () -> Boolean,
    private val refresh: () -> Unit,
    private val showInGraph: (Int) -> Unit,
    private val addToChart: (Int) -> Unit,
    private val openInCanvas: (Int) -> Unit,
) : JPanel(BorderLayout(0, JBUI.scale(8))), Disposable {
    private val service = project.service<CallGraphService>()
    private val enrichment = project.service<EnrichmentService>()
    private val entryPoints = project.service<EntryPointService>()

    /** A finished split, kept until the next one: its ids refer to [built], which may since have been replaced. */
    private class Split(val built: CallGraphResult, val entries: List<EntryPoint>, val result: ApplicationFlowResult)
    private var split: Split? = null
    private var job: Job? = null
    private var entryPointJob: Job? = null
    private var generation = 0
    private var disposed = false
    private var manualEntry: EntryPoint? = null
    private val sourceLabel = JLabel("Source: discovered entrypoints")
    private val chooseEntryButton = JButton("Choose entrypoint…").apply {
        toolTipText = "Search project functions by name or path and choose where flow splitting starts"
    }
    private val discoveredButton = JButton("Use discovered entrypoints").apply { isEnabled = false }

    private fun selectedEntries(): List<EntryPoint> = manualEntry?.let(::listOf) ?: entryPoints.entryPoints.value

    private fun setManualEntry(entry: EntryPoint?) {
        manualEntry = entry
        sourceLabel.text = entry?.let { "Source: ${it.title} · ${it.path}:${it.line}" } ?: "Source: discovered entrypoints"
        sourceLabel.toolTipText = sourceLabel.text
        discoveredButton.isEnabled = entry != null
        updateStatus()
    }

    private class ManualChoice(val entry: EntryPoint) {
        override fun toString() = "${entry.title} · ${entry.path}:${entry.line}"
    }

    private fun chooseEntryPoint() {
        if (building()) {
            status.text = "Wait for the application graph to finish building, then choose an entrypoint."
            return
        }
        val built = latest()
        if (built == null) {
            status.text = "Click Refresh to build the application graph, then choose an entrypoint."
            return
        }
        val choices = built.graph.nodes.mapNotNull { it.manualFlowEntryPoint(project.basePath) }
            .sortedWith(compareBy({ it.title }, { it.path }, { it.line })).map(::ManualChoice)
        if (choices.isEmpty()) {
            status.text = "No project functions found in the graph. Click Refresh to rebuild it."
            return
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
            .setTitle("Choose a function as entrypoint")
            .setNamerForFiltering { it.toString() }
            .setMovable(true)
            .setResizable(true)
            .setItemChosenCallback { choice -> if (!disposed) setManualEntry(choice.entry) }
            .createPopup().showUnderneathOf(chooseEntryButton)
    }

    private val splitButton = JButton("Split flows").apply {
        toolTipText = "Split each selected entrypoint into logical flows with the LLM and draw every flow as its own graph"
    }
    private val entryChoice = JComboBox<EntryChoice>().apply {
        toolTipText = "Show the flows of one entrypoint, or of all of them"
        isEnabled = false
    }
    private val zoomChoice = JComboBox(arrayOf("50%", "75%", "100%", "125%")).apply {
        selectedItem = "100%"
        toolTipText = "Zoom of every flow graph · Ctrl/Cmd + wheel zooms one graph"
    }
    private val status = JLabel()
    private val selection = JLabel(HINT)
    private val list = object : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) = JBUI.scale(24)
        override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) =
            if (orientation == SwingConstants.VERTICAL) visible.height - JBUI.scale(24) else visible.width
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }.apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(4)
    }
    private val scroll = JBScrollPane(list).apply { border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 1, 0) }
    private val canvases = mutableListOf<CallGraphCanvas>()
    /** The canvas holding the selection, so selecting in one graph clears the others. */
    private var active: CallGraphCanvas? = null

    private class EntryChoice(val entry: EntryPoint?, val flows: Int) {
        override fun toString() = (entry?.title ?: "All entrypoints") + " ($flows ${if (flows == 1) "flow" else "flows"})"
    }

    init {
        border = JBUI.Borders.empty(8)
        splitButton.addActionListener { if (job?.isActive == true) job?.cancel() else splitFlows() }
        chooseEntryButton.addActionListener { chooseEntryPoint() }
        discoveredButton.addActionListener { setManualEntry(null) }
        entryChoice.addActionListener { showFlows() }
        zoomChoice.addActionListener {
            val zoom = zoomChoice.selectedItem.toString().removeSuffix("%").toDouble() / 100
            for (canvas in canvases) canvas.zoom = zoom
            list.revalidate()
        }
        add(JPanel(BorderLayout(0, 4)).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(JButton("Refresh").apply { toolTipText = "Rebuild the application graph"; addActionListener { refresh() } })
                add(splitButton)
                add(JLabel("Show flows:"))
                add(entryChoice)
                add(JLabel("Zoom:"))
                add(zoomChoice)
            }, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
                add(chooseEntryButton)
                add(discoveredButton)
                add(sourceLabel)
            }, BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, 4)).apply {
            add(selection, BorderLayout.NORTH)
            add(JLabel("One graph per flow, read left to right from its entrypoint · Click: select · Double-click/Enter: open source · " +
                "Right-click: menu · Drag: pan · Ctrl/Cmd + wheel: zoom"), BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        showFlows()
        entryPointJob = service.scope.launch(Dispatchers.EDT) {
            entryPoints.entryPoints.collect { if (!disposed) updateStatus() }
        }
    }

    /** Called after every graph build; existing flows stay visible but are marked out of date. */
    fun graphChanged() = updateStatus()

    private fun splitFlows() {
        if (building()) {
            status.text = "Wait for the application graph to finish building, then click Split flows."
            return
        }
        val built = latest() ?: run {
            status.text = "Click Refresh to build the application graph, then click Split flows."
            return
        }
        val entries = selectedEntries()
        if (entries.isEmpty()) {
            status.text = "Choose an entrypoint here or find entrypoints in the Entry Points tab, then click Split flows."
            return
        }
        val request = ++generation
        splitButton.text = "Cancel"
        chooseEntryButton.isEnabled = false
        discoveredButton.isEnabled = false
        status.text = "Splitting entrypoint flows…"
        job = service.scope.launch(Dispatchers.EDT) {
            try {
                val computed = enrichment.splitFlows(built, entries) { message ->
                    SwingUtilities.invokeLater { if (!disposed && request == generation && job?.isActive == true) status.text = message }
                }
                if (disposed || request != generation) return@launch
                split = Split(built, entries, computed)
                job = null
                showFlows()
            } catch (e: CancellationException) {
                if (!disposed && request == generation) status.text = "Flow splitting cancelled."
                throw e
            } catch (e: Exception) {
                thisLogger().warn("Flow splitting failed", e)
                if (!disposed && request == generation) status.text = "Could not split flows: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                if (!disposed && request == generation) {
                    job = null
                    splitButton.text = "Split flows"
                    chooseEntryButton.isEnabled = true
                    discoveredButton.isEnabled = manualEntry != null
                }
            }
        }
    }

    /** Fills the entrypoint filter, keeping the chosen entrypoint while it still has flows, and redraws the list. */
    private fun showFlows() {
        val current = split
        val flows = current?.result?.flows.orEmpty()
        val chosen = (entryChoice.selectedItem as? EntryChoice)?.entry
        val byEntry = flows.groupBy { it.entry }
        val choices = listOf(EntryChoice(null, flows.size)) + byEntry.map { (entry, of) -> EntryChoice(entry, of.size) }
        val listeners = entryChoice.actionListeners
        listeners.forEach(entryChoice::removeActionListener)
        try {
            entryChoice.model = DefaultComboBoxModel(choices.toTypedArray())
            entryChoice.selectedItem = choices.firstOrNull { it.entry != null && it.entry == chosen } ?: choices.first()
        } finally { listeners.forEach(entryChoice::addActionListener) }
        entryChoice.isEnabled = byEntry.size > 1
        renderList()
        updateStatus()
    }

    private fun renderList() {
        list.removeAll()
        canvases.clear()
        active = null
        selection.text = HINT
        val current = split
        if (current == null || current.result.flows.isEmpty() && current.result.unmatched.isEmpty()) {
            list.add(note(if (current == null) "Choose an entrypoint here or find entrypoints in the Entry Points tab, then click Split flows. " +
                "Each entrypoint is split into logical flows, and every flow gets its own small graph."
                else "No flows were found for the current entrypoints."))
        } else {
            val only = (entryChoice.selectedItem as? EntryChoice)?.entry
            val starts = current.result.flows.mapTo(hashSetOf()) { it.root }
            for ((entry, flows) in current.result.flows.groupBy { it.entry }) {
                if (only != null && entry != only) continue
                list.add(entryHeader(entry, flows.size))
                flows.forEachIndexed { index, flow -> list.add(flowCard(current, flow, index + 1, flows.size, starts)) }
                list.add(Box.createVerticalStrut(JBUI.scale(16)))
            }
            if (only == null && current.result.unmatched.isNotEmpty()) list.add(note("Not found in the graph, so not split: " +
                current.result.unmatched.joinToString("; ") { "${it.title} (${it.path}:${it.line})" }))
        }
        list.add(Box.createVerticalGlue())
        list.revalidate()
        list.repaint()
        SwingUtilities.invokeLater { scroll.verticalScrollBar.value = 0 }
    }

    private fun entryHeader(entry: EntryPoint, flows: Int) = Row(BorderLayout(0, 2)).apply {
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.empty(4, 0)
        add(JLabel("▶ ${entry.title}").apply { font = font.deriveFont(Font.BOLD, font.size2D + 2f) }, BorderLayout.NORTH)
        add(wrapped("${entry.kind} · ${entry.path}:${entry.line} · $flows ${if (flows == 1) "flow" else "flows"}" +
            entry.description.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), muted = true), BorderLayout.CENTER)
    }

    private fun flowCard(current: Split, flow: ApplicationFlow, number: Int, of: Int, starts: Set<Int>): JComponent {
        val drawn = flow.graph(current.built.graph)
        lateinit var canvas: CallGraphCanvas
        canvas = CallGraphCanvas(onSelect = { id -> selected(canvas, current, drawn, flow, id) },
            onOpen = { id -> navigate(current, drawn.sources[id]) }, popup = { id -> menu(current, drawn, id) })
        canvases += canvas
        canvas.show(drawn.graph)
        canvas.zoom = zoomChoice.selectedItem.toString().removeSuffix("%").toDouble() / 100
        canvas.decorate(GraphDecorations(nodes = drawn.sources.withIndex().filter { it.value in starts }.associate { (id, source) ->
            id to NodeDecoration(entryPoint = true, tooltip = if (source == flow.root) "Start of ${flow.entry.title}"
                else "Separate entrypoint: its calls are drawn with its own flows")
        }))
        val view = object : JBScrollPane(canvas) {
            // Small flows take only the room they need; large ones scroll inside the card.
            override fun getPreferredSize(): Dimension {
                val size = canvas.preferredSize
                val bar = horizontalScrollBar.preferredSize.height
                return Dimension(JBUI.scale(200), minOf(size.height, JBUI.scale(MAX_GRAPH_HEIGHT)) + bar + insets.top + insets.bottom)
            }
            override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            viewport.background = UIUtil.getPanelBackground()
        }
        // The page scrolls with the wheel, so each graph zooms with Ctrl/Cmd + wheel only.
        canvas.mouseWheelListeners.forEach(canvas::removeMouseWheelListener)
        canvas.addMouseWheelListener { event ->
            if (event.isControlDown || event.isMetaDown) {
                canvas.zoomAt(canvas.zoom * 1.15.pow(-event.preciseWheelRotation), event.point)
            } else scroll.dispatchEvent(SwingUtilities.convertMouseEvent(canvas, event, scroll) as MouseWheelEvent)
            event.consume()
        }
        canvas.addPropertyChangeListener("zoom") { view.revalidate(); list.revalidate() }

        return Row(BorderLayout(0, 4)).apply {
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.empty(6, 12, 10, 0)
            add(JPanel(BorderLayout(0, 2)).apply {
                add(JLabel("${if (of > 1) "$number. " else ""}${flow.title}").apply { font = font.deriveFont(Font.BOLD) }, BorderLayout.NORTH)
                add(wrapped(listOfNotNull(
                    if (flow.inferred) "LLM grouping" else "structural branch",
                    "${flow.nodes.size} functions",
                    "partial: analysis or size limit reached".takeIf { flow.limited },
                    flow.description.takeIf { it.isNotBlank() },
                ).joinToString(" · "), muted = true), BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(view, BorderLayout.CENTER)
        }
    }

    /** Keeps one selection across all graphs and describes the selected function. */
    private fun selected(canvas: CallGraphCanvas, current: Split, drawn: FlowGraph, flow: ApplicationFlow, id: Int?) {
        if (id == null) {
            if (active === canvas) { active = null; selection.text = HINT }
            return
        }
        if (active !== canvas) {
            val previous = active
            active = canvas
            previous?.select(null)
        }
        val node = drawn.graph.nodes[id]
        val calls = drawn.graph.edges.count { it.caller == id && it.kind == RelationKind.CALL }
        val callers = drawn.graph.edges.count { it.callee == id && it.kind == RelationKind.CALL }
        selection.text = "${node.name} · ${node.container.ifEmpty { node.location }} · in ${flow.title} · " +
            "$callers callers and $calls calls in this flow" +
            (if (drawn.sources[id] != flow.root && current.result.flows.any { it.root == drawn.sources[id] }) " · separate entrypoint" else "")
    }

    private fun menu(current: Split, drawn: FlowGraph, id: Int): JPopupMenu {
        val source = drawn.sources[id]
        // Other tabs use the current graph's ids, which match only while the split's graph is still current.
        val fresh = latest() === current.built
        val function = current.built.graph.nodes[source].kind == SymbolKind.FUNCTION
        fun item(text: String, enabled: Boolean = fresh, action: () -> Unit) = JMenuItem(text).apply {
            isEnabled = enabled
            if (!enabled) toolTipText = "The code changed since this split; click Split flows to update"
            addActionListener { action() }
        }
        return JPopupMenu().apply {
            add(item("Open source", enabled = current.built.pointers.getOrNull(source) != null) { navigate(current, source) })
            add(item("Show in application graph") { showInGraph(source) })
            if (function) add(item("Add to flow chart") { addToChart(source) })
            if (function) add(item("Open in canvas") { openInCanvas(source) })
        }
    }

    private fun navigate(current: Split, source: Int) {
        (current.built.pointers.getOrNull(source)?.element?.navigationElement as? Navigatable)
            ?.takeIf { it.canNavigate() }?.navigate(true)
    }

    private fun updateStatus() {
        if (job?.isActive == true) return
        val current = split ?: run {
            status.text = if (selectedEntries().isEmpty()) "Choose an entrypoint here or find them in the Entry Points tab."
                else "${selectedEntries().size} entrypoints selected · click Split flows."
            return
        }
        val result = current.result
        val stale = listOfNotNull(
            "the code changed".takeIf { latest().let { it != null && it !== current.built } },
            "the entrypoints changed".takeIf { selectedEntries() != current.entries },
        )
        status.text = "${result.flows.size} flows from ${result.flows.distinctBy { it.entry }.size} entrypoints" +
            (if (result.unmatched.isNotEmpty()) " · ${result.unmatched.size} entrypoints not in the graph" else "") +
            (if (result.failures > 0) " · ${result.failures} model batches unavailable; structural branches used" else "") +
            (if (stale.isNotEmpty()) " · out of date: ${stale.joinToString(" and ")}; click Split flows to update" else "")
    }

    private fun note(text: String) = wrapped(text, muted = true).apply {
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.empty(8, 0)
    }

    /** A label that wraps to the list width. */
    private fun wrapped(text: String, muted: Boolean) = object : JLabel("<html>${StringUtil.escapeXmlEntities(text)}</html>") {
        override fun getPreferredSize(): Dimension {
            val width = (scroll.viewport.width - JBUI.scale(40)).coerceAtLeast(JBUI.scale(200))
            val view = getClientProperty("html") as? javax.swing.text.View ?: return super.getPreferredSize()
            view.setSize(width.toFloat(), 0f)
            return Dimension(width, view.getPreferredSpan(javax.swing.text.View.Y_AXIS).toInt())
        }
        override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
    }.apply {
        alignmentX = LEFT_ALIGNMENT
        if (muted) foreground = UIUtil.getContextHelpForeground()
    }

    override fun dispose() { disposed = true; job?.cancel(); entryPointJob?.cancel() }

    private companion object {
        const val HINT = "Select a function to see where it sits; double-click to open its source."
        const val MAX_GRAPH_HEIGHT = 420
    }
}

/** A list row as tall as its content and as wide as the list. */
private class Row(layout: LayoutManager) : JPanel(layout) {
    override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
}
