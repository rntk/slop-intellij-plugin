package dev.insight.diff

import dev.insight.chat.SourceSide
import dev.insight.chat.ToolInputException
import dev.insight.review.ReviewSourceBlock
import dev.insight.review.ReviewSourceRange

internal fun resolveSourceBlock(snapshot: ReviewSnapshot, range: ReviewSourceRange): ReviewSourceBlock {
    val file = snapshot.files.firstOrNull { it.path == range.path }
        ?: throw ToolInputException("${range.path} is not a file included in this diff.")
    if (range.side !in SourceSide.DIFF) throw ToolInputException("side must be before or after")
    if (range.startLine < 1 || range.endLine < range.startLine || range.endLine.toLong() - range.startLine >= 200)
        throw ToolInputException("Use an inclusive range of 1–200 lines, starting at line 1 or later.")
    val source = (if (range.side == SourceSide.BEFORE) file.before else file.after)
        ?: throw ToolInputException("The ${range.side.label} source is unavailable for ${range.path}.")
    val lines = source.lineSequence().toList().let { if (it.last().isEmpty()) it.dropLast(1) else it }
    if (range.endLine > lines.size) throw ToolInputException("Range exceeds the ${lines.size} lines on the ${range.side.label} side.")
    val text = lines.subList(range.startLine - 1, range.endLine).joinToString("\n")
    if (text.length > 20_000) throw ToolInputException("Source block exceeds 20000 characters. Select fewer lines.")
    val sourcePath = if (range.side == SourceSide.BEFORE) file.previousPath ?: file.path else file.path
    return ReviewSourceBlock(range, text, source,
        if (sourcePath == file.path) file.absolutePath else null, sourcePath)
}
