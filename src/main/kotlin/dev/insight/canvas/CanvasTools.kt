package dev.insight.canvas

import com.google.gson.JsonObject
import dev.insight.agent.CallAllowance
import dev.insight.agent.ChatTool
import dev.insight.agent.LimitedTool
import dev.insight.agent.ToolInputException
import dev.insight.agent.ToolRegistry
import dev.insight.agent.lenientInt
import dev.insight.agent.optionalStringArg
import dev.insight.agent.stringArg
import dev.insight.graph.RelationKind
import dev.insight.llm.JsonSchemas
import dev.insight.llm.LlmTool
import kotlin.math.abs

/**
 * A graph symbol as a card's chat sees it: [name] as the graph knows it (`save(Int)`), [label] for lists, and its
 * declaration at 1-based [line] of project-relative [path] when known. Symbols without [source] open cards without code.
 */
data class CanvasTarget(val id: Int, val name: String, val label: String, val path: String? = null, val line: Int? = null,
    val source: Boolean = true)

/**
 * What a card's chat may read and do on its canvas, the same actions the developer has. Reads may run on any thread;
 * opening and showing cards switch to the EDT and reveal the card. Opening returns the card already open at the same
 * place when there is one. New cards record [CardOpenedBy.LLM]; existing cards retain their original opener.
 * Failures the model can fix throw [ToolInputException].
 */
interface CanvasActions {
    val session: CanvasSession
    /** The source shown for [cardId], or the root file's for null; null when nothing is shown. */
    fun source(cardId: String?): CanvasSource?
    fun target(id: Int): CanvasTarget?
    /** The symbols named [name] (without parameters) declared in project file [path], after reading that file. */
    suspend fun declarations(path: String, name: String): List<CanvasTarget>
    /** Searches project files for callers and references to graph symbol [target]. */
    suspend fun findUsages(target: Int): CanvasUsageSearch =
        CanvasUsageSearch(target, target(target)?.name ?: "#$target", emptyList(), false)
    /** Opens [target]'s card from [start] until [end] of [parentId]'s source (null: the root file). */
    suspend fun openSymbol(parentId: String?, start: Int, end: Int, target: Int): CanvasCard
    suspend fun openSelection(parentId: String?, start: Int, end: Int): CanvasCard
    /** Opens a card showing the whole project file [path], anchored at [start] until [end] of [parentId]'s source. */
    suspend fun openFile(parentId: String?, start: Int, end: Int, path: String): CanvasCard
    /** Scrolls to [cardId] and highlights it. */
    suspend fun show(cardId: String)
}

/** Cards a chat may open in one reply, so one answer cannot flood the canvas. */
const val CANVAS_OPEN_LIMIT = 6

/**
 * The tools of [cardId]'s chat (null: the root file's): reading the canvas beyond the context, and opening and
 * showing cards. Opening tools share an allowance of [maxOpened] cards per reply; create the tools once per reply.
 */
fun canvasTools(actions: CanvasActions, cardId: String?, maxOpened: Int = CANVAS_OPEN_LIMIT): List<ChatTool> =
    canvasToolRegistry(actions, cardId, maxOpened).tools

/** [canvasTools] by topic, for a chat that starts with [CANVAS_CHAT_TOOLS] and loads the rest when it needs them. */
fun canvasToolRegistry(actions: CanvasActions, cardId: String?, maxOpened: Int = CANVAS_OPEN_LIMIT): ToolRegistry {
    val canvas = CanvasToolContext(actions, cardId)
    val opened = CallAllowance(maxOpened, "This reply has opened $maxOpened cards, the most one reply may open. " +
        "Answer with the cards already shown.")
    return ToolRegistry()
        .register("reading the canvas: card sources, their clickable symbols, project usages of symbols, and every card and chain",
            CanvasOverviewTool(canvas), ReadCardTool(canvas), ListSymbolsTool(canvas), FindUsagesTool(canvas))
        .register("showing code on the canvas: opening cards for symbols, selected lines, declarations anywhere in " +
            "the project or whole files, and highlighting an open card",
            listOf(ShowCardTool(canvas)) + listOf(OpenSymbolTool(canvas), OpenSelectionTool(canvas),
                OpenDeclarationTool(canvas), OpenFileCardTool(canvas)).map { LimitedTool(it, opened) })
}

