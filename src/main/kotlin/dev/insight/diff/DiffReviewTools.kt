package dev.insight.diff

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.insight.chat.ChatTool
import dev.insight.chat.ToolInputException
import dev.insight.chat.SourcePoint
import dev.insight.chat.sourcePointTool
import dev.insight.llm.LlmTool

/** One reply's view of a tab. The captured revision is checked by the context, never echoed by the model. */
interface DiffReviewContext {
    suspend fun pointToCode(point: SourcePoint): String = throw ToolInputException("Source locations are unavailable")
    /** One size-bounded page; [patchBudget] inlines whole patches that also fit the serialized result. */
    suspend fun state(offset: Int, patchBudget: Int = 0): String
    suspend fun read(paths: List<String>, offset: Int): String
    suspend fun addNotes(notes: List<DiffNote>): String
    suspend fun showSource(range: DiffSourceRange): String =
        throw ToolInputException("Source blocks are unavailable")
    suspend fun symbols(paths: List<String>, query: String, offset: Int): String =
        throw ToolInputException("Symbol discovery is unavailable")
    /** Files in scope without a note; empty once the captured evidence is stale. */
    suspend fun uncovered(): List<String>

    /** Builds the change-flow graph from source, shows it on the canvas and returns its first page. Not a model tool. */
    suspend fun buildFlows(): String = throw ToolInputException("Change flows are unavailable")
    suspend fun flows(offset: Int): String = throw ToolInputException("Change flows are unavailable")
    suspend fun readFlowNodes(ids: List<Int>): String = throw ToolInputException("Change flows are unavailable")
    suspend fun annotateFlows(update: FlowAnnotationUpdate): String = throw ToolInputException("Change flows are unavailable")
    /** Null when there is no current graph, as after the snapshot changed. */
    suspend fun flowsUncovered(): FlowCoverage? = null

    suspend fun startTopicSummaries(): ChangeSummaries = throw ToolInputException("Topic summaries are unavailable")
    suspend fun summarizeTopics(entries: Map<Int, String>): ChangeSummaries = throw ToolInputException("Topic summaries are unavailable")

    suspend fun startTopics(): ChangeTopics = throw ToolInputException("Change topics are unavailable")
    suspend fun assignTopic(path: String, label: String): ChangeTopics = throw ToolInputException("Change topics are unavailable")

    /** Starts a /summaries turn, restarting a complete tree, and shows it on the canvas. Not a model tool. */
    suspend fun startSummaries(): ChangeSummaries = throw ToolInputException("Change summaries are unavailable")
    suspend fun summaries(offset: Int): String = throw ToolInputException("Change summaries are unavailable")
    /** Saves summaries written by [ChangeSummarizer] and returns the tree with them. Not a model tool. */
    suspend fun summarize(entries: Map<Int, String>): ChangeSummaries = throw ToolInputException("Change summaries are unavailable")
}

