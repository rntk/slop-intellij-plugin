package dev.insight.apilog

import com.google.gson.JsonElement
import com.google.gson.GsonBuilder
import com.google.gson.Strictness

/** A lazy view of JSON: nested JSON strings are decoded only when their block is visited. */
internal class ApiLogValue(val key: String, original: JsonElement) {
    val value: JsonElement = decode(original)
    val decoded: Boolean = value !== original
    val expandable: Boolean get() = value.isJsonObject || value.isJsonArray
    val title: String get() = key + (if (decoded) " (decoded JSON)" else "") + when {
        value.isJsonObject -> " · Object · ${value.asJsonObject.size()} fields"
        value.isJsonArray -> " · Array · ${value.asJsonArray.size()} items"
        else -> ""
    }
    val text: String get() = if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else value.toString()

    fun children(): List<ApiLogValue> = when {
        value.isJsonObject -> value.asJsonObject.entrySet().map { ApiLogValue(it.key, it.value) }
        value.isJsonArray -> value.asJsonArray.mapIndexed { index, child -> ApiLogValue("[$index]", child) }
        else -> emptyList()
    }

    private fun decode(value: JsonElement): JsonElement {
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return value
        val text = value.asString.trim()
        if (!(text.startsWith('{') && text.endsWith('}')) && !(text.startsWith('[') && text.endsWith(']'))) return value
        return runCatching { strictJson.fromJson(text, JsonElement::class.java) }.getOrNull()
            ?.takeIf { it.isJsonObject || it.isJsonArray } ?: value
    }

    private companion object {
        val strictJson = GsonBuilder().setStrictness(Strictness.STRICT).create()
    }
}
