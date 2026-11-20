package dev.insight.context

import dev.insight.agent.SourceSide
import dev.insight.diff.ReviewFile
import dev.insight.review.PatchSource
import dev.insight.review.ReviewSource

/** Payload and preview cap of one context; longer text is cut and marked. */
internal const val MAX_CONTEXT_CHARS = 40_000

/** A declaration found around a position, without PSI: [lines] are 1-based and inclusive. */
data class SymbolSpan(val symbol: ContextSymbol, val lines: IntRange)

/** One `@@` block of a unified patch: its header line and rows, and the lines it covers on each side (1-based, inclusive). */
internal data class PatchHunk(val header: String, val rows: List<String>, val before: IntRange, val after: IntRange) {
    val text: String get() = (listOf(header) + rows).joinToString("\n", postfix = "\n")
    fun lines(side: SourceSide): IntRange = if (side == SourceSide.BEFORE) before else after
}

private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")

/** A side's range from a hunk header; an empty side, as in git's `+N,0`, anchors on line `max(N, 1)`. */
private fun headerRange(start: String, count: String): IntRange {
    val first = start.toInt()
    val size = count.ifEmpty { "1" }.toInt()
    return if (size == 0) maxOf(first, 1).let { it..it } else first until first + size
}

/** The hunks of unified [patch], in order. Text before the first hunk and the loader's bracketed notes belong to none. */
internal fun patchHunks(patch: String): List<PatchHunk> {
    val hunks = mutableListOf<PatchHunk>()
    var header: String? = null
    var match: MatchResult? = null
    val rows = mutableListOf<String>()
    fun flush() {
        val found = match ?: return
        val (oldStart, oldCount, newStart, newCount) = found.destructured
        hunks += PatchHunk(header!!, rows.toList(), headerRange(oldStart, oldCount), headerRange(newStart, newCount))
        rows.clear()
    }
    for (line in patch.replace("\r\n", "\n").removeSuffix("\n").split('\n')) {
        val next = HUNK_HEADER.find(line)
        when {
            next != null -> { flush(); header = line; match = next }
            match != null && !line.startsWith("[") -> rows += line
        }
    }
    flush()
    return hunks
}

/** The hunk of [patch] that covers [line] on [side], or null when the line is unchanged context outside every hunk. */
internal fun hunkAt(patch: String, line: Int, side: SourceSide): PatchHunk? = patchHunks(patch).firstOrNull { line in it.lines(side) }

/** [text] cut to [max] characters, preferably at a line break, with an omission note. */
internal fun capContext(text: String, max: Int = MAX_CONTEXT_CHARS): String {
    if (text.length <= max) return text
    val cut = text.lastIndexOf('\n', max - 1).takeIf { it > 0 }?.plus(1) ?: max
    val omitted = text.substring(cut)
    return text.substring(0, cut) + "… (truncated: ${omitted.count { it == '\n' } + if (omitted.endsWith('\n')) 0 else 1} more lines)\n"
}

private fun sourceLines(text: String): List<String> = text.replace("\r\n", "\n").removeSuffix("\n").split('\n')

/** Lines [range] (1-based, inclusive) of [text], clamped to it. */
private fun slice(text: String, range: IntRange): String {
    val lines = sourceLines(text)
    val from = (range.first - 1).coerceIn(0, lines.size)
    val to = range.last.coerceIn(from, lines.size)
    return lines.subList(from, to).joinToString("\n")
}

private fun rangeLabel(range: IntRange) = if (range.first == range.last) "${range.first}" else "${range.first}-${range.last}"

private fun symbolLabel(symbol: ContextSymbol) = "${symbol.kind.replaceFirstChar { it.uppercase() }} ${symbol.name}"

/** A known declaration in captured source. Offsets distinguish declarations that share a line. */
internal fun sourceSymbolScope(path: String, absolutePath: String?, text: String, span: SymbolSpan, offsets: IntRange?): CodeContext {
    val source = offsets?.takeIf { !it.isEmpty() && it.first >= 0 && it.last < text.length }?.let { text.substring(it) }
        ?: slice(text, span.lines)
    return CodeContext(ContextScopeKind.SYMBOL, symbolLabel(span.symbol), path, absolutePath, span.lines, SourceSide.CURRENT,
        capContext(source), symbol = span.symbol)
}

/**
 * Candidate scopes at [line] (null for none) on [side] of a diff [file], narrow to wide: the line, the enclosing [symbols]
 * (innermost first; their source lines on that side), the hunk containing the line, the file's diff, the whole file on that side,
 * and the whole change when [allFiles] holds more than one file.
 */
