package dev.insight.codereview

import dev.insight.chat.CancellableSearchInput
import dev.insight.chat.ToolChatRunner
import dev.insight.chat.ToolInputException
import dev.insight.diff.ChangeSummaries
import dev.insight.diff.DiffNote
import dev.insight.diff.DiffSourceBlock
import dev.insight.diff.DiffSourceRange
import dev.insight.diff.SummaryKind
import dev.insight.diff.SummaryNode
import dev.insight.diff.reviewJson
import java.nio.file.FileSystems
import java.nio.file.InvalidPathException
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/*
 * Pure, size-bounded JSON results of the code review tools. Every result fits [MAX_RESULT_CHARS] including JSON escaping:
 * entries are added one at a time and the last one is dropped (and paged) when the serialized result no longer fits.
 */

/** The limit every tool result is checked against. */
internal const val MAX_RESULT_CHARS = ToolChatRunner.DEFAULT_MAX_TOOL_RESULT_CHARS
internal const val CODE_OVERVIEW_CHARS = 10_000
internal const val CODE_FILES_PAGE = 100
internal const val CODE_SEARCH_PAGE = 50
internal const val CODE_READ_LINES = 400
internal const val CODE_READ_RANGES = 10
internal const val CODE_SOURCE_LINES = 200
internal const val CODE_SOURCE_CHARS = 20_000
private const val SEARCH_LINE_CHARS = 200
private const val READ_LINE_CHARS = 2_000

/** One-based lines of a file to read; a null [endLine] reads up to [CODE_READ_LINES] lines. */
data class CodeRange(val path: String, val startLine: Int = 1, val endLine: Int? = null)

