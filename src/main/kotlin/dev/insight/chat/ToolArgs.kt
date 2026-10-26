package dev.insight.chat

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.insight.llm.LlmTool

/** A tool whose arguments are one object of [properties] (JSON schema members) with no others allowed. */
internal fun objectTool(name: String, description: String, properties: String, required: String = "",
                        call: suspend (JsonObject) -> String): ChatTool = object : ChatTool {
    override val definition = LlmTool(name, description, JsonParser.parseString(
        """{"type":"object","properties":{$properties},"required":[$required],"additionalProperties":false}"""
    ).asJsonObject)
    override suspend fun call(arguments: JsonObject): String = call.invoke(arguments)
}

// Strict decoding of tool arguments. A prefix names the array item a value belongs to, such as "Note 2: ".

/** A nonnegative integer, or [default] when missing. */
internal fun JsonObject.intArg(name: String, default: Int, prefix: String = ""): Int {
    val value = this[name] ?: return default
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber || !value.asString.matches(Regex("0|[1-9][0-9]*")))
        throw ToolInputException("$prefix$name must be a nonnegative integer")
    return value.asString.toIntOrNull() ?: throw ToolInputException("$prefix$name is too large")
}

internal fun JsonObject.stringArg(name: String, prefix: String = ""): String {
    val value = this[name]
    if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString || value.asString.isBlank())
        throw ToolInputException("$prefix$name must be a nonempty string")
    return value.asString
}

/** Null when missing or null; otherwise as strict as [stringArg]. */
internal fun JsonObject.optionalStringArg(name: String, prefix: String = ""): String? =
    if (has(name) && !this[name].isJsonNull) stringArg(name, prefix) else null

/** False when missing. */
internal fun JsonObject.booleanArg(name: String): Boolean {
    val value = this[name] ?: return false
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) throw ToolInputException("$name must be true or false")
    return value.asBoolean
}

/** 1 to [max] nonempty path strings. */
internal fun pathsArg(value: JsonElement?, max: Int, prefix: String = ""): List<String> {
    if (value == null || !value.isJsonArray || value.asJsonArray.size() !in 1..max)
        throw ToolInputException("${prefix}paths must contain 1 to $max file paths")
    return value.asJsonArray.map { path ->
        if (!path.isJsonPrimitive || !path.asJsonPrimitive.isString || path.asString.isBlank())
            throw ToolInputException("${prefix}each path must be a nonempty string")
        path.asString
    }
}

/** 1 to [max] objects, each decoded by [decode] with its prefix ("Note 2: ") when there are several. */
internal fun <T> objectsArg(value: JsonElement?, name: String, item: String, max: Int,
                            decode: (JsonObject, prefix: String) -> T): List<T> {
    if (value == null || !value.isJsonArray || value.asJsonArray.size() !in 1..max)
        throw ToolInputException("$name must contain 1 to $max $name")
    val array = value.asJsonArray
    return array.mapIndexed { index, element ->
        val prefix = if (array.size() > 1) "${item.replaceFirstChar(Char::uppercase)} ${index + 1}: " else ""
        if (!element.isJsonObject) throw ToolInputException("${prefix}each $item must be an object")
        decode(element.asJsonObject, prefix)
    }
}
