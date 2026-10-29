package dev.insight.codereview

import dev.insight.enrichment.CODE_AS_DATA
import dev.insight.review.ReviewSource
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.ReviewTopics
import dev.insight.review.SummaryKind
import dev.insight.review.SummaryNode
import dev.insight.review.TopicEvidence
import dev.insight.prompts.PromptTemplates

/** Files are classified four at a time; each request sees the topics saved when it started. */
internal const val CODE_TOPIC_CONCURRENCY = 4
/** Excerpt characters of a file that already has a summary, and of one without. */
internal const val CODE_TOPIC_EXCERPT_WITH_SUMMARY = 4_000
internal const val CODE_TOPIC_EXCERPT = 12_000
/** Characters of part summaries shown for a large file. */
internal const val CODE_TOPIC_PARTS_CHARS = 3_000

/**
 * What a topic request shows of one source file: its path and line count, its summary and the summaries of its parts when
 * /summaries wrote them, and the head of its code. The excerpt is shorter when a summary describes the whole file. Only an
 * excerpt that was cut with no summary beside it counts as cut evidence, so the read tool is offered only then.
 * [summaries] is read at each request, so summaries written meanwhile are used.
 */
internal fun codeTopicEvidence(snapshot: CodeSnapshot, summaries: () -> ReviewSummaries): (ReviewSource, ReviewTopics) -> TopicEvidence {
    // The file and part nodes of the latest tree, indexed once per tree rather than once per file.
    var indexed: ReviewSummaryTree? = null
    var files: Map<String, SummaryNode> = emptyMap()
    var parts: Map<String, List<SummaryNode>> = emptyMap()
    val lock = Any()
    return { source, _ ->
        val file = source as? CodeFile ?: snapshot.byPath[source.path]
        val current = summaries()
        val (fileNode, partNodes) = synchronized(lock) {
            if (indexed !== current.tree) {
                indexed = current.tree
                files = current.tree.nodes.filter { it.kind == SummaryKind.FILE }.associateBy { it.path }
                // Parts in line order; part groups are left out, as their parts say more.
                parts = current.tree.nodes.filter { it.kind == SummaryKind.CHUNK && it.children.isEmpty() }.groupBy { it.path }
            }
            files[source.path] to parts[source.path].orEmpty()
        }
        val summary = fileNode?.let { current.text(it.id) }
        val fields = linkedMapOf<String, Any?>("path" to source.path)
        var cut = false
        if (file != null) {
            fields["lines"] = file.lineCount
            summary?.let { fields["summary"] = it }
            if (summary == null) partSummaries(current, partNodes)?.let { fields["parts"] = it }
            val excerpt = excerpt(file, if (summary != null) CODE_TOPIC_EXCERPT_WITH_SUMMARY else CODE_TOPIC_EXCERPT)
            fields["excerpt"] = excerpt.text
            excerpt.cutAtLine?.let { fields["excerpt_cut_at_line"] = it }
            cut = excerpt.cutAtLine != null && summary == null
        }
        TopicEvidence(fields, cut)
    }
}

/** "a-b: summary" for each written part, in line order, within [CODE_TOPIC_PARTS_CHARS]; null when none is written. */
private fun partSummaries(summaries: ReviewSummaries, parts: List<SummaryNode>): List<String>? {
    val lines = mutableListOf<String>()
    var chars = 0
    for (part in parts.sortedBy { it.lines?.first ?: 0 }) {
        val text = summaries.text(part.id) ?: continue
        val range = part.lines ?: continue
        val line = "${range.first}-${range.last}: $text"
        val room = CODE_TOPIC_PARTS_CHARS - chars
        if (room < 80) break
        lines += if (line.length <= room) line else line.take(room - 1) + "…"
        chars += minOf(line.length, room)
    }
    return lines.takeIf { it.isNotEmpty() }
}

private class Excerpt(val text: String, val cutAtLine: Int?)

/** The whole lines that fit in [chars]; the first line is cut when it alone is longer. [Excerpt.cutAtLine] is the first line not fully shown. */
private fun excerpt(file: CodeFile, chars: Int): Excerpt {
    val text = file.text.removeSuffix("\n")
    if (text.length <= chars) return Excerpt(text, null)
    val end = text.lastIndexOf('\n', chars)
    if (end <= 0) return Excerpt(text.take(chars), 1)
    val shown = text.substring(0, end)
    return Excerpt(shown, shown.count { it == '\n' } + 2)
}

/** [dev.insight.review.ReviewTopicGenerator.SYSTEM] for source files instead of diffs. */
internal val CODE_TOPIC_SYSTEM = PromptTemplates.render("code-topic-system.md", mapOf("CODE_AS_DATA" to CODE_AS_DATA))