/** The canvas tools a card's chat starts with, for walking the chain; the others are found with search_tools. */
val CANVAS_CHAT_TOOLS = listOf("read_card", "list_symbols", "open_symbol")

/** Resolves card handles and lines for the tools of one card's chat. */
private class CanvasToolContext(val actions: CanvasActions, val cardId: String?) {
    /** The card [handle] names: null for the root file, else an open card's id. */
    fun card(handle: String?): String? {
        val clean = handle?.trim()?.removePrefix("[")?.removeSuffix("]")?.removePrefix("card ")?.trim()
        return when {
            clean.isNullOrEmpty() || clean.equals(CANVAS_CURRENT, ignoreCase = true) ->
                cardId?.also { if (actions.session.card(it) == null) throw ToolInputException("The current card was closed.") }
            clean.equals(CANVAS_ROOT, ignoreCase = true) -> null
            actions.session.card(clean) != null -> clean
            else -> throw ToolInputException("No card '$handle' is open. Use \"$CANVAS_CURRENT\", \"$CANVAS_ROOT\" or an id from canvas_overview.")
        }
    }

    /** The source shown for [id], with text to open from. */
    fun shown(id: String?): Shown {
        val source = actions.source(id) ?: throw ToolInputException("${name(id)} is not shown yet; try again shortly.")
        if (source.text.isEmpty()) throw ToolInputException("${name(id)} has no source: ${source.notice ?: "nothing to open from"}")
        return Shown(source)
    }

    fun name(id: String?): String = if (id == null) "The root file" else "Card $id"

    /** Where a card opened from [parent] points at: [line] when given, else the first use of [word], else the first line. */
    fun anchor(parent: Shown, line: Int?, word: String?): IntRange {
        if (line != null) return parent.trimmedLine(line)
            ?: throw ToolInputException("from_line must be within lines ${parent.first}-${parent.last} of the card it names.")
        val use = word?.takeIf { it.isNotBlank() }
            ?.let { Regex("\\b${Regex.escape(it)}\\b").find(parent.source.text) }
        return use?.let { it.range.first until it.range.last + 1 } ?: parent.trimmedLine(parent.first) ?: 0 until 0
    }

    /** What a tool says after opening or showing [card]: where it is, then its summary and the start of its source. */
    fun report(card: CanvasCard, wasOpen: Boolean): String = buildString {
        append(if (wasOpen) "Card ${card.id} was already open; it is shown again: " else "Opened card ${card.id} on the canvas: ")
        append(describe(card))
        val parent = card.parentId?.let { "card $it" } ?: "the root file"
        append(", from $parent line ${card.anchor.line + 1}.\n")
        card.summary?.let { append("Summary (model-written): ${it.trim()}\n") }
        val source = actions.source(card.id)
        source?.notice?.let { append("Notice: $it\n") }
        val text = source?.text ?: card.text
        if (text.isEmpty()) append("It shows no source.\n")
        else append(numberedText(label(card), text, source?.firstLine ?: card.firstLine, card.filePath, REPORT_SOURCE_CHARS))
    }.trimEnd()

    fun label(card: CanvasCard) = if (card.kind == CardKind.ATTACHMENT) "Attachment text" else if (card.wholeFile) "File source" else if (card.kind == CardKind.SELECTION) "Selected text" else "Source"

    /** Runs [open] and reports the card it returns, telling whether it was open before. */
    suspend fun opening(open: suspend () -> CanvasCard): String {
        val before = actions.session.cards.map { it.id }.toSet()
        val card = open()
        return report(actions.session.card(card.id) ?: card, card.id in before)
    }
}