/** The tab's bounded view of its scope, sent with every question: totals, skipped files, folders, top summaries, selection and notes. */
internal fun codeOverview(
    snapshot: CodeSnapshot,
    scopePaths: Set<String>?,
    selected: List<String>,
    notes: List<DiffNote>,
    sources: List<DiffSourceBlock>,
    summaries: ChangeSummaries?,
    budget: Int = CODE_OVERVIEW_CHARS,
): String {
    val result = linkedMapOf<String, Any>()
    result["scope"] = snapshot.scope.displayName
    if (snapshot.scope.entry) result["entry_file"] = snapshot.scope.path
    // The user's exclusions: files matching them are out of scope, not missing.
    if (snapshot.scope.excludes.isNotEmpty()) result["excluded_patterns"] = snapshot.scope.excludes.take(30).map { it.take(200) }
    result["tab"] = if (scopePaths == null) "whole scope" else "fixed selection of ${scopePaths.size} paths"
    result["totals"] = buildMap {
        put("files", snapshot.files.size)
        put("lines", snapshot.totalLines)
        put("characters", snapshot.totalChars)
        if (snapshot.chunkedFiles > 0) { put("files_split_into_parts", snapshot.chunkedFiles); put("parts", snapshot.partCount) }
    }
    val skipped = snapshot.skipped.groupingBy { it.reason }.eachCount().toMutableMap()
    // Only the first files over the limits are listed; the count covers all of them.
    if (snapshot.overLimit > 0) skipped[SkipReason.LIMIT] = snapshot.overLimit
    if (skipped.isNotEmpty() || snapshot.ignoredCount > 0 || snapshot.excludedCount > 0 || snapshot.truncated) result["skipped"] = buildMap {
        SkipReason.entries.forEach { reason -> skipped[reason]?.let { put(reason.label, it) } }
        if (snapshot.ignoredCount > 0) put("ignored files and folders", snapshot.ignoredCount)
        if (snapshot.excludedCount > 0) put("excluded files and folders", snapshot.excludedCount)
        if (snapshot.truncated) put("truncated", true)
    }
    fun text() = reviewJson.toJson(result)
    fun fits() = text().length <= budget
    if (sources.isNotEmpty()) result["source_blocks_count"] = sources.size

    // The folder outline gets what is left after the fixed fields, keeping room for summaries, selection and notes.
    var outlineChars = minOf(4_000, budget - text().length - budget / 3)
    while (outlineChars > 200) {
        result["folders"] = folderOutline(snapshot.files.map { it.path }, snapshot.baseFolder, outlineChars)
        if (fits()) break
        outlineChars = outlineChars * 3 / 4
    }
    if (!fits()) result.remove("folders")

    if (summaries != null && summaries.count > 0) {
        val tree = summaries.tree
        val entries = mutableListOf<Map<String, Any>>()
        result["summaries"] = mapOf("summarized" to summaries.count, "nodes" to summaries.total, "top" to entries)
        // A chain of single folders below the root repeats the root's summary; the entries below it are the real top level.
        val head = generateSequence(tree.root) { node -> node.children.singleOrNull()?.let(tree::get)?.takeIf { it.kind == SummaryKind.DIRECTORY } }.last()
        val top = listOf(tree.root) + head.children.map(tree::get).flatMap { child ->
            // Groups only split a folder; their entries are the real top level.
            if (child.kind == SummaryKind.GROUP) child.children.map(tree::get) else listOf(child)
        }
        for (node in top) {
            val text = summaries.text(node.id) ?: continue
            entries += mapOf("location" to node.location, "summary" to text.take(if (node.id == tree.root.id) 1_500 else 400))
            if (!fits()) { entries.removeAt(entries.lastIndex); break }
        }
        if (entries.isEmpty()) result["summaries"] = mapOf("summarized" to summaries.count, "nodes" to summaries.total)
    }

    if (selected.isNotEmpty()) {
        result["selected_count"] = selected.size
        val shown = mutableListOf<String>()
        result["selected_paths"] = shown
        for (path in selected.take(100)) {
            shown += path
            if (!fits()) { shown.removeAt(shown.lastIndex); break }
        }
        if (shown.isEmpty()) result.remove("selected_paths")
    }

    if (notes.isNotEmpty()) {
        result["notes_count"] = notes.size
        val excerpts = mutableListOf<Map<String, Any>>()
        result["notes"] = excerpts
        for (note in notes.take(20)) {
            excerpts += buildMap {
                put("paths", note.paths.take(5))
                put("text_excerpt", note.text.take(160))
                note.symbol?.let { put("symbol", it) }
            }
            if (!fits()) { excerpts.removeAt(excerpts.lastIndex); break }
        }
        if (excerpts.isEmpty()) result.remove("notes")
    }
    return text()
}

/** One page of the files under [directory] (project-relative, "" for all) that match [glob]. */
internal fun codeFilesResult(snapshot: CodeSnapshot, directory: String, glob: String?, offset: Int): String {
    val folder = normalizeDirectory(directory)
    val matcher = globMatcher(glob)
    val matching = snapshot.files.filter { inDirectory(it.path, folder) && matches(matcher, glob, it.path) }
    if (matching.isEmpty() && folder.isNotEmpty() && snapshot.files.none { inDirectory(it.path, folder) })
        throw ToolInputException("No files in scope under $folder. Use list_code_files without directory to see the scope.")
    if (offset < 0 || offset > matching.size) throw ToolInputException("Offset is outside the file list")
    val entries = mutableListOf<Map<String, Any>>()
    fun text() = reviewJson.toJson(buildMap<String, Any> {
        if (folder.isNotEmpty()) put("directory", folder)
        glob?.let { put("glob", it) }
        put("total", matching.size)
        put("files", entries)
        (offset + entries.size).takeIf { it < matching.size }?.let { put("next_offset", it) }
        val skipped = snapshot.skipped.count { inDirectory(it.path, folder) }
        if (skipped > 0) put("skipped_files", skipped)
    })
    for (file in matching.drop(offset).take(CODE_FILES_PAGE)) {
        entries += buildMap {
            put("path", file.path)
            put("lines", file.lineCount)
            if (file.chunks.isNotEmpty()) put("parts", file.chunks.size)
        }
        if (text().length > MAX_RESULT_CHARS) { entries.removeAt(entries.lastIndex); break }
    }
    return text()
}

