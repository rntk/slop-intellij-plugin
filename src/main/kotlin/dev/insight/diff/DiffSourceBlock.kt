package dev.insight.diff

import dev.insight.chat.ToolInputException

/** One-based inclusive coordinates in an immutable side of the review snapshot. */
data class DiffSourceRange(val path: String, val startLine: Int, val endLine: Int, val side: String = "after")
data class DiffSourceBlock(val range: DiffSourceRange, val text: String) {
    internal val key get() = DiffNoteKey(listOf(range.path), null, range)
}

internal fun resolveSourceBlock(snapshot: ReviewSnapshot, range: DiffSourceRange): DiffSourceBlock {
    val file = snapshot.files.firstOrNull { it.path == range.path }
        ?: throw ToolInputException("${range.path} is not a file included in this diff.")
    if (range.side !in setOf("before", "after")) throw ToolInputException("side must be before or after")
    if (range.startLine < 1 || range.endLine < range.startLine || range.endLine.toLong() - range.startLine >= 200)
        throw ToolInputException("Use an inclusive range of 1–200 lines, starting at line 1 or later.")
    val source = (if (range.side == "before") file.before else file.after)
        ?: throw ToolInputException("The ${range.side} source is unavailable for ${range.path}.")
    val lines = source.lineSequence().toList().let { if (it.last().isEmpty()) it.dropLast(1) else it }
    if (range.endLine > lines.size) throw ToolInputException("Range exceeds the ${lines.size} lines on the ${range.side} side.")
    val text = lines.subList(range.startLine - 1, range.endLine).joinToString("\n")
    if (text.length > 20_000) throw ToolInputException("Source block exceeds 20000 characters. Select fewer lines.")
    return DiffSourceBlock(range, text)
}