/** A shown source with its lines: [first] to [last] are 1-based document lines. */
private class Shown(val source: CanvasSource) {
    private val starts: IntArray = source.text.split('\n').runningFold(0) { at, line -> at + line.length + 1 }.dropLast(1).toIntArray()
    val first: Int get() = source.firstLine + 1
    val last: Int get() = source.firstLine + starts.size

    /** The 1-based document line of [offset]. */
    fun line(offset: Int): Int {
        val index = starts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
        return source.firstLine + index.coerceAtLeast(0) + 1
    }

    /** Offsets of 1-based document [line], without its line break; null outside the text. */
    fun lineRange(line: Int): IntRange? {
        val index = line - first
        if (index !in starts.indices) return null
        val end = if (index + 1 < starts.size) starts[index + 1] - 1 else source.text.length
        return starts[index] until end
    }

    /** [line]'s offsets without leading and trailing blanks; the whole line when it is blank. */
    fun trimmedLine(line: Int): IntRange? {
        val range = lineRange(line) ?: return null
        val text = source.text.substring(range.first, range.last + 1)
        val from = text.indexOfFirst { !it.isWhitespace() }
        if (from < 0) return range
        return (range.first + from) until (range.first + text.indexOfLast { !it.isWhitespace() } + 1)
    }

    fun text(link: CanvasLink): String = source.text.substring(link.range.first, link.range.last + 1)
}

private abstract class CanvasTool(name: String, description: String, parameters: JsonObject) : ChatTool {
    final override val definition = LlmTool(name, description, parameters)
}

private val cardParameter = JsonSchemas.string(description =
    "\"$CANVAS_CURRENT\" (default) for this chat's card, \"$CANVAS_ROOT\" for the root file, or a card id.")

private class CanvasOverviewTool(private val canvas: CanvasToolContext) : CanvasTool(
    "canvas_overview",
    "List every card on the canvas as a tree under the root file, including chains other than the current one: id, " +
        "what and where each card is, the line it was opened from and its summary. Read-only.",
    JsonSchemas.obj(required = emptyList(), additionalProperties = false),
) {
    override suspend fun call(arguments: JsonObject): String {
        val session = canvas.actions.session
        val chain = canvas.cardId?.let(session::chain).orEmpty().map { it.id }.toSet()
        return buildString {
            append("Root file: ${session.rootPath} [$CANVAS_ROOT]${if (canvas.cardId == null) " (current card)" else ""}\n")
            if (session.cards.isEmpty()) append("No cards are open.\n")
            fun branch(parentId: String?, depth: Int) {
                for (card in session.children(parentId)) {
                    append("  ".repeat(depth)).append("- ${card.title} [card ${card.id}]: ${describe(card)}, from line ${card.anchor.line + 1}")
                    if (card.id == canvas.cardId) append(" (current card)") else if (card.id in chain) append(" (on the current chain)")
                    append("\n")
                    card.summary?.let { append("  ".repeat(depth + 1)).append("Summary: ${clip(it.trim(), 200)}\n") }
                    branch(card.id, depth + 1)
                }
            }
            branch(null, 0)
        }.trimEnd()
    }
}