/**
 * Numbered lines of up to [CODE_READ_RANGES] ranges, at most [CODE_READ_LINES] lines each, fitted to the result limit. A range
 * that was cut returns next_start_line; ranges that did not fit at all are listed in not_read.
 */
internal fun codeReadResult(snapshot: CodeSnapshot, ranges: List<CodeRange>): String {
    if (ranges.isEmpty() || ranges.size > CODE_READ_RANGES) throw ToolInputException("Read 1 to $CODE_READ_RANGES ranges.")
    class Wanted(val file: CodeFile, val start: Int, val end: Int, val requestedEnd: Int)
    val wanted = ranges.mapIndexed { index, range ->
        val prefix = if (ranges.size > 1) "Range ${index + 1}: " else ""
        val file = snapshot.byPath[range.path] ?: throw ToolInputException("$prefix${range.path} is not a file in this review's scope. " +
            "Use list_code_files or search_code to find paths.")
        if (range.startLine < 1) throw ToolInputException("${prefix}start_line must be at least 1.")
        if (range.endLine != null && range.endLine < range.startLine) throw ToolInputException("${prefix}end_line must not be before start_line.")
        if (range.startLine > maxOf(file.lineCount, 1)) throw ToolInputException("$prefix${range.path} has only ${file.lineCount} lines.")
        val requestedEnd = minOf(range.endLine ?: file.lineCount, file.lineCount)
        Wanted(file, range.startLine, minOf(requestedEnd, range.startLine + CODE_READ_LINES - 1), requestedEnd)
    }
    val read = mutableListOf<Map<String, Any>>()
    val notRead = mutableListOf<Map<String, Any>>()
    fun text(pending: List<Wanted>) = reviewJson.toJson(buildMap<String, Any> {
        put("ranges", read)
        val rest = notRead + pending.map { mapOf("path" to it.file.path, "start_line" to it.start, "end_line" to it.requestedEnd) }
        if (rest.isNotEmpty()) put("not_read", rest)
    })
    fun slice(item: Wanted, lines: Int): Map<String, Any> = buildMap {
        val last = item.start + lines - 1
        put("path", item.file.path)
        put("start_line", item.start)
        put("end_line", maxOf(last, item.start - 1))
        put("total_lines", item.file.lineCount)
        put("text", numberedLines(item.file, item.start, last))
        if (last < item.requestedEnd) put("next_start_line", last + 1)
    }
    for ((index, item) in wanted.withIndex()) {
        val pending = wanted.drop(index + 1)
        fun fits(entry: Map<String, Any>): Boolean { read += entry; val ok = text(pending).length <= MAX_RESULT_CHARS; read.removeAt(read.lastIndex); return ok }
        val count = item.end - item.start + 1
        if (count <= 0 || fits(slice(item, count))) { read += slice(item, maxOf(count, 0)); continue }
        var low = 1
        var high = count - 1
        var available = 0
        while (low <= high) {
            val size = (low + high) / 2
            if (fits(slice(item, size))) { available = size; low = size + 1 } else high = size - 1
        }
        // Avoid tiny slices of later ranges, but let the first range make progress.
        val minimum = if (read.isEmpty()) 1 else minOf(20, count)
        if (available >= minimum) read += slice(item, available)
        else notRead += mapOf("path" to item.file.path, "start_line" to item.start, "end_line" to item.requestedEnd)
        // The result is full: later ranges wait, so ranges are read in the order asked.
        pending.forEach { notRead += mapOf("path" to it.file.path, "start_line" to it.start, "end_line" to it.requestedEnd) }
        break
    }
    if (read.isEmpty()) throw ToolInputException("The requested lines exceed the result limit. Read fewer ranges.")
    return text(emptyList())
}

