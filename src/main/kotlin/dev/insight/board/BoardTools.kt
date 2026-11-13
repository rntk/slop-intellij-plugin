package dev.insight.board

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.insight.agent.CallAllowance
import dev.insight.agent.ChatTool
import dev.insight.agent.LimitedTool
import dev.insight.agent.ToolInputException
import dev.insight.agent.ToolRegistry
import dev.insight.agent.lenientInt
import dev.insight.agent.objectsArg
import dev.insight.agent.optionalStringArg
import dev.insight.agent.stringArg
import dev.insight.chat.visualCodeAssistantPrompt
import dev.insight.llm.JsonSchemas
import dev.insight.llm.LlmTool
import dev.insight.prompts.PromptTemplates

/*
 * The tools a board's chat builds and refines an explainer board with. Prose blocks hold the model's text; code, diff,
 * symbol and chain blocks hold only what [BoardSources] resolved, so the model chooses evidence but never writes it.
 * Every mutation runs inside the host's `edit` (the EDT in the IDE). PSI-free and Swing-free.
 */

/**
 * The board tools by topic, for a chat that starts with [BOARD_CHAT_TOOLS] and loads the rest with search_tools. Adding
 * tools share an allowance of [maxAdds] blocks per reply; create the registry once per reply. add_diff is offered only
 * when [sources] has a comparison.
 */
fun boardToolRegistry(model: BoardModel, sources: BoardSources, edit: suspend (() -> Unit) -> Unit = { it() },
    maxAdds: Int = BoardLimits.ADDS_PER_REPLY): ToolRegistry {
    val adds = CallAllowance(maxAdds, "This reply has added $maxAdds blocks, the most one reply may add. Finish with the " +
        "blocks already on the board; update_block, connect, move_block and remove_block still work.")
    val board = BoardToolContext(model, sources, edit, adds)
    fun limited(vararg tools: ChatTool): List<ChatTool> = tools.map { LimitedTool(it, adds) }
    val evidence = limited(AddCodeTool(board)) + (if (sources.hasComparison) limited(AddDiffTool(board)) else emptyList()) +
        limited(AddSymbolTool(board), AddChainTool(board))
    return ToolRegistry()
        .register(BOARD_TOPIC_READING, BoardOverviewTool(board))
        .register(BOARD_TOPIC_EVIDENCE, evidence)
        .register(BOARD_TOPIC_EXPLANATION, limited(AddHeadingTool(board), AddTextTool(board), AddStepsTool(board),
            AddTableTool(board), AddGroupTool(board)) + listOf(ConnectTool(board), DisconnectTool(board)))
        .register(BOARD_TOPIC_EDITING, SetTitleTool(board), UpdateBlockTool(board), MoveBlockTool(board), RemoveBlockTool(board))
}

/** Every board tool of [boardToolRegistry], in registration order. */
fun boardTools(model: BoardModel, sources: BoardSources, edit: suspend (() -> Unit) -> Unit = { it() },
    maxAdds: Int = BoardLimits.ADDS_PER_REPLY): List<ChatTool> = boardToolRegistry(model, sources, edit, maxAdds).tools

/** The board tools a turn starts with; the others load through search_tools. */
val BOARD_CHAT_TOOLS: List<String> = listOf("board_overview", "add_text", "add_code", "add_heading")

internal const val BOARD_TOPIC_READING = "reading the board"
internal const val BOARD_TOPIC_EVIDENCE = "adding evidence: code, diffs, symbols and call chains"
internal const val BOARD_TOPIC_EXPLANATION = "adding explanation: headings, prose, steps, tables, groups and arrows"
internal const val BOARD_TOPIC_EDITING = "editing blocks"

/** The board chat's system prompt: the shared assistant policy, then the board's own, for a comparison or project code. */
fun boardChatSystemPrompt(hasComparison: Boolean): String = visualCodeAssistantPrompt + "\n\n" +
    PromptTemplates.render("board-chat-system.md", mapOf("SOURCES" to if (hasComparison) COMPARISON_SOURCES else PROJECT_SOURCES))

private const val COMPARISON_SOURCES = "The board explains a comparison. Anchors read the after side unless side is " +
    "\"before\"; add_diff shows the patch of a file, optionally only the hunks touching after-side lines."
private const val PROJECT_SOURCES = "The board explains current project code: anchors read files as they are now; there " +
    "is no before side and no diff."

/** The board as the chat's context snapshot: its outline, then how many more blocks fit. */
fun boardChatContext(model: BoardModel): String = describeBoard(model.board).trimEnd() + "\n" + blocksNote(model.board)

private fun blocksNote(board: Board): String {
    val left = (BoardLimits.MAX_BLOCKS - board.blocks.size).coerceAtLeast(0)
    return "${board.blocks.size} of ${BoardLimits.MAX_BLOCKS} blocks used; $left more fit, and one reply may add up to " +
        "${BoardLimits.ADDS_PER_REPLY}."
}

