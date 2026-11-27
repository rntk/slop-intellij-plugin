package dev.insight.diff

import dev.insight.review.FileSummaries
import dev.insight.review.wall.MAX_ADDED_BLOCK_LINES
import dev.insight.review.wall.WALL_MAX_LINES
import dev.insight.review.wall.WALL_MAX_SKIM_LINES
import dev.insight.review.wall.WallBlock
import dev.insight.review.wall.WallContent
import dev.insight.review.wall.WallLine
import dev.insight.review.wall.WallScope

/**
 * The wall's scope, ordered snapshot files, and its file summaries.
 */
internal data class DiffsWallContent(
    val scope: WallScope,
    val files: List<ReviewFile>,
    val summaries: FileSummaries = FileSummaries(),
) {
    fun toWall(): WallContent = WallContent(scope, files.map { wallBlock(it) }, summaries)
}

private val HUNK = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

/** The rows of unified [patch]: hunk headers, changed and context lines numbered on their sides, and notes such as missing newlines. */
internal fun wallLines(patch: String): List<WallLine> {
    val raw = patch.replace("\r\n", "\n").split('\n').let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it }
    var old = 0
    var new = 0
    var inHunk = false
    return raw.map { line ->
        val text = line.replace("\t", "    ")
        val hunk = HUNK.find(line)
        when {
            hunk != null -> {
                old = hunk.groupValues[1].toInt(); new = hunk.groupValues[2].toInt(); inHunk = true
                WallLine(WallLine.Kind.HUNK, text)
            }
            // Text before the first hunk and the loader's bracketed notes are not file lines.
            !inHunk || line.startsWith("[") || line.startsWith("\\") -> WallLine(WallLine.Kind.NOTE, text)
            line.startsWith("+") -> WallLine(WallLine.Kind.ADDED, text, new = new++)
            line.startsWith("-") -> WallLine(WallLine.Kind.REMOVED, text, old = old++)
            else -> WallLine(WallLine.Kind.CONTEXT, if (line.isEmpty()) " " else text, old++, new++)
        }
    }
}

/**
 * Displays at most [maxLines] rows, keeping up to [WALL_MAX_SKIM_LINES] total rows for the skim column while still counting
 * added and removed lines across the whole patch, with an `old → new` title for renames.
 */
internal fun wallBlock(file: ReviewFile, maxLines: Int = WALL_MAX_LINES): WallBlock =
    wallBlock(file, maxLines, WALL_MAX_SKIM_LINES)

/**
 * Displays at most [maxLines] rows, keeping up to [maxSkimLines] total rows for the skim column while still counting added
 * and removed lines across the whole patch, with an `old → new` title for renames.
 */
internal fun wallBlock(
    file: ReviewFile,
    maxLines: Int,
    maxSkimLines: Int,
): WallBlock {
    val patch = file.patch
    val parseLimit = maxOf(maxLines, maxSkimLines).coerceAtLeast(0)
    val parsed = ArrayList<WallLine>(minOf(parseLimit, 64))
    var remainingLines = 0
    var old = 0
    var new = 0
    var inHunk = false
    var added = 0
    var removed = 0
    if (patch.isNotEmpty()) {
        val end = when {
            patch.endsWith("\r\n") -> patch.length - 2
            patch.endsWith('\n') -> patch.length - 1
            else -> patch.length
        }
        var start = 0
        while (start <= end) {
            val nl = patch.indexOf('\n', start)
            val lineEnd = if (nl < 0 || nl > end) end else nl
            val cleanEnd = if (lineEnd > start && patch[lineEnd - 1] == '\r') lineEnd - 1 else lineEnd
            if (parsed.size < parseLimit) {
                val rawLine = patch.substring(start, cleanEnd)
                val text = if ('\t' in rawLine) rawLine.replace("\t", "    ") else rawLine
                val hunk = HUNK.find(rawLine)
                val wallLine = when {
                    hunk != null -> {
                        old = hunk.groupValues[1].toInt(); new = hunk.groupValues[2].toInt(); inHunk = true
                        WallLine(WallLine.Kind.HUNK, text)
                    }
                    !inHunk || rawLine.startsWith("[") || rawLine.startsWith("\\") -> WallLine(WallLine.Kind.NOTE, text)
                    rawLine.startsWith("+") -> { added++; WallLine(WallLine.Kind.ADDED, text, new = new++) }
                    rawLine.startsWith("-") -> { removed++; WallLine(WallLine.Kind.REMOVED, text, old = old++) }
                    else -> WallLine(WallLine.Kind.CONTEXT, if (rawLine.isEmpty()) " " else text, old++, new++)
                }
                parsed += wallLine
            } else {
                remainingLines++
                val len = cleanEnd - start
                if (len >= 4 && patch[start] == '@' && patch[start + 1] == '@' && patch[start + 2] == ' ' &&
                    patch[start + 3] == '-' && HUNK.find(patch.subSequence(start, cleanEnd)) != null) {
                    inHunk = true
                } else if (inHunk && len > 0) {
                    val ch = patch[start]
                    if (ch == '+') added++ else if (ch == '-') removed++
                }
            }
            if (nl < 0 || nl >= end) break
            start = nl + 1
        }
    }
    val title = file.previousPath?.takeIf { it != file.path }?.let { "$it → ${file.path}" } ?: file.path
    val shown = parsed.take(maxLines)
    val totalLines = parsed.size + remainingLines
    val loadMore: ((Int) -> WallBlock)? = if (remainingLines > 0) {
        { nextMaxSkim -> wallBlock(file, maxLines, maxOf(parseLimit, nextMaxSkim)) }
    } else null
    return WallBlock(
        file.path,
        title,
        "+$added −$removed",
        shown,
        (totalLines - shown.size).coerceAtLeast(0),
        parsed.drop(maxLines),
        loadMore,
    )
}