private fun numberedLines(file: CodeFile, start: Int, last: Int): String {
    if (last < start) return ""
    val width = last.toString().length
    val lines = file.lines(start..last).split('\n')
    return lines.withIndex().joinToString("\n") { (index, line) ->
        "${(start + index).toString().padStart(width)}| ${clip(line, READ_LINE_CHARS)}"
    }
}

/**
 * Matching lines in path order, [CODE_SEARCH_PAGE] per page from [offset] (a match count). Plain queries match
 * case-insensitively; [regex] queries are Java regular expressions, bounded per line so a catastrophic pattern fails
 * instead of hanging. [checkCanceled] runs while matching.
 */
internal fun codeSearchResult(
    snapshot: CodeSnapshot,
    query: String,
    regex: Boolean,
    directory: String,
    glob: String?,
    offset: Int,
    checkCanceled: () -> Unit = {},
): String {
    if (query.isEmpty()) throw ToolInputException("query must be a nonempty string")
    if (offset < 0) throw ToolInputException("offset must be a nonnegative integer")
    val folder = normalizeDirectory(directory)
    val matcher = globMatcher(glob)
    val pattern = if (regex) try {
        Pattern.compile(query)
    } catch (e: PatternSyntaxException) {
        throw ToolInputException("query is not a valid regular expression: ${e.description}")
    } else null
    val matches = mutableListOf<Map<String, Any>>()
    var nextOffset: Int? = null
    var seen = 0
    fun text() = reviewJson.toJson(buildMap<String, Any> {
        put("query", query)
        if (regex) put("regex", true)
        if (folder.isNotEmpty()) put("directory", folder)
        glob?.let { put("glob", it) }
        put("matches", matches)
        nextOffset?.let { put("next_offset", it) }
        if (matches.isEmpty() && nextOffset == null) put("note", if (offset == 0) "No matches." else "No more matches.")
    })
    search@ for (file in snapshot.files) {
        if (!inDirectory(file.path, folder) || !matches(matcher, glob, file.path)) continue
        val text = file.text
        var start = 0
        var line = 1
        while (start <= text.length) {
            if (start == text.length && (text.isEmpty() || text.endsWith('\n'))) break
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            val content = text.substring(start, end)
            val found = if (pattern != null) regexFind(pattern, content, query, checkCanceled) else content.contains(query, ignoreCase = true)
            if (found) {
                if (seen >= offset) {
                    if (matches.size == CODE_SEARCH_PAGE) { nextOffset = seen; break@search }
                    matches += mapOf("path" to file.path, "line" to line, "text" to clip(content.trim(), SEARCH_LINE_CHARS))
                    if (text().length > MAX_RESULT_CHARS) { matches.removeAt(matches.lastIndex); nextOffset = seen; break@search }
                }
                seen++
            }
            start = end + 1
            line++
        }
        checkCanceled()
    }
    return text()
}

/** A regex search over one line that reads at most a few hundred times the line's characters before it gives up. */
private fun regexFind(pattern: Pattern, line: String, query: String, checkCanceled: () -> Unit): Boolean {
    var reads = 0L
    val limit = 200L * line.length + 10_000
    val input = CancellableSearchInput(line) {
        if (++reads > limit) throw ToolInputException("The regular expression $query is too expensive on some lines. Use a simpler pattern.")
        if ((reads and 0xFFFFL) == 0L) checkCanceled()
    }
    return pattern.matcher(input).find()
}

/**
 * One size-bounded page of the summary tree in pre-order, or of the subtree under [under]: a node's location, a file or folder
 * path. A path that names no node (inside a merged folder chain) pages the smallest node that holds all of its files.
 */
