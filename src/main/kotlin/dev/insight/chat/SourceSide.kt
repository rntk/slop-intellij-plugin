package dev.insight.chat

import com.google.gson.JsonObject
import dev.insight.enrichment.JsonSchemas

/** The snapshot text a location refers to: a diff's [BEFORE] or [AFTER] side, or a code review's [CURRENT] file. */
enum class SourceSide(val label: String) {
    BEFORE("before"), AFTER("after"), CURRENT("current");

    companion object {
        val DIFF = listOf(BEFORE, AFTER)

        /** The schema of a diff tool's `side` argument. */
        fun diffSchema(description: String? = null): JsonObject = JsonSchemas.enum(DIFF.map { it.label }, description = description)
    }
}

/** A diff tool's optional `side` argument; [SourceSide.AFTER] when missing. */
internal fun JsonObject.diffSideArg(): SourceSide {
    if (!has("side")) return SourceSide.AFTER
    val label = stringArg("side")
    return SourceSide.DIFF.firstOrNull { it.label == label } ?: throw ToolInputException("side must be before or after")
}
