package dev.insight.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.text.JTextComponent

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
    private val wordWeights = SummaryWordWeights(summaries)
    private val tree = summaries.tree
    /** The selected node is outlined with every file or part beneath it. */
    private val active: Set<String> = tree.nodes.firstOrNull { it.identity == state.selected }?.let { selected ->
        val ids = hashSetOf<String>()
        val pending = ArrayDeque(listOf(selected))
        while (pending.isNotEmpty()) pending.removeFirst().let { ids += it.identity; it.children.mapTo(pending, tree::get) }
        ids
    }.orEmpty()
    private val trace = state.resultTrace
    private val marks = traceMarks(tree, trace)

    override fun summaryFont() = super.summaryFont().forSummaryText()

    init {
        state.update(tree)
        cardWidth = measureCardWidth(tree.nodes.map(::title))
        when {
            summaries.total == 0 -> showEmpty(vocabulary.noFiles)
            !state.shows(tree.root, summaries) -> showEmpty("No summaries match the search.")
            else -> finish(place(tree.root, 0, 0, null) + scaled(ROW_GAP))
        }
    }

    /** Lays out [node] with its top-left at column [column] and row [y], and returns its height. */
    private fun place(node: SummaryNode, column: Int, y: Int, family: String?): Int =
        if (node.children.isEmpty()) fileRow(node, column, y, family) else branch(node, column, y, family)

    private fun branch(node: SummaryNode, column: Int, y: Int, family: String?): Int {
        // A branch whose entries are all hidden by a search reads as folded, with its own summary beside it.
        val children = node.children.map(tree::get).filter { state.shows(it, summaries) }
        val collapsed = !state.isOpen(node, summaries) || children.isEmpty()
        val toggle = toggle("toggle:${node.identity}", collapsed, "Show subfolders and files", "Fold subfolders and files") {
            actions.toggle(node.identity)
        }
        val mark = marks[node.identity]
        val component = branchCard("node:${node.identity}", title(node), family, column, node.files.size,
            node.identity == state.selected, node.location, toggle, icon(node), mark = mark, badge = badge(node),
            caption = if (node.kind == SummaryKind.CHUNK) "${node.children.size} parts" else null,
            levelLabel = "Level ${column + 1}",
            openDiff = actions.directoryAction(node),
            select = { actions.select(node.identity) },
            review = actions.review?.control(node, summaryFont()),
            explain = actions.explainAction(node), explainEnabled = card.canRun)
        val height = if (collapsed) {
            rowsPlaced++
            val summary = summaries.text(node.id)
            val note = note("summary:${node.identity}", summary ?: folded(node), summary != null, column + 1, y, mark,
                if (state.tagsCloud) wordWeights[node.id] else emptyMap())
            actions.review?.decorate(note, node)
            summary?.let { installWordTracing(note, node.identity, it, state, actions.trace, actions.searchSelection, actions.searchTab, actions.score, actions.follow) { actions.toggle(node.identity) } }
            maxOf(component.preferredSize.height, note.preferredSize.height)
        } else {
            val gap = scaled(ROW_GAP)
            var childY = y
            // Each top-level folder keeps one hue down its columns; the root stays neutral.
            for ((index, child) in children.withIndex()) {
                if (rowsLeft <= 0) {
                    childY += moreNote(children.size - index, column + 1, childY) + gap
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
            icon = if (part) AllIcons.FileTypes.Text else null, details = if (part) listOf(node.path) else null,
            levelLabel = "Level ${column + 1}",
            openWholeFile = actions.wholeFileAction(node),
            explain = actions.explainAction(node), explainEnabled = card.canRun,
            review = actions.review?.control(node, summaryFont()))
        val note = note("summary:${node.identity}", summary ?: missing(), summary != null, column + 1, y, mark,
            if (state.tagsCloud) wordWeights[node.id] else emptyMap())
        actions.review?.decorate(note, node)
        if (summary != null && state.traced(node)) installWordTracing(note, node.identity, summary, state, actions.trace, actions.searchSelection, score = actions.score, follow = actions.follow)
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
    private fun title(node: SummaryNode): String = summaryNodeTitle(node)

    private fun icon(node: SummaryNode): Icon = summaryNodeIcon(node)
}

/**
 * The review read as one document with its folder rail, like the topics document: every file in tree order down the
 * middle, its summary in the left margin and the folders and groups on the right, the root outermost. Each rail card spans
 * the diffs of its files, and each top-level folder keeps one hue down its columns.
 */
internal class ChangeSummaryDocumentPanel(
    private val card: SummariesCard,
    private val state: ChangeSummariesViewState,
    width: Int,
    zoom: Double,
    private val actions: ChangeSummaryActions,
    vocabulary: ReviewVocabulary = ReviewVocabulary.DIFF,
) : DocumentColumnsPanel(zoom, vocabulary) {
    private val summaries = card.summaries
    private val wordWeights = SummaryWordWeights(summaries)
    private val tree = summaries.tree
    private val nodesByIdentity = tree.nodes.associateBy { it.identity }
    private val fileNodes = tree.nodes.filter { it.kind == SummaryKind.FILE && it.file != null && state.shows(it, summaries) }
    val files: List<ReviewSource> = fileNodes.map { requireNotNull(it.file) }
    private val nodesByPath = fileNodes.associateBy { it.path }
    private val branches = tree.nodes.filter { it.children.isNotEmpty() }.mapTo(hashSetOf()) { it.identity }

    init {
        state.update(tree)
        val indexByPath = files.withIndex().associate { (index, file) -> file.path to index }
        val missing = if (card.running) "Summarizing…" else "No summary yet"
        val folderDepth = state.railDepth(tree)
        val depth = railDepth(tree, state)
        val items = mutableListOf<RailItem>()
        fun collect(node: SummaryNode, level: Int, family: String?) {
            if (!isFolderBranch(node) || level >= folderDepth || !state.shows(node, summaries)) return
            // A search may hide some of a folder's files; its card spans the ones left.
            val shown = node.files.mapNotNull(indexByPath::get)
            val first = shown.minOrNull()
            val last = shown.maxOrNull()
            if (first != null && last != null) {
                items += RailItem(
                    key = node.identity,
                    name = "node:${node.identity}",
                    title = summaryNodeTitle(node),
                    family = family,
                    level = level,
                    levelLabel = "Level ${tree.depth(node.id) + 1}",
                    first = first,
                    last = last,
                    tooltip = node.location,
                    icon = summaryNodeIcon(node),
                    summary = summaries.text(node.id),
                    missingSummary = missing,
                    summaryLabel = when (node.kind) {
                        SummaryKind.ROOT -> if (files.firstOrNull() is ReviewFile) "Change summary" else "Scope summary"
                        SummaryKind.GROUP -> "Group summary"
                        else -> "Folder summary"
                    },
                )
            }
            // Each top-level folder keeps one hue down its columns; the root stays neutral.
            for (child in node.children.map(tree::get)) collect(child, level + 1, family ?: child.identity)
        }
        if (files.isNotEmpty()) collect(tree.root, 0, null)
        // Source reviews give every complete file a path card, including files summarized in parts.
        if (depth > folderDepth) fileNodes.forEachIndexed { index, node ->
            items += RailItem(
                key = node.identity,
                name = "node:${node.identity}",
                title = node.path,
                family = generateSequence(node) { it.parent?.let(tree::get)?.takeUnless { parent -> parent.kind == SummaryKind.ROOT } }
                    .last().identity,
                level = folderDepth,
                levelLabel = "Level ${tree.depth(node.id) + 1}",
                first = index,
                last = index,
                tooltip = node.path,
                icon = summaryNodeIcon(node),
                summary = summaries.text(node.id),
                missingSummary = missing,
                summaryLabel = "File summary",
            )
        }
        val fileSummaries = fileNodes.associate { it.path to summaries.text(it.id) }
        layoutDocument(files, fileSummaries, items, depth, width, missing)
    }

    override fun summaryFont() = super.summaryFont().forSummaryText()

    override fun resultTrace(): SummaryTrace? = state.resultTrace

    override fun openFile(file: ReviewSource) {
        nodesByPath[file.path]?.let(actions.openNode) ?: actions.openFile(file.path)
    }

    override fun showFile(file: ReviewSource) {
        val show = actions.showNode
        val node = nodesByPath[file.path]
        if (show != null && node != null) show(node) else actions.showDiffCard(file.path)
    }

    override fun wholeFileAction(file: ReviewSource): (() -> Unit)? = nodesByPath[file.path]?.let(actions::wholeFileAction)

    override fun directoryDiffAction(item: RailItem): (() -> Unit)? =
        tree.nodes.firstOrNull { it.identity == item.key }?.let(actions::directoryAction)

    override fun explainFileAction(file: ReviewSource): (() -> Unit)? = nodesByPath[file.path]?.let(actions::explainAction)

    override fun explainItemAction(item: RailItem): (() -> Unit)? = nodesByIdentity[item.key]?.let(actions::explainAction)

    override val explainEnabled: Boolean get() = card.canRun

    override fun summaryBody(identity: String, summary: String?, missingSummary: String, width: Int): JComponent =
        (if (summary != null) summaryText(identity, summary, width)
            else super.summaryBody(identity, null, missingSummary, width)).also { area ->
            if (summary != null) installWordTracing(area as JTextComponent, identity, summary, state, actions.trace, actions.searchSelection,
                actions.searchTab.takeIf { identity in branches }, actions.score, actions.follow)
        }.let { actions.review?.body(identity, it) ?: it }

    private fun summaryText(identity: String, summary: String, width: Int) =
        WrappedSummaryText(summary, width, summaryFont(),
            if (state.tagsCloud) nodesByIdentity[identity]?.let { wordWeights[it.id] }.orEmpty() else emptyMap())

    override fun summaryWordWidth(identity: String, summary: String): Int = summaryText(identity, summary, 1).longestWordWidth()

    override fun isSelected(item: RailItem): Boolean = state.selected == item.key

    override fun select(item: RailItem) { state.select(item.key) }

    override fun selectedFiles(): IntRange? =
        files.indexOfFirst { "file:${it.path}" == state.selected }.takeIf { it >= 0 }?.let { it..it }

    companion object {
        fun railDepth(tree: ChangeSummaryTree, state: ChangeSummariesViewState): Int = state.railDepth(tree) +
            if (tree.nodes.any { it.kind == SummaryKind.FILE && it.file !is ReviewFile }) 1 else 0
    }
}

/** A file name or merged chain, a folder chain with a trailing slash, a group's range, a part's lines, or the root's folder. */
private fun summaryNodeTitle(node: SummaryNode): String = when (node.kind) {
    SummaryKind.FILE, SummaryKind.GROUP, SummaryKind.ROOT, SummaryKind.CHUNK -> node.label
    SummaryKind.DIRECTORY -> "${node.label}/"
}

private fun summaryNodeIcon(node: SummaryNode): Icon = when (node.kind) {
    SummaryKind.FILE -> FileTypeManager.getInstance().getFileTypeByFileName(node.path.substringAfterLast('/')).icon ?: AllIcons.FileTypes.Any_type
    SummaryKind.CHUNK -> AllIcons.FileTypes.Text
    SummaryKind.DIRECTORY -> AllIcons.Nodes.Folder
    SummaryKind.GROUP -> AllIcons.Actions.GroupBy
    SummaryKind.ROOT -> AllIcons.Vcs.Changelist
}
