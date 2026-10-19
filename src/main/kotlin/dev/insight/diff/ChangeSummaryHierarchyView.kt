package dev.insight.diff

import com.intellij.icons.AllIcons
import javax.swing.Icon

/**
 * The summary tree read left to right, like the topic hierarchy: the root folder in the first column, its folders and groups
 * in the next, each card as tall as the rows beneath it, and the changed files as leaves with their summary beside them.
 * A folded folder takes a single row and shows its own summary, whose scored words can be traced as in the outline.
 */
internal class ChangeSummaryHierarchyPanel(
    private val card: SummariesCard,
    private val state: ChangeSummariesViewState,
    availableWidth: Int,
    zoom: Double,
    private val actions: ChangeSummaryActions,
) : HierarchyColumnsPanel(availableWidth, zoom) {
    private val summaries = card.summaries
    private val tree = summaries.tree
    /** The selected node is outlined with every file beneath it. */
    private val active = tree.nodes.firstOrNull { it.identity == state.selected }?.files?.toSet().orEmpty()
    private val trace = state.tracing?.trace
    private val marks = traceMarks(tree, trace)

    init {
        state.update(tree)
        cardWidth = measureCardWidth(tree.nodes.map(::title))
        if (summaries.total == 0) showEmpty("No changed files") else finish(place(tree.root, 0, 0, null) + scaled(ROW_GAP))
    }

    /** Lays out [node] with its top-left at column [column] and row [y], and returns its height. */
    private fun place(node: SummaryNode, column: Int, y: Int, family: String?): Int =
        if (node.kind == SummaryKind.FILE) fileRow(node, column, y, family) else branch(node, column, y, family)

    private fun branch(node: SummaryNode, column: Int, y: Int, family: String?): Int {
        val collapsed = node.identity !in state.expanded
        val toggle = toggle("toggle:${node.identity}", collapsed, "Show subfolders and files", "Fold subfolders and files") {
            actions.toggle(node.identity)
        }
        val mark = marks[node.identity]
        val component = branchCard("node:${node.identity}", title(node), family, column, node.files.size,
            node.identity == state.selected, node.location, toggle, icon(node), mark = mark, badge = badge(node)) { actions.select(node.identity) }
        val height = if (collapsed) {
            val summary = summaries.text(node.id)
            val note = note("summary:${node.identity}", summary ?: folded(node), summary != null, column + 1, y, mark)
            summary?.let { installWordTracing(note, node.identity, it, state, actions.trace) }
            maxOf(component.preferredSize.height, note.preferredSize.height)
        } else {
            val gap = scaled(ROW_GAP)
            var childY = y
            // Each top-level folder keeps one hue down its columns; the root stays neutral.
            node.children.map(tree::get).forEach { child -> childY += place(child, column + 1, childY, family ?: child.identity) + gap }
            maxOf(childY - gap - y, component.preferredSize.height)
        }
        add(component.apply { setBounds(column * step, y, cardWidth, height) })
        anchors[node.identity] = component
        return height
    }

    private fun fileRow(node: SummaryNode, column: Int, y: Int, family: String?): Int {
        val file = node.file ?: return 0
        val summary = summaries.text(node.id)
        val mark = marks[node.identity]
        val leaf = fileCard(file, node.label, family, column, file.path in active, actions.openFile, actions.showDiffCard, mark, badge(node)) {
            actions.select(node.identity)
        }
        val note = note("summary:${node.identity}", summary ?: missing(), summary != null, column + 1, y, mark)
        val height = maxOf(leaf.preferredSize.height, note.preferredSize.height)
        add(leaf.apply { setBounds(column * step, y, cardWidth, height) })
        anchors[node.identity] = leaf
        return height
    }

    private fun badge(node: SummaryNode): String? = trace?.related?.firstOrNull { it.identity == node.identity }?.let { matchBadge(it, trace) }

    private fun missing() = if (card.running) "Summarizing…" else "No summary yet"

    /** A folded folder without a summary yet lists the entries it hides. */
    private fun folded(node: SummaryNode): String {
        val names = node.children.map { title(tree[it]) }
        return missing() + " · " + names.take(FOLDED_NAMES).joinToString(", ") +
            if (names.size > FOLDED_NAMES) ", +${names.size - FOLDED_NAMES} more" else ""
    }

    /** A file name or merged chain, a folder chain with a trailing slash, a group's range, or the root's folder. */
    private fun title(node: SummaryNode): String = when (node.kind) {
        SummaryKind.FILE, SummaryKind.GROUP, SummaryKind.ROOT -> node.label
        SummaryKind.DIRECTORY -> "${node.label}/"
    }

    private fun icon(node: SummaryNode): Icon = when (node.kind) {
        SummaryKind.FILE, SummaryKind.DIRECTORY -> AllIcons.Nodes.Folder
        SummaryKind.GROUP -> AllIcons.Actions.GroupBy
        SummaryKind.ROOT -> AllIcons.Vcs.Changelist
    }
}