/** Shared state of one reply's board tools: the model, its sources, the host's edit wrapper and the add allowance. */
private class BoardToolContext(val model: BoardModel, val sources: BoardSources, val edit: suspend (() -> Unit) -> Unit,
    val adds: CallAllowance) {
    val sides: Boolean get() = sources.hasComparison

    /** Runs [change] inside the host's edit, reporting invariant violations to the model. */
    suspend fun <T> mutate(change: BoardModel.() -> T): T {
        var outcome: Result<T>? = null
        edit { outcome = runCatching { model.change() } }
        val result = outcome ?: throw IllegalStateException("The board edit did not run.")
        return result.getOrElse { e -> throw if (e is BoardEditException) ToolInputException(e.message ?: "Invalid board edit.") else e }
    }

    /** Adds the block [create] makes with a fresh id of [prefix], before [before] or at the end. */
    suspend fun add(prefix: String, before: String?, create: (String) -> Block): Block = mutate { add(create(newId(prefix)), before) }

    fun block(id: String): Block = model.block(id) ?: throw ToolInputException("No block '$id' is on the board; board_overview lists the ids.")

    /** The anchor of [obj]'s path and lines, null without a path unless [required]. */
    fun anchor(obj: JsonObject, prefix: String = "", required: Boolean = false): BoardAnchor? {
        val path = obj.optionalStringArg("path", prefix)?.trim()
        if (path == null) {
            if (required) throw ToolInputException("${prefix}path must be a nonempty string")
            if (obj.present("first_line") || obj.present("last_line")) throw ToolInputException("${prefix}lines need a path")
            return null
        }
        val first = obj.lenientInt("first_line", "${prefix}first_line") ?: throw ToolInputException("${prefix}first_line is required with a path")
        val last = obj.lenientInt("last_line", "${prefix}last_line") ?: first
        if (first < 1 || last < first) throw ToolInputException("${prefix}lines must be 1-based with first_line not after last_line")
        return BoardAnchor(path, first, last, side(obj, prefix))
    }

    private fun side(obj: JsonObject, prefix: String): BoardSide = when (obj.optionalStringArg("side", prefix)?.trim()?.lowercase()) {
        null, "after", "current" -> BoardSide.AFTER
        "before" -> if (sides) BoardSide.BEFORE else throw ToolInputException("${prefix}side \"before\" needs a comparison; this board explains current code.")
        else -> throw ToolInputException("${prefix}side must be after or before")
    }

    /** A link anchor checked against the sources: the lines actually read. */
    suspend fun link(anchor: BoardAnchor?): BoardAnchor? = anchor?.let { sources.read(it).anchor }
}

private abstract class BoardTool(name: String, description: String, parameters: JsonObject) : ChatTool {
    final override val definition = LlmTool(name, description, parameters)
}

// --- shared argument schemas ---

private val beforeParameter = "before" to JsonSchemas.string(description = "Id of the block to insert before; default: the end of the board.")

private fun widthParameter(default: BlockWidth) = "width" to JsonSchemas.enum(BlockWidth.entries.map { it.name.lowercase() },
    "Columns it spans: narrow (one), wide (two) or full (the whole row); default ${default.name.lowercase()}.")

private val toneParameter = "tone" to JsonSchemas.enum(Tone.entries.map { it.name.lowercase() },
    "Shows the prose as a callout: note, insight, warning or question; omit for plain explanation.")

private val titleParameter = "title" to JsonSchemas.string(description = "Short title shown above the block.")

private fun anchorParameters(sides: Boolean, what: String, required: Boolean = false): Array<Pair<String, JsonObject>> = listOfNotNull(
    "path" to JsonSchemas.string(description = "Project-relative path of the file $what" + if (required) "." else "; omit for none."),
    "first_line" to JsonSchemas.integer(description = "First 1-based line" + if (required) "." else ", with path."),
    "last_line" to JsonSchemas.integer(description = "Last line, inclusive (default first_line)."),
    if (sides) "side" to JsonSchemas.enum(listOf("after", "before"), "Which side of the comparison the lines are on (default after).") else null,
).toTypedArray()

private fun annotationsParameter() = "annotations" to JsonSchemas.array(JsonSchemas.obj(
    "line" to JsonSchemas.integer(description = "1-based line within the block's lines."),
    "text" to JsonSchemas.string(description = "Why this line matters, in a few words."),
    "severity" to JsonSchemas.enum(Severity.entries.map { it.name.lowercase() }, "info (default), warning or error."),
    required = listOf("line", "text"), additionalProperties = false,
), maxItems = MAX_ANNOTATIONS, description = "Margin notes on lines that matter.")

private fun stepsParameter(sides: Boolean) = "steps" to JsonSchemas.array(JsonSchemas.obj(
    "text" to JsonSchemas.string(description = "What happens in this step, in a sentence or two."),
    *anchorParameters(sides, "the step points at"),
    required = listOf("text"), additionalProperties = false,
), minItems = 1, maxItems = MAX_STEPS, description = "The steps in order.")

private val columnsParameter = "columns" to JsonSchemas.array(JsonSchemas.string(), minItems = 1, maxItems = MAX_COLUMNS,
    description = "Column headers.")

private fun rowsParameter(sides: Boolean) = "rows" to JsonSchemas.array(JsonSchemas.array(JsonSchemas.obj(
    "text" to JsonSchemas.string(description = "Cell text."),
    *anchorParameters(sides, "the cell links to"),
    required = listOf("text"), additionalProperties = false,
)), minItems = 1, maxItems = MAX_ROWS, description = "Rows of cells, as many cells as columns; a cell with path links to code.")

// --- shared argument decoding ---

private fun JsonObject.present(name: String) = has(name) && !get(name).isJsonNull