fun diffReviewTools(context: DiffReviewContext): List<ChatTool> {
    fun integer(args: JsonObject, name: String, default: Int): Int {
        val value = args[name] ?: return default
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber || !value.asString.matches(Regex("0|[1-9][0-9]*")))
            throw ToolInputException("$name must be a nonnegative integer")
        return value.asString.toIntOrNull() ?: throw ToolInputException("$name is too large")
    }
    fun string(args: JsonObject, name: String, prefix: String = ""): String {
        val value = args[name]
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString || value.asString.isBlank())
            throw ToolInputException("$prefix$name must be a nonempty string")
        return value.asString
    }
    fun paths(value: JsonElement?, max: Int, prefix: String = ""): List<String> {
        if (value == null || !value.isJsonArray || value.asJsonArray.size() !in 1..max)
            throw ToolInputException("${prefix}paths must contain 1 to $max file paths")
        return value.asJsonArray.map { path ->
            if (!path.isJsonPrimitive || !path.asJsonPrimitive.isString || path.asString.isBlank())
                throw ToolInputException("${prefix}each path must be a nonempty string")
            path.asString
        }
    }
    fun tool(name: String, description: String, properties: String, required: String, call: suspend (JsonObject) -> String) = object : ChatTool {
        override val definition = LlmTool(name, description, JsonParser.parseString(
            """{"type":"object","properties":{$properties},"required":[$required],"additionalProperties":false}"""
        ).asJsonObject)
        override suspend fun call(arguments: JsonObject): String = call.invoke(arguments)
    }
    fun pathList(max: Int) = """{"type":"array","minItems":1,"maxItems":$max,"items":{"type":"string"},"description":"File paths exactly as listed in the snapshot."}"""
    return listOf(
        sourcePointTool(diff = true, show = context::pointToCode),
        tool("get_current_diff", "List the changed files under review (up to $DIFF_PAGE_SIZE per page), the user's selected files and existing canvas notes. " +
            "Selected paths and note excerpts may be omitted to fit the result limit; counts include omitted entries. " +
            "If next_offset is present, call again with it to get the next page.",
            """"offset":{"type":"integer","minimum":0,"description":"Start of the page; use next_offset from the previous page."}""", "") {
            context.state(integer(it, "offset", 0))
        },
        tool("read_diff_files", "Read the unified diffs of 1–$MAX_READ_PATHS files, including removed lines. A long patch returns next_offset: " +
            "call again with only that path and offset to continue. Files in not_read did not fit in this result; request them again.",
            """"paths":${pathList(MAX_READ_PATHS)},""" +
                """"offset":{"type":"integer","minimum":0,"description":"Only with a single path: continue its patch from next_offset."}""",
            """"paths"""") {
            val paths = paths(it["paths"], MAX_READ_PATHS)
            val offset = integer(it, "offset", 0)
            if (offset > 0 && paths.size > 1) throw ToolInputException("offset requires exactly one path")
            context.read(paths, offset)
        },
        tool("find_diff_symbols", "Find current-source declarations in 1–10 included files. Returns exact selectors, kinds and lines for add_diff_notes. " +
            "Deleted, unavailable or edited-since-snapshot source cannot be resolved. Use next_offset to continue; query optionally filters names.",
            """"paths":${pathList(10)},"query":{"type":"string","minLength":1},"offset":{"type":"integer","minimum":0}""", """"paths"""") {
            context.symbols(paths(it["paths"], 10), if (it.has("query")) string(it, "query") else "", integer(it, "offset", 0))
        },
        tool("show_diff_source", "Show a separate read-only source code block on the canvas for an included file. " +
            "Uses exact snapshot source with syntax highlighting and original line numbers, never model-authored code. " +
            "Lines are one-based and inclusive (maximum 200 lines / 20000 characters). side defaults to after; " +
            "use before for removed code. Repeating the same path, side and range reuses its card. Does not count as an explanatory note.",
            """"path":{"type":"string","minLength":1},"start_line":{"type":"integer","minimum":1},"end_line":{"type":"integer","minimum":1},"side":{"type":"string","enum":["before","after"]}""",
            """"path","start_line","end_line"""") {
            val start = integer(it, "start_line", 0)
            val end = integer(it, "end_line", 0)
            if (start < 1 || end < start || end.toLong() - start >= 200)
                throw ToolInputException("Use an inclusive range of 1–200 lines, starting at line 1 or later.")
            val side = if (it.has("side")) string(it, "side") else "after"
            if (side !in setOf("before", "after")) throw ToolInputException("side must be before or after")
            context.showSource(DiffSourceRange(string(it, "path"), start, end, side))
        },
        tool("add_diff_notes", "Show 1–$MAX_NOTES_PER_CALL notes on the canvas next to the chat, to explain or illustrate an answer. Each note is attached to " +
            "one file or a group of related files. Optionally attach a symbol (function, class, property or other declaration) from those files. " +
            "Symbols are validated and get a small diagram of static source relationships. Use find_diff_symbols for exact selectors or ambiguous names. " +
            "A note for the same paths and symbol replaces the old one; different symbols coexist. Only describe diffs you have read.",
            """"notes":{"type":"array","minItems":1,"maxItems":$MAX_NOTES_PER_CALL,"items":{"type":"object","properties":{""" +
                """"paths":${pathList(30)},""" +
                """"text":{"type":"string","minLength":1,"maxLength":2000,"description":"A short explanation, usually 1–3 sentences."},""" +
                """"symbol":{"type":"string","minLength":1,"maxLength":500,"description":"Optional declaration name, qualified name, or exact selector from find_diff_symbols. Must resolve uniquely within paths in current source."}""" +
                """},"required":["paths","text"],"additionalProperties":false}}""",
            """"notes"""") { args ->
            val notes = args["notes"]
            if (notes == null || !notes.isJsonArray || notes.asJsonArray.size() !in 1..MAX_NOTES_PER_CALL)
                throw ToolInputException("notes must contain 1 to $MAX_NOTES_PER_CALL notes")
            val parsed = notes.asJsonArray.mapIndexed { index, value ->
                val prefix = if (notes.asJsonArray.size() > 1) "Note ${index + 1}: " else ""
                if (!value.isJsonObject) throw ToolInputException("${prefix}each note must be an object")
                val note = value.asJsonObject
                val text = string(note, "text", prefix)
                if (text.length > 2000) throw ToolInputException("${prefix}text is too long")
                val symbol = if (note.has("symbol")) string(note, "symbol", prefix).trim() else null
                if (symbol != null && symbol.length > 500) throw ToolInputException("${prefix}symbol is too long")
                DiffNote(paths(note["paths"], 30, prefix), text, symbol)
            }
            context.addNotes(parsed)
        },
    )
}

