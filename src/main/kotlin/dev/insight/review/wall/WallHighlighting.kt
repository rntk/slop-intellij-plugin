package dev.insight.review.wall

import java.awt.Color
import java.awt.Font

/** How a stretch of a row paints: its foreground, or the row's own colour when null, and a [Font] style. */
internal data class WallStyle(val color: Color?, val fontStyle: Int = Font.PLAIN)

/** A lexer token of a whole file's text, from [start] to [end] (exclusive), that paints in [style]. */
internal data class WallToken(val start: Int, val end: Int, val style: WallStyle)

/** A stretch of one row's text, columns [from] to [to] (exclusive) as painted with tabs expanded, that paints in [style]. */
internal data class WallSpan(val from: Int, val to: Int, val style: WallStyle)

/** The width a tab takes on the wall, as [WallLine.source] and the diff rows expand it. */
private const val TAB = "    "

/**
 * One side of a file, highlighted: its [lines] with tabs expanded as the wall paints them, and each line's styled [spans] in
 * those columns. A row is coloured only when its text is the line's, so a row that does not match stays plain.
 */
internal class WallSideHighlight(val lines: List<String>, val spans: List<List<WallSpan>>) {
    companion object {
        /** [text] (with `\n` line breaks) split into lines, with [tokens] cut at line breaks and their offsets turned into columns. */
        fun of(text: String, tokens: List<WallToken>): WallSideHighlight {
            val raw = text.split('\n')
            val starts = IntArray(raw.size)
            for (index in 1 until raw.size) starts[index] = starts[index - 1] + raw[index - 1].length + 1
            val spans = List(raw.size) { mutableListOf<WallSpan>() }
            var line = 0
            for (token in tokens.sortedBy { it.start }) {
                if (token.end <= token.start) continue
                while (line + 1 < raw.size && starts[line + 1] <= token.start) line++
                var at = line
                while (at < raw.size && starts[at] < token.end) {
                    val from = (token.start - starts[at]).coerceAtLeast(0)
                    val to = (token.end - starts[at]).coerceAtMost(raw[at].length)
                    if (to > from) {
                        val columns = wallColumns(raw[at])
                        spans[at] += WallSpan(columns[from], columns[to], token.style)
                    }
                    at++
                }
            }
            return WallSideHighlight(raw.map { it.replace("\t", TAB) }, spans)
        }
    }
}

/** The painted column of each character index of [line], and of its end, with tabs expanded to the wall's width. */
internal fun wallColumns(line: String): IntArray {
    val columns = IntArray(line.length + 1)
    for (index in line.indices) columns[index + 1] = columns[index] + if (line[index] == '\t') TAB.length else 1
    return columns
}

/**
 * The styled spans of each of [rows], in the columns of the row's own text, or null for a row painted plainly. Source rows and
 * a diff's added and context rows take line [WallLine.new] of [after], removed rows line [WallLine.old] of [before]; diff rows
 * keep their marker in the first column, so their spans move one column right. Hunk headers, notes and any row whose text is not
 * its side's line stay plain, so a stale or missing side never paints wrong colours.
 */
internal fun wallRowSpans(rows: List<WallLine>, before: WallSideHighlight?, after: WallSideHighlight?): List<List<WallSpan>?> =
    rows.map { row ->
        val (side, number, shift) = when (row.kind) {
            WallLine.Kind.SOURCE -> Triple(after, row.new, 0)
            WallLine.Kind.ADDED, WallLine.Kind.CONTEXT -> Triple(after, row.new, 1)
            WallLine.Kind.REMOVED -> Triple(before, row.old, 1)
            WallLine.Kind.HUNK, WallLine.Kind.NOTE -> Triple(null, null, 0)
        }
        val index = number?.minus(1) ?: return@map null
        val line = side?.lines?.getOrNull(index) ?: return@map null
        if (row.text.drop(shift) != line) return@map null
        side.spans[index].takeIf { it.isNotEmpty() }?.map { it.copy(from = it.from + shift, to = it.to + shift) }
    }

/** The last line of [rows] on each side that [wallRowSpans] reads, as (before, after), so a side need only be lexed that far. */
internal fun wallSideExtents(rows: List<WallLine>): Pair<Int, Int> {
    var before = 0
    var after = 0
    rows.forEach { row ->
        when (row.kind) {
            WallLine.Kind.REMOVED -> before = maxOf(before, row.old ?: 0)
            WallLine.Kind.SOURCE, WallLine.Kind.ADDED, WallLine.Kind.CONTEXT -> after = maxOf(after, row.new ?: 0)
            else -> {}
        }
    }
    return before to after
}

/** [text] with `\n` line breaks, cut after its line [lastLine] (1-based), so lexing it stops where the wall stops reading. */
internal fun wallTextThrough(text: String, lastLine: Int): String {
    val body = text.replace("\r\n", "\n")
    if (lastLine <= 0) return ""
    var at = -1
    repeat(lastLine) {
        at = body.indexOf('\n', at + 1)
        if (at < 0) return body
    }
    return body.substring(0, at)
}

/**
 * [text] cut into consecutive pieces that cover it, each with its style or null for the row's own colour, from [spans] in
 * [text]'s columns; overlapping or out-of-range spans are clipped.
 */
internal fun wallSegments(text: String, spans: List<WallSpan>?): List<Pair<String, WallStyle?>> {
    if (spans.isNullOrEmpty()) return listOf(text to null)
    val pieces = mutableListOf<Pair<String, WallStyle?>>()
    var at = 0
    for (span in spans.sortedBy { it.from }) {
        val from = span.from.coerceIn(at, text.length)
        val to = span.to.coerceIn(from, text.length)
        if (from > at) pieces += text.substring(at, from) to null
        if (to > from) pieces += text.substring(from, to) to span.style
        at = to
    }
    if (at < text.length) pieces += text.substring(at) to null
    return pieces
}