/** A block id, tolerating the brackets board_overview prints around ids. */
private fun cleanId(value: String) = value.trim().removePrefix("[").removeSuffix("]").trim()

private fun JsonObject.idArg(name: String): String = cleanId(stringArg(name))

private fun JsonObject.optionalIdArg(name: String): String? = optionalStringArg(name)?.let(::cleanId)

/** Trimmed text of at most [max] characters. */
private fun JsonObject.textArg(name: String, max: Int, prefix: String = ""): String {
    val text = stringArg(name, prefix).trim()
    if (text.length > max) throw ToolInputException("$prefix$name has ${text.length} characters; keep it within $max or split it.")
    return text
}

private fun JsonObject.optionalTextArg(name: String, max: Int): String? = if (present(name)) textArg(name, max) else null

/** For updates: null when absent, "" to clear, else the trimmed text. */
private fun JsonObject.clearableTextArg(name: String, max: Int): String? {
    if (!present(name)) return null
    val value = get(name)
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) throw ToolInputException("$name must be a string")
    val text = value.asString.trim()
    if (text.length > max) throw ToolInputException("$name has ${text.length} characters; keep it within $max.")
    return text
}

private inline fun <reified E : Enum<E>> JsonObject.enumArg(name: String, prefix: String = ""): E? {
    val text = optionalStringArg(name, prefix)?.trim() ?: return null
    return enumValues<E>().firstOrNull { it.name.equals(text, ignoreCase = true) }
        ?: throw ToolInputException("$prefix$name must be one of ${enumValues<E>().joinToString { it.name.lowercase() }}")
}

private fun JsonObject.toneArg(): Tone? = enumArg<Tone>("tone")

private fun JsonObject.widthArg(): BlockWidth? = enumArg<BlockWidth>("width")

private fun JsonObject.annotationsArg(): List<Annotation> {
    val value = get("annotations")?.takeUnless { it.isJsonNull } ?: return emptyList()
    if (value.isJsonArray && value.asJsonArray.isEmpty) return emptyList()
    return objectsArg(value, "annotations", "annotation", MAX_ANNOTATIONS) { obj, prefix ->
        val line = obj.lenientInt("line", "${prefix}line") ?: throw ToolInputException("${prefix}line is required")
        Annotation(line, obj.textArg("text", MAX_ANNOTATION_CHARS, prefix), obj.enumArg<Severity>("severity", prefix) ?: Severity.INFO)
    }
}

/** Throws unless every annotation is on a line of [anchor], the lines actually shown. */
private fun checkAnnotations(annotations: List<Annotation>, anchor: BoardAnchor) {
    annotations.withIndex().firstOrNull { it.value.line !in anchor.first..anchor.last }?.let { (index, a) ->
        val which = if (annotations.size > 1) "Annotation ${index + 1}: line" else "Annotation line"
        throw ToolInputException("$which ${a.line} is outside the lines shown, $anchor; annotate lines ${anchor.first}-${anchor.last}.")
    }
}

private suspend fun BoardToolContext.stepsArg(value: JsonElement?): List<Step> {
    val decoded = objectsArg(value, "steps", "step", MAX_STEPS) { obj, prefix -> obj.textArg("text", MAX_STEP_CHARS, prefix) to anchor(obj, prefix) }
    return decoded.map { (text, anchor) -> Step(text, link(anchor)) }
}

private fun columnsArg(value: JsonElement?): List<String> {
    if (value == null || !value.isJsonArray || value.asJsonArray.size() !in 1..MAX_COLUMNS)
        throw ToolInputException("columns must contain 1 to $MAX_COLUMNS headers")
    return value.asJsonArray.map { c ->
        if (!c.isJsonPrimitive || !c.asJsonPrimitive.isString || c.asString.isBlank()) throw ToolInputException("each column must be a nonempty string")
        c.asString.trim().also { if (it.length > MAX_TITLE_CHARS) throw ToolInputException("column '$it' is too long; keep headers short") }
    }
}

private suspend fun BoardToolContext.rowsArg(value: JsonElement?, columns: Int): List<List<Cell>> {
    if (value == null || !value.isJsonArray || value.asJsonArray.size() !in 1..MAX_ROWS)
        throw ToolInputException("rows must contain 1 to $MAX_ROWS rows")
    return value.asJsonArray.mapIndexed { r, row ->
        if (!row.isJsonArray) throw ToolInputException("Row ${r + 1}: each row must be an array of cells")
        if (row.asJsonArray.size() != columns) throw ToolInputException("Row ${r + 1}: has ${row.asJsonArray.size()} cells; every row needs $columns, like the columns.")
        row.asJsonArray.mapIndexed { c, cell ->
            val prefix = "Row ${r + 1} cell ${c + 1}: "
            when {
                cell.isJsonPrimitive && cell.asJsonPrimitive.isString -> Cell(cell.asString.trim().also {
                    if (it.length > MAX_CELL_CHARS) throw ToolInputException("${prefix}text is too long; keep cells within $MAX_CELL_CHARS characters.")
                })
                cell.isJsonObject -> {
                    val obj = cell.asJsonObject
                    val text = obj.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                        ?: throw ToolInputException("${prefix}text must be a string")
                    if (text.length > MAX_CELL_CHARS) throw ToolInputException("${prefix}text is too long; keep cells within $MAX_CELL_CHARS characters.")
                    Cell(text, link(anchor(obj, prefix)))
                }
                else -> throw ToolInputException("${prefix}a cell must be a string or an object with text")
            }
        }
    }
}