private class ReadCardTool(private val canvas: CanvasToolContext) : CanvasTool(
    "read_card",
    "Read a card on the canvas, or the root file: what it is, where it was opened from, its summary and its numbered " +
        "source as shown on the canvas. Read-only; use it for cards outside the current chain or beyond the context's excerpt.",
    JsonSchemas.obj(
        "card" to cardParameter,
        "start_line" to JsonSchemas.integer(description = "First 1-based document line of the source to show (default: the card's first)."),
        "end_line" to JsonSchemas.integer(description = "Last line to show, inclusive (default: as much as fits)."),
        required = emptyList(), additionalProperties = false,
    ),
) {
    override suspend fun call(arguments: JsonObject): String {
        val id = canvas.card(arguments.optionalStringArg("card"))
        val card = id?.let { canvas.actions.session.card(it) }
        val source = canvas.actions.source(id)
        return buildString {
            if (card == null) append("Root file ${canvas.actions.session.rootPath}\n")
            else {
                append("Card ${card.id}: ${describe(card)}\n")
                card.attachment?.let { append("Attachment: ${it.mediaType}, ${it.size} bytes; linked to ${card.parentId ?: CANVAS_ROOT}.\n") }
                append("Opened from: ${card.parentId?.let { "card $it" } ?: "the root file"}, line ${card.anchor.line + 1}\n")
                card.summary?.let { append("Summary (model-written): ${it.trim()}\n") }
                val children = canvas.actions.session.children(card.id)
                if (children.isNotEmpty()) append("Cards opened from it: ${children.joinToString("; ") { "${it.title} [card ${it.id}] from line ${it.anchor.line + 1}" }}\n")
            }
            source?.notice?.let { append("Notice: $it\n") }
            val text = source?.text ?: card?.text.orEmpty()
            val firstLine = source?.firstLine ?: card?.firstLine ?: 0
            if (text.isEmpty()) {
                append("No source is shown.")
                return@buildString
            }
            val lines = text.split('\n')
            val from = ((arguments.lenientInt("start_line") ?: (firstLine + 1)) - firstLine - 1).coerceIn(0, lines.lastIndex)
            val to = ((arguments.lenientInt("end_line") ?: (firstLine + lines.size)) - firstLine - 1).coerceIn(from, lines.lastIndex)
            val path = card?.filePath ?: source?.path ?: canvas.actions.session.rootPath
            append(numberedText(card?.let(canvas::label) ?: "Source", lines.subList(from, to + 1).joinToString("\n"),
                firstLine + from, path, READ_SOURCE_CHARS, truncated = to < lines.lastIndex))
        }.trimEnd()
    }
}

private class ListSymbolsTool(private val canvas: CanvasToolContext) : CanvasTool(
    "list_symbols",
    "List the clickable symbols in a card's source, or the root file's: each with its 1-based line, its name in the " +
        "code and the declarations it leads to, numbered when there are several. Read-only; open one with open_symbol.",
    JsonSchemas.obj(
        "card" to cardParameter,
        "start_line" to JsonSchemas.integer(description = "Only symbols from this 1-based document line on."),
        "end_line" to JsonSchemas.integer(description = "Only symbols up to this line, inclusive."),
        "name" to JsonSchemas.string(description = "Only symbols whose name contains this text, case-insensitive."),
        "max_results" to JsonSchemas.integer(description = "Most symbols to list (default 80, at most 200)."),
        required = emptyList(), additionalProperties = false,
    ),
) {
    override suspend fun call(arguments: JsonObject): String {
        val id = canvas.card(arguments.optionalStringArg("card"))
        val shown = canvas.shown(id)
        val from = arguments.lenientInt("start_line") ?: shown.first
        val to = arguments.lenientInt("end_line") ?: shown.last
        val filter = arguments.optionalStringArg("name")
        val limit = (arguments.lenientInt("max_results") ?: 80).coerceIn(1, 200)
        val rows = shown.source.links.asSequence().mapNotNull { link ->
            val line = shown.line(link.range.first)
            if (line !in from..to) return@mapNotNull null
            val targets = link.targets.mapNotNull(canvas.actions::target)
            val name = clip(shown.text(link).trim().replace(Regex("\\s+"), " "), 60)
            if (filter != null && !name.contains(filter, ignoreCase = true) && targets.none { it.name.contains(filter, ignoreCase = true) })
                return@mapNotNull null
            val leads = when (targets.size) {
                0 -> "(unknown)"
                1 -> targets.single().label
                else -> targets.withIndex().joinToString("; ") { (index, target) -> "${index + 1}) ${target.label}" }
            }
            "$line: $name → $leads"
        }.toList()
        if (rows.isEmpty()) return "No clickable symbols${filter?.let { " matching '$it'" }.orEmpty()} in lines $from-$to of ${canvas.name(id).lowercase()}."
        return "Symbols in ${canvas.name(id).lowercase()}, lines $from-$to:\n" + rows.take(limit).joinToString("\n") +
            if (rows.size > limit) "\n[${rows.size - limit} more not shown; narrow the lines or name.]" else ""
    }
}

