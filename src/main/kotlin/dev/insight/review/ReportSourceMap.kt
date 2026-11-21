package dev.insight.review

/** Offsets in the report's normalized source text, tracked through Markdown rendering. */
internal class ReportSourceMap {
    val spans = mutableListOf<ReportSourceText>()
    private val markup = mutableListOf<Pair<IntRange, IntRange>>()
    private val omissionBoundaries = mutableListOf<Int>()

    fun render(text: ReportSourceText, display: String): String {
        if (display.isEmpty()) return ""
        val id = spans.size
        spans += text
        return "<span id=\"report-source-$id\">${escapeHtml(display)}</span>"
    }

    fun recordMarkup(source: ReportSourceText, content: ReportSourceText) {
        markup += source.range to content.range
    }

    fun recordOmissionBefore(offset: Int) {
        omissionBoundaries += offset
    }

    /** Remove delimiters of fully selected inline markup, and keep them around surviving text. */
    fun deletionRanges(selected: IntRange): List<IntRange> {
        if (omissionBoundaries.any { it > selected.first && it <= selected.last }) return emptyList()
        var first = selected.first
        var last = selected.last
        val keep = mutableListOf<IntRange>()
        for ((source, content) in markup) {
            if (first <= content.first && last >= content.last) {
                first = minOf(first, source.first)
                last = maxOf(last, source.last)
            } else if (first <= content.last && last >= content.first) {
                keep += source.first until content.first
                keep += content.last + 1..source.last
            }
        }
        val ranges = mutableListOf<IntRange>()
        for (protected in keep.filterNot { it.isEmpty() }.sortedBy { it.first }) {
            if (protected.last < first || protected.first > last) continue
            if (first < protected.first) ranges += first until protected.first
            first = protected.last + 1
        }
        if (first <= last) ranges += first..last
        return ranges
    }
}

internal data class ReportSourceText(val text: String, val offset: Int, val map: ReportSourceMap?) {
    val range: IntRange get() = offset until offset + text.length

    fun slice(start: Int, end: Int = text.length) = ReportSourceText(text.substring(start, end), offset + start, map)

    fun trim(): ReportSourceText {
        val start = text.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: text.length
        val end = text.indexOfLast { !it.isWhitespace() } + 1
        return slice(start, maxOf(start, end))
    }

    fun html(display: String = text): String = map?.render(this, display) ?: escapeHtml(display)

    fun recordMarkup(content: ReportSourceText) {
        if (content.text.isNotEmpty()) map?.recordMarkup(this, content)
    }
}

internal fun reportSourceLines(text: String, map: ReportSourceMap?): List<ReportSourceText> {
    var offset = 0
    return text.split('\n').map { line ->
        ReportSourceText(line, offset, map).also { offset += line.length + 1 }
    }
}
