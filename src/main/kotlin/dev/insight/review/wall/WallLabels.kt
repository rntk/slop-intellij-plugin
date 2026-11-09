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
)

/** How the wall's controls name the menu modifier: `⌘` on macOS, else `Ctrl+`. */
internal val WALL_MENU_KEY: String = if (SystemInfo.isMac) "⌘" else "Ctrl+"
