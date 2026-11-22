package dev.insight.codereview

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.awt.RelativePoint
import dev.insight.context.ContextOrigin
import dev.insight.context.ContextData
import dev.insight.context.ContextKeys
import dev.insight.context.ContextPick
import dev.insight.context.ContextSummaryActions
import dev.insight.context.ReviewDestination
import dev.insight.context.capturedSourcePick
import dev.insight.context.showContextPicker
import dev.insight.review.ReportLink
import dev.insight.review.ReportProjectIndex
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.ReviewVocabulary
import dev.insight.review.ui.findReportSourceFile
import dev.insight.review.ui.matchingReviewSource
import dev.insight.review.ui.openReportFile
import dev.insight.review.ui.readVirtualFileText
import dev.insight.review.ui.reportLinkContextPick
import dev.insight.review.wall.AddedWallSource
import dev.insight.review.wall.MAX_ADDED_FILE_BYTES
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
import kotlinx.coroutines.CoroutineScope
import javax.swing.JPanel

/**
 * The Code wall page: the shared [WallView] showing one numbered source block per file in scope. Blocks are built once per
 * snapshot of the wall's files, so a rebuild that only brings summaries keeps them.
 */
internal class CodeWallView(
    /** Opens the file at the path in the editor, at the double-clicked line when there is one. */
    private val openFile: (path: String, line: Int?) -> Unit,
    /** Shown as a link when the wall holds a subset of the scope. */
    showAllFiles: () -> Unit,
    /** Each file's chats, opened from the Chat tab on its block's right edge; without them blocks have no tab. */
    chats: WallChats? = null,
    /** The project the files' syntax highlighting is looked up for; highlighting still works without one for most file types. */
    private val project: Project? = null,
    /** Destinations belonging to the tab that owns this wall. */
    private val reviewDestinations: () -> Map<ReviewDestination, () -> Unit> = { emptyMap() },
    private val summaryActions: ContextSummaryActions? = null,
    scope: CoroutineScope? = null,
    private val allFiles: (() -> List<CodeFile>)? = null,
    private val summaryTree: (() -> ReviewSummaryTree?)? = null,
    private val openOnWall: ((List<CodeFile>, String) -> Unit)? = null,
) : Disposable {
    private var files: List<CodeFile> = emptyList()
    private var baseContent: CodeWallContent? = null
    private var baseBlocks: List<WallBlock> = emptyList()
    private val addedScopeFiles = linkedMapOf<String, CodeFile>()
    private val addedProjectFiles = linkedMapOf<String, AddedWallSource>()
    private val expandedBlocks = mutableMapOf<String, WallBlock>()
    private var shown: WallContent? = null

    private fun snapshotFiles(): List<CodeFile> = allFiles?.invoke() ?: files

    private fun scopeFileFor(path: String): CodeFile? =
        files.firstOrNull { it.path == path } ?: addedScopeFiles[path]

    private val linkHost = object : WallLinkHost {
        override val isDiffWall: Boolean = false

        override fun scopePaths(): List<String> = snapshotFiles().map { it.path }

        override fun wallPathFor(path: String, absolutePath: String?): String {
            val normAbs = absolutePath?.let(ReportProjectIndex::normalizeAbsPath)
            val all = snapshotFiles()
            if (normAbs != null) {
                all.firstOrNull { it.absolutePath.isNotBlank() && ReportProjectIndex.normalizeAbsPath(it.absolutePath) == normAbs }
                    ?.let { return it.path }
                addedProjectFiles.values.firstOrNull { !it.absolutePath.isNullOrBlank() && ReportProjectIndex.normalizeAbsPath(it.absolutePath) == normAbs }
                    ?.let { return it.path }
            }
            val norm = normalizeWallPath(path)
            all.firstOrNull { normalizeWallPath(it.path) == norm }?.let { return it.path }
            return norm
        }

        override fun ensureCard(path: String, absolutePath: String?, lines: IntRange?): String? {
            val all = snapshotFiles()
            val scopeMatch = matchingReviewSource(all, path, absolutePath) as? CodeFile
            if (scopeMatch != null) {
                addedScopeFiles[scopeMatch.path] = scopeMatch
                lines?.first?.let { target ->
                    val base = codeWallBlock(scopeMatch)
                    val expanded = wallExpandBlockForLine(base, target)
                    if (expanded !== base) expandedBlocks[scopeMatch.path] = expanded
                }
                rebuildAndShow()
                return scopeMatch.path
            }
            val p = project ?: return null
            val vf = findReportSourceFile(p, path, absolutePath) ?: return null
            if (!vf.isValid || vf.isDirectory || vf.fileType.isBinary || vf.length > MAX_ADDED_FILE_BYTES) return null
            val text = readVirtualFileText(vf) ?: return null
            val canonical = normalizeWallPath(path)
            addedProjectFiles[canonical] = AddedWallSource(canonical, vf.path, text, lines?.first)
            expandedBlocks.remove(canonical)
            rebuildAndShow()
            return canonical
        }

        override fun ensureLineLoaded(path: String, targetLine: Int): Boolean {
            val current = shown?.blocks?.firstOrNull { it.path == path } ?: return false
            val expanded = wallExpandBlockForLine(current, targetLine)
            if (expanded === current) return false
            expandedBlocks[path] = expanded
            rebuildAndShow()
            return true
        }

        override fun isAddedCard(path: String): Boolean = path in addedScopeFiles || path in addedProjectFiles

        override fun contextPick(link: ReportLink, path: String, absolutePath: String?): ContextPick? {
            val p = project ?: return null
            return reportLinkContextPick(
                p, link.kind, path, link.lines,
                reviewFiles = snapshotFiles(),
                summaryTree = summaryTree?.invoke(),
                reviewDestinations = reviewDestinations(),
                summaryActions = summaryActions,
                openOnWall = openOnWall?.let { open -> { srcs, title -> open(srcs.filterIsInstance<CodeFile>(), title) } },
                openOnCanvas = null,
                absolutePath = absolutePath,
            )
        }
    }

    val view = WallView(
        CODE_WALL_LABELS,
        openLine = ::openWallTarget,
        showAll = showAllFiles,
        chats = chats,
        highlighter = codeWallHighlighter(project, ::scopeFileFor, addedProjectFiles::get),
        openOnCanvas = null,
        onContext = project?.let { p -> { block, line, point -> showContext(p, block, line, point) } },
        summarize = null,
        cardWidthScale = 1.0,
        links = WallLinkSupport(
            project = project,
            coroutineScope = scope,
            linkHost = linkHost,
            onRemoveBlock = ::removeAdded,
        ),
    )
    val card: JPanel get() = view.card

    private fun openWallTarget(path: String, line: WallLine?) {
        if (scopeFileFor(path) != null) {
            openFile(path, line?.new)
            return
        }
        val added = addedProjectFiles[path] ?: return
        val p = project
        if (p != null) openReportFile(p, added.path, line?.new?.let { it..it }, absolutePath = added.absolutePath)
        else openFile(path, line?.new)
    }

    private fun removeAdded(path: String) {
        val removed = addedScopeFiles.remove(path) != null || addedProjectFiles.remove(path) != null
        if (!removed) return
        expandedBlocks.remove(path)
        rebuildAndShow()
    }

    /** Opens the context picker for a right-click on [block]: the source scopes at the row, or only the file for the header. */
    private fun showContext(project: Project, block: WallBlock, line: WallLine?, point: RelativePoint) {
        val file = scopeFileFor(block.path)
        val path = file?.path ?: addedProjectFiles[block.path]?.path ?: return
        val abs = file?.absolutePath ?: addedProjectFiles[block.path]?.absolutePath
        val text = file?.text ?: addedProjectFiles[block.path]?.text ?: return
        val data = ContextData().put(ContextKeys.REVIEW_DESTINATIONS, reviewDestinations())
        if (file != null) summaryActions?.let { data.put(ContextKeys.SUMMARY_GENERATION, it) }
        showContextPicker(project, capturedSourcePick(project, ContextOrigin.CODE_WALL, path, abs, text, line?.new, data),
            point.component, point)
    }

    /** Shows [content]; a new scope relays the blocks out, the same scope keeps where they were moved. */
    fun show(content: CodeWallContent) {
        val scopeChanged = content.scope != baseContent?.scope
        if (scopeChanged) {
            addedScopeFiles.clear()
            addedProjectFiles.clear()
            expandedBlocks.clear()
        }
        val basePaths = content.files.map { it.path }.toSet()
        val allByPath = snapshotFiles().associateBy { it.path }
        addedScopeFiles.entries.filter { (path, prev) -> path in basePaths || allByPath[path] != prev }.map { it.key }.forEach {
            expandedBlocks.remove(it)
        }
        addedScopeFiles.keys.retainAll { it !in basePaths && it in allByPath }
        addedScopeFiles.replaceAll { path, _ -> allByPath.getValue(path) }
        addedProjectFiles.keys.removeAll { it in basePaths || it in allByPath }
        expandedBlocks.keys.retainAll { it in basePaths || it in addedScopeFiles || it in addedProjectFiles }

        val blocksChanged = baseContent == null || content.files != files || scopeChanged
        if (blocksChanged) {
            baseBlocks = content.files.map { codeWallBlock(it) }
            expandedBlocks.keys.removeAll { it in basePaths }
        }
        files = content.files
        baseContent = content
        rebuildAndShow()
    }

    private fun rebuildAndShow() {
        val content = baseContent ?: return
        val blocks = buildList {
            baseBlocks.forEach { block -> add(expandedBlocks[block.path] ?: block) }
            addedScopeFiles.values.forEach { cf -> add(expandedBlocks[cf.path] ?: codeWallBlock(cf)) }
            addedProjectFiles.values.forEach { src ->
                add(expandedBlocks[src.path] ?: wallAddedSourceBlock(src.path, src.text, src.targetLine, badge = "outside scope"))
            }
        }
        val wall = WallContent(content.scope, blocks, content.summaries)
        shown = wall
        view.show(wall)
    }

    override fun dispose() = view.dispose()
}

