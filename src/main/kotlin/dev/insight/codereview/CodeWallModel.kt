package dev.insight.codereview

import dev.insight.review.wall.WALL_MAX_LINES
import dev.insight.review.wall.WALL_MAX_SKIM_LINES
import dev.insight.review.wall.WallBlock
import dev.insight.review.wall.WallContent
import dev.insight.review.wall.WallScope
import dev.insight.review.wall.wallSourceLines

/**
 * What the Code wall shows: [scope] names it and keeps its layout; [files] are its current snapshot files, in order; [summaries]
 * holds the summary text of the files that have one, by path; [summarizing] lists the paths currently being written.
 */
internal data class CodeWallContent(
    val scope: WallScope,
    val files: List<CodeFile>,
    val summaries: Map<String, String> = emptyMap(),
    val summarizing: Set<String> = emptySet(),
) {
    /** The wall's content: one source block per file. */
    fun toWall(): WallContent = WallContent(scope, files.map { codeWallBlock(it) }, summaries, summarizing)
}

/**
 * [file] as a wall block of at most [maxLines] numbered source lines (keeping up to [WALL_MAX_SKIM_LINES] total lines for the
 * skim column), with its line count; a longer file stays readable on a crowded wall and opens in full in the editor.
 */
internal fun codeWallBlock(file: CodeFile, maxLines: Int = WALL_MAX_LINES): WallBlock =
    codeWallBlock(file, maxLines, WALL_MAX_SKIM_LINES)

/**
 * [file] as a wall block of at most [maxLines] numbered source lines (keeping up to [maxSkimLines] total lines for the skim
 * column), with its line count; a longer file stays readable on a crowded wall and opens in full in the editor.
 */
internal fun codeWallBlock(
    file: CodeFile,
    maxLines: Int,
    maxSkimLines: Int,
): WallBlock {
    val parseLimit = maxOf(maxLines, maxSkimLines).coerceAtLeast(0)
    val parsed = wallSourceLines(file.text, maxLines = parseLimit)
    val lines = parsed.take(maxLines)
    val more = parsed.drop(maxLines)
    val hiddenLines = (file.lineCount - lines.size).coerceAtLeast(0)
    val stats = if (file.lineCount == 0) "" else "${file.lineCount} ${if (file.lineCount == 1) "line" else "lines"}"
    val loadMore: ((Int) -> WallBlock)? = if (hiddenLines > more.size) {
        { nextMaxSkim -> codeWallBlock(file, maxLines, maxOf(parseLimit, nextMaxSkim)) }
    } else null
    return WallBlock(file.path, file.path, stats, lines, hiddenLines, more, loadMore)
}
