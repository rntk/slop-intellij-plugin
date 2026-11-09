package dev.insight.review.ui

import dev.insight.review.PatchSource
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewVocabulary
import dev.insight.review.SummaryKind
import dev.insight.review.SummaryNode
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

/** What the canvas shows for a tab's summaries: the tree so far and whether a /summaries turn can run. */
internal data class SummariesCard(val summaries: ReviewSummaries, val running: Boolean, val canRun: Boolean)

/** Summary prose is slightly larger than the surrounding labels, at every zoom level. */
internal fun Font.forSummaryText(): Font = deriveFont(size2D * 1.15f)

/** File and folder headings stand out from the surrounding metadata. */
internal fun Font.forReviewName(): Font = deriveFont(size2D * 1.2f)

/** How far summarizing has come, shown at the top of both modes. */
internal fun summariesStatus(card: SummariesCard, noFiles: String = ReviewVocabulary.DIFF.noFiles): String {
    val summaries = card.summaries
    val count = summaries.count
    val total = summaries.total
    val files = summaries.tree.root.files.size
    val fileCount = "$files file${if (files == 1) "" else "s"}"
    return when {
        total == 0 -> noFiles
        card.running && !summaries.complete -> "Summarizing… $count of $total summaries · $fileCount"
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
    /** Lay out the diffs of every file of a directory, group or topic on the diffs wall; null hides Open on diffs wall. */
    val openOnDiffsWall: ((SummaryNode) -> Unit)? = null,
) {
    fun addEntryItems(menu: JPopupMenu, node: SummaryNode) {
        diffsWallAction(node)?.let { open ->
            menu.add(JMenuItem(DIFFS_WALL_LABEL).apply {
                name = "summary-action:$DIFFS_WALL_LABEL"
                toolTipText = "Show the raw diffs of this entry's files together on the diffs wall"
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
        if (resummarize == null && toChat == null && diffsWallAction(node) == null) return
        installSummaryEntryMenu(component) { JPopupMenu().also { addEntryItems(it, node) } }
    }

    fun wholeFileAction(node: SummaryNode): (() -> Unit)? {
        val file = (node.file as? PatchSource)?.takeIf { it.fullFileText != null } ?: return null
        return openWholeFile?.let { open -> { open(file) } }
    }

    fun directoryAction(node: SummaryNode): (() -> Unit)? =
        if (node.file == null && node.files.isNotEmpty() && node.kind in setOf(SummaryKind.ROOT, SummaryKind.DIRECTORY, SummaryKind.GROUP))
            openDirectory?.let { open -> { open(node) } } else null

    /** Open the several files of a root, directory, group or topic on the diffs wall; a single file or part has its own diff. */
    fun diffsWallAction(node: SummaryNode): (() -> Unit)? =
        if (node.file == null && node.files.isNotEmpty()) openOnDiffsWall?.let { open -> { open(node) } } else null

    fun explainAction(node: SummaryNode): (() -> Unit)? =
        if (node.files.isEmpty() || node.isTopicBranch) null else explain?.let { explain -> { explain(node) } }

    fun inspectAction(node: SummaryNode): (() -> Unit)? =
        if (node.files.isEmpty()) null else inspect?.let { inspect -> { inspect(node) } }

    fun openOnCanvasAction(node: SummaryNode): (() -> Unit)? =
        if (node.kind != SummaryKind.FILE) null else openOnCanvas?.let { open -> { open(node) } }
}

internal const val DIFFS_WALL_LABEL = "Open on diffs wall"

/** What the diffs wall calls the entries of [node]: the root, a group or topic by its label, a directory by its path. */
internal fun diffsWallTitle(node: SummaryNode): String =
    if (node.kind == SummaryKind.DIRECTORY) node.path.ifEmpty { node.label } else node.label