private class FindUsagesTool(private val canvas: CanvasToolContext) : CanvasTool(
    "find_usages",
    "Find project callers and references to a symbol: either the symbol shown by a card (default: the current card) " +
        "or a clickable symbol named in a card's source. Read-only; open referencing declarations with open_declaration or open_file.",
    JsonSchemas.obj(
        "card" to cardParameter,
        "name" to JsonSchemas.string(description = "Optional symbol name in the card's source; omit to search usages of the symbol card itself."),
        "line" to JsonSchemas.integer(description = "1-based document line when name occurs on several lines."),
        "target" to JsonSchemas.integer(description = "Which declaration when list_symbols numbers several for name."),
        required = emptyList(), additionalProperties = false,
    ),
) {
    override suspend fun call(arguments: JsonObject): String {
        val id = canvas.card(arguments.optionalStringArg("card"))
        val name = arguments.optionalStringArg("name")?.trim()?.takeIf { it.isNotEmpty() }
        val targetId = if (name == null) {
            val source = canvas.actions.source(id) ?: throw ToolInputException("${canvas.name(id)} is not shown yet; try again shortly.")
            val node = source.node
            if (node == null || id == null || canvas.actions.session.card(id)?.wholeFile == true) {
                throw ToolInputException("${canvas.name(id)} does not represent a single symbol; pass name to find usages of a symbol in its source.")
            }
            node
        } else {
            val line = arguments.lenientInt("line")
            val shown = canvas.shown(id)
            val links = shown.source.links.filter { line == null || shown.line(it.range.first) == line }
            fun named(link: CanvasLink, ignoreCase: Boolean) = shown.text(link).trim().equals(name, ignoreCase) ||
                link.targets.mapNotNull(canvas.actions::target).any { it.name.substringBefore('(').equals(name, ignoreCase) }
            val found = links.filter { named(it, false) }.ifEmpty { links.filter { named(it, true) } }
                .sortedWith(compareBy({ it.range.first }, { it.range.last - it.range.first }))
            val link = found.firstOrNull() ?: throw ToolInputException("No clickable symbol '$name'" +
                (line?.let { " on line $it" } ?: "") + " in ${canvas.name(id).lowercase()}. list_symbols shows its symbols.")
            val targets = link.targets.mapNotNull(canvas.actions::target)
            val index = arguments.lenientInt("target")
            val chosen = when {
                index != null -> targets.getOrNull(index - 1) ?: throw ToolInputException("target must be 1 to ${targets.size}.")
                targets.size == 1 -> targets.single()
                else -> targets.filter { it.name.substringBefore('(') == name }.singleOrNull()
                    ?: throw ToolInputException("'$name' on line ${shown.line(link.range.first)} leads to several declarations; " +
                        "pass target: " + targets.withIndex().joinToString("; ") { (i, t) -> "${i + 1}) ${t.label}" })
            }
            chosen.id
        }
        val found = canvas.actions.findUsages(targetId)
        if (found.usages.isEmpty()) return "No project usages found for ${found.targetName}."
        return buildString {
            append("Usages of ${found.targetName} (${found.usages.size}${if (found.truncated) "+" else ""} sites):\n")
            found.usages.forEach { usage ->
                val kind = if (usage.kind == RelationKind.CALL) "Call" else "Use"
                append("- $kind at ${usage.path}:${usage.line} in ${usage.ownerLabel}\n")
            }
            if (found.truncated) append("[More usages exist; showing ${found.usages.size} sites.]\n")
        }.trimEnd()
    }
}

private class ShowCardTool(private val canvas: CanvasToolContext) : CanvasTool(
    "show_card",
    "Scroll the canvas to a card that is already open and highlight it for a moment, to point the developer at it.",
    JsonSchemas.obj("card" to cardParameter, required = listOf("card"), additionalProperties = false),
) {
    override suspend fun call(arguments: JsonObject): String {
        val id = canvas.card(arguments.stringArg("card")) ?: throw ToolInputException("The root file is always shown; name a card.")
        canvas.actions.show(id)
        val card = canvas.actions.session.card(id) ?: throw ToolInputException("Card $id was closed.")
        return "Showing card ${card.id}: ${describe(card)}."
    }
}

