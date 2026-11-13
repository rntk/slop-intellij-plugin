package dev.insight.board

import dev.insight.agent.ToolInputException
import dev.insight.diff.ReviewFile
import dev.insight.diff.wallLines
import dev.insight.review.wall.WallLine

/**
 * Board sources over a review snapshot: both sides and the patch of each reviewed [files] entry, and the call graph
 * through [graph] when one can be built (on first use). Safe on any thread.
 */
class ReviewBoardSources(private val files: List<ReviewFile>, private val graph: BoardGraph) : BoardSources {
    override val hasComparison: Boolean get() = true

    override suspend fun read(anchor: BoardAnchor): ResolvedSource {
        val file = file(anchor.path, anchor.side)
        val text = when (anchor.side) {
            BoardSide.BEFORE -> file.before ?: throw ToolInputException("The before side of ${anchor.path} is unavailable" +
                if (file.status == "added") ": the file is new." else ".")
            BoardSide.AFTER -> file.after ?: throw ToolInputException("The after side of ${anchor.path} is unavailable" +
                if (file.status == "deleted") ": the file is deleted; read its before side." else ".")
        }
        return sourceExcerpt(anchor, text)
    }

    override suspend fun diff(path: String, first: Int?, last: Int?): ResolvedSource {
        val file = file(path, BoardSide.AFTER)
        return patchExcerpt(file.path, file.patch, first, last)
    }

    override suspend fun symbols(name: String, path: String?): List<ResolvedSymbol> = requireGraph().symbols(name, path)
    override suspend fun symbol(node: Int): ResolvedSymbol? = requireGraph().symbol(node)
    override suspend fun chain(from: Int, to: Int): ResolvedChain = requireGraph().chain(from, to)
    override suspend fun callees(node: Int, limit: Int): List<ResolvedSymbol> = requireGraph().callees(node, limit)

    private suspend fun requireGraph(): BoardGraphSources = graph() ?: throw noCallGraph()

    private fun file(path: String, side: BoardSide): ReviewFile {
        val clean = path.trim().removePrefix("./")
        return files.firstOrNull { it.path == clean }
            ?: files.firstOrNull { side == BoardSide.BEFORE && it.previousPath == clean }
            ?: throw ToolInputException("$path is not in this comparison. Its files: " +
                files.take(MAX_LISTED_FILES).joinToString { it.path } + if (files.size > MAX_LISTED_FILES) ", …" else ".")
    }

    private companion object {
        const val MAX_LISTED_FILES = 20
    }
}

internal fun noCallGraph() = ToolInputException("No call graph is available for this board; show code with add_code instead.")

/**
 * Lines [anchor] of [text], clamped to the file's end and to [BoardLimits.MAX_CODE_LINES], with a notice when clamped.
 * Throws [ToolInputException] when the anchor starts past the end.
 */
internal fun sourceExcerpt(anchor: BoardAnchor, text: String): ResolvedSource {
    val lines = text.replace("\r\n", "\n").removeSuffix("\n").split('\n')
    if (anchor.first > lines.size) throw ToolInputException("${anchor.path}" + (if (anchor.side == BoardSide.BEFORE) " (before)" else "") +
        " has only ${lines.size} lines; line ${anchor.first} is past its end.")
    val last = minOf(anchor.last, lines.size, anchor.first + BoardLimits.MAX_CODE_LINES - 1)
    val notice = when {
        last == anchor.last -> null
        anchor.last > lines.size && last == lines.size -> "${anchor.path} ends at line ${lines.size}; showing lines ${anchor.first}-$last."
        else -> "A code block shows at most ${BoardLimits.MAX_CODE_LINES} lines; showing lines ${anchor.first}-$last."
    }
    return ResolvedSource(anchor.copy(last = last), lines.subList(anchor.first - 1, last).joinToString("\n"), notice)
}

private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")

/**
 * The unified [patch] of [path] as a diff block's text: every hunk, or only those whose after-side lines overlap
 * [first]..[last], with their headers and `+`/`-`/space markers, cut to [BoardLimits.MAX_CODE_LINES] rows. The anchor
 * spans the after-side lines shown (the before-side lines for a hunk that only removes).
 */
internal fun patchExcerpt(path: String, patch: String, first: Int?, last: Int?): ResolvedSource {
    val raw = patch.replace("\r\n", "\n").split('\n').let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it }
    val rows = wallLines(patch)
    val starts = rows.indices.filter { rows[it].kind == WallLine.Kind.HUNK }
    if (starts.isEmpty()) throw ToolInputException("$path has no textual changes to show.")
    val hunks = starts.mapIndexed { i, start -> start until (starts.getOrNull(i + 1) ?: rows.size) }
    fun span(hunk: IntRange): IntRange {
        val header = HUNK_HEADER.find(raw[hunk.first])!!
        val start = header.groupValues[3].toInt().coerceAtLeast(1)
        val count = header.groupValues[4].ifEmpty { "1" }.toInt()
        return start..(start + count - 1).coerceAtLeast(start)
    }
    val wanted = first?.let { it..(last ?: it) }
    val chosen = if (wanted == null) hunks else hunks.filter { span(it).first <= wanted.last && wanted.first <= span(it).last }
    if (chosen.isEmpty()) throw ToolInputException("No hunk of $path touches after-side lines ${wanted!!.first}-${wanted.last}; " +
        "its hunks cover after-side lines " + hunks.joinToString { span(it).let { s -> "${s.first}-${s.last}" } } + ".")
    val indices = chosen.flatMap { it.toList() }
    val shown = indices.take(BoardLimits.MAX_CODE_LINES)
    val after = shown.mapNotNull { rows[it].new }
    val before = shown.mapNotNull { rows[it].old }
    val anchor = when {
        after.isNotEmpty() -> BoardAnchor(path, after.min(), after.max())
        before.isNotEmpty() -> BoardAnchor(path, before.min(), before.max(), BoardSide.BEFORE)
        else -> span(chosen.first()).let { BoardAnchor(path, it.first, it.first) }
    }
    val notice = if (shown.size < indices.size) "The selected hunks have ${indices.size} patch rows; a diff block shows the first " +
        "${BoardLimits.MAX_CODE_LINES}. Pass first_line and last_line to show later hunks." else null
    return ResolvedSource(anchor, shown.joinToString("\n") { raw[it] }, notice)
}
