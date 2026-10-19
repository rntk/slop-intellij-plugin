package dev.insight.diff

/**
 * The topic hierarchy read left to right, like the article hierarchy: broad topics in the first column, each card as tall as
 * the rows beneath it, and the changed files as leaves with their summary beside them. A folded topic takes a single row.
 */
internal class ChangeTopicHierarchyPanel(
    card: TopicsCard,
    private val state: ChangeTopicsViewState,
    availableWidth: Int,
    zoom: Double,
    private val actions: ChangeTopicActions,
) : HierarchyColumnsPanel(availableWidth, zoom) {
    val outline = topicOutline(card.topics)
    private val summaries = card.summaries
    private val fileSummaries = summaries.tree.nodes.filter { it.kind == SummaryKind.FILE }.associate { it.path to summaries.text(it.id) }
    /** Summaries by the exact files beneath their node, so a folded topic covering the same files can show one. */
    private val summariesByFiles = summaries.tree.nodes.mapNotNull { node -> summaries.text(node.id)?.let { node.files.toSet() to it } }.toMap()
    private val cardTopicSummaries = card.topicSummaries.tree.nodes.mapNotNull { node ->
        card.topicSummaries.text(node.id)?.let { node.identity.removePrefix("topic:") to it }
    }.toMap()
    private val active = outline.nodes.firstOrNull(state::isSelected)?.let { it.first..it.last }

    init {
        state.update(outline)
        cardWidth = measureCardWidth(outline.nodes.map { it.name } + outline.files.map { it.path.substringAfterLast('/') })
        if (outline.files.isEmpty()) showEmpty("No changed files") else layoutHierarchy()
    }

    private fun layoutHierarchy() {
        val gap = scaled(ROW_GAP)
        var y = 0
        outline.children(null).forEach { y += place(it, 0, y) + gap }
        outline.pending?.let { pending ->
            var childY = y
            for (index in pending) childY += fileRow(index, 1, childY, null) + gap
            val placeholder = branchCard("topic:pending", "Awaiting topic", null, 0, pending.count(), active = false,
                tooltip = "Run /topics to classify these files", toggle = null, placeholder = true)
            val height = maxOf(childY - gap - y, placeholder.preferredSize.height)
            add(placeholder.apply { setBounds(0, y, cardWidth, height) })
            y += height + gap
        }
        finish(y)
    }

    /** Lays out [entry] with its top-left at column [column] and row [y], and returns its height. */
    private fun place(entry: TopicEntry, column: Int, y: Int, topLevel: String? = null): Int = when (entry) {
        is TopicEntry.File -> fileRow(entry.index, column, y, topLevel)
        is TopicEntry.Topic -> topicNode(entry.node, column, y)
    }

    private fun topicNode(node: TopicNode, column: Int, y: Int): Int {
        val collapsed = state.isCollapsed(node)
        val toggle = toggle("toggle:${node.level}:${node.identity}", collapsed, "Show sub-topics and files", "Fold sub-topics and files") {
            state.toggle(node); actions.refresh()
        }
        val component = branchCard("topic:${node.level}:${node.identity}", node.name, node.path.first(), column, node.size,
            state.isSelected(node), node.fullPath, toggle) { state.select(node); actions.refresh() }
        val height = if (collapsed) {
            val (summary, written) = foldedSummary(node)
            val note = note("summary:${node.level}:${node.identity}", summary, written, column + 1, y)
            maxOf(component.preferredSize.height, note.preferredSize.height)
        } else {
            val gap = scaled(ROW_GAP)
            var childY = y
            outline.children(node).forEach { childY += place(it, column + 1, childY, node.path.first()) + gap }
            maxOf(childY - gap - y, component.preferredSize.height)
        }
        add(component.apply { setBounds(column * step, y, cardWidth, height) })
        return height
    }

    private fun fileRow(index: Int, column: Int, y: Int, topLevel: String?): Int {
        val file = outline.files[index]
        val summary = fileSummaries[file.path]
        val leaf = fileCard(file, file.path.substringAfterLast('/'), topLevel, column, active?.contains(index) == true,
            actions.openFile, actions.showDiffCard)
        val note = note("summary:${file.path}", summary ?: "No summary yet", summary != null, column + 1, y)
        val height = maxOf(leaf.preferredSize.height, note.preferredSize.height)
        add(leaf.apply { setBounds(column * step, y, cardWidth, height) })
        return height
    }

    /** The summary of a folded topic's exact file set when one exists, else the names of the files it hides. */
    private fun foldedSummary(node: TopicNode): Pair<String, Boolean> {
        val paths = (node.first..node.last).map { outline.files[it].path }
        cardTopicSummaries[node.identity]?.let { return it to true }
        summariesByFiles[paths.toSet()]?.let { return it to true }
        val names = paths.map { it.substringAfterLast('/') }
        return (names.take(FOLDED_NAMES).joinToString(", ") + if (names.size > FOLDED_NAMES) ", +${names.size - FOLDED_NAMES} more" else "") to false
    }
}
