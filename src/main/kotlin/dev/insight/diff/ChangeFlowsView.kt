package dev.insight.diff

import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import kotlin.math.roundToInt

/** Expansion survives re-rendering; keys are only meaningful for the graph they were made for. */
internal class ChangeFlowsViewState {
    val expanded = linkedSetOf<String>()
    var mode = ChangeFlowsMode.MAP
    val selected = mutableMapOf<Int, Int>()
    var graph: ChangeGraph? = null
        private set
    /** A node to scroll to once, after the next render. */
    var reveal: Int? = null

    /** Starts over for a new graph: the most important theme opens, everything else stays folded. */
    fun reset(flows: ChangeFlows?) {
        if (flows?.graph === graph) return
        graph = flows?.graph
        expanded.clear()
        selected.clear()
        reveal = null
        if (flows != null && flows.graph.clusters.isNotEmpty()) expanded += theme(0)
    }

    fun toggle(key: String) { if (!expanded.remove(key)) expanded += key }

    /** Opens [node] with its theme and cluster, for following a call. */
    fun focus(flows: ChangeFlows, node: Int) {
        val cluster = flows.graph.cluster(node) ?: return
        selected[cluster.id] = node
        if (changeMapOrder(flows.graph, cluster).indexOf(node) >= CHANGE_MAP_PREVIEW_STEPS) expanded += mapAll(cluster.id)
        flows.displayThemes().indexOfFirst { theme -> theme.clusters.any { it.id == cluster.id } }.takeIf { it >= 0 }?.let { expanded += theme(it) }
        expanded += cluster(cluster.id)
        if (flows.nodeNotes[node]?.importance == ChangeImportance.MINOR || flows.graph.nodes[node].trivial != null) expanded += minor(cluster.id)
        expanded += node(node)
        reveal = node
    }

    companion object {
        fun theme(index: Int) = "t:$index"
        fun cluster(id: Int) = "c:$id"
        fun node(id: Int) = "n:$id"
        fun diff(id: Int) = "d:$id"
        fun minor(cluster: Int) = "m:$cluster"
        fun steps(id: Int) = "s:$id"
        fun mapAll(cluster: Int) = "map:$cluster"
        const val OTHER = "o"
    }
}

internal enum class ChangeFlowsMode { MAP, OUTLINE }

internal class ChangeFlowActions(
    val toggle: (String) -> Unit,
    val focus: (Int) -> Unit,
    val openNode: (ChangeNode) -> Unit,
    val openCaller: (ChangeCaller) -> Unit,
    val openFile: (String) -> Unit,
)

/**
 * Two views of a [ChangeFlows]: a connected step map and a call tree. Both lead to a function's source outline
 * and literal diff lines. Regular text comes from source analysis; italic text is the model's interpretation.
 */
