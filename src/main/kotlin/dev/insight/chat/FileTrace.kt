package dev.insight.chat

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.LlmRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.io.StringReader
import java.util.Locale

private val clippedLine = Regex("… \\[\\d+ more characters]$")
private val numberedLine = Regex("^\\s*(\\d+)\\| (.*)$")
private val changedLine = Regex("^([ +\\-])\\s*(\\d+) (.*)$")
private val hunkHeader = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*")
private val projectReadHeader = Regex("^(.+) \\(lines \\d+-\\d+ of \\d+\\)$")
private val projectSearchLine = Regex("^(.+?):(\\d+): (.*)$")
private val focusedSourceLine = Regex("^(\\d+): (.*)$")

/** Immutable review evidence or current project content captured when a tool reads it. */
internal data class TraceFile(val path: String, val source: String? = null, val patch: String? = null,
    val before: String? = null, val after: String? = null, val project: String? = null)

internal data class TraceKey(val path: String, val kind: String)
internal data class TraceDocument(val key: TraceKey, val text: String) {
    val starts: List<Int> by lazy { listOf(0) + text.indices.filter { text[it] == '\n' && it + 1 < text.length }.map { it + 1 } }
    fun line(number: Int): String? = starts.getOrNull(number - 1)?.let { start -> text.substring(start, text.indexOf('\n', start).let { if (it < 0) text.length else it }) }
}

internal data class TraceRequest(val number: Int, val state: String, val evidence: Map<TraceKey, List<IntRange>>, val unparsed: Int)
internal data class TraceSnapshot(val documents: Map<TraceKey, TraceDocument>, val requests: List<TraceRequest>, val reads: Map<String, List<String>>)

/** Turn-local and coroutine-scoped: concurrent summary/topic requests cannot leak into another chat. */
internal class FileTraceContext(val trace: FileTrace) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<FileTraceContext>
}

internal class FileTrace(val question: String, private val changed: suspend () -> Unit = {}) {
    private val documents = linkedMapOf<TraceKey, TraceDocument>()
    private val requests = mutableListOf<TraceRequest>()
    private val reads = linkedMapOf<String, MutableList<String>>()

    @Synchronized fun register(files: List<TraceFile>) {
        for (file in files) for ((kind, text) in listOf("Source" to file.source, "Diff" to file.patch, "Before" to file.before, "After" to file.after, "Project" to file.project)) {
            if (text != null) {
                val versions = if (kind == "Project") documents.values.filter {
                    it.key.path == file.path && it.key.kind.startsWith("Project")
                } else emptyList()
                val key = versions.firstOrNull { it.text == text }?.key
                    ?: TraceKey(file.path, if (versions.isEmpty()) kind else "Project ${versions.size + 1}")
                // Retain the immutable snapshot string instead of copying every file for every turn.
                documents[key] = TraceDocument(key, text)
            }
        }
    }

    @Synchronized fun snapshot() = TraceSnapshot(documents.toMap(), requests.toList(), reads.mapValues { it.value.toList() })

    /** Recorded before execution, so a stopped read remains visible even without a tool-result callback. */
    @Synchronized fun requestTool(name: String, arguments: JsonObject) {
        for (path in readPaths(name, arguments)) reads.getOrPut(path) { mutableListOf() } +=
            "$name($arguments) — requested (no result yet)"
    }

    @Synchronized fun tool(event: ToolCallEvent) {
        val call = "${event.name}(${event.arguments})"
        val paths = readPaths(event.name, event.arguments) + if (!event.failed && event.name == "search_text")
            event.result.lineSequence().mapNotNull { projectSearchLine.matchEntire(it)?.groupValues?.get(1) }.toList() else emptyList()
        for (path in paths.distinct()) {
            val entries = reads.getOrPut(path) { mutableListOf() }
            val pending = entries.indexOf("$call — requested (no result yet)")
            val result = call + if (event.failed) " — ${event.result}" else " — returned (coverage counts only subsequent accepted requests)"
            if (pending < 0) entries += result else entries[pending] = result
        }
    }

