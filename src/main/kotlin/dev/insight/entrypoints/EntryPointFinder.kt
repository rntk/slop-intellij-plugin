package dev.insight.entrypoints

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.logger
import dev.insight.agent.ChatTool
import dev.insight.agent.ToolChatRunner
import dev.insight.agent.ToolInputException
import dev.insight.agent.BudgetedTool
import dev.insight.agent.RecordingTool
import dev.insight.agent.SchemaOverride
import dev.insight.agent.SourceReadBudget
import dev.insight.llm.CODE_AS_DATA
import dev.insight.llm.JsonSchemas
import dev.insight.llm.clipLine
import dev.insight.llm.completeJson
import dev.insight.llm.jsonText
import dev.insight.llm.parseJsonObjectLeniently
import dev.insight.llm.CompletionFn
import dev.insight.llm.LlmMessage
import kotlinx.coroutines.CancellationException
import java.io.IOException
import dev.insight.core.Hashing
import dev.insight.prompts.PromptTemplates
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory results of earlier runs, validated against the current code on every use rather than trusted:
 * - a whole answer is reused when the file tree and hints are the same and no file it was based on changed;
 * - otherwise, the notes on files that are unchanged are given to the model so it does not read them again.
 * Nothing is written to disk, so a stale answer cannot outlive the IDE session.
 */
class EntryPointCache(private val maxRuns: Int = 8) {
    internal data class Search(val arguments: JsonObject, val resultHash: String)
    internal class Run(val result: EntryPointResult, val consulted: Map<String, String>, val searches: List<Search>)

    private val notes = ConcurrentHashMap<String, FileNote>()
    private val runs = object : LinkedHashMap<String, Run>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Run>?) = size > maxRuns
    }

    fun notes(): List<FileNote> = notes.values.sortedBy { it.path }
    fun putNote(note: FileNote) { notes[note.path] = note }
    fun dropNote(path: String) { notes.remove(path) }

    internal fun run(key: String): Run? = synchronized(runs) { runs[key] }

    internal fun putRun(key: String, result: EntryPointResult, consulted: Map<String, String>, searches: List<Search>) =
        synchronized(runs) { runs[key] = Run(result, consulted, searches.toList()) }

    fun clear() { notes.clear(); synchronized(runs) { runs.clear() } }
}

/**
 * Finds where a project's execution starts with an LLM that may list and read files. The model gets a compact file
 * tree and call-graph hints up front and a hard limit on file reads, so it skims manifests and likely files instead
 * of reading the codebase. [tools] must include `read_file`; every read is recorded with the file's content hash.
 */
