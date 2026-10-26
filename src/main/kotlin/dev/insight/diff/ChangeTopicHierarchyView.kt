package dev.insight.diff

import dev.insight.review.ReviewVocabulary
import dev.insight.review.SummaryKind
import dev.insight.review.TopicEntry
import dev.insight.review.TopicNode
import dev.insight.review.topicReadingSummaries

/**
 * The topic hierarchy read left to right, like the article hierarchy: broad topics in the first column, each card as tall as
 * the rows beneath it, and the changed files as leaves with their summary beside them. A folded topic takes a single row and
 * shows its summary, whose scored words can be traced as in the summaries outline.
 */
internal class ChangeTopicHierarchyPanel(
    card: TopicsCard,
    private val state: ChangeTopicsViewState,
    availableWidth: Int,
    zoom: Double,
    private val actions: ChangeTopicActions,
    vocabulary: ReviewVocabulary = ReviewVocabulary.DIFF,
) : HierarchyColumnsPanel(availableWidth, zoom, vocabulary) {
    val outline = state.outline(card)
    private val summaries = card.summaries
    private val fileNodes = summaries.tree.nodes.filter { it.kind == SummaryKind.FILE }.associateBy { it.path }
    private val fileSummaries = fileNodes.mapValues { summaries.text(it.value.id) }
    /** Topic summaries, else the summary of exactly a topic's files, by reading identity; see [topicReadingSummaries]. */
    private val reading = card.reading
    private val readingIds = reading.tree.nodes.associate { it.identity to it.id }
    private val active = outline.nodes.firstOrNull(state::isSelected)?.let { it.first..it.last }
    private val trace = state.tracing?.trace
    private val marks = traceMarks(reading.tree, trace)

    init {
        cardWidth = measureCardWidth(outline.nodes.map { it.name } + outline.files.map { it.path.substringAfterLast('/') })
        if (outline.files.isEmpty()) showEmpty(vocabulary.noFiles) else layoutHierarchy()
    }

    private fun layoutHierarchy() {
        val gap = scaled(ROW_GAP)
        var y = 0
        y = placeEntries(outline.children(null), 0, y, null)
        outline.pending?.let { pending ->
            var childY = y
            for (index in pending) {
                if (rowsLeft <= 0) {
                    childY += moreNote(pending.last - index + 1, 1, childY) + gap
                    break
                }
                childY += fileRow(index, 1, childY, null) + gap
            }
            val placeholder = branchCard("topic:pending", "Awaiting topic", null, 0, pending.count(), active = false,
                tooltip = "Run /topics to classify these files", toggle = null, placeholder = true)
            val height = maxOf(childY - gap - y, placeholder.preferredSize.height)
            add(placeholder.apply { setBounds(0, y, cardWidth, height) })
            y += height + gap
        }
        finish(y)
    }

    /** Lays out [entries] one under another from row [y], stopping at the row cap, and returns the row after the last one. */
    private fun placeEntries(entries: List<TopicEntry>, column: Int, y: Int, topLevel: String?): Int {
        val gap = scaled(ROW_GAP)
        var next = y
        for ((index, entry) in entries.withIndex()) {
            if (rowsLeft <= 0) {
                next += moreNote(entries.size - index, column, next) + gap
                break
            }
            next += place(entry, column, next, topLevel) + gap
        }
        return next
    }

    /** Lays out [entry] with its top-left at column [column] and row [y], and returns its height. */
    private fun place(entry: TopicEntry, column: Int, y: Int, topLevel: String? = null): Int = when (entry) {
        is TopicEntry.File -> fileRow(entry.index, column, y, topLevel)
        is TopicEntry.Topic -> topicNode(entry.node, column, y)
    }

    private fun topicNode(node: TopicNode, column: Int, y: Int): Int {
        val collapsed = state.isCollapsed(node)
        val identity = ChangeTopicsViewState.identity(node)
        val toggle = toggle("toggle:${node.level}:${node.identity}", collapsed, "Show sub-topics and files", "Fold sub-topics and files") {
            state.toggle(node); actions.refresh()
        }
        val mark = marks[identity]
        val component = branchCard("topic:${node.level}:${node.identity}", node.name, node.path.first(), column, node.size,
            state.isSelected(node), node.fullPath, toggle, mark = mark, badge = badge(identity),
            review = readingIds[identity]?.let { actions.review?.control(reading.tree[it], summaryFont()) }) { state.select(node); actions.refresh() }
        val height = if (collapsed) {
            rowsPlaced++
            val summary = readingIds[identity]?.let(reading::text)
            val note = note("summary:${node.level}:${node.identity}", summary ?: fileNames(node), summary != null, column + 1, y, mark, emptyMap())
            readingIds[identity]?.let { actions.review?.decorate(note, reading.tree[it]) }
            summary?.let { installWordTracing(note, identity, it, state, actions.trace) }
            actions.installEntryMenu(note, identity)
            maxOf(component.preferredSize.height, note.preferredSize.height)
        } else {
            val gap = scaled(ROW_GAP)
            var childY = y
            childY = placeEntries(outline.children(node), column + 1, childY, node.path.first())
            maxOf(childY - gap - y, component.preferredSize.height)
        }
        actions.installEntryMenu(component, identity)
        add(component.apply { setBounds(column * step, y, cardWidth, height) })
        anchors[identity] = component
        return height
    }

    private fun fileRow(index: Int, column: Int, y: Int, topLevel: String?): Int {
        val file = outline.files[index]
        rowsPlaced++
        val identity = "file:${file.path}"
        val summary = fileSummaries[file.path] ?: readingIds[identity]?.let(reading::text)
        val mark = marks[identity]
        val leaf = fileCard(file, file.path.substringAfterLast('/'), topLevel, column, active?.contains(index) == true,
            { fileNodes[file.path]?.let(actions.openNode) ?: actions.openFile(file.path) },
            actions.showNode?.let { show -> { fileNodes[file.path]?.let(show) ?: actions.showDiffCard(file.path) } }, mark, badge(identity),
            review = readingIds[identity]?.let { actions.review?.control(reading.tree[it], summaryFont()) })
        val note = note("summary:${file.path}", summary ?: "No summary yet", summary != null, column + 1, y, mark, emptyMap())
        readingIds[identity]?.let { actions.review?.decorate(note, reading.tree[it]) }
        if (summary != null) installWordTracing(note, identity, summary, state, actions.trace)
        actions.installEntryMenu(leaf, identity)
        actions.installEntryMenu(note, identity)
        val height = maxOf(leaf.preferredSize.height, note.preferredSize.height)
        add(leaf.apply { setBounds(column * step, y, cardWidth, height) })
        anchors[identity] = leaf
        return height
    }

    private fun badge(identity: String): String? = trace?.related?.firstOrNull { it.identity == identity }?.let { matchBadge(it, trace) }

    /** A folded topic without a summary lists the files it hides. */
    private fun fileNames(node: TopicNode): String {
        val names = (node.first..node.last).map { outline.files[it].path.substringAfterLast('/') }
        return names.take(FOLDED_NAMES).joinToString(", ") + if (names.size > FOLDED_NAMES) ", +${names.size - FOLDED_NAMES} more" else ""
    }
}