/** Flow authoring belongs only to an explicit /flows turn, never to ordinary review chat. */
internal fun diffChatTools(context: DiffReviewContext, flows: Boolean = false): List<ChatTool> =
    diffReviewTools(context) + changeSummaryTools(context) + changeFlowTools(context).filter {
        flows || it.definition.name != "annotate_change_flows"
    }

internal const val DIFF_PAGE_SIZE = 50
internal const val MAX_READ_PATHS = 10
internal const val MAX_NOTES_PER_CALL = 20

/** Patches stay readable: the default Gson escapes `<`, `>`, `=`, `&` and `'` as < etc. */
internal val reviewJson = GsonBuilder().disableHtmlEscaping().create()

internal fun isDescriptionRequest(text: String): Boolean =
    text.trim().lowercase().let { it == "description" || it == "/description" || it.startsWith("/description ") }

/** Sent back when a description reply leaves files without a note. */
internal fun coverageNote(uncovered: List<String>, total: Int): String {
    val listed = uncovered.take(30).joinToString(", ") + if (uncovered.size > 30) " and ${uncovered.size - 30} more" else ""
    return "Coverage check: ${uncovered.size} of $total files still have no note: $listed. " +
        "Read them and add notes. If a file cannot be described, say which and why in your reply."
}

internal fun diffReviewPrompt() = dev.insight.chat.visualCodeAssistantPrompt + "\n\n" + """
    You help a developer understand the changes under review using the available snapshot tools.
    Use existing summaries and change flows to navigate, then verify claims against snapshot source.
    /summaries, /topics and /flows generate the corresponding views; flow annotation is available only for /flows.

    The comparison identifies the repository and revisions. Before and after refer to those snapshot sides;
    a historical after-side is not the working tree. Use the corresponding side's line numbers.
    Use before for removed code and include the side when citing a diff location. Symbol discovery is available
    only where current project source matches the snapshot after-side; otherwise use snapshot source locations.
""".trimIndent()

/** Expands `/description [focus]` for the current turn only, so other questions keep a general prompt. */
internal fun descriptionRequest(text: String): String {
    val focus = text.trim().substringAfter(" ", "").trim()
    return "Describe all changes as notes on the canvas: one note per file, or per group of files that make one change, " +
        "such as code and its test. Read each file before describing it. " +
        "For changes centered on a declaration, attach its symbol so the canvas shows its verified relationships. " +
        "Use separate symbol notes when distinct changed declarations need separate explanations; keep file-wide notes for broader changes. " +
        "Keep each explanation focused on one change and avoid repeating the same context across cards. " +
        "Then reply with one short summary line." +
        if (focus.isEmpty()) "" else "\nFocus: $focus"
}
