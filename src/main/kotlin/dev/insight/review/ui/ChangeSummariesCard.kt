package dev.insight.review.ui

import com.intellij.ui.components.ActionLink
import dev.insight.context.ContextSummaryActions
import dev.insight.review.CanvasTabKey
import dev.insight.review.PatchSource
import dev.insight.review.ReviewSource
import dev.insight.review.ReviewSummarySnapshot
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewVocabulary
import dev.insight.review.SummaryKind
import dev.insight.review.SummaryNode
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.text.JTextComponent

/** What the canvas shows for a tab's summaries: the tree so far and whether a /summaries turn can run. */
internal data class SummariesCard(
    val summaries: ReviewSummaries,
    val running: Boolean,
    val canRun: Boolean,
    val pendingEntries: Set<Int> = emptySet(),
    val pages: Set<CanvasTabKey.SummaryPage> = setOf(CanvasTabKey.Summaries),
) {
    val reading: ReviewSummaries get() = summaries.reading
    val summarySnapshot: ReviewSummarySnapshot
        get() = ReviewSummarySnapshot(summaries, running, canGenerate = true, pendingIds = pendingEntries)
}

/** An action scoped to the original summaries of one review entry. */
internal class SummaryEntryFill(val pending: Boolean, val enabled: Boolean, val run: () -> Unit) {
    val label get() = if (pending) "Summarizing…" else "Summarize"
}

/** Builds an entry fill action for [node] from [snapshot] and [actions], or null when already summarized or unfillable. */
internal fun summaryEntryFill(
    snapshot: ReviewSummarySnapshot,
    node: SummaryNode,
    actions: ContextSummaryActions?,
): SummaryEntryFill? {
    if (actions == null) return null
    val target = snapshot.fillTarget(node) ?: return null
    val pending = snapshot.isPending(target)
    return SummaryEntryFill(pending, !pending && snapshot.canGenerate && actions.available(target.path, target.kind)) {
        if (actions.available(target.path, target.kind)) actions.generate(target.path, target.kind)
    }
}

/** Summary prose is slightly larger than the surrounding labels, at every zoom level. */
internal fun Font.forSummaryText(): Font = deriveFont(size2D * 1.15f)

/** File and folder headings stand out from the surrounding metadata. */
internal fun Font.forReviewName(): Font = deriveFont(size2D * 1.2f)

/** How far summarizing has come, available in the view's metadata. */
internal fun summariesStatus(card: SummariesCard, noFiles: String = ReviewVocabulary.DIFF.noFiles): String {
    val summaries = card.summaries
    val count = summaries.count
    val total = summaries.total
    val files = summaries.tree.root.files.size
    val fileCount = "$files file${if (files == 1) "" else "s"}"
    return when {
        total == 0 -> noFiles
        (card.running || card.pendingEntries.isNotEmpty()) && !summaries.complete -> "Summarizing… $count of $total summaries · $fileCount"
        summaries.complete -> "$total summaries · $fileCount"
        else -> "$count of $total summarized · $fileCount"
    }
}