private fun childrenArg(value: JsonElement?): List<String> {
    if (value == null || !value.isJsonArray || value.asJsonArray.size() !in 1..MAX_CHILDREN)
        throw ToolInputException("children must contain 1 to $MAX_CHILDREN block ids")
    return value.asJsonArray.map { c ->
        if (!c.isJsonPrimitive || !c.asJsonPrimitive.isString || c.asString.isBlank()) throw ToolInputException("each child must be a block id")
        cleanId(c.asString)
    }
}

// --- reporting ---

private fun kindName(block: Block): String = when (block) {
    is HeadingBlock -> "heading"
    is TextBlock -> if (block.tone != null) "${block.tone.name.lowercase()} callout" else "text block"
    is CodeBlock -> if (block.kind == CodeKind.DIFF) "diff block" else "code block"
    is SymbolBlock -> "symbol block"
    is ChainBlock -> "chain block"
    is StepsBlock -> "steps block"
    is TableBlock -> "table"
    is GroupBlock -> "group"
}

private fun clip(text: String, max: Int): String {
    val line = text.replace(Regex("\\s+"), " ").trim()
    return if (line.length <= max) line else line.take(max - 1).trimEnd() + "…"
}

private fun firstLine(text: String): String = text.lineSequence().firstOrNull { it.isNotBlank() }?.let { clip(it, 100) } ?: "(blank)"

private fun where(before: String?) = if (before == null) "" else " before [$before]"

private fun notices(vararg notices: String?): String = notices.filterNotNull().joinToString("") { "\nNotice: $it" }

private fun symbolLabel(s: ResolvedSymbol): String =
    "node ${s.node}: ${s.title}" + listOfNotNull(s.meta, s.anchor?.toString()).joinToString(" · ").let { if (it.isEmpty()) "" else " ($it)" }

// --- reading ---

private class BoardOverviewTool(private val board: BoardToolContext) : BoardTool(
    "board_overview",
    "Read the whole board as an outline in layout order: each block's id, kind, title, anchor and the start of its text, " +
        "groups with their blocks, and arrows. Read-only; call it before adding when the board may have changed.",
    JsonSchemas.obj(required = emptyList(), additionalProperties = false),
) {
    override suspend fun call(arguments: JsonObject): String {
        val current = board.model.board
        val left = (board.adds.max - board.adds.used).coerceAtLeast(0)
        return describeBoard(current).trimEnd() + "\n" + blocksNote(current) + " This reply may add $left more."
    }
}

// --- evidence ---

private class AddCodeTool(private val board: BoardToolContext) : BoardTool(
    "add_code",
    "Add a code block showing real source lines, read by the host, with optional margin annotations on the lines that " +
        "matter. Use it whenever the reader should see code; never paste code into prose. At most " +
        "${BoardLimits.MAX_CODE_LINES} lines. Returns the block id and the lines actually read.",
    JsonSchemas.obj(
        *anchorParameters(board.sides, "to show", required = true),
        titleParameter, annotationsParameter(), widthParameter(BlockWidth.WIDE), beforeParameter,
        required = listOf("path", "first_line", "last_line"), additionalProperties = false,
    ),
) {
    override suspend fun call(arguments: JsonObject): String {
        val requested = board.anchor(arguments, required = true)!!
        val clamped = if (requested.lineCount > BoardLimits.MAX_CODE_LINES) requested.copy(last = requested.first + BoardLimits.MAX_CODE_LINES - 1) else requested
        val title = arguments.optionalTextArg("title", MAX_TITLE_CHARS)
        val annotations = arguments.annotationsArg()
        val width = arguments.widthArg() ?: BlockWidth.WIDE
        val before = arguments.optionalIdArg("before")
        val resolved = board.sources.read(clamped)
        checkAnnotations(annotations, resolved.anchor)
        val block = board.add("c", before) { CodeBlock(it, resolved.anchor, resolved.text, CodeKind.SOURCE, title, annotations, width) }
        return "Added code block [${block.id}]${where(before)} showing ${resolved.anchor} (${resolved.anchor.lineCount} lines), " +
            "starting: ${firstLine(resolved.text)}" + (if (annotations.isNotEmpty()) "\nWith ${annotations.size} annotation(s)." else "") +
            notices(if (clamped != requested) "$requested is longer than a code block shows; it shows ${clamped.first}-${clamped.last}." else null,
                resolved.notice)
    }
}