private class OpenSymbolTool(private val canvas: CanvasToolContext) : CanvasTool(
    "open_symbol",
    "Click a symbol in a card's source, or the root file's, to open its card beside it on the canvas, as the developer " +
        "would. Changes the canvas. Returns the card's id, place and the start of its source.",
    JsonSchemas.obj(
        "card" to cardParameter,
        "name" to JsonSchemas.string(description = "The symbol's name as written in the code, such as save or Store."),
        "line" to JsonSchemas.integer(description = "1-based document line it is on; needed when the name occurs on several lines."),
        "target" to JsonSchemas.integer(description = "Which declaration to open when list_symbols numbers several for this symbol."),
        required = listOf("name"), additionalProperties = false,
    ),
) {
    override suspend fun call(arguments: JsonObject): String {
        val id = canvas.card(arguments.optionalStringArg("card"))
        val name = arguments.stringArg("name").trim()
        val line = arguments.lenientInt("line")
        val shown = canvas.shown(id)
        val links = shown.source.links.filter { line == null || shown.line(it.range.first) == line }
        fun named(link: CanvasLink, ignoreCase: Boolean) = shown.text(link).trim().equals(name, ignoreCase) ||
            link.targets.mapNotNull(canvas.actions::target).any { it.name.substringBefore('(').equals(name, ignoreCase) }
        val found = links.filter { named(it, false) }.ifEmpty { links.filter { named(it, true) } }
            .sortedWith(compareBy({ it.range.first }, { it.range.last - it.range.first }))
        val link = found.firstOrNull() ?: throw ToolInputException("No clickable symbol '$name'" +
            (line?.let { " on line $it" } ?: "") + " in ${canvas.name(id).lowercase()}. list_symbols shows its symbols.")
        val targets = link.targets.mapNotNull(canvas.actions::target)
        val index = arguments.lenientInt("target")
        val target = when {
            index != null -> targets.getOrNull(index - 1) ?: throw ToolInputException("target must be 1 to ${targets.size}.")
            targets.size == 1 -> targets.single()
            else -> targets.filter { it.name.substringBefore('(') == name }.singleOrNull()
                ?: throw ToolInputException("'$name' on line ${shown.line(link.range.first)} leads to several declarations; " +
                    "pass target: " + targets.withIndex().joinToString("; ") { (i, t) -> "${i + 1}) ${t.label}" })
        }
        val others = found.map { shown.line(it.range.first) }.distinct().drop(1)
        return canvas.opening { canvas.actions.openSymbol(id, link.range.first, link.range.last + 1, target.id) } +
            if (line == null && others.isNotEmpty()) "\n(Opened from line ${shown.line(link.range.first)}; '$name' also occurs on " +
                "line${if (others.size > 1) "s" else ""} ${others.take(8).joinToString()}.)" else ""
    }
}

private class OpenSelectionTool(private val canvas: CanvasToolContext) : CanvasTool(
    "open_selection",
    "Select lines of a card's source, or the root file's, and open a card with just those lines beside it, as the " +
        "developer would by selecting code. Use it to point at the part of a long card that matters. Changes the canvas.",
    JsonSchemas.obj(
        "card" to cardParameter,
        "start_line" to JsonSchemas.integer(description = "First 1-based document line to select."),
        "end_line" to JsonSchemas.integer(description = "Last line to select, inclusive (default start_line)."),
        required = listOf("start_line"), additionalProperties = false,
    ),
) {
    override suspend fun call(arguments: JsonObject): String {
        val id = canvas.card(arguments.optionalStringArg("card"))
        val shown = canvas.shown(id)
        val start = arguments.lenientInt("start_line") ?: throw ToolInputException("start_line is required")
        val end = arguments.lenientInt("end_line") ?: start
        val from = shown.trimmedLine(start)
        val to = shown.lineRange(end)
        if (from == null || to == null || end < start)
            throw ToolInputException("Select lines within ${shown.first}-${shown.last} of ${canvas.name(id).lowercase()}, start_line first.")
        if (shown.source.text.substring(from.first, to.last + 1).isBlank()) throw ToolInputException("Those lines are blank.")
        return canvas.opening { canvas.actions.openSelection(id, from.first, to.last + 1) }
    }
}

