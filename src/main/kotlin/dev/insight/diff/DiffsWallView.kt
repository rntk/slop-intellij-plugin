package dev.insight.diff

import com.intellij.diff.DiffManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.awt.RelativePoint
import dev.insight.agent.SourceSide
import dev.insight.context.ContextData
import dev.insight.context.ContextKeys
import dev.insight.context.ContextOrigin
import dev.insight.context.ContextPick
import dev.insight.context.ContextSummaryActions
import dev.insight.context.capturedSourcePick
import dev.insight.context.reviewDiffPick
import dev.insight.context.showContextPicker
import dev.insight.review.ReportLink
import dev.insight.review.ReportProjectIndex
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.ReviewVocabulary
import dev.insight.review.ui.EvidenceLine
import dev.insight.review.ui.LineSide
import dev.insight.review.ui.LineTrace
import dev.insight.review.ui.findReportSourceFile
import dev.insight.review.ui.matchingReviewSource
import dev.insight.review.ui.openReportFile
import dev.insight.review.ui.readVirtualFileText
import dev.insight.review.ui.reportLinkContextPick
import dev.insight.review.wall.AddedWallSource
import dev.insight.review.wall.MAX_ADDED_BLOCK_LINES
import dev.insight.review.wall.MAX_ADDED_FILE_BYTES
import dev.insight.review.wall.WALL_LOAD_CHUNK
import dev.insight.review.wall.WALL_MAX_LINES
import dev.insight.review.wall.WALL_MAX_SKIM_LINES
import dev.insight.review.wall.WALL_MENU_KEY
import dev.insight.review.wall.WallChatText
import dev.insight.review.wall.WallChats
import dev.insight.review.wall.WallBlock
import dev.insight.review.wall.WallContent
import dev.insight.review.wall.WallHighlighter
import dev.insight.review.wall.WallLabels
import dev.insight.review.wall.WallLine
import dev.insight.review.wall.WallLinkHost
import dev.insight.review.wall.WallLinkSupport
import dev.insight.review.wall.WallSyntax
import dev.insight.review.wall.WallView
import dev.insight.review.wall.normalizeWallPath
import dev.insight.review.wall.wallAddedSourceBlock
import dev.insight.review.wall.wallExpandBlockForLine
import dev.insight.review.wall.wallRowSpans
import dev.insight.review.wall.wallSideExtents
import dev.insight.review.wall.wallTargetRow
import kotlinx.coroutines.CoroutineScope
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
    private val project: Project? = null,
    /** Opens a file of the wall on a canvas session; null leaves the block menu without that item. */
    private val openOnCanvas: ((path: String, line: Int?) -> Unit)? = null,
    private val openOnWall: ((List<ReviewFile>, String) -> Unit)? = null,
    private val reviewDestinations: (() -> Map<dev.insight.context.ReviewDestination, () -> Unit>)? = null,
    /** Asks for the summary of a file that has none; see [WallView]. Null leaves only files with a summary their Summary switch. */
    summarize: ((path: String) -> Boolean)? = null,
    private val summaryActions: ContextSummaryActions? = null,
    /** Coroutine scope for background link rendering in summaries and chats. */
    scope: CoroutineScope? = null,
    /** Every changed file in the current comparison snapshot, so subset walls can pull in linked diff files. */
    private val allFiles: (() -> List<ReviewFile>)? = null,
    /** Optional summary tree for context picker actions on linked files. */
    private val summaryTree: (() -> ReviewSummaryTree?)? = null,
    /** Number of diff blocks loaded per chunk so large changes stay responsive. */
    loadChunk: Int = WALL_LOAD_CHUNK,
) : Disposable {
    private var files: List<ReviewFile> = emptyList()
    private var filesByPath: Map<String, ReviewFile> = emptyMap()
    private var baseContent: DiffsWallContent? = null
    private val blockCache = mutableMapOf<String, Pair<ReviewFile, WallBlock>>()
    private val addedDiffs = linkedMapOf<String, ReviewFile>()
    private val addedSources = linkedMapOf<String, AddedWallSource>()
    private val expandedBlocks = mutableMapOf<String, WallBlock>()

    private fun snapshotFiles(): List<ReviewFile> = allFiles?.invoke() ?: files

    private fun diffFileFor(path: String): ReviewFile? =
        filesByPath[path] ?: addedDiffs[path]

    private fun baseBlockFor(file: ReviewFile): WallBlock {
        val cached = blockCache[file.path]
        if (cached != null && cached.first == file) return cached.second
        return wallBlock(file).also { blockCache[file.path] = file to it }
    }

    private fun blockForPath(path: String): WallBlock? {
        expandedBlocks[path]?.let { return it }
        filesByPath[path]?.let { return baseBlockFor(it) }
        addedDiffs[path]?.let { return baseBlockFor(it) }
        return addedSources[path]?.let { wallAddedSourceBlock(it.path, it.text, it.targetLine, badge = "not in diff") }
    }

    private fun expandForTargetLine(path: String, current: WallBlock, targetLine: Int): WallBlock? {
        val rf = diffFileFor(path)
        if (rf != null) return wallExpandDiffBlock(rf, current, targetLine)
        if (wallTargetRow(current.lines, targetLine..targetLine)?.exact == true) return null
        val expanded = wallExpandBlockForLine(current, targetLine)
        if (expanded !== current) return expanded
        if (current.hiddenLines <= current.more.size) return null
        val src = addedSources[path] ?: return null
        if (targetLine > MAX_ADDED_BLOCK_LINES) return null
        val maxSkim = minOf(MAX_ADDED_BLOCK_LINES, maxOf(WALL_MAX_SKIM_LINES, targetLine + 51))
        val larger = wallAddedSourceBlock(src.path, src.text, targetLine, "not in diff", WALL_MAX_LINES, maxSkim)
        return larger.takeIf { it.lines.size > current.lines.size }
    }

    private val linkHost = object : WallLinkHost {
        override val isDiffWall: Boolean = true

        override fun scopePaths(): List<String> = snapshotFiles().map { it.path }

        override fun previousPaths(): Map<String, String> =
            (files + addedDiffs.values + snapshotFiles()).mapNotNull { f ->
                f.previousPath?.takeIf { it != f.path }?.let { it to f.path }
            }.toMap()

        override fun wallPathFor(path: String, absolutePath: String?): String {
            val normAbs = absolutePath?.let(ReportProjectIndex::normalizeAbsPath)
            val all = snapshotFiles()
            if (normAbs != null) {
                all.firstOrNull { it.absolutePath.isNotBlank() && ReportProjectIndex.normalizeAbsPath(it.absolutePath) == normAbs }
                    ?.let { return it.path }
                addedSources.values.firstOrNull { !it.absolutePath.isNullOrBlank() && ReportProjectIndex.normalizeAbsPath(it.absolutePath) == normAbs }
                    ?.let { return it.path }
            }
            val norm = normalizeWallPath(path)
            all.firstOrNull { normalizeWallPath(it.path) == norm }?.let { return it.path }
            all.firstOrNull { it.previousPath?.let(::normalizeWallPath) == norm }?.let { return it.path }
            return norm
        }

        override fun ensureCard(path: String, absolutePath: String?, lines: IntRange?): String? {
            val all = snapshotFiles()
            val matchingDiff = matchingReviewSource(all, path, absolutePath) as? ReviewFile
                ?: all.firstOrNull { it.previousPath?.let(::normalizeWallPath) == normalizeWallPath(path) }
            if (matchingDiff != null) {
                val inBase = matchingDiff.path in filesByPath
                if (!inBase) addedDiffs[matchingDiff.path] = matchingDiff
                val expanded = lines?.first?.let { target ->
                    val current = expandedBlocks[matchingDiff.path] ?: baseBlockFor(matchingDiff)
                    expandForTargetLine(matchingDiff.path, current, target)
                }
                if (expanded != null) expandedBlocks[matchingDiff.path] = expanded
                if (!inBase || expanded != null) rebuildAndShow()
                return matchingDiff.path
            }
            val p = project ?: return null
            val vf = findReportSourceFile(p, path, absolutePath) ?: return null
            if (!vf.isValid || vf.isDirectory || vf.fileType.isBinary || vf.length > MAX_ADDED_FILE_BYTES) return null
            val text = readVirtualFileText(vf) ?: return null
            val canonical = normalizeWallPath(path)
            addedSources[canonical] = AddedWallSource(canonical, vf.path, text, lines?.first)
            expandedBlocks.remove(canonical)
            rebuildAndShow()
            return canonical
        }

        override fun ensureLineLoaded(path: String, targetLine: Int): Boolean {
            val current = view.blocks.firstOrNull { it.block.path == path }?.block ?: blockForPath(path) ?: return false
            val expanded = expandForTargetLine(path, current, targetLine) ?: return false
            expandedBlocks[path] = expanded
            rebuildAndShow()
            return true
        }

        override fun isAddedCard(path: String): Boolean = path in addedDiffs || path in addedSources

        override fun contextPick(link: ReportLink, path: String, absolutePath: String?): ContextPick? {
            val p = project ?: return null
            return reportLinkContextPick(
                p, link.kind, path, link.lines,
                reviewFiles = snapshotFiles(),
                summaryTree = summaryTree?.invoke(),
                reviewDestinations = reviewDestinations?.invoke().orEmpty(),
                summaryActions = summaryActions,
                openOnWall = openOnWall?.let { open -> { srcs, title -> open(srcs.filterIsInstance<ReviewFile>(), title) } },
                openOnCanvas = openOnCanvas?.let { open -> { node -> open(node.path, node.lines?.first) } },
                absolutePath = absolutePath,
            )
        }
    }

    val view: WallView = WallView(
        DIFFS_WALL_LABELS,
        openLine = ::openWallTarget,
        showAll = showAllChanges,
        chats = chats,
        highlighter = diffsWallHighlighter(project, ::diffFileFor, addedSources::get),
        openOnCanvas = openOnCanvas?.let { open -> { path -> open(path, null) } },
        onContext = project?.let { p -> { block, line, point -> showContext(p, block, line, point) } },
        summarize = summarize,
        cardWidthScale = 1.2,
        links = WallLinkSupport(
            project = project,
            coroutineScope = scope,
            linkHost = linkHost,
            canSummarize = { it !in addedSources },
            onRemoveBlock = ::removeAdded,
        ),
        loadChunk = loadChunk,
    )
    val card: JPanel get() = view.card

    private fun openWallTarget(path: String, line: WallLine?) {
        diffFileFor(path)?.let { openDiff(it, line); return }
        val src = addedSources[path] ?: return
        val p = project ?: return
        openReportFile(p, src.path, line?.new?.let { it..it }, absolutePath = src.absolutePath)
    }

    private fun removeAdded(path: String) {
        val removed = addedDiffs.remove(path) != null || addedSources.remove(path) != null
        if (!removed) return
        expandedBlocks.remove(path)
        rebuildAndShow()
    }

    /** Opens the context picker for a right-click on [block]: the diff scopes at the row, and the whole wall as one change. */
    private fun showContext(project: Project, block: WallBlock, line: WallLine?, point: RelativePoint) {
        val file = diffFileFor(block.path)
        if (file != null) {
            val (side, number) = wallLineSide(line)
            val data = ContextData()
            reviewDestinations?.invoke()?.let { data.put(ContextKeys.REVIEW_DESTINATIONS, it) }
            summaryActions?.let { data.put(ContextKeys.SUMMARY_GENERATION, it) }
            val contextFiles = (snapshotFiles() + addedDiffs.values).distinctBy { it.path }
            openOnWall?.let { open -> data.put(ContextKeys.OPEN_ON_DIFFS_WALL) { context ->
                val paths = if (context.kind == dev.insight.context.ContextScopeKind.CHANGE) context.files.toSet() else setOf(file.path)
                val selected = contextFiles.filter { it.path in paths }
                if (selected.isNotEmpty()) open(selected, context.label)
            } }
            openOnCanvas?.let { open -> data.put(ContextKeys.OPEN_ON_CANVAS) { context -> context.path?.let { open(it, context.lines?.first ?: number) } } }
            showContextPicker(project, reviewDiffPick(project, ContextOrigin.DIFFS_WALL, file, number, side, contextFiles, data),
                point.component, point)
            return
        }
        val added = addedSources[block.path] ?: return
        val data = ContextData()
        reviewDestinations?.invoke()?.let { data.put(ContextKeys.REVIEW_DESTINATIONS, it) }
        showContextPicker(project, capturedSourcePick(project, ContextOrigin.DIFFS_WALL, added.path, added.absolutePath, added.text, line?.new, data),
            point.component, point)
    }

    /** Shows [content]; a new scope relays the blocks out, the same scope keeps where they were moved. */
    fun show(content: DiffsWallContent) {
        val scopeChanged = content.scope != baseContent?.scope
        if (scopeChanged) {
            addedDiffs.clear()
            addedSources.clear()
            expandedBlocks.clear()
            blockCache.clear()
        }
        val basePaths = content.files.map { it.path }.toSet()
        val allByPath = snapshotFiles().associateBy { it.path }
        addedDiffs.entries.filter { (path, prev) -> path in basePaths || allByPath[path] != prev }.map { it.key }.forEach {
            expandedBlocks.remove(it)
        }
        addedDiffs.keys.retainAll { it !in basePaths && it in allByPath }
        addedDiffs.replaceAll { path, _ -> allByPath.getValue(path) }
        addedSources.keys.removeAll { it in basePaths || it in allByPath }
        expandedBlocks.keys.retainAll { it in basePaths || it in addedDiffs || it in addedSources }

        val oldByPath = filesByPath
        files = content.files
        filesByPath = content.files.associateBy { it.path }
        for ((path, rf) in filesByPath) {
            if (oldByPath[path] != rf) expandedBlocks.remove(path)
        }
        blockCache.keys.retainAll(filesByPath.keys + addedDiffs.keys)
        baseContent = content
        rebuildAndShow()
    }

    private fun rebuildAndShow() {
        val content = baseContent ?: return
        val allPaths = buildList(files.size + addedDiffs.size + addedSources.size) {
            files.forEach { add(it.path) }
            addedDiffs.keys.forEach { add(it) }
            addedSources.keys.forEach { add(it) }
        }
        val wall = WallContent(
            scope = content.scope,
            blocks = emptyList(),
            summaries = content.summaries,
            summarizing = content.summarizing,
            allPaths = allPaths,
            blockFor = ::blockForPath,
        )
        view.show(wall)
    }

    override fun dispose() = view.dispose()
}

/**
 * Colours a diff block from both whole sides of its file, or an added project source block from its source text.
 */
internal fun diffsWallHighlighter(
    project: Project?,
    file: (path: String) -> ReviewFile?,
): WallHighlighter = diffsWallHighlighter(project, file) { null }

internal fun diffsWallHighlighter(
    project: Project?,
    file: (path: String) -> ReviewFile?,
    source: (path: String) -> AddedWallSource?,
) = WallHighlighter { block ->
    val review = file(block.path)
    if (review != null) {
        val (lastBefore, lastAfter) = wallSideExtents(block.lines)
        if (lastBefore == 0 && lastAfter == 0) return@WallHighlighter null
        return@WallHighlighter {
            val before = WallSyntax.side(project, review.previousPath ?: review.path, review.before, lastBefore)
            val after = WallSyntax.side(project, review.path, review.after, lastAfter)
            if (before == null && after == null) null else wallRowSpans(block.lines, before, after)
        }
    }
    val added = source(block.path) ?: return@WallHighlighter null
    val last = wallSideExtents(block.lines).second
    if (last == 0) return@WallHighlighter null
    { WallSyntax.side(project, added.path, added.text, last)?.let { wallRowSpans(block.lines, null, it) } }
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
