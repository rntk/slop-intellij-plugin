package dev.insight.codereview

import dev.insight.review.SourceChunk
import dev.insight.core.Hashing

/**
 * Limits for splitting a large source file into parts. A file of at most [singleChars] characters is read whole. Parts hold
 * [minChars]..[maxChars] characters and at most [maxLines] lines; a part of [minLines] lines also counts as big enough, so
 * files of many short lines still split into whole parts. A file with a line over [minifiedLineChars] characters, or an
 * average line over [minifiedAverageChars] (once it is at least [minifiedLineChars] long), is treated as minified.
 */
data class ChunkOptions(
    val singleChars: Int = 12_000,
    val maxChars: Int = 10_000,
    val minChars: Int = 4_000,
    val maxLines: Int = 400,
    val minLines: Int = 160,
    val minifiedLineChars: Int = 5_000,
    val minifiedAverageChars: Int = 300,
) {
    init {
        require(minChars in 1..maxChars) { "minChars must be between 1 and maxChars" }
        require(minLines in 1..maxLines) { "minLines must be between 1 and maxLines" }
    }
}

/**
 * Splits [text] into contiguous 1-based line ranges that cover every line once, or returns no parts when the file is small
 * enough to read whole. Boundaries depend only on indentation, blank lines and the text around them, so an edit moves only
 * the boundaries near it and later parts keep their hashes. Language-agnostic: no parser is involved.
 */
fun chunkSource(text: String, o: ChunkOptions = ChunkOptions()): List<SourceChunk> {
    val source = normalizeLineSeparators(text)
    if (source.length <= o.singleChars) return emptyList()
    val lines = sourceLines(source)
    val n = lines.size
    if (n == 0) return emptyList()
    // prefix[i] = characters of lines 0 until i, each with its line separator.
    val prefix = IntArray(n + 1)
    for (i in 0 until n) prefix[i + 1] = prefix[i] + lines[i].length + 1
    val scores = boundaryScores(lines)
    fun big(chars: Int, count: Int) = chars >= o.minChars || count >= o.minLines

    fun nextEnd(start: Int): Int {
        val restChars = prefix[n] - prefix[start]
        if (restChars <= o.maxChars && n - start <= o.maxLines) return n
        // A line longer than a whole part is a part of its own; its evidence is cut when read.
        if (prefix[start + 1] - prefix[start] > o.maxChars) return start + 1
        val last = minOf(n - 1, start + o.maxLines)
        var best = -1
        var bestScore = -1
        var bestHash = 0L
        var fits = -1
        var fitsWithRest = -1
        for (end in start + 1..last) {
            val chars = prefix[end] - prefix[start]
            if (chars > o.maxChars) break
            fits = end
            val restBig = big(prefix[n] - prefix[end], n - end)
            if (restBig) fitsWithRest = end
            val score = scores[end]
            if (score < 0 || !restBig || !big(chars, end - start)) continue
            if (score > bestScore) {
                best = end; bestScore = score; bestHash = boundaryHash(lines, end)
            } else if (score == bestScore) {
                val hash = boundaryHash(lines, end)
                if (java.lang.Long.compareUnsigned(hash, bestHash) < 0) { best = end; bestHash = hash }
            }
        }
        if (best > 0) return best
        // No boundary gives two big enough parts: take as much as fits, leaving a big enough rest when possible.
        return when {
            fitsWithRest > 0 -> fitsWithRest
            fits > 0 -> fits
            else -> start + 1
        }
    }

    val chunks = mutableListOf<SourceChunk>()
    var start = 0
    while (start < n) {
        val end = nextEnd(start)
        chunks += SourceChunk(chunks.size, start + 1..end, sha256(lines, start, end))
        start = end
    }
    return chunks
}

/**
 * How good a split before each line is, from indentation and blank lines only:
 * 4 = top level, not a closer, after a blank line or a top-level closer; 3 = top level, not a closer;
 * 2 = at most one indent unit, after a blank line, not a closer; 1 = after a blank line; 0 = elsewhere; -1 = blank line,
 * which never starts a part. A split of score 2 or more moves up over the comments and annotations directly above it,
 * so documentation stays with its declaration. Index 0 has no line before it and is never used as a split.
 */