class EntryPointFinder(
    private val complete: CompletionFn,
    private val snapshot: ProjectSnapshot,
    private val tools: List<ChatTool>,
    private val cache: EntryPointCache,
    private val maxFileReads: Int = 20,
    private val maxToolRounds: Int = 16,
    private val treeChars: Int = 8_000,
    private val maxSourceLines: Int = 1_600,
    private val maxSourceChars: Int = 32_000,
) {
    init {
        require(maxFileReads > 0 && maxSourceLines > 0 && maxSourceChars > 0) { "Read budgets must be positive" }
    }
    suspend fun find(hints: List<EntryPointHint>, progress: EntryPointProgress = EntryPointProgress.NONE): EntryPointResult {
        progress.report("Listing project files…")
        val files = snapshot.files()
        val tree = FileTree.render(files, treeChars)
        val key = digest(systemPrompt(maxFileReads, maxSourceLines, maxSourceChars), CONVERT_PROMPT,
            SCHEMA.toString(), userPrompt(tree, emptyList(), emptyList(), emptyList()),
            PromptTemplates.load("entrypoint-hints.md"), PromptTemplates.load("entrypoint-known.md"),
            PromptTemplates.load("entrypoint-changed.md"),
            hints.joinToString("\n") { it.key })

        progress.report("Checking earlier results…")
        cache.run(key)?.let { run ->
            val searchTool = tools.firstOrNull { it.definition.name == SEARCH_TOOL }
            val unchanged = run.consulted.all { (path, hash) -> snapshot.identify(path)?.hash == hash }
            if (unchanged && run.searches.all { search ->
                if (searchTool == null) false else try {
                    digest(searchTool.call(search.arguments.deepCopy())) == search.resultHash
                } catch (e: ToolInputException) {
                    LOG.debug("Cached entry point search no longer runs", e)
                    false
                } catch (e: IOException) {
                    LOG.debug("Cached entry point search could not be replayed", e)
                    false
                }
            }) {
                return run.result.copy(cached = true, requestCount = 0)
            }
        }
        val present = files.toHashSet()
        val known = mutableListOf<FileNote>()
        val changed = mutableListOf<String>()
        for (note in cache.notes()) {
            if (note.path !in present) { cache.dropNote(note.path); continue }
            if (snapshot.identify(note.path)?.hash == note.hash) known += note else changed += note.path
        }

        val reads = LinkedHashMap<String, FileId>()
        val budget = SourceReadBudget(maxSourceLines, maxSourceChars)
        val searches = mutableListOf<EntryPointCache.Search>()
        val runner = ToolChatRunner(complete, tools.map { tool ->
            when (tool.definition.name) {
                SEARCH_TOOL -> BudgetedTool(RecordingTool(SchemaOverride(tool) { schema ->
                    schema.getAsJsonObject("properties")?.getAsJsonObject("max_results")?.addProperty("description",
                        "Maximum matching lines, default 50, at most 80.")
                }) { arguments, result ->
                    searches += EntryPointCache.Search(arguments, digest(result))
                }, budget, BudgetedTool.Format.SEARCH)
                READ_TOOL -> RecordingReadTool(BudgetedTool(SchemaOverride(tool) { schema ->
                    schema.getAsJsonObject("properties")?.getAsJsonObject("end_line")?.addProperty("description",
                        "Last line, inclusive; default start_line + 79, at most 80 lines.")
                }, budget, BudgetedTool.Format.READ), reads)
                else -> tool
            }
        }, maxToolRounds = maxToolRounds)
        val messages = listOf(
            LlmMessage.system(systemPrompt(maxFileReads, maxSourceLines, maxSourceChars)),
            LlmMessage.user(userPrompt(tree, hints, known, changed)),
        )
        progress.report("Asking the model…")
        val chat = runner.run(messages) { event ->
            val path = event.arguments.jsonText("path") ?: event.arguments.jsonText("directory")
            progress.report(if (event.failed) "${event.name} failed" else listOfNotNull(event.name, path).joinToString(" "))
        }
        var requests = chat.requestCount
        var json = parseJsonObjectLeniently(chat.reply)
        val original = json
        if (json == null || validationErrors(json).isNotEmpty()) {
            progress.report("Formatting the answer…")
            val errors = json?.let(::validationErrors).orEmpty()
            val counted: CompletionFn = { request -> requests++; complete(request) }
            try {
                json = completeJson(counted, CONVERT_PROMPT,
                    "Validation errors: ${errors.joinToString("; ")}\n\nAnalysis:\n${chat.reply.take(12_000)}", SCHEMA, maxTokens = 2048)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // A failed repair must not erase a parseable analysis from the tool loop.
                LOG.warn("Entry point answer repair failed", e)
                if (json == null) throw e
            }
        }
        val answer = requireNotNull(json)
        // If repair is still malformed, salvage both answers. A missing array in the repair must not erase
        // valid entries from the original. Repaired entries take precedence when their location is the same.
        val candidates = if (original != null && original !== answer && validationErrors(answer).isNotEmpty())
            listOf(answer, original) else listOf(answer)

        // Entry points must name a real project file; the path is normalized to the canonical form.
        val ids = HashMap<String, FileId?>()
        suspend fun id(path: String) = ids.getOrPut(path) { reads[path] ?: snapshot.identify(path) }
        val entryPoints = candidates.flatMap { it.array("entry_points").toList() }.mapNotNull { element ->
            val o = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val file = o.jsonText("path")?.let { id(it) } ?: return@mapNotNull null
            // Do not guess locations; skip only the entry with missing or invalid location evidence.
            val line = positiveLine(o.get("line")) ?: return@mapNotNull null
            if (file.lineCount != null && line > file.lineCount) return@mapNotNull null
            EntryPoint(
                path = file.path,
                line = line,
                symbol = o.jsonText("symbol").orEmpty().clipLine(120),
                kind = if (o.jsonText("kind")?.lowercase() in setOf("frontend", "client")) EntryPointKind.APPLICATION
                    else EntryPointKind.fromWire(o.jsonText("kind")),
                title = (o.jsonText("title") ?: o.jsonText("symbol") ?: file.path.substringAfterLast('/')).clipLine(100),
                description = o.jsonText("description").orEmpty().clipLine(400),
                confidence = o.get("confidence")?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.5,
            )
        }.distinctBy { Triple(it.path, it.line, it.symbol) }

        val fileNotes = candidates.asReversed().flatMap { it.array("files").toList() }.mapNotNull { element ->
            val o = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val file = o.jsonText("path")?.let { id(it) } ?: return@mapNotNull null
            file.path to o.jsonText("note").orEmpty().clipLine(200)
        }.toMap()
        val summary = candidates.firstNotNullOfOrNull { it.jsonText("summary") }.orEmpty().clipLine(600)
        if (entryPoints.isEmpty() && fileNotes.isEmpty() && summary.isEmpty())
            throw IOException("Entry-point answer contains no usable analysis")
        for ((path, file) in reads) {
            cache.putNote(FileNote(path, file.hash, fileNotes[path].orEmpty(), entryPoints.filter { it.path == path }))
        }

        val reused = known.map { it.path }.filter { it !in reads }
        val result = EntryPointResult(
            entryPoints = entryPoints,
            summary = summary,
            filesRead = reads.keys.toList(),
            filesReused = reused,
            requestCount = requests,
            cached = false,
        )
        // The answer rests on what was read, on the reused notes, and on the files it names.
        val consulted = LinkedHashMap<String, String>()
        reads.values.forEach { consulted[it.path] = it.hash }
        known.forEach { consulted.putIfAbsent(it.path, it.hash) }
        entryPoints.forEach { entry -> id(entry.path)?.let { consulted.putIfAbsent(it.path, it.hash) } }
        // Replaying queries also detects new matches after a previous no-match search, without hashing the project.
        cache.putRun(key, result, consulted, searches)
        return result
    }

    /** The distinct-file policy and file identities belong to entry-point discovery, rather than source tools. */
    private inner class RecordingReadTool(
        private val tool: ChatTool,
        private val reads: MutableMap<String, FileId>,
    ) : ChatTool by tool {
        override suspend fun call(arguments: JsonObject): String {
            val file = arguments.jsonText("path")?.let { snapshot.identify(it) }
            if (file != null && file.path !in reads && reads.size >= maxFileReads) {
                throw ToolInputException("The limit of $maxFileReads files is reached. Do not read more files; answer now with what you know.")
            }
            val result = tool.call(arguments)
            if (file != null) reads.putIfAbsent(file.path, file)
            return result
        }
    }

    private fun JsonObject.array(name: String): JsonArray = get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

    companion object {
        const val READ_TOOL = "read_file"
        const val LIST_TOOL = "list_files"
        const val SEARCH_TOOL = "search_text"

        private val LOG = logger<EntryPointFinder>()

        private val KINDS = EntryPointKind.entries.map { it.name.lowercase() }

        /** Accept integral JSON numbers such as 12.0, but never round fractional or overflowing locations. */
        private fun positiveLine(value: com.google.gson.JsonElement?): Int? {
            if (value == null || !value.isJsonPrimitive) return null
            return try {
                value.asString.trim().toBigDecimalOrNull()?.intValueExact()?.takeIf { it > 0 }
            } catch (e: ArithmeticException) {
                LOG.debug("Ignoring out-of-range line number", e)
                null
            }
        }

        /** Numeric strings are tolerated for local models; required fields and enums are checked. */
        internal fun validationErrors(json: JsonObject): List<String> = buildList {
            fun text(o: JsonObject, key: String): Boolean = o.get(key)?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } == true
            if (!text(json, "summary")) add("summary must be a string")
            for (name in listOf("entry_points", "files")) {
                val array = json.get(name)
                if (array == null || !array.isJsonArray) { add("$name must be an array"); continue }
                array.asJsonArray.forEachIndexed { i, element ->
                    val o = element.takeIf { it.isJsonObject }?.asJsonObject
                    if (o == null) { add("$name[$i] must be an object"); return@forEachIndexed }
                    val fields = if (name == "files") listOf("path", "note") else listOf("path", "symbol", "kind", "title", "description")
                    fields.filterNot { text(o, it) }.forEach { add("$name[$i].$it must be a string") }
                    if (name == "entry_points") {
                        if (o.jsonText("kind") !in KINDS) add("entry_points[$i].kind must be one of ${KINDS.joinToString()}")
                        if (positiveLine(o.get("line")) == null) add("entry_points[$i].line must be a positive integer")
                        val confidence = o.get("confidence")?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()
                        if (confidence == null || !confidence.isFinite() || confidence !in 0.0..1.0) add("entry_points[$i].confidence must be between 0 and 1")
                    }
                }
            }
        }

        val SCHEMA: JsonObject = JsonSchemas.obj(
            "summary" to JsonSchemas.string(),
            "entry_points" to JsonSchemas.array(JsonSchemas.obj(
                "path" to JsonSchemas.string(), "line" to JsonSchemas.integer().apply { addProperty("minimum", 1) }, "symbol" to JsonSchemas.string(),
                "kind" to JsonSchemas.enum(KINDS), "title" to JsonSchemas.string(), "description" to JsonSchemas.string(),
                "confidence" to JsonObject().apply { addProperty("type", "number"); addProperty("minimum", 0); addProperty("maximum", 1) },
            )),
            "files" to JsonSchemas.array(JsonSchemas.obj("path" to JsonSchemas.string(), "note" to JsonSchemas.string())),
        )

        private val ANSWER_FORMAT = PromptTemplates.render("entrypoint-answer.md", mapOf("kinds" to KINDS.joinToString("|")))

        fun systemPrompt(maxFileReads: Int, maxSourceLines: Int = 1_600, maxSourceChars: Int = 32_000) =
            PromptTemplates.render("entrypoint-system.md", mapOf(
                "maxFileReads" to maxFileReads.toString(),
                "maxSourceLines" to maxSourceLines.toString(),
                "maxSourceChars" to maxSourceChars.toString(),
                "CODE_AS_DATA" to CODE_AS_DATA,
                "ANSWER_FORMAT" to ANSWER_FORMAT,
            ))

        private val CONVERT_PROMPT = PromptTemplates.render(
            "entrypoint-convert.md", mapOf("CODE_AS_DATA" to CODE_AS_DATA, "ANSWER_FORMAT" to ANSWER_FORMAT),
        )

        fun userPrompt(tree: String, hints: List<EntryPointHint>, known: List<FileNote>, changed: List<String>) = buildString {
            append("File tree (folders end with /; \"…\" summarizes files not listed, which list_files can show):\n")
            append(tree).append('\n')
            if (hints.isNotEmpty()) {
                append("\n").append(PromptTemplates.load("entrypoint-hints.md")).append('\n')
                hints.forEach { append("- ").append(it.text).append('\n') }
            }
            if (known.isNotEmpty()) {
                append("\n").append(PromptTemplates.load("entrypoint-known.md")).append('\n')
                known.forEach { note ->
                    append("- ").append(note.path).append(": ").append(note.note.ifEmpty { "no note" })
                    if (note.entryPoints.isNotEmpty()) append(" Entry points: ")
                        .append(note.entryPoints.joinToString("; ") { "${it.symbol.ifEmpty { it.title }} at line ${it.line} (${it.kind.name.lowercase()}): ${it.description}" })
                    append('\n')
                }
            }
            if (changed.isNotEmpty()) {
                append("\n").append(PromptTemplates.load("entrypoint-changed.md")).append('\n')
                changed.forEach { append("- ").append(it).append('\n') }
            }
            append("\nFind the entry points.")
        }

        fun digest(vararg parts: String): String = Hashing.sha256(*parts)
    }
}
