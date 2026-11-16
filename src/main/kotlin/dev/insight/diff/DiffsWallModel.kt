package dev.insight.diff

import dev.insight.review.wall.WALL_MAX_LINES
import dev.insight.review.wall.WallBlock
import dev.insight.review.wall.WallContent
import dev.insight.review.wall.WallLine
import dev.insight.review.wall.WallScope

/**
 * The wall's scope, ordered snapshot files, optional summaries keyed by path, and the paths whose summary is being written.
 */
internal data class DiffsWallContent(
    val scope: WallScope,
    val files: List<ReviewFile>,
    val summaries: Map<String, String> = emptyMap(),
    val summarizing: Set<String> = emptySet(),
) {
    fun toWall(): WallContent = WallContent(scope, files.map { wallBlock(it) }, summaries, summarizing)
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
 * Displays at most [maxLines] rows, keeping the rest for the skim column, with line counts and an `old → new` title for renames.
 */
internal fun wallBlock(file: ReviewFile, maxLines: Int = WALL_MAX_LINES): WallBlock {
    val all = wallLines(file.patch)
    val title = file.previousPath?.takeIf { it != file.path }?.let { "$it → ${file.path}" } ?: file.path
    val added = all.count { it.kind == WallLine.Kind.ADDED }
    val removed = all.count { it.kind == WallLine.Kind.REMOVED }
    return WallBlock(file.path, title, "+$added −$removed", all.take(maxLines), (all.size - maxLines).coerceAtLeast(0),
        all.drop(maxLines))
}