internal fun codeSummariesJson(summaries: ChangeSummaries, offset: Int, under: String?): String {
    val tree = summaries.tree
    val top = under?.trim()?.takeIf { it.isNotEmpty() && it != "." && it != "./" }?.let { subtreeRoot(summaries, it) } ?: tree.root
    val ids = mutableListOf<Int>()
    fun visit(id: Int) { ids += id; tree[id].children.forEach(::visit) }
    visit(top.id)
    if (offset < 0 || offset > ids.size) throw ToolInputException("Offset is outside the node list")
    val nodes = mutableListOf<Map<String, Any>>()
    val summarized = ids.count { summaries.text(it) != null }
    fun text(): String = reviewJson.toJson(buildMap {
        put("evidence", "The tree comes from the folders and files in scope; large files are split into parts by line range. " +
            "Summaries are model interpretation.")
        if (top.id != tree.root.id) put("under", top.location)
        put("totals", mapOf("nodes" to ids.size, "files" to top.files.size, "summarized" to summarized, "missing" to ids.size - summarized))
        if (summaries.count == 0) put("note", "No summaries are written yet. Suggest /summaries to the user.")
        put("nodes", nodes)
        (offset + nodes.size).takeIf { it < ids.size }?.let { put("next_offset", it) }
    })
    for (id in ids.drop(offset)) {
        nodes += summaryNodeJson(summaries, tree[id])
        if (text().length > MAX_RESULT_CHARS) { nodes.removeAt(nodes.lastIndex); break }
    }
    if (nodes.isEmpty() && offset < ids.size) throw ToolInputException("Node metadata exceeds the result limit at offset $offset.")
    return text()
}

private fun subtreeRoot(summaries: ChangeSummaries, under: String): SummaryNode {
    val tree = summaries.tree
    tree.nodes.firstOrNull { it.location == under }?.let { return it }
    val path = under.trimEnd('/')
    tree.nodes.firstOrNull { it.path == path && it.kind != SummaryKind.GROUP && it.kind != SummaryKind.CHUNK }?.let { return it }
    val wanted = tree.root.files.filterTo(HashSet()) { it == path || it.startsWith("$path/") }
    if (wanted.isEmpty()) throw ToolInputException("No summary node covers $under. Use a folder or file path from the tree, or omit path.")
    var node = tree.root
    while (true) {
        node = node.children.map(tree::get).firstOrNull { child -> child.kind != SummaryKind.CHUNK && child.files.containsAll(wanted) } ?: return node
    }
}

private fun summaryNodeJson(summaries: ChangeSummaries, node: SummaryNode): Map<String, Any> = buildMap {
    put("id", node.id); put("kind", node.kind.id); put("location", node.location)
    node.parent?.let { put("parent", it) }
    if (node.kind == SummaryKind.FILE) node.file?.facts?.takeIf { it.isNotEmpty() }?.let { put("facts", it) }
    node.lines?.let { put("lines", mapOf("start" to it.first, "end" to it.last)) }
    if (node.children.isNotEmpty()) {
        if (node.kind != SummaryKind.FILE && node.kind != SummaryKind.CHUNK) put("files", node.files.size)
        put("children", node.children)
    }
    summaries.text(node.id)?.let { put("summary", it) }
}

/** The exact current lines of a file in scope, as a canvas source block: at most 200 lines and 20000 characters. */
internal fun resolveCodeSource(snapshot: CodeSnapshot, range: DiffSourceRange): DiffSourceBlock {
    val file = snapshot.byPath[range.path] ?: throw ToolInputException("${range.path} is not a file in this review's scope.")
    if (range.startLine < 1 || range.endLine < range.startLine || range.endLine.toLong() - range.startLine >= CODE_SOURCE_LINES)
        throw ToolInputException("Use an inclusive range of 1–$CODE_SOURCE_LINES lines, starting at line 1 or later.")
    if (range.endLine > file.lineCount) throw ToolInputException("Range exceeds the ${file.lineCount} lines of ${range.path}.")
    val text = file.lines(range.startLine..range.endLine)
    if (text.length > CODE_SOURCE_CHARS) throw ToolInputException("Source block exceeds $CODE_SOURCE_CHARS characters. Select fewer lines.")
    return DiffSourceBlock(range.copy(side = CODE_SOURCE_SIDE), text)
}