private val fromParameters = arrayOf(
    "from_card" to JsonSchemas.string(description = "The card to attach the new card to: \"$CANVAS_CURRENT\" (default), \"$CANVAS_ROOT\" or a card id."),
    "from_line" to JsonSchemas.integer(description = "1-based line of from_card the new card relates to, where its arrow starts " +
        "(default: where the name first occurs there, else its first line)."),
)

private class OpenDeclarationTool(private val canvas: CanvasToolContext) : CanvasTool(
    "open_declaration",
    "Open a card for a symbol declared anywhere in the project, even when no card's source links to it; find its file " +
        "with search_text first. The card is attached to the current card unless from_card says otherwise. Changes the canvas.",
    JsonSchemas.obj(
        "path" to JsonSchemas.string(description = "Project-relative path of the file that declares the symbol."),
        "name" to JsonSchemas.string(description = "The symbol's name without parameters, such as save or Store."),
        "line" to JsonSchemas.integer(description = "1-based line of its declaration; needed when the file declares the name more than once."),
        *fromParameters,
        required = listOf("path", "name"), additionalProperties = false,
    ),
) {
    override suspend fun call(arguments: JsonObject): String {
        val path = arguments.stringArg("path").trim()
        val name = arguments.stringArg("name").trim().substringBefore('(')
        val line = arguments.lenientInt("line")
        val parentId = canvas.card(arguments.optionalStringArg("from_card"))
        val parent = canvas.shown(parentId)
        val declared = canvas.actions.declarations(path, name)
        if (declared.isEmpty()) throw ToolInputException("$path declares nothing named '$name' that the IDE resolves; " +
            "check the path and name with search_text.")
        val target = when {
            line != null -> declared.minBy { abs((it.line ?: Int.MAX_VALUE / 2) - line) }
            declared.size == 1 -> declared.single()
            else -> throw ToolInputException("$path declares '$name' more than once; pass line: " +
                declared.joinToString("; ") { "${it.label} (line ${it.line ?: "?"})" })
        }
        val anchor = canvas.anchor(parent, arguments.lenientInt("from_line"), name)
        return canvas.opening { canvas.actions.openSymbol(parentId, anchor.first, anchor.last + 1, target.id) }
    }
}

private class OpenFileCardTool(private val canvas: CanvasToolContext) : CanvasTool(
    "open_file",
    "Open a card showing a whole project source file, whose symbols can then be opened with open_symbol. The card is " +
        "attached to the current card unless from_card says otherwise. Changes the canvas; prefer open_declaration " +
        "when one symbol of the file is what matters.",
    JsonSchemas.obj(
        "path" to JsonSchemas.string(description = "Project-relative file path, as returned by list_files or search_text."),
        *fromParameters,
        required = listOf("path"), additionalProperties = false,
    ),
) {
    override suspend fun call(arguments: JsonObject): String {
        val path = arguments.stringArg("path").trim()
        val parentId = canvas.card(arguments.optionalStringArg("from_card"))
        val parent = canvas.shown(parentId)
        val anchor = canvas.anchor(parent, arguments.lenientInt("from_line"), path.substringAfterLast('/').substringBefore('.'))
        return canvas.opening { canvas.actions.openFile(parentId, anchor.first, anchor.last + 1, path) }
    }
}

private fun clip(text: String, max: Int) = if (text.length <= max) text else text.take(max - 1) + "…"

/** Characters of source a tool returns after opening a card; read_card and read_file show more. */
private const val REPORT_SOURCE_CHARS = 4_000

/** Characters of source read_card returns. */
private const val READ_SOURCE_CHARS = 12_000