internal class ChangeFlowsPanel(
    private val flows: ChangeFlows,
    private val state: ChangeFlowsViewState,
    private val cardWidth: Int,
    private val zoom: Double,
    private val actions: ChangeFlowActions,
) : JPanel(VerticalLayout(0)) {
    private val graph = flows.graph
    private fun scaled(n: Int) = zoomScaled(n, zoom)
    private fun font(base: Font) = base.zoomed(zoom)
    private val help get() = UIUtil.getContextHelpForeground()

    init {
        isOpaque = false
        add(overview())
        val themes = flows.displayThemes()
        themes.forEachIndexed { index, theme -> add(theme(index, theme)) }
        flows.otherFiles().takeIf { it.isNotEmpty() }?.let { add(otherFiles(it)) }
        add(text("Regular text, badges, arrows and counts come from source analysis. Italic text is LLM interpretation; open a function to check it against its code.",
            cardWidth, font(JBFont.small()), help).apply { border = pad(top = scaled(10)) })
    }

    private fun overview(): JComponent = JPanel(VerticalLayout(scaled(2))).apply {
        isOpaque = false
        border = pad(bottom = scaled(8))
        val changed = graph.changed
        val counts = listOfNotNull(
            changed.count { it.status == ChangeStatus.ADDED }.takeIf { it > 0 }?.let { "$it new" },
            changed.count { it.status == ChangeStatus.MODIFIED && it.trivial == null }.takeIf { it > 0 }?.let { "$it modified" },
            changed.count { it.status == ChangeStatus.DELETED }.takeIf { it > 0 }?.let { "$it deleted" },
            changed.count { it.trivial != null }.takeIf { it > 0 }?.let { "$it whitespace/comments only" },
        )
        val files = changed.map { it.path }.distinct().size
        add(text("${plural(changed.size, "function")} changed in ${plural(files, "file")}" +
            (if (counts.isEmpty()) "" else ": " + counts.joinToString(" · ")), cardWidth, font(JBFont.label().asBold()), null))
        val unresolved = graph.files.count { it.analysis == null && !it.calls }
        if (unresolved > 0) add(label("Calls not resolved in ${plural(unresolved, "file")}: the after side is not the project's current source.",
            font(JBFont.small()), help))
        if (graph.truncated) add(label("The diff exceeds the analysis limit; some functions are not shown.", font(JBFont.small()), help))
        if (graph.nodes.any { it.stepsTruncated }) add(label("Some outlines reached the analysis limit; calls and connections may be missing.", font(JBFont.small()), help))
        val coverage = flows.uncovered()
        val required = graph.changed.count { it.trivial == null }.coerceAtMost(ChangeFlows.MAX_REQUIRED_NODES)
        add(label(if (flows.nodeNotes.isEmpty() && flows.themes.isEmpty()) "Structural view · not interpreted yet"
            else "Interpreted: ${required - coverage.nodes.size}/$required functions · " +
                "${graph.clusters.size - coverage.clusters.size}/${graph.clusters.size} clusters in themes", font(JBFont.small()), help))
    }

    private fun theme(index: Int, theme: DisplayTheme): JComponent = JPanel(VerticalLayout(scaled(4))).apply {
        isOpaque = false
        border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), pad(scaled(8), 0, scaled(4), 0))
        val key = ChangeFlowsViewState.theme(index)
        val open = key in state.expanded
        val members = theme.clusters.flatMap { it.members }.map { graph.nodes[it] }.filter { it.changed }
        add(JPanel(BorderLayout(scaled(6), 0)).apply {
            isOpaque = false
            add(toggle(key, open), BorderLayout.WEST)
            add(ActionLink(theme.title) { actions.toggle(key) }.apply {
                font = font(JBFont.label().asBold().biggerOn(1f)).let { if (theme.inferred) it.deriveFont(Font.BOLD or Font.ITALIC) else it }
                toolTipText = if (theme.inferred) "Theme title: LLM interpretation" else "Structural title from the clusters' functions"
                minimumSize = Dimension(0, preferredSize.height)
            }, BorderLayout.CENTER)
            add(label(stats(members, theme), font(JBFont.small()), help), BorderLayout.EAST)
        })
        if (state.mode == ChangeFlowsMode.OUTLINE || !open) theme.summary?.let {
            add(text(it, cardWidth - scaled(20), italic(JBFont.label()), null, indent = scaled(20)))
        }
        chips(members)?.let { add(it.apply { border = pad(left = scaled(20)) }) }
        if (!open) return@apply
        val body = JPanel(VerticalLayout(scaled(6))).apply { isOpaque = false; border = pad(scaled(4), scaled(12), 0, 0) }
        if (theme.clusters.size == 1) body.add(cluster(theme.clusters.single(), nested = false))
        else theme.clusters.forEach { body.add(cluster(it, nested = true)) }
        if (theme.files.isNotEmpty()) body.add(JPanel(VerticalLayout(scaled(2))).apply {
            isOpaque = false
            add(label("Also in this change", font(JBFont.small()), help))
            theme.files.forEach { path -> add(fileLink(path, graph.files.firstOrNull { it.path == path })) }
        })
        add(body)
        if (state.mode == ChangeFlowsMode.MAP) theme.summary?.let { summary ->
            add(JPanel(BorderLayout(scaled(16), 0)).apply {
                isOpaque = false
                border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), pad(scaled(10), scaled(20), scaled(10), 0))
                add(label("FLOW ${(index + 1).toString().padStart(2, '0')}", monospace(), help), BorderLayout.WEST)
                add(text(summary, cardWidth - scaled(116), italic(JBFont.label().biggerOn(1f)), help), BorderLayout.CENTER)
            })
        }
    }

    private fun stats(members: List<ChangeNode>, theme: DisplayTheme): String {
        val files = (members.map { it.path } + theme.files).distinct().size
        return "${plural(members.size, "function")} · ${plural(files, "file")} · +${members.sumOf { it.added }} −${members.sumOf { it.removed }}"
    }

    /** Counts of the model's intent labels and attention flags; the counting is code, the labels are not. */
    private fun chips(members: List<ChangeNode>): JComponent? {
        val notes = members.mapNotNull { flows.nodeNotes[it.id] }
        val tests = members.count { it.test }
        if (notes.isEmpty() && tests == 0) return null
        return JPanel(FlowLayout(FlowLayout.LEFT, scaled(8), 0)).apply {
            isOpaque = false
            notes.groupingBy { it.intent }.eachCount().entries.sortedBy { it.key.ordinal }.forEach { (intent, count) ->
                add(label("${intent.label} $count", italic(JBFont.small()), help).apply { toolTipText = "Intent: LLM classification" })
            }
            notes.count { it.attention != null }.takeIf { it > 0 }?.let { add(label("⚑ $it to verify", italic(JBFont.small()), ATTENTION)) }
            if (tests > 0) add(label("${plural(tests, "test")} changed", font(JBFont.small()), help).apply { toolTipText = "Functions in test sources" })
        }
    }

    private fun cluster(cluster: ChangeCluster, nested: Boolean): JComponent = JPanel(VerticalLayout(scaled(3))).apply {
        isOpaque = false
        val key = ChangeFlowsViewState.cluster(cluster.id)
        if (nested) {
            val note = flows.clusterNotes[cluster.id]
            val open = key in state.expanded
            add(JPanel(BorderLayout(scaled(6), 0)).apply {
                isOpaque = false
                add(toggle(key, open), BorderLayout.WEST)
                add(ActionLink(note?.title ?: cluster.title) { actions.toggle(key) }.apply {
                    font = if (note != null) italic(JBFont.label()) else font(JBFont.label())
                    minimumSize = Dimension(0, preferredSize.height)
                }, BorderLayout.CENTER)
                val changed = cluster.members.map { graph.nodes[it] }.filter { it.changed }
                add(label("${plural(changed.size, "function")} · +${changed.sumOf { it.added }} −${changed.sumOf { it.removed }}", font(JBFont.small()), help), BorderLayout.EAST)
            })
            note?.summary?.let { add(text(it, cardWidth - scaled(40), italic(JBFont.small()), help, indent = scaled(20))) }
            if (!open) return@apply
        }
        add(JPanel(VerticalLayout(scaled(2))).apply {
            isOpaque = false
            if (nested) border = pad(left = scaled(16))
            if (state.mode == ChangeFlowsMode.MAP) add(map(cluster, cardWidth - scaled(if (nested) 48 else 24)))
            else tree(cluster).forEach(::add)
        })
    }

    private fun map(cluster: ChangeCluster, width: Int): JComponent = JPanel(VerticalLayout(scaled(8))).apply {
        isOpaque = false
        val all = ChangeFlowsViewState.mapAll(cluster.id) in state.expanded
        add(ChangeFlowMap(flows, cluster, width, zoom, state.selected[cluster.id], actions.focus,
            if (all) Int.MAX_VALUE else CHANGE_MAP_PREVIEW_STEPS))
        if (cluster.members.size > CHANGE_MAP_PREVIEW_STEPS) add(ActionLink(
            if (all) "Show fewer steps" else "Show all ${cluster.members.size} steps (+${cluster.members.size - CHANGE_MAP_PREVIEW_STEPS})"
        ) {
            if (all && state.selected[cluster.id] !in changeMapOrder(graph, cluster).take(CHANGE_MAP_PREVIEW_STEPS)) state.selected.remove(cluster.id)
            actions.toggle(ChangeFlowsViewState.mapAll(cluster.id))
        }.apply { font = font(JBFont.small()) })
        state.selected[cluster.id]?.let { id ->
            val node = graph.nodes[id]
            add(JPanel(VerticalLayout(scaled(3))).apply {
                isOpaque = false
                name = "node:$id"
                border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), pad(top = scaled(10)))
                add(label(node.qualifiedName, font(JBFont.label().asBold())))
                flows.nodeNotes[id]?.let { add(text(it.summary, width, italic(JBFont.label()), null)) }
                add(details(node, width - scaled(24)))
            })
        }
    }

    private data class Row(val node: Int, val depth: Int, val repeat: Boolean)

    private fun minor(id: Int) = graph.nodes[id].let { it.trivial != null || (it.changed && flows.nodeNotes[id]?.importance == ChangeImportance.MINOR) }

    /** The call tree from the cluster's entry functions; shared callees and cycles are drawn once and referenced after that. */
    private fun tree(cluster: ChangeCluster): List<JComponent> {
        val members = cluster.members.toHashSet()
        val rows = mutableListOf<Row>()
        val drawn = hashSetOf<Int>()
        fun visit(id: Int, depth: Int) {
            val repeat = id in drawn
            rows += Row(id, depth, repeat)
            if (repeat) return
            drawn += id
            if (depth < MAX_DEPTH) graph.callees(id).filter { it in members && it != id }.distinct()
                .filter { !graph.nodes[it].test || graph.nodes[id].test }.forEach { visit(it, depth + 1) }
        }
        flowRoots(graph.nodes, graph.edges, cluster.members).forEach { visit(it, 0) }
        cluster.members.filter { it !in drawn && !graph.nodes[it].test && graph.nodes[it].changed }.forEach { visit(it, 0) }
        val tests = cluster.members.filter { it !in drawn && graph.nodes[it].test }
        // A row folds when it and everything under it is minor; context rows fold with what they connect.
        val important = BooleanArray(rows.size)
        for (i in rows.indices.reversed()) {
            val row = rows[i]
            var below = false
            var j = i + 1
            while (j < rows.size && rows[j].depth > row.depth) { if (rows[j].depth == row.depth + 1 && important[j]) below = true; j++ }
            important[i] = below || (!row.repeat && graph.nodes[row.node].changed && !minor(row.node))
        }
        val showMinor = ChangeFlowsViewState.minor(cluster.id) in state.expanded
        val result = mutableListOf<JComponent>()
        var hidden = 0
        var overflow = 0
        rows.forEachIndexed { index, row ->
            if (!showMinor && !important[index]) { if (!row.repeat) hidden++; return@forEachIndexed }
            if (result.size >= MAX_ROWS) { if (!row.repeat) overflow++; return@forEachIndexed }
            result += node(graph.nodes[row.node], row.depth, row.repeat)
        }
        if (overflow > 0) result += label("+ ${plural(overflow, "more function")} in this cluster; open them from their callers' outlines",
            font(JBFont.small()), help)
        if (tests.isNotEmpty()) {
            result += label("Tests", font(JBFont.small()), help).apply { border = pad(top = scaled(6)) }
            tests.forEach { id ->
                if (!showMinor && minor(id)) hidden++ else result += node(graph.nodes[id], 0, false)
            }
        }
        if (hidden > 0 || showMinor && rows.any { minor(it.node) }) {
            val key = ChangeFlowsViewState.minor(cluster.id)
            result += ActionLink(if (showMinor) "Hide minor changes" else "+ $hidden minor or connecting ${if (hidden == 1) "function" else "functions"}") { actions.toggle(key) }.apply {
                font = font(JBFont.small())
                toolTipText = "Whitespace/comment-only edits and changes the LLM classified as minor, with the unchanged functions that only connect them"
            }
        }
        return result
    }

    private fun node(node: ChangeNode, depth: Int, repeat: Boolean): JComponent = JPanel(VerticalLayout(scaled(2))).apply {
        isOpaque = false
        name = "node:${node.id}"
        val indent = depth * scaled(INDENT)
        border = pad(left = indent)
        val key = ChangeFlowsViewState.node(node.id)
        val open = !repeat && key in state.expanded
        val note = flows.nodeNotes[node.id]
        val rowWidth = cardWidth - indent - scaled(24)
        add(JPanel(BorderLayout(scaled(6), 0)).apply {
            isOpaque = false
            add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(4), 0)).apply {
                isOpaque = false
                if (depth > 0) add(label("↳", font(JBFont.label()), help).apply { toolTipText = "Called by the function above (resolved call)" })
                if (repeat) add(label("↺", font(JBFont.label()), help)) else add(toggle(key, open))
                add(badge(node))
            }, BorderLayout.WEST)
            add(ActionLink(node.qualifiedName) { if (repeat) actions.focus(node.id) else actions.toggle(key) }.apply {
                font = font(JBFont.label()).let { if (node.changed) it else it.deriveFont(Font.PLAIN) }
                if (!node.changed) foreground = help
                toolTipText = "${node.status.id} · ${node.path}:${node.line}" + if (repeat) " · shown above" else ""
                minimumSize = Dimension(0, preferredSize.height)
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, scaled(6), 0)).apply {
                isOpaque = false
                note?.let { add(label(it.intent.label, italic(JBFont.small()), help).apply { toolTipText = "Intent: LLM classification · importance ${it.importance.id}" }) }
                if (note?.attention != null) add(label("⚑", font(JBFont.label()), ATTENTION).apply { toolTipText = note.attention })
                if (node.changed) add(label("+${node.added} −${node.removed}", font(JBFont.small()), help).apply { toolTipText = "Changed lines in this function" })
            }, BorderLayout.EAST)
        })
        if (repeat) return@apply
        // Without an interpretation the row shows what the code says: the header, or why the change is trivial.
        val line = note?.summary?.let { text(it, rowWidth, italic(JBFont.small()), null, indent = scaled(24)) }
            ?: label(when {
                !node.changed -> "Unchanged · connects the changes around it"
                node.trivial != null -> node.trivial.id.replaceFirstChar { it.uppercase() }
                else -> node.header
            }, font(JBFont.small()), help).apply { border = pad(left = scaled(24)) }
        add(line)
        if (open) add(details(node, rowWidth - scaled(24)))
    }

    /** Level 2: everything here is read from source. */
    private fun details(node: ChangeNode, width: Int): JComponent = JPanel(VerticalLayout(scaled(4))).apply {
        isOpaque = false
        border = pad(scaled(4), scaled(24), scaled(6), 0)
        // An uninterpreted changed row already shows its header.
        if (flows.nodeNotes[node.id] != null || !node.changed || node.trivial != null) add(text(node.header, width, monospace(), null))
        add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(10), 0)).apply {
            isOpaque = false
            add(ActionLink("${node.path.substringAfterLast('/')}:${node.line}") { actions.openNode(node) }.apply {
                font = font(JBFont.small())
                toolTipText = if (node.offset != null) "Open in editor" else "View changes in ${node.path}"
            })
            add(ActionLink(if (ChangeFlowsViewState.diff(node.id) in state.expanded) "Hide changed lines" else "Show changed lines") {
                actions.toggle(ChangeFlowsViewState.diff(node.id))
            }.apply { font = font(JBFont.small()) })
            if (node.signatureChanged) add(label("signature changed", font(JBFont.small()), ATTENTION))
        })
        flows.nodeNotes[node.id]?.attention?.let { add(text("⚑ $it", width, italic(JBFont.small()), ATTENTION)) }
        if (ChangeFlowsViewState.diff(node.id) in state.expanded) add(hunk(node, width))
        val callers = graph.callers(node.id).map { graph.nodes[it] }
        if (callers.isNotEmpty()) add(links("Called by in this diff", callers.map { it.qualifiedName to { actions.focus(it.id) } }, width))
        if (node.callers.isNotEmpty()) add(links("Also called by (unchanged)" + if (node.callersTruncated) ", and more" else "",
            node.callers.map { (it.qualifiedName + if (it.test) " · test" else "") to { actions.openCaller(it) } }, width))
        else if (node.callersSearched && callers.isEmpty()) add(label("No in-project callers found", font(JBFont.small()), help))
        node.steps?.let { add(steps(node, it, width)) } ?: add(label(
            if (node.status == ChangeStatus.DELETED) "Deleted: its calls are only in the before side" else "Calls not resolved: the after side is not the project's current source",
            font(JBFont.small()), help))
    }

    private fun links(title: String, entries: List<Pair<String, () -> Unit>>, width: Int): JComponent = JPanel(VerticalLayout(scaled(1))).apply {
        isOpaque = false
        add(label(title, font(JBFont.small()), help))
        entries.forEach { (text, action) ->
            add(ActionLink(text) { action() }.apply { font = font(JBFont.small()); border = pad(left = scaled(10)); maximumSize = Dimension(width, preferredSize.height) })
        }
    }

    /** Calls and exits in source order; long runs of unchanged calls that reach nothing in the graph fold into a count. */
    private fun steps(node: ChangeNode, steps: List<ChangeStep>, width: Int): JComponent = JPanel(VerticalLayout(scaled(1))).apply {
        isOpaque = false
        add(label(if (steps.isEmpty()) "No in-project calls or exits" else "Outline · in-project calls and exits in source order", font(JBFont.small()), help))
        val all = ChangeFlowsViewState.steps(node.id) in state.expanded
        fun notable(step: ChangeStep) = step.changed || step.target != null || step.exit
        var skipped = 0
        fun flush() {
            if (skipped == 0) return
            add(ActionLink("… ${plural(skipped, "unchanged call")}") { actions.toggle(ChangeFlowsViewState.steps(node.id)) }.apply {
                font = font(JBFont.small()); border = pad(left = scaled(10))
            })
            skipped = 0
        }
        steps.forEach { step ->
            if (!all && steps.size > 8 && !notable(step)) { skipped++; return@forEach }
            flush()
            add(step(step, width))
        }
        flush()
        if (node.stepsTruncated) add(text("Outline truncated after ${steps.size} steps; later calls and connections may be missing. Open source to inspect the full function.", width, font(JBFont.small()), help))
    }

    private fun step(step: ChangeStep, width: Int): JComponent = JPanel(BorderLayout(scaled(6), 0)).apply {
        isOpaque = false
        border = pad(left = scaled(10))
        val color = if (step.changed) null else help
        add(label("L${step.line}" + if (step.changed) " Δ" else "", font(JBFont.small()), if (step.changed) CHANGED else help).apply {
            toolTipText = if (step.changed) "This line changed" else "Unchanged line"
        }, BorderLayout.WEST)
        val text = buildString {
            if (step.conditions.isNotEmpty()) append(step.conditions.joinToString(" › ")).append(" › ")
            append(if (step.exit) "⏎ " else "").append(step.text)
        }
        val target = step.target
        add(if (target != null) ActionLink(text + " → " + graph.nodes[target].name) { actions.focus(target) }.apply {
            font = font(JBFont.small()); toolTipText = "Follow the call to ${graph.nodes[target].qualifiedName}"
            minimumSize = Dimension(0, preferredSize.height)
        } else label(text, font(JBFont.small()), color).apply {
            toolTipText = step.callee?.let { "Calls $it" } ?: if (step.exit) "Leaves the function" else null
        }, BorderLayout.CENTER)
        maximumSize = Dimension(width, preferredSize.height)
    }

    /** Level 3: the function's literal diff lines from the snapshot. */
    private fun hunk(node: ChangeNode, width: Int): JComponent = JPanel(VerticalLayout(0)).apply {
        isOpaque = true
        background = UIUtil.getEditorPaneBackground()
        border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border()), pad(scaled(4), scaled(4), scaled(4), scaled(4)))
        val mono = monospace()
        node.hunk.forEach { line ->
            add(JLabel(when (line.kind) {
                '…' -> "      ⋯ ${line.text}"
                else -> "${line.kind}${(line.after ?: line.before)?.toString()?.padStart(5) ?: "     "}  ${line.text.replace("\t", "    ")}"
            }).apply {
                font = mono
                isOpaque = line.kind == '+' || line.kind == '-'
                background = if (line.kind == '+') ADDED_LINE else REMOVED_LINE
                if (line.kind == '…') foreground = help
                maximumSize = Dimension(width, preferredSize.height)
            })
        }
        if (node.hunk.isEmpty()) add(label("No line changes", font(JBFont.small()), help))
        if (node.hunkTruncated) add(label("… more lines in the file diff", font(JBFont.small()), help))
    }

    private fun otherFiles(files: List<ChangeFile>): JComponent = JPanel(VerticalLayout(scaled(2))).apply {
        isOpaque = false
        border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), pad(top = scaled(8)))
        val open = ChangeFlowsViewState.OTHER in state.expanded
        add(JPanel(BorderLayout(scaled(6), 0)).apply {
            isOpaque = false
            add(toggle(ChangeFlowsViewState.OTHER, open), BorderLayout.WEST)
            add(ActionLink("Other changed files (${files.size})") { actions.toggle(ChangeFlowsViewState.OTHER) }.apply {
                font = font(JBFont.label().asBold()); toolTipText = "Files with no changed function, or whose code was not analyzed"
            }, BorderLayout.CENTER)
        })
        if (open) files.forEach { add(fileLink(it.path, it).apply { border = pad(left = scaled(20)) }) }
    }

    private fun fileLink(path: String, file: ChangeFile?): JComponent = JPanel(VerticalLayout(0)).apply {
        isOpaque = false
        add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(6), 0)).apply {
            isOpaque = false
            add(ActionLink(path) { actions.openFile(path) }.apply { font = font(JBFont.small()); toolTipText = "View changes in $path" })
            file?.let { add(label(it.status, font(JBFont.small()), help)) }
        })
        val facts = listOfNotNull(file?.analysis,
            file?.takeIf { it.outsideAdded + it.outsideRemoved > 0 }?.let { "+${it.outsideAdded} −${it.outsideRemoved} lines outside functions" }) +
            file?.declarations.orEmpty()
        if (facts.isNotEmpty()) add(label(facts.joinToString(" · "), font(JBFont.small()), help).apply { border = pad(left = scaled(12)) })
    }

    private fun badge(node: ChangeNode) = label(node.status.badge, font(JBFont.small().asBold()), when (node.status) {
        ChangeStatus.ADDED -> ADDED
        ChangeStatus.MODIFIED -> if (node.trivial != null) help else CHANGED
        ChangeStatus.DELETED -> DELETED
        ChangeStatus.CONTEXT -> help
    }).apply {
        toolTipText = when (node.status) {
            ChangeStatus.ADDED -> "New function"
            ChangeStatus.MODIFIED -> "Modified function" + (node.trivial?.let { ": ${it.id}" } ?: "") + if (node.signatureChanged) ", signature changed" else ""
            ChangeStatus.DELETED -> "Deleted function"
            ChangeStatus.CONTEXT -> "Unchanged function on a call path between changes"
        } + (if (node.test) " · test source" else "") + " · from source comparison"
    }

    private fun toggle(key: String, open: Boolean): JComponent = label(if (open) "▾" else "▸", font(JBFont.label()), help).apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = if (open) "Collapse" else "Expand"
        addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { actions.toggle(key) } })
    }

    /** Pixel insets: values here are already scaled for the screen and the canvas zoom, unlike JBUI's self-scaling borders. */
    private fun pad(top: Int = 0, left: Int = 0, bottom: Int = 0, right: Int = 0) = pixelBorder(top, left, bottom, right)

    private fun italic(base: Font) = font(base).deriveFont(font(base).style or Font.ITALIC)
    private fun monospace() = Font(Font.MONOSPACED, Font.PLAIN, (JBFont.small().size2D * zoom).roundToInt().coerceAtLeast(6))

    private fun label(text: String, font: Font, color: Color? = null) = JLabel(text).apply {
        this.font = font
        color?.let { foreground = it }
        minimumSize = Dimension(0, preferredSize.height)
    }

    private fun text(value: String, width: Int, font: Font, color: Color?, indent: Int = 0): JComponent = WrappedText(value, width).apply {
        isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true; border = pad(left = indent)
        this.font = font
        color?.let { foreground = it }
    }

    private fun plural(count: Int, word: String) = "$count $word${if (count == 1) "" else "s"}"

    companion object {
        const val MAX_DEPTH = 6
        const val MAX_ROWS = 80
        const val INDENT = 18
        val ADDED: Color = JBColor(Color(0x2E7D32), Color(0x81C784))
        val CHANGED: Color = JBColor(Color(0x1565C0), Color(0x64B5F6))
        val DELETED: Color = JBColor(Color(0xC62828), Color(0xE57373))
        val ATTENTION: Color = JBColor(Color(0xB26A00), Color(0xE0A100))
        val ADDED_LINE: Color = JBColor(Color(0xE6F4EA), Color(0x2B3B2E))
        val REMOVED_LINE: Color = JBColor(Color(0xFCE8E6), Color(0x47302F))
    }
}

/**
 * Measures its height at a fixed wrap width. A plain wrapping text area measures at its current width, which nested
 * layouts can briefly make tiny, wrapping every character onto its own line.
 */
internal class WrappedText(value: String, private val wrapWidth: Int) : JTextArea(value) {
    private var measurement: WrappedTextMeasurement? = null
    init {
        // These read-only labels measure the full content width. BasicTextUI otherwise reserves a pixel for the caret
        // during painting, making the wrapped view alternate widths and request another layout on every repaint.
        putClientProperty("caretWidth", 0)
    }

    override fun getPreferredSize(): Dimension {
        val measure = measurement ?: WrappedTextMeasurement(this, wrapWidth).also { measurement = it }
        return measure.preferredSize() ?: super.getPreferredSize()
    }
    override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)
}
