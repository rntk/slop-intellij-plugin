package dev.insight.codereview

import dev.insight.review.wall.WALL_MAX_LINES
import dev.insight.review.wall.WallBlock
import dev.insight.review.wall.WallContent
import dev.insight.review.wall.WallScope
import dev.insight.review.wall.wallSourceLines

/**
 * What the Code wall shows: [scope] names it and keeps its layout; [files] are its current snapshot files, in order; [summaries]
 * holds the summary text of the files that have one, by path.
 */
internal data class CodeWallContent(val scope: WallScope, val files: List<CodeFile>, val summaries: Map<String, String> = emptyMap()) {
    /** The wall's content: one source block per file. */
    fun toWall(): WallContent = WallContent(scope, files.map { codeWallBlock(it) }, summaries)
}

/**
 * [file] as a wall block of at most [maxLines] numbered source lines, with its line count; a longer file stays readable on a
 * crowded wall and opens in full in the editor.
 */
internal fun codeWallBlock(file: CodeFile, maxLines: Int = WALL_MAX_LINES): WallBlock {
    val lines = wallSourceLines(file.text, maxLines = maxLines)
    val stats = if (file.lineCount == 0) "" else "${file.lineCount} ${if (file.lineCount == 1) "line" else "lines"}"
    return WallBlock(file.path, file.path, stats, lines, (file.lineCount - lines.size).coerceAtLeast(0))
}