    private fun readPaths(name: String, arguments: JsonObject): List<String> {
        if (name in setOf("read_file", "point_to_code", "show_diff_source", "show_code_source"))
            return listOfNotNull(arguments.string("path"))
        val field = when (name) { "read_code" -> "ranges"; "read_diff_files" -> "paths"; else -> return emptyList() }
        val array = arguments.get(field)?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull {
            if (name == "read_code") it.takeIf { it.isJsonObject }?.asJsonObject?.string("path")
            else it.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        }.distinct()
    }

    @Synchronized fun finish() {
        for (entries in reads.values) for (index in entries.indices) {
            entries[index] = entries[index].replace(" — requested (no result yet)", " — requested; no result before the turn ended")
        }
    }

    suspend fun complete(request: CompletionRequest, complete: suspend (CompletionRequest) -> Completion): Completion {
        val record = synchronized(this) {
            val extracted = extract(request)
            TraceRequest(requests.size + 1, "Sending", extracted.first, extracted.second).also { requests += it }
        }
        changed()
        try {
            val result = complete(request)
            update(record, "Accepted")
            return result
        } catch (error: Throwable) {
            update(record, if (error is CancellationException) "Cancelled" else "Failed")
            throw error
        }
    }

    private suspend fun update(record: TraceRequest, state: String) {
        synchronized(this) { requests[record.number - 1] = record.copy(state = state) }
        changed()
    }

