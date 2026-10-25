package dev.insight.chat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.insight.llm.LlmTool

/** One-based, inclusive coordinates. [side] is present only in a diff review. */
data class SourcePoint(
    val path: String,
    val line: Int,
    val endLine: Int = line,
    val comment: String? = null,
    val side: String? = null,
)

/** Shared presentation tool; the caller resolves paths against its own chat context. */
fun sourcePointTool(diff: Boolean = false, show: suspend (SourcePoint) -> String): ChatTool = object : ChatTool {
    override val definition = LlmTool(
        "point_to_code",
        "Show a file at a highlighted line or range in a read-only text editor, with an optional comment. " +
            "Each call adds a separate side-by-side column, including repeated locations. Use paths and line numbers from inspected code.",
        JsonParser.parseString("""{"type":"object","properties":{
            "path":{"type":"string","minLength":1,"description":"Project-relative file path. Review files use snapshot source; other project files use current content."},
            "line":{"type":"integer","minimum":1,"description":"First line to highlight, one-based."},
            "end_line":{"type":"integer","minimum":1,"description":"Last line to highlight, inclusive. Defaults to line; clamped to the end of the file."},
            "comment":{"type":"string","maxLength":2000,"description":"Optional explanation shown above this editor."}
            ${if (diff) """, "side":{"type":"string","enum":["before","after"],"description":"Snapshot side, default after. Use before for removed code."}""" else ""}
        },"required":["path","line"],"additionalProperties":false}""").asJsonObject,
    )

    override suspend fun call(arguments: JsonObject): String {
        val allowed = setOf("path", "line", "end_line", "comment") + if (diff) setOf("side") else emptySet()
        if (arguments.keySet().any { it !in allowed }) throw ToolInputException("Unknown argument for point_to_code")
        fun string(name: String): String {
            val value = arguments[name]
            if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString)
                throw ToolInputException("$name must be a string")
            return value.asString
        }
        fun line(name: String): Int {
            val value = arguments[name]
            val number = value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asString?.takeIf { it.matches(Regex("[1-9][0-9]*")) }?.toIntOrNull()
            return number ?: throw ToolInputException("$name must be a positive integer")
        }
        val path = string("path").takeIf { it.isNotBlank() } ?: throw ToolInputException("path must not be empty")
        val start = line("line")
        val end = if (arguments.has("end_line")) line("end_line") else start
        if (end < start) throw ToolInputException("end_line must not be before line")
        val comment = if (arguments.has("comment")) string("comment").takeIf { it.isNotBlank() } else null
        if (comment != null && comment.length > 2000) throw ToolInputException("comment exceeds 2000 characters")
        val side = if (diff) (if (arguments.has("side")) string("side") else "after") else null
        if (side != null && side !in setOf("before", "after")) throw ToolInputException("side must be before or after")
        return show(SourcePoint(path, start, end, comment, side))
    }
}