private class AddDiffTool(private val board: BoardToolContext) : BoardTool(
    "add_diff",
    "Add a diff block showing the real patch of a file in the comparison, whole or only the hunks touching after-side " +
        "lines first_line..last_line, with optional annotations on after-side lines. Returns the block id and the lines shown.",
    JsonSchemas.obj(
        "path" to JsonSchemas.string(description = "Project-relative path of a file in the comparison."),
        "first_line" to JsonSchemas.integer(description = "Only hunks touching after-side lines from this one (default: the whole patch)."),
        "last_line" to JsonSchemas.integer(description = "Last after-side line, inclusive (default first_line)."),
        titleParameter, annotationsParameter(), widthParameter(BlockWidth.WIDE), beforeParameter,
        required = listOf("path"), additionalProperties = false,
    ),
) {
    override suspend fun call(arguments: JsonObject): String {
        val path = arguments.stringArg("path").trim()
        val first = arguments.lenientInt("first_line")
        val last = arguments.lenientInt("last_line") ?: first
        if (first == null && last != null) throw ToolInputException("last_line needs first_line")
        if (first != null && (first < 1 || last!! < first)) throw ToolInputException("lines must be 1-based with first_line not after last_line")
        val title = arguments.optionalTextArg("title", MAX_TITLE_CHARS)
        val annotations = arguments.annotationsArg()
        val width = arguments.widthArg() ?: BlockWidth.WIDE
        val before = arguments.optionalIdArg("before")
        val resolved = board.sources.diff(path, first, last)
        checkAnnotations(annotations, resolved.anchor)
        val block = board.add("d", before) { CodeBlock(it, resolved.anchor, resolved.text, CodeKind.DIFF, title, annotations, width) }
        val rows = resolved.text.lineSequence().count()
        return "Added diff block [${block.id}]${where(before)} showing $rows patch rows of ${resolved.anchor}, starting: " +
            firstLine(resolved.text) + notices(resolved.notice)
    }
}

private val nodeParameter = "node" to JsonSchemas.integer(description = "Call graph node id, as listed when a name is ambiguous.")

/** The symbol [prefix]node or [prefix]name (optionally in [prefix]path) names; ambiguous names list their candidates. */
private suspend fun BoardToolContext.symbolArg(arguments: JsonObject, prefix: String = ""): ResolvedSymbol {
    val node = arguments.lenientInt("${prefix}node", nonnegative = true)
    if (node != null) return sources.symbol(node) ?: throw ToolInputException("The call graph has no node $node.")
    val name = arguments.optionalStringArg("${prefix}name")?.trim()?.substringBefore('(')?.takeIf { it.isNotEmpty() }
        ?: throw ToolInputException("Pass ${prefix}name or ${prefix}node.")
    val path = arguments.optionalStringArg("${prefix}path")?.trim()
    val found = sources.symbols(name, path)
    return when (found.size) {
        0 -> throw ToolInputException("The call graph has no symbol named '$name'${path?.let { " in $it" }.orEmpty()}. " +
            "Check the name with the source tools, or add the code with add_code.")
        1 -> found.single()
        else -> throw ToolInputException("'$name' matches ${found.size} call graph symbols; pass ${prefix}node with one of: " +
            found.take(MAX_CANDIDATES).joinToString("; ") { symbolLabel(it) } +
            if (found.size > MAX_CANDIDATES) "; and ${found.size - MAX_CANDIDATES} more (pass ${prefix}path to narrow)." else ".")
    }
}

private class AddSymbolTool(private val board: BoardToolContext) : BoardTool(
    "add_symbol",
    "Add a symbol block for a function or class from the call graph: its title, kind and place come from the graph, " +
        "its summary from enrichment unless you pass one. Name it, or pass node when a name is ambiguous.",
    JsonSchemas.obj(
        "name" to JsonSchemas.string(description = "The symbol's name without parameters, such as save or Store.save."),
        "path" to JsonSchemas.string(description = "Project-relative file that declares it, to narrow a common name."),
        nodeParameter,
        "summary" to JsonSchemas.string(description = "Your one-sentence summary; default: the enrichment summary, if any."),
        widthParameter(BlockWidth.NARROW), beforeParameter,
        required = emptyList(), additionalProperties = false,
    ),
) {
    override suspend fun call(arguments: JsonObject): String {
        val summary = arguments.optionalTextArg("summary", MAX_STEP_CHARS)
        val width = arguments.widthArg() ?: BlockWidth.NARROW
        val before = arguments.optionalIdArg("before")
        val symbol = board.symbolArg(arguments)
        val block = board.add("s", before) {
            SymbolBlock(it, symbol.node, symbol.title, symbol.meta, summary ?: symbol.summary, symbol.anchor, width)
        }
        return "Added symbol block [${block.id}]${where(before)} for ${symbolLabel(symbol)}." +
            ((summary ?: symbol.summary)?.let { "\nSummary${if (summary == null) " (enrichment)" else ""}: ${clip(it, 160)}" } ?: "")
    }
}

private class AddChainTool(private val board: BoardToolContext) : BoardTool(
    "add_chain",
    "Add a chain block with the shortest call path between two symbols, computed by the host from the call graph " +
        "(at most ${BoardLimits.MAX_CHAIN_STEPS} steps). Name each end, or pass its node id.",
    JsonSchemas.obj(
        "from_name" to JsonSchemas.string(description = "The caller end's name, such as main."),
        "from_path" to JsonSchemas.string(description = "File declaring from_name, to narrow it."),
        "from_node" to JsonSchemas.integer(description = "The caller end's node id, instead of from_name."),
        "to_name" to JsonSchemas.string(description = "The callee end's name, such as save."),
        "to_path" to JsonSchemas.string(description = "File declaring to_name, to narrow it."),
        "to_node" to JsonSchemas.integer(description = "The callee end's node id, instead of to_name."),
        titleParameter, widthParameter(BlockWidth.WIDE), beforeParameter,
        required = emptyList(), additionalProperties = false,
    ),
) {
    override suspend fun call(arguments: JsonObject): String {
        val title = arguments.optionalTextArg("title", MAX_TITLE_CHARS)
        val width = arguments.widthArg() ?: BlockWidth.WIDE
        val before = arguments.optionalIdArg("before")
        val from = board.symbolArg(arguments, "from_")
        val to = board.symbolArg(arguments, "to_")
        if (from.node == to.node) throw ToolInputException("from and to are the same symbol; use add_symbol for one symbol.")
        val chain = board.sources.chain(from.node, to.node)
        if (chain.steps.isEmpty()) throw ToolInputException("No call path from ${from.title} to ${to.title} in the call graph " +
            "within ${BoardLimits.MAX_CHAIN_STEPS} steps. Add them as separate symbols, or pick nearer ends.")
        val block = board.add("ch", before) { ChainBlock(it, chain.steps, title, chain.complete, width) }
        return "Added chain block [${block.id}]${where(before)} of ${chain.steps.size} steps: " +
            chain.steps.joinToString(" → ") { it.title } + if (chain.complete) "." else " (cut at the step budget)."
    }
}

