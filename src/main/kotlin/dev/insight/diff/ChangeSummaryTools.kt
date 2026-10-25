package dev.insight.diff

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.insight.chat.ChatTool
import dev.insight.chat.ToolChatRunner
import dev.insight.chat.ToolInputException
import dev.insight.llm.LlmTool

/** Reads the summary tree in chat. Code writes it on /summaries (see [ChangeSummarizer]); the model never pages or tracks it. */
fun changeSummaryTools(context: DiffReviewContext): List<ChatTool> = listOf(object : ChatTool {
    override val definition = LlmTool("get_change_summaries", "Page through the summary tree of the change, written on /summaries: the root, " +
        "folders, groups that split folders with more than ${ChangeSummaryTree.MAX_CHILDREN} entries, and files, " +
        "each with its summary where one exists. If next_offset is present, call again with it.",
        JsonParser.parseString("""{"type":"object","properties":{"offset":{"type":"integer","minimum":0,""" +
            """"description":"First node of the page; use next_offset."}},"required":[],"additionalProperties":false}""").asJsonObject)
    override suspend fun call(arguments: JsonObject): String = context.summaries(FlowArgs.integer(arguments, "offset", 0))
})

private fun nodeJson(summaries: ChangeSummaries, node: SummaryNode): Map<String, Any> = buildMap {
    put("id", node.id); put("kind", node.kind.id); put("location", node.location)
    node.parent?.let { put("parent", it) }
    when (val file = node.file) {
        is ReviewFile -> {
            put("status", file.status)
            file.previousPath?.let { put("previous_path", it) }
            put("lines", changedLines(file))
        }
        null -> {}
        else -> {
            if (node.kind == SummaryKind.FILE && file.facts.isNotEmpty()) put("facts", file.facts)
            node.lines?.let { put("lines", mapOf("start" to it.first, "end" to it.last)) }
        }
    }
    if (node.children.isNotEmpty() || node.file == null) { put("files", node.files.size); put("children", node.children) }
    summaries.text(node.id)?.let { put("summary", it) }
}

/** One size-bounded page of the tree in pre-order. */
internal fun changeSummariesJson(summaries: ChangeSummaries, offset: Int): String {
    val tree = summaries.tree
    if (offset < 0 || offset > tree.nodes.size) throw ToolInputException("Offset is outside the node list")
    val nodes = mutableListOf<Map<String, Any>>()
    fun result(): String = reviewJson.toJson(buildMap {
        put("evidence", "The tree comes from the changed paths. Summaries are model interpretation.")
        put("totals", mapOf("nodes" to summaries.total, "files" to tree.root.files.size, "summarized" to summaries.count,
            "missing" to summaries.total - summaries.count))
        put("nodes", nodes)
        (offset + nodes.size).takeIf { it < tree.nodes.size }?.let { put("next_offset", it) }
    })
    for (node in tree.nodes.drop(offset)) {
        nodes += nodeJson(summaries, node)
        if (result().length > ToolChatRunner.DEFAULT_MAX_TOOL_RESULT_CHARS) { nodes.removeAt(nodes.lastIndex); break }
    }
    if (nodes.isEmpty() && offset < tree.nodes.size) throw ToolInputException("Node metadata exceeds the result limit at offset $offset.")
    return result()
}

internal fun isSummariesRequest(text: String): Boolean =
    text.trim().lowercase().let { it == "summaries" || it == "/summaries" || it.startsWith("/summaries ") }
