package dev.insight.diff

import com.intellij.diff.DiffManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.awt.RelativePoint
import dev.insight.agent.SourceSide
import dev.insight.context.ContextData
import dev.insight.context.ContextKeys
import dev.insight.context.ContextOrigin
import dev.insight.context.ContextSummaryActions
import dev.insight.context.reviewDiffPick
import dev.insight.context.showContextPicker
import dev.insight.review.ReviewVocabulary
import dev.insight.review.ui.EvidenceLine
import dev.insight.review.ui.LineSide
import dev.insight.review.ui.LineTrace
import dev.insight.review.wall.WALL_MENU_KEY
import dev.insight.review.wall.WallChatText
import dev.insight.review.wall.WallChats
import dev.insight.review.wall.WallBlock
import dev.insight.review.wall.WallContent
import dev.insight.review.wall.WallHighlighter
import dev.insight.review.wall.WallLabels
import dev.insight.review.wall.WallLine
import dev.insight.review.wall.WallSyntax
import dev.insight.review.wall.WallView
import dev.insight.review.wall.wallRowSpans
import dev.insight.review.wall.wallSideExtents
import javax.swing.JPanel

/**
 * The Diffs wall page: the shared [WallView] showing one `git diff` block per changed file. Blocks are parsed once per snapshot
 * of the wall's files, so a rebuild that only brings summaries, or the files whose summary is being written, keeps them.
 */
internal class DiffsWallView(
    /** Opens [ReviewFile]'s native diff, at the line when one was double-clicked. */
    private val openDiff: (ReviewFile, WallLine?) -> Unit,
    /** Shown as a link when the wall holds a subset of the change. */
    showAllChanges: () -> Unit,
    /** Each file's chats, opened from the Chat tab on its block's right edge; without them blocks have no tab. */
    chats: WallChats? = null,
    /** The project the files' syntax highlighting is looked up for; highlighting still works without one for most file types. */
    project: Project? = null,
    /** Opens a file of the wall on a canvas session; null leaves the block menu without that item. */
    private val openOnCanvas: ((path: String) -> Unit)? = null,
    private val openOnWall: ((List<ReviewFile>, String) -> Unit)? = null,
    private val reviewDestinations: (() -> Map<dev.insight.context.ReviewDestination, () -> Unit>)? = null,
    /** Asks for the summary of a file that has none; see [WallView]. Null leaves only files with a summary their Summary switch. */
    summarize: ((path: String) -> Boolean)? = null,
    private val summaryActions: ContextSummaryActions? = null,
) : Disposable {
    private var files: List<ReviewFile> = emptyList()
    private var shown: WallContent? = null
    val view = WallView(DIFFS_WALL_LABELS, { path, line -> files.firstOrNull { it.path == path }?.let { openDiff(it, line) } },
        showAllChanges, chats, diffsWallHighlighter(project) { path -> files.firstOrNull { it.path == path } }, openOnCanvas,
        project?.let { p -> { block, line, point -> showContext(p, block, line, point) } }, summarize, cardWidthScale = 1.2)
    val card: JPanel get() = view.card

    /** Opens the context picker for a right-click on [block]: the diff scopes at the row, and the whole wall as one change. */
    private fun showContext(project: Project, block: WallBlock, line: WallLine?, point: RelativePoint) {
        val file = files.firstOrNull { it.path == block.path } ?: return
        val (side, number) = wallLineSide(line)
        val data = ContextData()
        reviewDestinations?.invoke()?.let { data.put(ContextKeys.REVIEW_DESTINATIONS, it) }
        summaryActions?.let { data.put(ContextKeys.SUMMARY_GENERATION, it) }
        openOnWall?.let { open -> data.put(ContextKeys.OPEN_ON_DIFFS_WALL) { context ->
            val paths = if (context.kind == dev.insight.context.ContextScopeKind.CHANGE) context.files.toSet() else setOf(file.path)
            val selected = files.filter { it.path in paths }
            if (selected.isNotEmpty()) open(selected, context.label)
        } }
        openOnCanvas?.let { open -> data.put(ContextKeys.OPEN_ON_CANVAS) { context -> context.path?.let(open) } }
        showContextPicker(project, reviewDiffPick(project, ContextOrigin.DIFFS_WALL, file, number, side, files, data), point.component, point)
    }

    /** Shows [content]; a new scope relays the blocks out, the same scope keeps where they were moved. */
    fun show(content: DiffsWallContent) {
        val previous = shown
        val wall = if (previous != null && content.files == files) previous.copy(scope = content.scope,
            summaries = content.summaries, summarizing = content.summarizing)
            else content.toWall()
        files = content.files
        shown = wall
        view.show(wall)
    }

    override fun dispose() = view.dispose()
}

/**
 * Colours a diff block from both whole sides of its file, so a row inside a comment or string that starts outside its hunk is
 * still coloured right; the old side is lexed by its old name, for renames. Rows whose side is missing stay plain.
 */