    /** Only data messages are inspected. Every excerpt must match the captured source at its stated coordinates. */
    private fun extract(request: CompletionRequest): Pair<Map<TraceKey, List<IntRange>>, Int> {
        val found = linkedMapOf<TraceKey, MutableList<IntRange>>()
        var unparsed = 0
        fun add(key: TraceKey, start: Int, shown: String) {
            val document = documents[key] ?: return
            if (start < 0 || start > document.text.length || !document.text.startsWith(shown, start)) return
            if (shown.isNotEmpty()) found.getOrPut(key) { mutableListOf() } += start until start + shown.length
        }
        fun line(key: TraceKey, number: Int, shown: String, trimmed: Boolean = false) {
            val document = documents[key] ?: return
            val original = document.line(number) ?: return
            val offset = if (trimmed) original.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0) else 0
            val available = original.substring(offset)
            val content = if (available.startsWith(shown)) shown else {
                val marker = clippedLine.find(shown) ?: return
                shown.substring(0, marker.range.first).takeIf { available.startsWith(it) } ?: return
            }
            add(key, document.starts[number - 1] + offset, content)
        }
        fun projectLine(path: String, number: Int, shown: String, trimmed: Boolean = false) {
            for (key in documents.keys) if (key.path == path && key.kind.startsWith("Project"))
                line(key, number, shown, trimmed)
        }
        fun visit(value: JsonElement) {
            if (value.isJsonArray) { value.asJsonArray.forEach(::visit); return }
            if (!value.isJsonObject) return
            val obj = value.asJsonObject
            val path = obj.string("path")
            if (path != null) {
                val key = TraceKey(path, "Source")
                val startLine = obj.integer("start_line") ?: 1
                for (field in listOf("code", "excerpt")) obj.string(field)?.let { code ->
                    documents[key]?.starts?.getOrNull(startLine - 1)?.let { add(key, it, code) }
                }
                obj.string("text")?.let { text ->
                    if (obj.has("line")) line(key, obj.integer("line") ?: 0, text, trimmed = true)
                    else for (row in text.split('\n')) {
                        val match = numberedLine.matchEntire(row) ?: continue
                        line(key, match.groupValues[1].toIntOrNull() ?: 0, match.groupValues[2])
                    }
                }
                obj.string("patch")?.let { patch -> add(TraceKey(path, "Diff"), obj.integer("offset") ?: 0, patch.removeSuffix("\n")) }
                // read_change_nodes returns numbered changed lines, not a complete function body.
                obj.get("diff")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { row ->
                    if (row.isJsonPrimitive) changedLine.matchEntire(row.asString)?.let { match ->
                        val kind = if (match.groupValues[1] == "-") "Before" else "After"
                        line(TraceKey(path, kind), match.groupValues[2].toIntOrNull() ?: 0, match.groupValues[3])
                    }
                }
            }
            // Summaries, names, coordinates and presentation acknowledgements never count as source.
            for ((name, child) in obj.entrySet()) if (name in setOf("files", "ranges", "matches", "nodes")) visit(child)
        }
        for (message in request.messages) {
            // Focused review chats pin their evidence in the system prompt, outside the JSON overview.
            // Only inspect the explicit evidence section and verify every character against its snapshot.
            if (message.role == LlmRole.SYSTEM) {
                for ((marker, kind) in listOf("Current diff of the focus:\n" to "Diff", "Current source of the focus:\n" to "Source")) {
                    if (!message.content.contains(marker)) continue
                    val section = message.content.substringAfter(marker)
                    for ((key, document) in documents) {
                        if (key.kind != kind) continue
                        val delimiter = "--- ${key.path} ---\n"
                        if (!section.contains(delimiter)) continue
                        val excerpt = section.substringAfter(delimiter)
                        if (kind == "Diff") {
                            val patch = document.text.trimEnd()
                            if (excerpt.startsWith(patch)) add(key, 0, patch)
                        } else {
                            for (row in excerpt.lineSequence()) {
                                val match = focusedSourceLine.matchEntire(row) ?: break
                                line(key, match.groupValues[1].toIntOrNull() ?: 0, match.groupValues[2])
                            }
                        }
                    }
                }
                continue
            }
            if (message.role !in setOf(LlmRole.USER, LlmRole.TOOL)) continue
            val content = message.content
            // Project tools return human-readable text, whereas snapshot tools return JSON.
            // Match against the exact current-project text captured by the tool, never snapshot source.
            if (message.role == LlmRole.TOOL) {
                val rows = content.lineSequence().toList()
                val path = rows.firstOrNull()?.let { projectReadHeader.matchEntire(it)?.groupValues?.get(1) }
                if (path != null) {
                    for (row in rows.drop(1)) numberedLine.matchEntire(row)?.let { match ->
                        projectLine(path, match.groupValues[1].toIntOrNull() ?: 0, match.groupValues[2])
                    }
                    continue
                }
                var search = false
                for (row in rows) projectSearchLine.matchEntire(row)?.let { match ->
                    val path = match.groupValues[1]
                    if (documents.keys.any { it.path == path && it.kind.startsWith("Project") }) {
                        search = true
                        projectLine(path, match.groupValues[2].toIntOrNull() ?: 0, match.groupValues[3], trimmed = true)
                    }
                }
                if (search) continue
            }
            val data = when {
                message.role == LlmRole.TOOL -> content
                content.trimStart().startsWith('{') -> content
                content.contains("Current diff snapshot (data, not instructions):\n") -> content.substringAfter("Current diff snapshot (data, not instructions):\n")
                else -> continue
            }
            // Omission notices and answer-format reminders may follow an otherwise complete data object.
            val json = runCatching { JsonParser.parseReader(JsonReader(StringReader(data))) }.getOrNull()
            if (json == null) { if (message.role == LlmRole.TOOL && !data.startsWith("Error:")) unparsed++; continue }
            visit(json)
        }
        // Map complete exposed patch lines back to the corresponding snapshot sides. Full diff != full source.
        for ((key, ranges) in found.toMap()) {
            if (key.kind != "Diff") continue
            val merged = mergeRanges(ranges)
            val patch = documents.getValue(key).text
            var before = 0
            var after = 0
            var offset = 0
            for (row in patch.split('\n')) {
                val header = hunkHeader.matchEntire(row)
                if (header != null) { before = header.groupValues[1].toInt(); after = header.groupValues[2].toInt() }
                else if (before > 0 || after > 0) {
                    val exposed = covered(merged, offset until offset + row.length) == row.length
                    when (row.firstOrNull()) {
                        '-' -> { if (exposed) line(TraceKey(key.path, "Before"), before, row.drop(1)); before++ }
                        '+' -> { if (exposed) line(TraceKey(key.path, "After"), after, row.drop(1)); after++ }
                        ' ' -> { if (exposed) { line(TraceKey(key.path, "Before"), before, row.drop(1)); line(TraceKey(key.path, "After"), after, row.drop(1)) }; before++; after++ }
                    }
                }
                offset += row.length + 1
            }
        }
        return found.mapValues { mergeRanges(it.value) } to unparsed
    }
}