// --- explanation ---

private class AddHeadingTool(private val board: BoardToolContext) : BoardTool(
    "add_heading",
    "Add a section heading spanning the board's width. Level 1 is the largest; use 2 for sections under it.",
    JsonSchemas.obj(
        "text" to JsonSchemas.string(description = "The heading text."),
        "level" to JsonSchemas.integer(minimum = 1, maximum = MAX_LEVEL, description = "1 to $MAX_LEVEL (default 1)."),
        beforeParameter,
        required = listOf("text"), additionalProperties = false,
    ),
) {
    override suspend fun call(arguments: JsonObject): String {
        val text = arguments.textArg("text", MAX_TITLE_CHARS)
        val level = levelArg(arguments) ?: 1
        val before = arguments.optionalIdArg("before")
        val block = board.add("h", before) { HeadingBlock(it, text, level) }
        return "Added heading [${block.id}]${where(before)}: $text"
    }
}

private fun levelArg(arguments: JsonObject): Int? = arguments.lenientInt("level")?.also {
    if (it !in 1..MAX_LEVEL) throw ToolInputException("level must be 1 to $MAX_LEVEL")
}

private class AddTextTool(private val board: BoardToolContext) : BoardTool(
    "add_text",
    "Add a prose block with your explanation, plain or as a callout in a tone. Shown as interpretation, not evidence. " +
        "Plain text with blank lines between paragraphs; no Markdown and no pasted source (add a code block instead).",
    JsonSchemas.obj(
        "text" to JsonSchemas.string(description = "The prose, at most ${BoardLimits.MAX_TEXT_CHARS} characters."),
        titleParameter, toneParameter, widthParameter(BlockWidth.NARROW), beforeParameter,
        required = listOf("text"), additionalProperties = false,
    ),
) {
    override suspend fun call(arguments: JsonObject): String {
        val text = arguments.textArg("text", BoardLimits.MAX_TEXT_CHARS)
        val title = arguments.optionalTextArg("title", MAX_TITLE_CHARS)
        val tone = arguments.toneArg()
        val width = arguments.widthArg() ?: BlockWidth.NARROW
        val before = arguments.optionalIdArg("before")
        val block = board.add("t", before) { TextBlock(it, text, title, tone, width) }
        return "Added ${kindName(block)} [${block.id}]${where(before)}: ${clip(title ?: text, 80)}"
    }
}

private class AddStepsTool(private val board: BoardToolContext) : BoardTool(
    "add_steps",
    "Add a numbered walkthrough you write; a step with path and lines links to that code. Use it for sequences such as " +
        "what happens on a request. Link anchors are checked against the source.",
    JsonSchemas.obj(
        titleParameter, stepsParameter(board.sides), widthParameter(BlockWidth.NARROW), beforeParameter,
        required = listOf("steps"), additionalProperties = false,
    ),
) {
    override suspend fun call(arguments: JsonObject): String {
        val title = arguments.optionalTextArg("title", MAX_TITLE_CHARS)
        val width = arguments.widthArg() ?: BlockWidth.NARROW
        val before = arguments.optionalIdArg("before")
        val steps = board.stepsArg(arguments.get("steps"))
        val block = board.add("st", before) { StepsBlock(it, steps, title, width) }
        val linked = steps.mapNotNull { it.anchor }
        return "Added steps block [${block.id}]${where(before)} with ${steps.size} steps" +
            if (linked.isEmpty()) "." else ", linking ${linked.joinToString()}."
    }
}

private class AddTableTool(private val board: BoardToolContext) : BoardTool(
    "add_table",
    "Add a table you write, such as a comparison of cases or a list of changed functions; a cell with path and lines " +
        "links to that code. Every row has as many cells as there are columns.",
    JsonSchemas.obj(
        titleParameter, columnsParameter, rowsParameter(board.sides), widthParameter(BlockWidth.WIDE), beforeParameter,
        required = listOf("columns", "rows"), additionalProperties = false,
    ),
) {
    override suspend fun call(arguments: JsonObject): String {
        val title = arguments.optionalTextArg("title", MAX_TITLE_CHARS)
        val width = arguments.widthArg() ?: BlockWidth.WIDE
        val before = arguments.optionalIdArg("before")
        val columns = columnsArg(arguments.get("columns"))
        val rows = board.rowsArg(arguments.get("rows"), columns.size)
        val block = board.add("tb", before) { TableBlock(it, columns, rows, title, width) }
        return "Added table [${block.id}]${where(before)} with ${columns.size} columns and ${rows.size} rows."
    }
}

