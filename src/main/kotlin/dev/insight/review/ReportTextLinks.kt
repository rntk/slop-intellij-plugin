package dev.insight.review

import dev.insight.links.SymbolNames

/**
 * The links of plain [text] shown as is, such as a summary whose characters other views trace by offset: the file paths, line
 * references, URLs and code symbols [renderMarkdownReport] would link, as `[start, end)` spans of [text] itself. Backticked spans
 * link as code, their backticks staying outside the span. Bare line references bind to [defaultFile] until the text names a file.
 */
internal fun findReportTextLinks(
    text: String,
    index: ReportProjectIndex,
    symbols: SymbolNames = SymbolNames.NONE,
    defaultFile: String? = null,
): List<LineLinkMatch> {
    if (text.isBlank()) return emptyList()
    val collector = ReportLinkCollector(index, ReportLinkContext(defaultFile), symbols)
    val found = mutableListOf<LineLinkMatch>()
    fun scan(start: Int, end: Int, code: Boolean) {
        if (end <= start) return
        collector.scanSegment(text.substring(start, end), inBackticks = code, allowContextualLines = true)
            .mapTo(found) { it.copy(start = it.start + start, end = it.end + start) }
    }
    var lineStart = 0
    while (lineStart <= text.length) {
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        if (text.substring(lineStart, lineEnd).isBlank()) collector.context.onBlankLine() else collector.context.onPlainLine()
        var plain = lineStart
        var i = lineStart
        while (i < lineEnd) {
            if (text[i] != '`') { i++; continue }
            var ticks = 0
            while (i + ticks < lineEnd && text[i + ticks] == '`') ticks++
            val close = closingTicks(text, i + ticks, lineEnd, ticks)
            if (close == null) { i += ticks; continue }
            scan(plain, i, code = false)
            scan(i + ticks, close, code = true)
            i = close + ticks
            plain = i
        }
        scan(plain, lineEnd, code = false)
        lineStart = lineEnd + 1
    }
    return found
}

private fun closingTicks(text: String, from: Int, end: Int, count: Int): Int? {
    var i = from
    while (i < end) {
        if (text[i] != '`') { i++; continue }
        var run = 0
        while (i + run < end && text[i + run] == '`') run++
        if (run == count) return i
        i += run
    }
    return null
}