/** Colours a source block from its file's whole text, lexed only as far as the block shows. */
internal fun codeWallHighlighter(
    project: Project?,
    file: (path: String) -> CodeFile?,
): WallHighlighter = codeWallHighlighter(project, file) { null }

internal fun codeWallHighlighter(
    project: Project?,
    file: (path: String) -> CodeFile?,
    source: (path: String) -> AddedWallSource?,
) = WallHighlighter { block ->
    val code = file(block.path)
    val path = code?.path ?: source(block.path)?.path ?: return@WallHighlighter null
    val text = code?.text ?: source(block.path)?.text ?: return@WallHighlighter null
    val last = wallSideExtents(block.lines).second
    if (last == 0) return@WallHighlighter null
    { WallSyntax.side(project, path, text, last)?.let { wallRowSpans(block.lines, null, it) } }
}

/** The words and component names of the Code wall. */
internal val CODE_WALL_LABELS = WallLabels(
    id = "code-wall",
    header = { title, count -> "${ReviewVocabulary.CODE.wallName} · $title · $count file${if (count == 1) "" else "s"}" },
    controls = """<html>Drag a block to move it; drag the empty canvas, or middle-drag anywhere, to pan<br>
        |Double-click a line, or press Enter on the active block, to open the file in the editor at that line<br>
        |${WALL_MENU_KEY}wheel or $WALL_MENU_KEY= / $WALL_MENU_KEY− zooms, ${WALL_MENU_KEY}0 resets to 100%, F or ${WALL_MENU_KEY}Shift+0 fits the whole wall<br>
        |Arrows, Page Up/Down, Space / Shift+Space and Home/End scroll; Shift scrolls sideways<br>
        |Click a block to focus and zoom in. Tab / Shift+Tab step through blocks<br>
        |The wheel and scroll keys use the focused block where it can scroll and the canvas on other axes<br>
        |Esc restores canvas navigation. Press Esc again to clear the current block<br>
        |Skim above the wall, or M, reads every file whole in one column<br>
        |Compact in a block's header, or C, caps a tall block; its source scrolls inside<br>
        |Summary in a block's header shows the file's summary in place of its source<br>
        |Chat on a block's right edge unfolds a chat about that file; Esc in it folds it again<br>
        |Chat about these files, above the wall, opens a chat about every file it shows</html>""".trimMargin(),
    showAll = "Show all files",
    canvasChatLink = "Chat about these files",
    canvasChatLinkTooltip = "Chat about every file on the wall",
    emptyWall = "No source files in this scope",
    emptyBlock = "Empty file",
    hiddenLines = { count -> "⋯ $count more lines; double-click to open the file" },
    expandTooltip = "Show the whole file (C)",
    compactTooltip = "Cap the block's height; its source scrolls inside (C)",
    bodyTooltip = "Show the source again",
    summaryTooltip = "Show this file's summary in place of its source",
    chatTabTooltip = "Chat about this file",
    fileChat = { path ->
        WallChatText(
            title = "Chat · ${path.substringAfterLast('/')}",
            titleTooltip = "Ask about $path; the rest of the scope and project files are read when needed",
            placeholder = "Ask about this file… (Enter sends)",
            historyTooltip = "Earlier chats about this file",
            newChatTooltip = "Start an independent chat about this file; earlier chats stay in the list",
        )
    },
    canvasChat = WallChatText(
        title = "Chat · all files on the wall",
        titleTooltip = "Ask about every file on the wall; the rest of the scope and project files are read when needed",
        placeholder = "Ask about these files… (Enter sends)",
        historyTooltip = "Earlier chats about the wall's files",
        newChatTooltip = "Start an independent chat about the wall's files; earlier chats stay in the list",
    ),
)