private class AddGroupTool(private val board: BoardToolContext) : BoardTool(
    "add_group",
    "Frame related blocks already on the board in a titled group, laid out side by side. A block belongs to one group " +
        "at most and groups do not nest. The group takes the place of its first block unless before says otherwise.",
    JsonSchemas.obj(
        "children" to JsonSchemas.array(JsonSchemas.string(), minItems = 1, maxItems = MAX_CHILDREN, description = "Ids of the blocks to group, in order."),
        titleParameter, toneParameter, widthParameter(BlockWidth.FULL), beforeParameter,
        required = listOf("children"), additionalProperties = false,
    ),
) {
    override suspend fun call(arguments: JsonObject): String {
        val children = childrenArg(arguments.get("children"))
        val title = arguments.optionalTextArg("title", MAX_TITLE_CHARS)
        val tone = arguments.toneArg()
        val width = arguments.widthArg() ?: BlockWidth.FULL
        val before = arguments.optionalIdArg("before") ?: children.first()
        val block = board.add("g", before) { GroupBlock(it, children, title, tone, width) }
        return "Added group [${block.id}] of ${children.joinToString { "[$it]" }}${title?.let { ": $it" }.orEmpty()}."
    }
}

private class ConnectTool(private val board: BoardToolContext) : BoardTool(
    "connect",
    "Draw an arrow from one block to another, such as cause to effect or caller to callee, with an optional short label.",
    JsonSchemas.obj(
        "from" to JsonSchemas.string(description = "Id of the block the arrow starts at."),
        "to" to JsonSchemas.string(description = "Id of the block it points at."),
        "label" to JsonSchemas.string(description = "A few words on the arrow, such as \"calls\" or \"causes\"."),
        required = listOf("from", "to"), additionalProperties = false,
    ),
) {
    override suspend fun call(arguments: JsonObject): String {
        val from = arguments.idArg("from")
        val to = arguments.idArg("to")
        val label = arguments.optionalTextArg("label", MAX_LABEL_CHARS)
        board.mutate { connect(BoardEdge(from, to, label)) }
        return "Connected [$from] → [$to]${label?.let { " ($it)" }.orEmpty()}."
    }
}

private class DisconnectTool(private val board: BoardToolContext) : BoardTool(
    "disconnect",
    "Remove the arrow from one block to another.",
    JsonSchemas.obj(
        "from" to JsonSchemas.string(description = "Id of the block the arrow starts at."),
        "to" to JsonSchemas.string(description = "Id of the block it points at."),
        required = listOf("from", "to"), additionalProperties = false,
    ),
) {
    override suspend fun call(arguments: JsonObject): String {
        val from = arguments.idArg("from")
        val to = arguments.idArg("to")
        board.mutate { disconnect(from, to) }
        return "Removed the arrow [$from] → [$to]."
    }
}

// --- editing ---

private class SetTitleTool(private val board: BoardToolContext) : BoardTool(
    "set_title",
    "Set the board's title, shown in its tab and header.",
    JsonSchemas.obj("title" to JsonSchemas.string(description = "A short title for what the board explains."),
        required = listOf("title"), additionalProperties = false),
) {
    override suspend fun call(arguments: JsonObject): String {
        val title = arguments.textArg("title", MAX_TITLE_CHARS)
        board.mutate { setTitle(title) }
        return "The board is titled: $title"
    }
}

