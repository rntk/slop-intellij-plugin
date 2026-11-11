package dev.insight.codereview

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import dev.insight.review.ReviewVocabulary
import dev.insight.review.wall.WALL_MENU_KEY
import dev.insight.review.wall.WallChatText
import dev.insight.review.wall.WallChats
import dev.insight.review.wall.WallContent
import dev.insight.review.wall.WallHighlighter
import dev.insight.review.wall.WallLabels
import dev.insight.review.wall.WallSyntax
import dev.insight.review.wall.WallView
import dev.insight.review.wall.wallRowSpans
import dev.insight.review.wall.wallSideExtents
import javax.swing.JPanel

/**
 * The Code wall page: the shared [WallView] showing one numbered source block per file in scope. Blocks are built once per
 * snapshot of the wall's files, so a rebuild that only brings summaries keeps them.
 */
internal class CodeWallView(
    /** Opens the file at the path in the editor, at the double-clicked line when there is one. */
    openFile: (path: String, line: Int?) -> Unit,
    /** Shown as a link when the wall holds a subset of the scope. */
    showAllFiles: () -> Unit,
    /** Each file's chats, opened from the Chat tab on its block's right edge; without them blocks have no tab. */
    chats: WallChats? = null,
    /** The project the files' syntax highlighting is looked up for; highlighting still works without one for most file types. */
    project: Project? = null,
) : Disposable {
    private var files: List<CodeFile> = emptyList()
    private var shown: WallContent? = null
    val view = WallView(CODE_WALL_LABELS, { path, line -> openFile(path, line?.new) }, showAllFiles, chats,
        codeWallHighlighter(project) { path -> files.firstOrNull { it.path == path } })
    val card: JPanel get() = view.card

    /** Shows [content]; a new scope relays the blocks out, the same scope keeps where they were moved. */
    fun show(content: CodeWallContent) {
        val previous = shown
        val wall = if (previous != null && content.files == files) previous.copy(scope = content.scope, summaries = content.summaries)
            else content.toWall()
        files = content.files
        shown = wall
        view.show(wall)
    }

    override fun dispose() = view.dispose()
}

/** Colours a source block from its file's whole text, lexed only as far as the block shows. */
internal fun codeWallHighlighter(project: Project?, file: (path: String) -> CodeFile?) = WallHighlighter { block ->
    val code = file(block.path) ?: return@WallHighlighter null
    val last = wallSideExtents(block.lines).second
    if (last == 0) return@WallHighlighter null
    { WallSyntax.side(project, code.path, code.text, last)?.let { wallRowSpans(block.lines, null, it) } }
}

/** The words and component names of the Code wall. */
internal val CODE_WALL_LABELS = WallLabels(
    id = "code-wall",
    header = { title, count -> "${ReviewVocabulary.CODE.wallName} · $title · $count file${if (count == 1) "" else "s"}" },
    controls = """<html>Drag a block to move it; drag the empty canvas, or middle-drag anywhere, to pan<br>
        |Double-click a line, or press Enter on the active block, to open the file in the editor at that line<br>
        |${WALL_MENU_KEY}wheel or $WALL_MENU_KEY= / $WALL_MENU_KEY− zooms, ${WALL_MENU_KEY}0 resets to 100%, F or ${WALL_MENU_KEY}Shift+0 fits the whole wall<br>
        |Arrows, Page Up/Down, Space / Shift+Space and Home/End scroll; Shift scrolls sideways<br>
        |In Tab focus mode, scrolling stays inside the active block; Esc restores canvas navigation<br>
        |Tab / Shift+Tab step through the blocks, Esc returns to the overview<br>
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