/** The side of every code review source block: the file as it is in the snapshot. */
internal const val CODE_SOURCE_SIDE = "current"

/**
 * Folders beneath [root] with their file counts, indented, as deep as fits in [chars]. When even the top level does not fit,
 * its first folders are listed with a count of the rest. Linear in the number of paths, so a 20,000-file scope stays cheap.
 */
internal fun folderOutline(paths: Collection<String>, root: String, chars: Int): String {
    val base = root.trim('/')
    val counts = HashMap<String, Int>()
    val children = HashMap<String, MutableSet<String>>()
    var direct = 0
    for (path in paths) {
        val relative = if (base.isNotEmpty() && path.startsWith("$base/")) path.substring(base.length + 1) else path
        var slash = relative.indexOf('/')
        if (slash < 0) { direct++; continue }
        var parent = ""
        while (slash >= 0) {
            val folder = relative.substring(0, slash)
            counts.merge(folder, 1, Int::plus)
            children.getOrPut(parent) { HashSet() } += folder
            parent = folder
            slash = relative.indexOf('/', slash + 1)
        }
    }
    fun line(folder: String, depth: Int) = "${"  ".repeat(depth)}${folder.substringAfterLast('/')}/ (${counts.getValue(folder)} ${if (counts.getValue(folder) == 1) "file" else "files"})"
    val header = (if (base.isEmpty()) "./" else "$base/") + " (${paths.size} files" + (if (direct > 0) ", $direct directly in it)" else ")")
    if (children.isEmpty()) return header
    // Characters needed to show every folder down to each depth.
    val byDepth = mutableListOf<Int>()
    var level = children[""].orEmpty().toList()
    var depth = 0
    while (level.isNotEmpty()) {
        val size = level.sumOf { line(it, depth + 1).length + 1 }
        byDepth += (byDepth.lastOrNull() ?: header.length) + size
        level = level.flatMap { children[it].orEmpty() }
        depth++
    }
    val shown = byDepth.indexOfLast { it <= chars } + 1
    val out = StringBuilder(header)
    if (shown == 0) {
        val top = children.getValue("").sorted()
        for ((index, folder) in top.withIndex()) {
            val next = "\n" + line(folder, 1)
            val rest = "\n  … ${top.size - index} more folders"
            if (out.length + next.length + rest.length > chars) { out.append(rest); break }
            out.append(next)
        }
        return out.toString()
    }
    fun visit(folder: String, depth: Int) {
        out.append('\n').append(line(folder, depth))
        if (depth < shown) children[folder]?.sorted()?.forEach { visit(it, depth + 1) }
    }
    children.getValue("").sorted().forEach { visit(it, 1) }
    if (shown < byDepth.size) out.append("\n(deeper folders omitted)")
    return out.toString()
}

private fun normalizeDirectory(directory: String): String = directory.trim().trim('/').let { if (it == ".") "" else it }

private fun inDirectory(path: String, folder: String) = folder.isEmpty() || path == folder || path.startsWith("$folder/")

private fun globMatcher(glob: String?): PathMatcher? = glob?.takeIf { it.isNotBlank() }?.let {
    try {
        FileSystems.getDefault().getPathMatcher("glob:$it")
    } catch (_: IllegalArgumentException) {
        throw ToolInputException("glob is not a valid pattern: $it")
    }
}

/** A glob without a slash matches the file name, such as *.kt; with a slash it matches the project-relative path. */
private fun matches(matcher: PathMatcher?, glob: String?, path: String): Boolean {
    if (matcher == null) return true
    return try {
        matcher.matches(Paths.get(if ('/' in glob.orEmpty()) path else path.substringAfterLast('/')))
    } catch (_: InvalidPathException) {
        false
    }
}

private fun clip(text: String, max: Int): String = if (text.length <= max) text else text.take(max) + "… [${text.length - max} more characters]"