private class UpdateBlockTool(private val board: BoardToolContext) : BoardTool(
    "update_block",
    "Change what you wrote in a block: the text, level, title, tone or width of headings, prose, steps, tables and " +
        "groups (and their steps, rows or children), the title and annotations of code and diff blocks, the summary of " +
        "symbols. Evidence itself cannot be edited; remove the block and add a new one to show other code. Pass an empty " +
        "title, or tone \"none\", to clear it.",
    JsonSchemas.obj(
        "id" to JsonSchemas.string(description = "Id of the block to change."),
        "text" to JsonSchemas.string(description = "New text of a heading or prose block."),
        "level" to JsonSchemas.integer(minimum = 1, maximum = MAX_LEVEL, description = "New heading level."),
        "title" to JsonSchemas.string(description = "New title; empty to clear."),
        "tone" to JsonSchemas.enum(Tone.entries.map { it.name.lowercase() } + "none", "New tone of prose or a group; none for plain."),
        "width" to JsonSchemas.enum(BlockWidth.entries.map { it.name.lowercase() }, "New width: narrow, wide or full."),
        annotationsParameter().let { (name, schema) -> name to schema.deepCopy().apply { addProperty("description", "Replaces a code or diff block's annotations; [] removes them.") } },
        "summary" to JsonSchemas.string(description = "New summary of a symbol block; empty to clear."),
        stepsParameter(board.sides).let { (name, schema) -> name to schema.deepCopy().apply { addProperty("description", "Replaces a steps block's steps.") } },
        columnsParameter, rowsParameter(board.sides),
        "children" to JsonSchemas.array(JsonSchemas.string(), minItems = 1, maxItems = MAX_CHILDREN, description = "Replaces a group's block ids."),
        required = listOf("id"), additionalProperties = false,
    ),
) {
    override suspend fun call(arguments: JsonObject): String {
        val id = arguments.idArg("id")
        val block = board.block(id)
        val given = FIELDS.filter { arguments.present(it) }
        if (given.isEmpty()) throw ToolInputException("Pass at least one field to change: ${allowed(block).joinToString()}.")
        val wrong = given - allowed(block).toSet()
        if (wrong.isNotEmpty()) throw ToolInputException("A ${kindName(block)} cannot change ${wrong.joinToString()}; it can change " +
            allowed(block).joinToString() + "." + if (block is CodeBlock || block is SymbolBlock || block is ChainBlock)
                " Its evidence comes from the code; remove it and add another to show different code." else "")
        val width = arguments.widthArg() ?: block.width
        fun title(current: String?) = arguments.clearableTextArg("title", MAX_TITLE_CHARS)?.ifEmpty { null } ?: if (arguments.present("title")) null else current
        fun tone(current: Tone?): Tone? = if (!arguments.present("tone")) current else
            arguments.optionalStringArg("tone")?.trim()?.let { t -> if (t.equals("none", true)) null else arguments.toneArg() }
        val updated: Block = when (block) {
            is HeadingBlock -> block.copy(text = arguments.optionalTextArg("text", MAX_TITLE_CHARS) ?: block.text,
                level = levelArg(arguments) ?: block.level, width = width)
            is TextBlock -> block.copy(text = arguments.optionalTextArg("text", BoardLimits.MAX_TEXT_CHARS) ?: block.text,
                title = title(block.title), tone = tone(block.tone), width = width)
            is StepsBlock -> block.copy(steps = if (arguments.present("steps")) board.stepsArg(arguments.get("steps")) else block.steps,
                title = title(block.title), width = width)
            is TableBlock -> {
                val columns = if (arguments.present("columns")) columnsArg(arguments.get("columns")) else block.columns
                val rows = if (arguments.present("rows")) board.rowsArg(arguments.get("rows"), columns.size) else block.rows
                block.copy(columns = columns, rows = rows, title = title(block.title), width = width)
            }
            is GroupBlock -> block.copy(children = if (arguments.present("children")) childrenArg(arguments.get("children")) else block.children,
                title = title(block.title), tone = tone(block.tone), width = width)
            is CodeBlock -> {
                val annotations = if (arguments.present("annotations")) arguments.annotationsArg().also { checkAnnotations(it, block.anchor) } else block.annotations
                block.copy(title = title(block.title), annotations = annotations, width = width)
            }
            is SymbolBlock -> block.copy(summary = if (arguments.present("summary")) arguments.clearableTextArg("summary", MAX_STEP_CHARS)?.ifEmpty { null }
                else block.summary, width = width)
            is ChainBlock -> block.copy(title = title(block.title), width = width)
        }
        board.mutate { update(updated) }
        return "Updated ${kindName(updated)} [$id]: ${given.joinToString()}."
    }

    private fun allowed(block: Block): List<String> = when (block) {
        is HeadingBlock -> listOf("text", "level", "width")
        is TextBlock -> listOf("text", "title", "tone", "width")
        is StepsBlock -> listOf("steps", "title", "width")
        is TableBlock -> listOf("columns", "rows", "title", "width")
        is GroupBlock -> listOf("children", "title", "tone", "width")
        is CodeBlock -> listOf("title", "annotations", "width")
        is SymbolBlock -> listOf("summary", "width")
        is ChainBlock -> listOf("title", "width")
    }

    private companion object {
        val FIELDS = listOf("text", "level", "title", "tone", "width", "annotations", "summary", "steps", "columns", "rows", "children")
    }
}

private class MoveBlockTool(private val board: BoardToolContext) : BoardTool(
    "move_block",
    "Move a block before another one, or to the end of the board.",
    JsonSchemas.obj(
        "id" to JsonSchemas.string(description = "Id of the block to move."),
        "before" to JsonSchemas.string(description = "Id of the block to move it before; omit for the end."),
        required = listOf("id"), additionalProperties = false,
    ),
) {
    override suspend fun call(arguments: JsonObject): String {
        val id = arguments.idArg("id")
        val before = arguments.optionalIdArg("before")
        board.mutate { move(id, before) }
        return "Moved [$id] " + if (before == null) "to the end." else "before [$before]."
    }
}

private class RemoveBlockTool(private val board: BoardToolContext) : BoardTool(
    "remove_block",
    "Remove a block and its arrows from the board. Removing a group keeps its blocks on the board.",
    JsonSchemas.obj("id" to JsonSchemas.string(description = "Id of the block to remove."), required = listOf("id"), additionalProperties = false),
) {
    override suspend fun call(arguments: JsonObject): String {
        val id = arguments.idArg("id")
        val removed = board.mutate { remove(id) }
        return "Removed ${kindName(removed)} [$id]." + if (removed is GroupBlock) " Its blocks stay on the board." else ""
    }
}

private const val MAX_ANNOTATIONS = 12
private const val MAX_ANNOTATION_CHARS = 200
private const val MAX_STEPS = 15
private const val MAX_STEP_CHARS = 400
private const val MAX_COLUMNS = 6
private const val MAX_ROWS = 30
private const val MAX_CELL_CHARS = 300
private const val MAX_CHILDREN = 12
private const val MAX_TITLE_CHARS = 160
private const val MAX_LABEL_CHARS = 60
private const val MAX_LEVEL = 3
private const val MAX_CANDIDATES = 8