fun diffScopes(file: ReviewFile, line: Int?, side: SourceSide, symbols: List<SymbolSpan>, allFiles: List<ReviewFile>?): List<CodeContext> {
    val sideText = (if (side == SourceSide.BEFORE) file.before else file.after).orEmpty()
    fun context(kind: ContextScopeKind, label: String, lines: IntRange?, text: String, patch: Boolean = false, symbol: ContextSymbol? = null) =
        CodeContext(kind, label, file.path, file.absolutePath, lines, side, capContext(text), patch, symbol)
    return buildList {
        val hunk = line?.let { hunkAt(file.patch, it, side) }
        if (line != null) {
            // A side without loaded text still shows the line through its patch row.
            val text = if (sideText.isNotEmpty()) slice(sideText, line..line)
                else hunk?.let { patchRow(it, line, side) }.orEmpty()
            add(context(ContextScopeKind.LINE, "Line $line", line..line, text))
        }
        for (span in symbols) add(context(ContextScopeKind.SYMBOL, symbolLabel(span.symbol), span.lines, slice(sideText, span.lines), symbol = span.symbol))
        if (hunk != null) add(context(ContextScopeKind.HUNK, "Hunk ${HUNK_HEADER.find(hunk.header)!!.value}", hunk.lines(side), hunk.text, patch = true))
        add(context(ContextScopeKind.FILE_DIFF, "Diff of ${file.path.substringAfterLast('/')}", null, file.patch, patch = true))
        val whole = sideText.ifEmpty { file.fullFileText.orEmpty() }
        if (whole.isNotEmpty()) add(context(ContextScopeKind.FILE, "File ${file.path.substringAfterLast('/')}", null, whole))
        if (allFiles != null && allFiles.size > 1) add(changeScope(allFiles, side))
    }
}

/** The patch row of [line] on [side] in [hunk], without its marker. */
private fun patchRow(hunk: PatchHunk, line: Int, side: SourceSide): String? {
    var old = hunk.before.first
    var new = hunk.after.first
    for (row in hunk.rows) {
        if (row.startsWith("\\")) continue
        val marker = row.firstOrNull() ?: ' '
        val onSide = if (side == SourceSide.BEFORE) marker != '+' && old == line else marker != '-' && new == line
        if (onSide) return row.drop(1)
        if (marker != '+') old++
        if (marker != '-') new++
    }
    return null
}

/** Every file's patch under a `---`/`+++` header, as one patch. */
private fun changeScope(files: List<ReviewFile>, side: SourceSide): CodeContext {
    val text = files.joinToString("") { file ->
        val patch = file.patch.let { if (it.endsWith('\n') || it.isEmpty()) it else "$it\n" }
        "--- a/${file.previousPath ?: file.path}\n+++ b/${file.path}\n$patch"
    }
    return CodeContext(ContextScopeKind.CHANGE, "Change (${files.size} files)", null, null, null, side, capContext(text), true,
        files = files.map { it.path })
}

/** A physical directory in a review, even when it contains only one file in scope. */
internal fun directoryScope(path: String, files: List<ReviewSource>): CodeContext =
    CodeContext(ContextScopeKind.DIRECTORY, "Directory ${path.ifEmpty { "." }}", path, directoryAbsolutePath(path, files), null,
        SourceSide.CURRENT, reviewFilesPreview(files), files.all { it is PatchSource }, files = files.map { it.path })

private fun directoryAbsolutePath(path: String, files: List<ReviewSource>): String? {
    val prefix = path.takeIf { it.isNotEmpty() }?.plus("/").orEmpty()
    val matches = files.filter { it.path.startsWith(prefix) }
    if (matches.isEmpty()) return null
    return matches.map { file ->
        val suffix = "/" + file.path.removePrefix(prefix)
        file.absolutePath.takeIf { it.endsWith(suffix) }?.removeSuffix(suffix)?.ifEmpty { "/" }
    }.distinct().singleOrNull()
}

internal fun reviewFilesPreview(files: List<ReviewSource>): String = buildString {
    for (file in files) {
        val text = (file as? PatchSource)?.patch ?: file.text.orEmpty()
        append("--- ").append(file.path).append(" ---\n")
        if (length + text.length + 2 > MAX_CONTEXT_CHARS) {
            append(text, 0, (MAX_CONTEXT_CHARS - length).coerceIn(0, text.length))
            append("\n… (preview truncated)\n")
            break
        }
        append(text).append("\n\n")
    }
}

/**
 * Candidate scopes at [line] of a source [text], narrow to wide: the [selection] (lines), the line, the enclosing [symbols]
 * (innermost first), the VCS hunk [vcsHunk] around the line when known, and the whole file.
 */
fun sourceScopes(path: String, absolutePath: String?, text: String, line: Int, selection: IntRange?, symbols: List<SymbolSpan>,
    vcsHunk: IntRange?): List<CodeContext> {
    fun context(kind: ContextScopeKind, label: String, lines: IntRange?, payload: String, symbol: ContextSymbol? = null) =
        CodeContext(kind, label, path, absolutePath, lines, SourceSide.CURRENT, capContext(payload), symbol = symbol)
    return buildList {
        if (selection != null) add(context(ContextScopeKind.SELECTION, "Selection ${rangeLabel(selection)}", selection, slice(text, selection)))
        add(context(ContextScopeKind.LINE, "Line $line", line..line, slice(text, line..line)))
        for (span in symbols) add(context(ContextScopeKind.SYMBOL, symbolLabel(span.symbol), span.lines, slice(text, span.lines), span.symbol))
        if (vcsHunk != null) add(context(ContextScopeKind.HUNK, "Changed lines ${rangeLabel(vcsHunk)}", vcsHunk, slice(text, vcsHunk)))
        add(context(ContextScopeKind.FILE, "File ${path.substringAfterLast('/')}", null, text))
    }
}

/** The scope picked first: the selection, else the innermost symbol, else the hunk, else the line (or the first scope). */
fun defaultScopeIndex(candidates: List<CodeContext>): Int =
    listOf(ContextScopeKind.SELECTION, ContextScopeKind.SYMBOL, ContextScopeKind.HUNK, ContextScopeKind.LINE)
        .firstNotNullOfOrNull { kind -> candidates.indexOfFirst { it.kind == kind }.takeIf { it >= 0 } } ?: 0
