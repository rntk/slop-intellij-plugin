package dev.insight.agent

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.insight.llm.LlmTool

/** A tool with an object argument schema built with JsonSchemas. */
internal fun objectTool(name: String, description: String, schema: JsonObject,
                        call: suspend (JsonObject) -> String): ChatTool = object : ChatTool {
    override val definition = LlmTool(name, description, schema.deepCopy())
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

/** False when missing or null. */
internal fun JsonObject.booleanArg(name: String): Boolean = optionalBooleanArg(name) ?: false

/** Null when missing or null. */
internal fun JsonObject.optionalBooleanArg(name: String): Boolean? {
    val value = this[name]?.takeUnless { it.isJsonNull } ?: return null
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

// Lenient decoding for tools that local models call. They sometimes quote numbers and booleans, so numeric and
// "true"/"false" strings are accepted, and null counts as missing. A label names the array item, such as "nodes[2].id".

/** Null when missing or null. */
internal fun JsonObject.lenientInt(name: String, label: String = name, nonnegative: Boolean = false): Int? {
    val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
    val number = if (value.isJsonPrimitive) value.asString.trim().toIntOrNull() else null
    return number?.takeIf { !nonnegative || it >= 0 }
        ?: throw ToolInputException(if (nonnegative) "$label must be a nonnegative integer" else "$label must be an integer")
}

/** Null when missing or null. */
internal fun JsonObject.lenientBoolean(name: String): Boolean? {
    val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
    val flag = if (value.isJsonPrimitive) value.asString.trim().lowercase().toBooleanStrictOrNull() else null
    return flag ?: throw ToolInputException("$name must be true or false")
}

/** Distinct nonnegative integer IDs; the array must hold [count] of them before duplicates are removed. */
internal fun JsonObject.lenientIds(name: String, count: IntRange, label: String = name): List<Int> {
    val array = get(name)?.takeIf { it.isJsonArray }?.asJsonArray
        ?: throw ToolInputException("$label must be an array of ${count.first} to ${count.last} IDs")
    if (array.size() !in count) throw ToolInputException("$label must contain ${count.first} to ${count.last} IDs")
    return array.map { value ->
        (if (value.isJsonPrimitive) value.asString.trim().toIntOrNull() else null)?.takeIf { it >= 0 }
            ?: throw ToolInputException("$label must contain nonnegative integers")
    }.distinct()
}