internal fun boundaryScores(lines: List<String>): IntArray {
    val n = lines.size
    val unit = indentUnit(lines)
    val indents = IntArray(n) { indentWidth(lines[it], unit) }
    val blank = BooleanArray(n) { lines[it].isBlank() }
    val closer = BooleanArray(n) { !blank[it] && isCloser(lines[it].trimStart()) }
    val intrinsic = IntArray(n) { i ->
        val previousBlank = i > 0 && blank[i - 1]
        val previousTopCloser = i > 0 && closer[i - 1] && indents[i - 1] == 0
        when {
            blank[i] -> -1
            indents[i] == 0 && !closer[i] && (previousBlank || previousTopCloser) -> 4
            indents[i] == 0 && !closer[i] -> 3
            indents[i] <= unit && previousBlank && !closer[i] -> 2
            previousBlank -> 1
            else -> 0
        }
    }
    val scores = intrinsic.copyOf()
    val comment = BooleanArray(n) { !blank[it] && isCommentOrAnnotation(lines[it].trimStart()) }
    for (i in 1 until n) {
        if (intrinsic[i] < 2 || comment[i]) continue
        var top = i
        // KDoc and block comment continuation lines sit one column deeper than their opening line.
        while (top > 0 && comment[top - 1] && indents[top - 1] - indents[i] in 0..1) top--
        if (top == i) continue
        for (k in top + 1..i) scores[k] = 0
        if (top > 0) scores[top] = maxOf(intrinsic[top], intrinsic[i])
    }
    return scores
}

/** Minified or generated-looking text that would waste a reader's budget: one very long line, or very long lines on average. */
fun isMinified(text: String, o: ChunkOptions = ChunkOptions()): Boolean {
    var lines = 0
    var lineStart = 0
    for (i in 0..text.length) {
        if (i == text.length || text[i] == '\n') {
            if (i == text.length && i == lineStart && lines > 0) break
            if (i - lineStart > o.minifiedLineChars) return true
            lines++
            lineStart = i + 1
        }
    }
    return text.length >= o.minifiedLineChars && lines > 0 && text.length / lines > o.minifiedAverageChars
}

/** The lines of LF text; a final line separator does not start another line. */
internal fun sourceLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val lines = text.split('\n')
    return if (text.endsWith('\n')) lines.dropLast(1) else lines
}

internal fun normalizeLineSeparators(text: String): String =
    if ('\r' !in text) text else text.replace("\r\n", "\n").replace('\r', '\n')

/** The smallest indentation of two or more spaces, so one-space KDoc continuation lines do not count; a tab is one unit. */
private fun indentUnit(lines: List<String>): Int {
    var unit = Int.MAX_VALUE
    for (line in lines) {
        var spaces = 0
        while (spaces < line.length && line[spaces] == ' ') spaces++
        if (spaces >= 2 && spaces < line.length && line[spaces] != '\t' && spaces < unit) unit = spaces
    }
    return if (unit == Int.MAX_VALUE) 4 else unit
}

private fun indentWidth(line: String, unit: Int): Int {
    var width = 0
    for (c in line) when (c) {
        ' ' -> width++
        '\t' -> width += unit
        else -> return width
    }
    return width
}

private fun isCloser(trimmed: String): Boolean {
    if (trimmed.isEmpty()) return false
    val first = trimmed[0]
    if (first == '}' || first == ')' || first == ']' || trimmed.startsWith("</")) return true
    return trimmed.startsWith("end") && (trimmed.length == 3 || !trimmed[3].isLetterOrDigit() && trimmed[3] != '_')
}

private fun isCommentOrAnnotation(trimmed: String): Boolean =
    trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*") || trimmed.startsWith("#") ||
        trimmed.startsWith("--") || trimmed.startsWith("@") || trimmed.startsWith("\"\"\"")

/** FNV-1a 64 of the line before [index], a line separator and the line at [index]: content-defined, so stable across edits elsewhere. */
private fun boundaryHash(lines: List<String>, index: Int): Long {
    var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    fun add(c: Char) {
        hash = hash xor (c.code.toLong() and 0xffff)
        hash *= 0x100000001b3L
    }
    lines[index - 1].forEach(::add)
    add('\n')
    lines[index].forEach(::add)
    return hash
}

private fun sha256(lines: List<String>, start: Int, end: Int): String =
    Hashing.sha256Of(lines.subList(start, end).joinToString("\n"))
