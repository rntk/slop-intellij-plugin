package dev.insight.knowledge

/** What a [DocClaim] is: a sentence of prose, or an item of an "Entry points" list (a link plus its short description). */
enum class DocClaimKind { PROSE, ENTRY_POINT }

/**
 * One statement a Markdown doc makes. [text] keeps the doc's own Markdown (links, code spans) so an anchorer can read link
 * targets; [section] is the nearest heading; [startLine] and [endLine] are one-based and inclusive.
 */
data class DocClaim(
    val text: String,
    val section: String?,
    val startLine: Int,
    val endLine: Int,
    val kind: DocClaimKind = DocClaimKind.PROSE,
)

/**
 * The claim area of a doc: the flow name (file name without extension) for `flows/<name>.md`, else the root `""`.
 * Docs outside `flows/` are not flows, so their claims are scoped to the whole project.
 */
fun flowAreaOf(docPath: String): String {
    val parts = docPath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
    if (parts.size < 2 || parts[parts.size - 2] != "flows") return ""
    val name = parts.last()
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(0, dot) else name
}

/**
 * Parses [markdown] into claim candidates. Headings, code fences, tables, HTML comments, rules and blank lines are skipped.
 * Paragraphs and list items are split into sentences, each with the line range it spans; items below a lead-in such as
 * "Entry points for further exploration:" (or a heading with "entry points") become one [DocClaimKind.ENTRY_POINT] each.
 */
fun parseDocClaims(markdown: String): List<DocClaim> = DocParser(markdown.lines()).parse()

/**
 * Splits [text] into sentences. A full stop, `!` or `?` ends a sentence only when whitespace and then something that can
 * start a sentence (a capital, digit, link, code span, quote or bracket) follow. Backtick spans and Markdown link targets are
 * opaque, and abbreviations such as "e.g." or "U.S." and dots inside file names never end a sentence.
 */
fun splitSentences(text: String): List<String> = sentenceSpans(text).map { text.substring(it.first, it.last + 1).trim() }
    .filter { s -> s.any { it.isLetterOrDigit() } }.map { it.replace(WS, " ") }

private val WS = Regex("\\s+")

internal fun sentenceSpans(text: String): List<IntRange> {
    val n = text.length
    val opaque = opaqueMask(text)
    val spans = mutableListOf<IntRange>()
    var start = 0
    var i = 0
    while (i < n) {
        val c = text[i]
        if ((c == '.' || c == '!' || c == '?') && !opaque[i]) {
            var j = i + 1
            while (j < n && !opaque[j] && text[j] in "!?.") j++
            while (j < n && !opaque[j] && text[j] in CLOSERS) j++
            val atEnd = j >= n
            if ((atEnd || text[j].isWhitespace()) && endsSentence(text, i, j, atEnd)) {
                if (j > start) spans += start until j
                var k = j
                while (k < n && text[k].isWhitespace()) k++
                start = k
                i = k
                continue
            }
            i = j.coerceAtLeast(i + 1)
            continue
        }
        i++
    }
    if (start < n && text.substring(start).isNotBlank()) spans += start until n
    return spans
}

private const val CLOSERS = ")]\"'*_”’"
private val ABBREVIATIONS = setOf("vs", "cf", "viz", "approx", "incl", "resp", "fig", "mr", "mrs", "ms", "dr", "st", "eg", "ie")
private val DOTTED_INITIALS = Regex("^\\p{L}(\\.\\p{L})+$")

private fun endsSentence(text: String, dot: Int, after: Int, atEnd: Boolean): Boolean {
    if (text[dot] == '.' && !(dot + 1 < text.length && text[dot + 1] in "!?.")) {
        var s = dot
        while (s > 0 && !text[s - 1].isWhitespace() && text[s - 1] !in "([\"") s--
        val token = text.substring(s, dot).trimStart('*', '_')
        if (DOTTED_INITIALS.matches(token) || token.lowercase() in ABBREVIATIONS) return false
    }
    if (atEnd) return true
    var k = after
    while (k < text.length && text[k].isWhitespace()) k++
    if (k >= text.length) return true
    val next = text[k]
    return next.isUpperCase() || next.isDigit() || next in "[`*_\"(“'"
}

/** Marks characters inside backtick spans and Markdown link destinations, which never hold sentence ends. */
private fun opaqueMask(text: String): BooleanArray {
    val n = text.length
    val mask = BooleanArray(n)
    var i = 0
    while (i < n) {
        val c = text[i]
        if (c == '`') {
            var run = 1
            while (i + run < n && text[i + run] == '`') run++
            var j = i + run
            var close = -1
            while (j < n) {
                if (text[j] == '`') {
                    var r = 1
                    while (j + r < n && text[j + r] == '`') r++
                    if (r == run) { close = j + r; break }
                    j += r
                } else j++
            }
            if (close > 0) { for (m in i until close) mask[m] = true; i = close } else i += run
        } else if (c == ']' && i + 1 < n && text[i + 1] == '(') {
            var depth = 0
            var j = i + 1
            var close = -1
            while (j < n) {
                if (text[j] == '(') depth++
                else if (text[j] == ')') { depth--; if (depth == 0) { close = j; break } }
                j++
            }
            if (close > 0) { for (m in i..close) mask[m] = true; i = close + 1 } else i++
        } else i++
    }
    return mask
}