/**
 * Finds the 0-based row index in unified [patch] matching 1-based [targetLine] by tracking hunk coordinates,
 * preferring the first new-side match (`+` or context row) and falling back to the first old-side match (`-` or context row).
 */
internal fun wallPatchTargetRow(patch: String, targetLine: Int): Int? {
    if (patch.isEmpty() || targetLine <= 0) return null
    val end = when {
        patch.endsWith("\r\n") -> patch.length - 2
        patch.endsWith('\n') -> patch.length - 1
        else -> patch.length
    }
    var start = 0
    var row = 0
    var old = 0
    var new = 0
    var inHunk = false
    var firstOldMatch: Int? = null
    while (start <= end) {
        val nl = patch.indexOf('\n', start)
        val lineEnd = if (nl < 0 || nl > end) end else nl
        val cleanEnd = if (lineEnd > start && patch[lineEnd - 1] == '\r') lineEnd - 1 else lineEnd
        val len = cleanEnd - start
        val hunk = if (len >= 4 && patch[start] == '@' && patch[start + 1] == '@' && patch[start + 2] == ' ' &&
            patch[start + 3] == '-') HUNK.find(patch.subSequence(start, cleanEnd)) else null
        when {
            hunk != null -> {
                old = hunk.groupValues[1].toInt()
                new = hunk.groupValues[2].toInt()
                inHunk = true
            }
            !inHunk || (len > 0 && (patch[start] == '[' || patch[start] == '\\')) -> Unit
            len > 0 && patch[start] == '+' -> {
                if (new == targetLine) return row
                new++
            }
            len > 0 && patch[start] == '-' -> {
                if (old == targetLine && firstOldMatch == null) firstOldMatch = row
                old++
            }
            else -> {
                if (new == targetLine) return row
                if (old == targetLine && firstOldMatch == null) firstOldMatch = row
                old++
                new++
            }
        }
        row++
        if (nl < 0 || nl >= end) break
        start = nl + 1
    }
    return firstOldMatch
}

/**
 * Expands [current] to materialize the patch row corresponding to 1-based [targetLine] when it lies past the currently
 * shown prefix (even in deletion-heavy patches where a small new line number follows thousands of deletions).
 */
internal fun wallExpandDiffBlock(
    file: ReviewFile,
    current: WallBlock,
    targetLine: Int,
    maxLines: Int = WALL_MAX_LINES,
    maxSkimLines: Int = WALL_MAX_SKIM_LINES,
    maxExpandedLines: Int = MAX_ADDED_BLOCK_LINES,
): WallBlock? {
    val targetRow = wallPatchTargetRow(file.patch, targetLine) ?: return null
    if (targetRow < current.lines.size || targetRow >= maxExpandedLines) return null
    val neededLines = minOf(maxExpandedLines, maxOf(maxLines, current.lines.size, targetRow + 51))
    val available = current.lines.size + current.more.size
    if (neededLines <= available) {
        val take = neededLines - current.lines.size
        if (take <= 0) return null
        return current.copy(
            lines = current.lines + current.more.take(take),
            hiddenLines = (current.hiddenLines - take).coerceAtLeast(0),
            more = current.more.drop(take),
        )
    }
    return wallBlock(file, neededLines, maxOf(maxSkimLines, neededLines))
        .takeIf { it.lines.size > current.lines.size }
}