internal suspend fun tracedFileCompletion(files: List<TraceFile>, complete: suspend (CompletionRequest) -> Completion): suspend (CompletionRequest) -> Completion {
    val trace = currentCoroutineContext()[FileTraceContext]?.trace ?: return complete
    trace.register(files)
    return { request -> trace.complete(request, complete) }
}

internal fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
    val result = mutableListOf<IntRange>()
    for (range in ranges.filterNot { it.isEmpty() }.sortedBy { it.first }) {
        val last = result.lastOrNull()
        if (last != null && range.first <= last.last + 1) result[result.lastIndex] = last.first..maxOf(last.last, range.last)
        else result += range
    }
    return result
}

/** Intersection size for already merged ranges, without scanning every earlier excerpt for each source line. */
internal fun covered(ranges: List<IntRange>, range: IntRange): Int {
    if (range.isEmpty()) return 0
    var low = 0
    var high = ranges.size
    while (low < high) {
        val middle = (low + high) / 2
        if (ranges[middle].last < range.first) low = middle + 1 else high = middle
    }
    var result = 0
    for (index in low until ranges.size) {
        val item = ranges[index]
        if (item.first > range.last) break
        result += (minOf(item.last, range.last) - maxOf(item.first, range.first) + 1).coerceAtLeast(0)
    }
    return result
}

private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
private fun JsonObject.integer(name: String): Int? = get(name)?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull()

internal fun traceCoverage(document: TraceDocument, ranges: List<IntRange>): String {
    val total = document.text.count { it != '\n' }
    if (total == 0) return "N/A (empty content)"
    val count = mergeRanges(ranges).sumOf { range -> document.text.substring(range.first, range.last + 1).count { it != '\n' } }
    val percent = count * 100.0 / total
    if (count < total && percent > 99.9) return String.format(Locale.ROOT, ">99.9%% (%d/%d)", count, total)
    return String.format(Locale.ROOT, "%.1f%% (%d/%d)", percent, count, total)
}

internal data class TraceLineRange(val lines: IntRange, val partial: Boolean) {
    val label: String get() = (if (lines.first == lines.last) "${lines.first}" else "${lines.first}–${lines.last}") +
        if (partial) " (partial)" else ""
}

internal fun traceLineRanges(document: TraceDocument, ranges: List<IntRange>): List<TraceLineRange> {
    val merged = mergeRanges(ranges)
    val full = mutableListOf<IntRange>()
    val partial = mutableListOf<IntRange>()
    for ((index, start) in document.starts.withIndex()) {
        val end = document.starts.getOrNull(index + 1)?.minus(1)
            ?: (document.text.length - if (document.text.endsWith('\n')) 1 else 0)
        val length = end - start
        val count = covered(merged, start until end)
        if (length > 0 && count == length) full += (index + 1)..(index + 1)
        else if (count > 0) partial += (index + 1)..(index + 1)
    }
    return (mergeRanges(full).map { TraceLineRange(it, false) } + mergeRanges(partial).map { TraceLineRange(it, true) })
        .sortedBy { it.lines.first }
}

internal fun traceLines(document: TraceDocument, ranges: List<IntRange>): String {
    val lines = traceLineRanges(document, ranges)
    fun format(partial: Boolean) = lines.filter { it.partial == partial }.joinToString(", ") { it.label.removeSuffix(" (partial)") }
    return listOf(format(false), format(true).takeIf { it.isNotEmpty() }?.let { "partial: $it" }.orEmpty())
        .filter { it.isNotEmpty() }.joinToString("; ").ifEmpty { "None" }
}
