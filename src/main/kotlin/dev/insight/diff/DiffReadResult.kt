package dev.insight.diff

import dev.insight.agent.ToolChatRunner
import dev.insight.agent.ToolInputException
import dev.insight.review.numberedRows
import dev.insight.review.reviewJson

/**
 * Budget the complete wire response, including escaped patches and pagination for deferred files. A page's patch lines
 * carry their numbers in the whole patch, as summary evidence numbers them, and a page ends after a complete line unless
 * one line alone exceeds it. Offsets stay in characters of the raw patch.
 */
internal fun diffReadResult(files: List<ReviewFile>, offset: Int): String {
    val read = linkedMapOf<String, Map<String, Any>>()
    fun result(): String = reviewJson.toJson(buildMap<String, Any> {
        put("files", read.values.toList())
        files.map { it.path }.filter { it !in read }.takeIf { it.isNotEmpty() }?.let { put("not_read", it) }
    })
    fun fits() = result().length <= ToolChatRunner.DEFAULT_MAX_TOOL_RESULT_CHARS
    if (!fits()) throw ToolInputException("File metadata exceeds the result limit. Request fewer paths.")
    for (file in files) {
        val firstLine = patchRowAt(file.patch, offset)
        fun slice(size: Int): Map<String, Any> = buildMap {
            val end = patchPageEnd(file.patch, offset, offset + size)
            val text = file.patch.substring(offset, end)
            put("path", file.path)
            file.previousPath?.let { put("previous_path", it) }
            put("status", file.status)
            if (offset > 0) put("offset", offset)
            if (text.isNotEmpty()) {
                put("first_line", firstLine)
                put("last_line", firstLine + text.removeSuffix("\n").count { it == '\n' })
                put("patch_lines", patchRows(file.patch))
                // Only a line longer than a page is split; its rest starts the next page under the same number.
                if (offset > 0 && file.patch[offset - 1] != '\n') put("first_line_continues", true)
            }
            put("patch", if (text.isEmpty()) "" else numberedRows(text, firstLine))
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
