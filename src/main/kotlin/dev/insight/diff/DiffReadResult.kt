package dev.insight.diff

import dev.insight.chat.ToolChatRunner
import dev.insight.chat.ToolInputException

/** Budget the complete wire response, including escaped patches and pagination for deferred files. */
internal fun diffReadResult(files: List<ReviewFile>, offset: Int): String {
    val read = linkedMapOf<String, Map<String, Any>>()
    fun result(): String = reviewJson.toJson(buildMap<String, Any> {
        put("files", read.values.toList())
        files.map { it.path }.filter { it !in read }.takeIf { it.isNotEmpty() }?.let { put("not_read", it) }
    })
    fun fits() = result().length <= ToolChatRunner.DEFAULT_MAX_TOOL_RESULT_CHARS
    if (!fits()) throw ToolInputException("File metadata exceeds the result limit. Request fewer paths.")
    for (file in files) {
        fun slice(size: Int): Map<String, Any> = buildMap {
            val end = offset + size
            put("path", file.path)
            file.previousPath?.let { put("previous_path", it) }
            put("status", file.status)
            if (offset > 0) put("offset", offset)
            put("patch", file.patch.substring(offset, end))
            if (end < file.patch.length) put("next_offset", end)
        }
        val wanted = minOf(6000, file.patch.length - offset)
        read[file.path] = slice(wanted)
        if (fits()) continue

        // Try the complete slice first: finishing a patch removes its next_offset field.
        var low = 0
        var high = wanted - 1
        var available = -1
        while (low <= high) {
            val size = (low + high) / 2
            read[file.path] = slice(size)
            if (fits()) { available = size; low = size + 1 } else high = size - 1
        }
        read.remove(file.path)
        // Avoid tiny later slices, but let the first file make progress even with large metadata.
        val minimum = if (read.isEmpty()) minOf(1, wanted) else minOf(1000, wanted)
        if (available >= minimum) read[file.path] = slice(available)
    }
    if (read.isEmpty() && files.isNotEmpty())
        throw ToolInputException("File metadata exceeds the result limit. Request fewer paths.")
    return result()
}