internal class ChangeSummaryActions(
    val toggle: (String) -> Unit,
    val select: (String) -> Unit,
    val openFile: (String) -> Unit,
    val showDiffCard: (String) -> Unit,
    /** Shows the context of word [Int]; with `true`, also searches what its segment cites and selects the best match. */
    val trace: (String, Int, Boolean) -> Unit = { _, _, _ -> },
    /** Searches what the segments holding the selected characters of a summary cite; null hides the selection's context menu. */
    val searchSelection: ((String, Int, Int) -> Unit)? = null,
    /** Searches a summary with children in a new tab that keeps only the related summaries; null hides Search tab. */
    val searchTab: ((SummaryContext) -> Unit)? = null,
    /** Searches further down from the words of a match that continue the search; null leaves them unmarked. */
    val follow: ((SummaryContext) -> Unit)? = null,
    /** Open the file of a node, at the node's first line for a part; by default the file by its path. */
    val openNode: (SummaryNode) -> Unit = { openFile(it.path) },
    /** Put a node's source on the canvas; null hides the buttons. */
    val showNode: ((SummaryNode) -> Unit)? = { showDiffCard(it.path) },
    val openWholeFile: ((PatchSource) -> Unit)? = null,
    val openDirectory: ((SummaryNode) -> Unit)? = null,
    /** Ask the review chat to explain a summary entry (/explain); null hides Explain. */
    val explain: ((SummaryNode) -> Unit)? = null,
    val review: SummaryReviewPresentation? = null,
    val resummarize: ((SummaryNode, Boolean) -> Unit)? = null,
    val resummaryEnabled: Boolean = true,
    /** Open a new review tab whose chat is focused on an entry; null hides To Chat. */
    val toChat: ((SummaryNode) -> Unit)? = null,
    /** Run the IDE's inspections over an entry's files and list the problems; null hides Inspect code. */
    val inspect: ((SummaryNode) -> Unit)? = null,
    /** Open a file's canvas session (Open on canvas); null hides it. */
    val openOnCanvas: ((SummaryNode) -> Unit)? = null,
    /** Lay out every file of a directory, group or topic on the review's wall; null hides the wall's menu item. */
    val openOnWall: ((SummaryNode) -> Unit)? = null,
    /** The wall's menu item and its tooltip: "Open on diffs wall" for a diff. */
    val openOnWallLabel: String = ReviewVocabulary.DIFF.openOnWall,
    val openOnWallTooltip: String = ReviewVocabulary.DIFF.openOnWallTooltip,
    val contextProject: com.intellij.openapi.project.Project? = null,
    val contextSources: List<dev.insight.review.ReviewSource> = emptyList(),
    val reviewDestinations: () -> Map<dev.insight.context.ReviewDestination, () -> Unit> = { emptyMap() },
    val fillEntry: (SummaryNode) -> SummaryEntryFill? = { null },
    val contextSummaryActions: ContextSummaryActions? = null,
    /** Rebuilds the card showing the panel, as after a hierarchy shows more of its entries. */
    val refresh: () -> Unit = {},
    /** The tree the panel shows, for the Context picker of the files its summaries link to. */
    val summaryTree: ReviewSummaryTree? = null,
) {
    private val linkScope by lazy { contextSources.map { it.path } }
    private val linkDiff by lazy { contextSources.any { it is PatchSource } }
    private val linkFileNodes by lazy { summaryTree?.nodes?.filter { it.kind == SummaryKind.FILE }?.associateBy { it.path }.orEmpty() }

    /**
     * Links the file paths, line references and code symbols of [node]'s summary [text] shown in [area], as the report viewer
     * does; see [installSummaryTextLinks]. Needs a [contextProject]. Call before [installEntryMenu] so the link menu holds the
     * entry's items.
     */
    fun installTextLinks(area: JTextComponent, node: SummaryNode, text: String) {
        val project = contextProject ?: return
        val scope = linkScope
        val diff = linkDiff
        val wall: ((List<ReviewSource>, String) -> Unit)? = openOnWall?.let { open -> { files, _ -> open(node.copy(files = files.map { it.path })) } }
        val navigator = summaryTextNavigator(project, node.file?.path, node.files, scope, diff) { link, path, absolutePath ->
            reportLinkContextPick(project, link.kind, path, link.lines, contextSources, summaryTree, reviewDestinations(),
                contextSummaryActions, wall, openOnCanvas, absolutePath)
        }
        installSummaryTextLinks(area, text, project, SummaryTextLinkContext(navigator, reviewDestinations, contextSources, wall) { path, _ ->
            val target = matchingReviewSource(contextSources, path, null)?.let { linkFileNodes[it.path] }
            if (!diff || target == null) emptyList() else listOf(JMenuItem("Open diff").apply {
                name = "summary-link-action:OpenDiff"
                toolTipText = "Open the diff of ${target.path} in this review"
                addActionListener { openNode(target) }
            })
        })
    }

    fun addEntryItems(menu: JPopupMenu, node: SummaryNode) {
        addSummaryContextItem(menu, contextProject, node, contextSources, openOnCanvas,
            openOnWall?.let { open -> { files, _ -> open(node.copy(files = files.map { it.path })) } }, reviewDestinations(),
            summaryActions = contextSummaryActions)
        fillEntry(node)?.let { fill ->
            menu.add(JMenuItem(fill.label).apply {
                name = "summary-action:Summarize"
                isEnabled = fill.enabled
                addActionListener { fill.run() }
            })
        }
        wallAction(node)?.let { open ->
            menu.add(JMenuItem(openOnWallLabel).apply {
                name = "summary-action:$openOnWallLabel"
                toolTipText = openOnWallTooltip
                addActionListener { open() }
            })
        }
        if (toChat != null && node.files.isNotEmpty()) menu.add(JMenuItem("To Chat").apply {
            name = "summary-action:To Chat"
            toolTipText = "Chat about this entry in a new tab, with its summaries and evidence in context"
            addActionListener { toChat(node) }
        })
        val run = resummarize ?: return
        if (node.files.isEmpty() || node.isTopicBranch) return
        for ((label, recursive) in listOf("Resummarize" to false, "Resummarize all" to true)) {
            if (recursive && node.children.isEmpty()) continue
            menu.add(JMenuItem(label).apply {
                name = "summary-action:$label"
                isEnabled = resummaryEnabled
                addActionListener { run(node, recursive) }
            })
        }
    }

    fun installEntryMenu(component: JComponent, node: SummaryNode) {
        if (resummarize == null && toChat == null && wallAction(node) == null && contextProject == null && fillEntry(node) == null) return
        installSummaryEntryMenu(component) { JPopupMenu().also { addEntryItems(it, node) } }
    }

    fun fillLink(node: SummaryNode, font: Font): ActionLink? = fillEntry(node)?.let { fill ->
        ActionLink(fill.label) { fill.run() }.apply {
            name = "summarize-entry:${node.identity}"
            this.font = font
            isEnabled = fill.enabled
        }
    }

    fun wholeFileAction(node: SummaryNode): (() -> Unit)? {
        val file = (node.file as? PatchSource)?.takeIf { it.fullFileText != null } ?: return null
        return openWholeFile?.let { open -> { open(file) } }
    }

    fun directoryAction(node: SummaryNode): (() -> Unit)? =
        if (node.file == null && node.files.isNotEmpty() && node.kind in setOf(SummaryKind.ROOT, SummaryKind.DIRECTORY, SummaryKind.GROUP))
            openDirectory?.let { open -> { open(node) } } else null

    /** Open the several files of a root, directory, group or topic on the wall; a single file or part has its own view. */
    fun wallAction(node: SummaryNode): (() -> Unit)? =
        if (node.file == null && node.files.isNotEmpty()) openOnWall?.let { open -> { open(node) } } else null

    fun explainAction(node: SummaryNode): (() -> Unit)? =
        if (node.files.isEmpty() || node.isTopicBranch) null else explain?.let { explain -> { explain(node) } }

    fun inspectAction(node: SummaryNode): (() -> Unit)? =
        if (node.files.isEmpty()) null else inspect?.let { inspect -> { inspect(node) } }

    fun openOnCanvasAction(node: SummaryNode): (() -> Unit)? =
        if (node.kind != SummaryKind.FILE) null else openOnCanvas?.let { open -> { open(node) } }
}

/** What the wall calls the entries of [node]: the root, a group or topic by its label, a directory by its path. */
internal fun wallTitle(node: SummaryNode): String =
    if (node.kind == SummaryKind.DIRECTORY) node.path.ifEmpty { node.label } else node.label