internal fun diffsWallHighlighter(project: Project?, file: (path: String) -> ReviewFile?) = WallHighlighter { block ->
    val review = file(block.path) ?: return@WallHighlighter null
    val (lastBefore, lastAfter) = wallSideExtents(block.lines)
    if (lastBefore == 0 && lastAfter == 0) return@WallHighlighter null
    {
        val before = WallSyntax.side(project, review.previousPath ?: review.path, review.before, lastBefore)
        val after = WallSyntax.side(project, review.path, review.after, lastAfter)
        if (before == null && after == null) null else wallRowSpans(block.lines, before, after)
    }
}

/** The words and component names of the Diffs wall. */
internal val DIFFS_WALL_LABELS = WallLabels(
    id = "diffs-wall",
    header = { title, count -> "${ReviewVocabulary.DIFF.wallName} · $title · $count file${if (count == 1) "" else "s"}" },
    controls = """<html>Drag a block to move it; drag the empty canvas, or middle-drag anywhere, to pan<br>
        |Double-click a line, or press Enter on the active block, to open its diff<br>
        |${WALL_MENU_KEY}wheel or $WALL_MENU_KEY= / $WALL_MENU_KEY− zooms, ${WALL_MENU_KEY}0 resets to 100%, F or ${WALL_MENU_KEY}Shift+0 fits the whole wall<br>
        |Arrows, Page Up/Down, Space / Shift+Space and Home/End scroll; Shift scrolls sideways<br>
        |Click a block to focus and zoom in. Tab / Shift+Tab step through blocks<br>
        |The wheel and scroll keys use the focused block where it can scroll and the canvas on other axes<br>
        |Esc restores canvas navigation. Press Esc again to clear the current block<br>
        |Skim above the wall, or M, reads every file whole in one column<br>
        |Compact in a block's header, or C, caps a tall block; its diff scrolls inside<br>
        |Summary in a block's header shows the file's summary in place of its diff, writing it first when it has none<br>
        |Chat on a block's right edge unfolds a chat about that file's diff; Esc in it folds it again<br>
        |Chat about these changes, above the wall, opens a chat about every diff it shows</html>""".trimMargin(),
    showAll = "Show all changes",
    canvasChatLink = "Chat about these changes",
    canvasChatLinkTooltip = "Chat about every diff on the wall",
    emptyWall = "No changed files in this scope",
    emptyBlock = "No textual changes",
    hiddenLines = { count -> "⋯ $count more lines; double-click to open the diff" },
    expandTooltip = "Show the whole diff (C)",
    compactTooltip = "Cap the block's height; its diff scrolls inside (C)",
    bodyTooltip = "Show the diff again",
    summaryTooltip = "Show this file's summary in place of its diff",
    chatTabTooltip = "Chat about this file's diff",
    fileChat = { path ->
        WallChatText(
            title = "Chat · ${path.substringAfterLast('/')}",
            titleTooltip = "Ask about $path's diff; the rest of the change and project files are read when needed",
            placeholder = "Ask about this diff… (Enter sends)",
            historyTooltip = "Earlier chats about this file",
            newChatTooltip = "Start an independent chat about this file; earlier chats stay in the list",
        )
    },
    canvasChat = WallChatText(
        title = "Chat · all diffs on the wall",
        titleTooltip = "Ask about every diff on the wall; the rest of the change and project files are read when needed",
        placeholder = "Ask about these changes… (Enter sends)",
        historyTooltip = "Earlier chats about the wall's changes",
        newChatTooltip = "Start an independent chat about the wall's changes; earlier chats stay in the list",
    ),
)

/** Opens [file]'s native diff, at [line] with it highlighted when the line has a number on a side that has text. */
internal fun openWallDiff(project: Project, file: ReviewFile, line: WallLine?, session: (() -> ReviewDiffSession?)? = null) {
    val (side, number) = when {
        line?.new != null -> LineSide.AFTER to line.new
        line?.old != null -> LineSide.BEFORE to line.old
        else -> null to 0
    }
    // Without both sides the native diff shows the patch itself, whose lines are not the file's.
    if (side == null || file.before == null || file.after == null) {
        DiffManager.getInstance().showDiff(project, createDiffRequest(project, file, session))
        return
    }
    val evidence = EvidenceLine(side, number, line!!.text.drop(1), line.text.firstOrNull() ?: ' ', diff = true)
    showDiffLines(project, file, LineTrace(file.path, file.path, "", emptyList(), listOf(evidence)), session)
}

/** The side and 1-based line of a wall row for the context picker: its new line, else its old line, else none (hunk headers, notes, padding). */
internal fun wallLineSide(line: WallLine?): Pair<SourceSide, Int?> = when {
    line?.new != null -> SourceSide.AFTER to line.new
    line?.old != null -> SourceSide.BEFORE to line.old
    else -> SourceSide.AFTER to null
}
