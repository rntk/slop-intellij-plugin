package dev.insight.review.wall

import com.intellij.openapi.util.SystemInfo

/**
 * The words and component names of one client's wall, so the same [WallView] shows diffs or plain source files. The view's own
 * switches ("Compact", "Summary", "Chat", "Hide chat") are shared.
 */
internal data class WallLabels(
    /** The prefix of the wall's component names: `id`, `id-surface`, `id-block:path`, `id-chat-tab:path`, `id-chat:path`... */
    val id: String,
    /** The label row's text for a scope [title] of [count] files. */
    val header: (title: String, count: Int) -> String,
    /** The label row's tooltip listing the wall's controls; HTML. */
    val controls: String,
    /** The link shown while the wall holds a subset of the review's files. */
    val showAll: String,
    /** The link that unfolds the wall's own chat, and its tooltip. */
    val canvasChatLink: String,
    val canvasChatLinkTooltip: String,
    /** Painted on a wall without blocks. */
    val emptyWall: String,
    /** The row a block without rows paints. */
    val emptyBlock: String,
    /** The row noting how many rows the line cap cut. */
    val hiddenLines: (Int) -> String,
    /** The row noting how many rows the skim column's initial cap cut when clicking loads the rest. */
    val hiddenSkimLines: (Int) -> String = { count -> "⋯ $count more ${if (count == 1) "line" else "lines"}; click to load remaining lines" },
    /** The Compact switch's tooltip while compact, and while whole. */
    val expandTooltip: String,
    val compactTooltip: String,
    /** The Summary switch's tooltip while the summary shows, and while the body shows. */
    val bodyTooltip: String,
    val summaryTooltip: String,
    /** The folded Chat tab's tooltip. */
    val chatTabTooltip: String,
    /** The words of the chat about the single file at a path. */
    val fileChat: (String) -> WallChatText,
    /** The words of the wall's own chat about every file it shows. */
    val canvasChat: WallChatText,
    /** The label row's tooltip in the skim column; HTML. */
    val skimControls: String = WALL_SKIM_CONTROLS,
    /** The Summary switch's tooltip on a file without a summary, which pressing it writes. */
    val summarizeTooltip: String = "Write this file's summary and show it; it is saved with the review's summaries",
    /** Shown in place of a summary while it is being written. */
    val summarizing: String = "Summarizing this file…",
)

/** How the wall's controls name the menu modifier: `⌘` on macOS, else `Ctrl+`. */
internal val WALL_MENU_KEY: String = if (SystemInfo.isMac) "⌘" else "Ctrl+"

/** The skim column's controls, shared by every wall. */
internal val WALL_SKIM_CONTROLS: String = """<html>Skim: every file in one column, whole, its header sticky while it scrolls<br>
    |Wheel, arrows, Page Up/Down, Space / Shift+Space and Home/End scroll<br>
    |N / Shift+N jump to the next or previous hunk; Tab / Shift+Tab to the next or previous file<br>
    |Double-click a line, or press Enter, to open the file at the top in its own view; right-click for more views<br>
    |Fold in a header, or C, folds the file at the top to its header; Shift+C folds or unfolds them all<br>
    |Summary in a header, or S, shows the file's summary above its rows<br>
    |${WALL_MENU_KEY}wheel or $WALL_MENU_KEY= / $WALL_MENU_KEY− zooms the text, ${WALL_MENU_KEY}0 resets to 100%<br>
    |M switches back to the cards</html>""".trimMargin()