private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+(.*?)\\s*#*\\s*$")
private val RULE = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
private val LIST_ITEM = Regex("^\\s*(?:[-*+]|\\d{1,9}[.)])\\s+(.*)$")
private val FENCE = Regex("^\\s{0,3}(`{3,}|~{3,})")
private val QUOTE = Regex("^\\s*>+\\s?")
private val ENTRY_LEAD = Regex("(?i)^\\**entry points?\\b.*:\\**$")

private class DocParser(private val lines: List<String>) {
    private val claims = mutableListOf<DocClaim>()
    private var section: String? = null
    private var entryMode = false
    private val block = mutableListOf<Pair<Int, String>>()
    private var blockIsItem = false

    fun parse(): List<DocClaim> {
        var fence: String? = null
        var inComment = false
        lines.forEachIndexed { index, raw ->
            val no = index + 1
            var line = raw.trimEnd('\r')
            fence?.let { open ->
                if (FENCE.find(line)?.groupValues?.get(1)?.let { it[0] == open[0] && it.length >= open.length } == true) fence = null
                return@forEachIndexed
            }
            if (!inComment) FENCE.find(line)?.let { flush(); fence = it.groupValues[1]; return@forEachIndexed }
            if (!inComment && line.isBlank()) { flush(); return@forEachIndexed }
            // Remove comments while retaining surrounding prose and its source line.
            val visible = StringBuilder()
            var i = 0
            var codeTicks = 0
            while (i < line.length) {
                if (inComment) {
                    val end = line.indexOf("-->", i)
                    if (end < 0) break
                    inComment = false
                    i = end + 3
                    visible.append(' ')
                } else if (codeTicks == 0 && line.startsWith("<!--", i)) {
                    inComment = true
                    i += 4
                } else if (line[i] == '`') {
                    var end = i + 1
                    while (end < line.length && line[end] == '`') end++
                    val count = end - i
                    if (codeTicks == 0) codeTicks = count else if (codeTicks == count) codeTicks = 0
                    visible.append(line, i, end)
                    i = end
                } else {
                    visible.append(line[i++])
                }
            }
            line = visible.toString()
            if (line.isBlank()) return@forEachIndexed
            HEADING.find(line)?.let {
                flush()
                val title = it.groupValues[1].trim()
                section = title.ifEmpty { null }
                entryMode = title.contains("entry point", ignoreCase = true)
                return@forEachIndexed
            }
            if (RULE.matches(line)) { flush(); return@forEachIndexed }
            if (line.trimStart().startsWith("|")) { flush(); return@forEachIndexed }
            line = line.replace(QUOTE, "")
            val item = LIST_ITEM.find(line)
            if (item != null) {
                flush()
                blockIsItem = true
                block += no to item.groupValues[1].trim()
            } else {
                if (block.isEmpty()) blockIsItem = false
                block += no to line.trim()
            }
        }
        flush()
        return claims
    }

    private fun flush() {
        if (block.isEmpty()) return
        val pieces = block.filter { it.second.isNotEmpty() }
        val isItem = blockIsItem
        block.clear()
        blockIsItem = false
        if (pieces.isEmpty()) return
        val text = pieces.joinToString(" ") { it.second }
        if (!isItem) {
            if (ENTRY_LEAD.matches(text.trim())) { entryMode = true; return }
            entryMode = false
        }
        if (isItem && entryMode) {
            claims += DocClaim(text.replace(WS, " ").trim(), section, pieces.first().first, pieces.last().first, DocClaimKind.ENTRY_POINT)
            return
        }
        val offsets = IntArray(pieces.size)
        var at = 0
        pieces.forEachIndexed { i, p -> offsets[i] = at; at += p.second.length + 1 }
        fun lineAt(offset: Int): Int {
            var idx = 0
            for (i in pieces.indices) if (offsets[i] <= offset) idx = i
            return pieces[idx].first
        }
        for (span in sentenceSpans(text)) {
            val raw = text.substring(span.first, span.last + 1)
            val trimmed = raw.trim()
            if (trimmed.none { it.isLetterOrDigit() }) continue
            val lead = raw.length - raw.trimStart().length
            val startOffset = span.first + lead
            val endOffset = startOffset + trimmed.length - 1
            claims += DocClaim(trimmed.replace(WS, " "), section, lineAt(startOffset), lineAt(endOffset))
        }
    }
}
