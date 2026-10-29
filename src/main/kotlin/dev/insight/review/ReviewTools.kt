package dev.insight.review

import com.google.gson.JsonObject
import dev.insight.agent.ChatTool
import dev.insight.agent.ToolInputException
import dev.insight.agent.intArg
import dev.insight.agent.objectTool
import dev.insight.agent.objectsArg
import dev.insight.agent.pathsArg
import dev.insight.agent.stringArg
import dev.insight.llm.JsonSchemas

/** The schema of 1 to [max] file paths, described by [description]. */
internal fun pathListSchema(max: Int, description: String) =
    JsonSchemas.array(JsonSchemas.string(), minItems = 1, maxItems = max, description = description)

/** The inclusive, one-based start_line..end_line of a source block tool, at most [maxLines] lines. */
internal fun JsonObject.sourceLinesArg(maxLines: Int): IntRange {
    val start = intArg("start_line", 0)
    val end = intArg("end_line", 0)
    if (start < 1 || end < start || end.toLong() - start >= maxLines)
        throw ToolInputException("Use an inclusive range of 1–$maxLines lines, starting at line 1 or later.")
    return start..end
}

/**
 * The tool that adds notes to a review canvas. [name], [findTool] and [subject] ("diffs" | "code") are the review's;
 * [pathList] is its schema of file paths and [symbolScope] what a symbol must resolve within.
 */
internal fun addNotesTool(
    name: String,
    findTool: String,
    subject: String,
    pathList: (Int) -> JsonObject,
    symbolScope: String,
    add: suspend (List<ReviewNote>) -> String,
): ChatTool = objectTool(name, "Show 1–$MAX_REVIEW_NOTES_PER_CALL notes on the canvas next to the chat, to explain or illustrate an answer. " +
    "Each note is attached to one file or a group of related files. Optionally attach a symbol (function, class, property or other " +
    "declaration) from those files. Symbols are validated and get a small diagram of static source relationships. Use $findTool for " +
    "exact selectors or ambiguous names. A note for the same paths and symbol replaces the old one; different symbols coexist. " +
    "Only describe $subject you have read.",
    JsonSchemas.obj("notes" to JsonSchemas.array(JsonSchemas.obj(
        "paths" to pathList(MAX_NOTE_PATHS),
        "text" to JsonSchemas.string(minLength = 1, maxLength = MAX_NOTE_CHARS,
            description = "A short explanation, usually 1–3 sentences."),
        "symbol" to JsonSchemas.string(minLength = 1, maxLength = MAX_SYMBOL_CHARS,
            description = "Optional declaration name, qualified name, or exact selector from $findTool. Must resolve uniquely within $symbolScope."),
        required = listOf("paths", "text"), additionalProperties = false,
    ), minItems = 1, maxItems = MAX_REVIEW_NOTES_PER_CALL), additionalProperties = false)) { args ->
    add(objectsArg(args["notes"], "notes", "note", MAX_REVIEW_NOTES_PER_CALL) { note, prefix ->
        val text = note.stringArg("text", prefix)
        if (text.length > MAX_NOTE_CHARS) throw ToolInputException("${prefix}text is too long")
        val symbol = if (note.has("symbol")) note.stringArg("symbol", prefix).trim() else null
        if (symbol != null && symbol.length > MAX_SYMBOL_CHARS) throw ToolInputException("${prefix}symbol is too long")
        ReviewNote(pathsArg(note["paths"], MAX_NOTE_PATHS, prefix), text, symbol)
    })
}

internal const val MAX_REVIEW_NOTES_PER_CALL = 20
internal const val MAX_NOTE_PATHS = 30
internal const val MAX_NOTE_CHARS = 2000
internal const val MAX_SYMBOL_CHARS = 500
