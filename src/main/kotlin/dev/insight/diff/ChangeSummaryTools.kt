package dev.insight.diff

import dev.insight.agent.ChatTool
import dev.insight.agent.intArg
import dev.insight.agent.objectTool
import dev.insight.llm.JsonSchemas
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewCommand
import dev.insight.review.ReviewSummarizer
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.SummaryKind
import dev.insight.review.reviewSummariesJson

/** Reads the summary tree in chat. Code writes it on /summaries (see [ReviewSummarizer]); the model never pages or tracks it. */
fun changeSummaryTools(context: DiffReviewContext): List<ChatTool> = listOf(objectTool("get_change_summaries",
    "Page through the summary tree of the change, written on /summaries: the root, " +
        "folders, groups that split folders with more than ${ReviewSummaryTree.MAX_CHILDREN} entries, and files, " +
        "each with its summary where one exists. If next_offset is present, call again with it.",
    JsonSchemas.obj("offset" to JsonSchemas.integer(minimum = 0, description = "First node of the page; use next_offset."),
        required = emptyList(), additionalProperties = false)) {
    context.summaries(it.intArg("offset", 0))
})

/** One size-bounded page of the tree in pre-order. */
internal fun changeSummariesJson(summaries: ReviewSummaries, offset: Int): String = reviewSummariesJson(
    summaries = summaries,
    offset = offset,
    evidence = "The tree comes from the changed paths. Summaries are model interpretation.",
    useTreeTotals = true,
) { node, map ->
    when (val file = node.file) {
        is ReviewFile -> {
            map["status"] = file.status
            file.previousPath?.let { map["previous_path"] = it }
            map["lines"] = changedLines(file)
        }
        null -> {}
        else -> {
            if (node.kind == SummaryKind.FILE && file.facts.isNotEmpty()) map["facts"] = file.facts
            node.lines?.let { map["lines"] = mapOf("start" to it.first, "end" to it.last) }
        }
    }
    if (node.children.isNotEmpty() || node.file == null) {
        map["files"] = node.files.size
        map["children"] = node.children
    }
}

internal fun isSummariesRequest(text: String): Boolean =
    ReviewCommand.parse(text) == ReviewCommand.SUMMARIES
