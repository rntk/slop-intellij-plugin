package dev.insight.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
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
    vocabulary: ReviewVocabulary = ReviewVocabulary.DIFF,
) : HierarchyColumnsPanel(availableWidth, zoom, vocabulary) {
    private val summaries = card.summaries
    private val tree = summaries.tree
    /** The selected node is outlined with every file or part beneath it. */
    private val active: Set<String> = tree.nodes.firstOrNull { it.identity == state.selected }?.let { selected ->
        val ids = hashSetOf<String>()
        val pending = ArrayDeque(listOf(selected))
        while (pending.isNotEmpty()) pending.removeFirst().let { ids += it.identity; it.children.mapTo(pending, tree::get) }
        ids
    }.orEmpty()
    private val trace = state.tracing?.trace
    private val marks = traceMarks(tree, trace)

    init {
        state.update(tree)
        cardWidth = measureCardWidth(tree.nodes.map(::title))
        if (summaries.total == 0) showEmpty(vocabulary.noFiles) else finish(place(tree.root, 0, 0, null) + scaled(ROW_GAP))
    }

    /** Lays out [node] with its top-left at column [column] and row [y], and returns its height. */
    private fun place(node: SummaryNode, column: Int, y: Int, family: String?): Int =
        if (node.children.isEmpty()) fileRow(node, column, y, family) else branch(node, column, y, family)

    private fun branch(node: SummaryNode, column: Int, y: Int, family: String?): Int {
        val collapsed = node.identity !in state.expanded
        val toggle = toggle("toggle:${node.identity}", collapsed, "Show subfolders and files", "Fold subfolders and files") {
            actions.toggle(node.identity)
        }
        val mark = marks[node.identity]
        val component = branchCard("node:${node.identity}", title(node), family, column, node.files.size,
            node.identity == state.selected, node.location, toggle, icon(node), mark = mark, badge = badge(node),
            caption = if (node.kind == SummaryKind.CHUNK) "${node.children.size} parts" else null) { actions.select(node.identity) }
        val height = if (collapsed) {
            rowsPlaced++
            val summary = summaries.text(node.id)
            val note = note("summary:${node.identity}", summary ?: folded(node), summary != null, column + 1, y, mark)
            summary?.let { installWordTracing(note, node.identity, it, state, actions.trace) }
            maxOf(component.preferredSize.height, note.preferredSize.height)
        } else {
            val gap = scaled(ROW_GAP)
            var childY = y
            // Each top-level folder keeps one hue down its columns; the root stays neutral.
            for ((index, child) in node.children.map(tree::get).withIndex()) {
                if (rowsLeft <= 0) {
                    childY += moreNote(node.children.size - index, column + 1, childY) + gap
                    break
                }
                childY += place(child, column + 1, childY, family ?: child.identity) + gap
            }
            maxOf(childY - gap - y, component.preferredSize.height)
        }
        add(component.apply { setBounds(column * step, y, cardWidth, height) })
        anchors[node.identity] = component
        return height
    }

    private fun fileRow(node: SummaryNode, column: Int, y: Int, family: String?): Int {
        val file = node.file ?: return 0
        rowsPlaced++
        val summary = summaries.text(node.id)
        val mark = marks[node.identity]
        val part = node.kind == SummaryKind.CHUNK
        val leaf = fileCard(file, node.label, family, column, node.identity in active,
            { actions.openNode(node) }, actions.showNode?.let { show -> { show(node) } }, mark, badge(node),
            select = { actions.select(node.identity) },
            name = if (part) node.identity else "file:${file.path}", tooltip = if (part) node.location else file.path,
            icon = if (part) AllIcons.FileTypes.Text else null, details = if (part) listOf(node.path) else null)
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

    /** A file name or merged chain, a folder chain with a trailing slash, a group's range, a part's lines, or the root's folder. */
    private fun title(node: SummaryNode): String = when (node.kind) {
        SummaryKind.FILE, SummaryKind.GROUP, SummaryKind.ROOT, SummaryKind.CHUNK -> node.label
        SummaryKind.DIRECTORY -> "${node.label}/"
    }

    private fun icon(node: SummaryNode): Icon = when (node.kind) {
        SummaryKind.FILE -> FileTypeManager.getInstance().getFileTypeByFileName(node.path.substringAfterLast('/')).icon ?: AllIcons.FileTypes.Any_type
        SummaryKind.CHUNK -> AllIcons.FileTypes.Text
        SummaryKind.DIRECTORY -> AllIcons.Nodes.Folder
        SummaryKind.GROUP -> AllIcons.Actions.GroupBy
        SummaryKind.ROOT -> AllIcons.Vcs.Changelist
    }
}
