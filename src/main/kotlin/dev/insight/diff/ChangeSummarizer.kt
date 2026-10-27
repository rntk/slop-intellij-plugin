package dev.insight.diff

import com.google.gson.JsonObject
import dev.insight.chat.ChatTool
import dev.insight.enrichment.CODE_AS_DATA
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.review.BRANCH_CITATION_GUIDANCE
import dev.insight.review.LEAF_CITATION_GUIDANCE
import dev.insight.review.ReviewSummarizer
import dev.insight.review.ReviewSummaryOptions
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.SUMMARY_JSON_SHAPE
import dev.insight.review.SUMMARY_SEGMENT_GUIDANCE
import dev.insight.review.SUMMARY_WRITING_GUIDANCE
import dev.insight.review.SummaryEvidence
import dev.insight.review.SummaryEvidencePage
import dev.insight.review.SummaryNode
import dev.insight.review.numberedRows
import dev.insight.review.summaryFocusLine

/** Supplies VCS patch evidence to the shared summary pipeline. */
internal fun diffSummarizer(
    complete: suspend (CompletionRequest) -> Completion,
    readTool: ChatTool?,
    options: ReviewSummaryOptions = ReviewSummaryOptions(),
    evidence: SummaryEvidence = DiffSummaryEvidence(options),
): ReviewSummarizer = ReviewSummarizer(complete, readTool, options, evidence)

/** A changed file's patch, with the changed paths as context. A summary cites the patch's own lines, numbered from 1. */
internal class DiffSummaryEvidence(private val options: ReviewSummaryOptions) : SummaryEvidence {
    override fun size(node: SummaryNode): Int = (node.file as? ReviewFile)?.patch?.length ?: 0

    override fun rows(node: SummaryNode): IntRange = 1..((node.file as? ReviewFile)?.let { patchRows(it.patch) } ?: 0)

    override fun pages(node: SummaryNode, limit: Int): List<SummaryEvidencePage>? {
        val file = node.file as? ReviewFile ?: return null
        if (file.patch.length <= limit) return null
        require(limit > 0) { "Patch page size must be positive" }
        val pages = mutableListOf<SummaryEvidencePage>()
        var offset = 0
        var firstLine = 1
        while (offset < file.patch.length) {
            val end = patchPageEnd(file.patch, offset, (offset + limit).coerceAtMost(file.patch.length))
            val text = file.patch.substring(offset, end)
            val lastLine = firstLine + patchRows(text) - 1
            pages += SummaryEvidencePage(JsonObject().apply {
                addProperty("path", file.path)
                addProperty("status", file.status)
                addProperty("first_line", firstLine)
                addProperty("last_line", lastLine)
                addProperty("first_line_continues", offset > 0 && file.patch[offset - 1] != '\n')
                addProperty("last_line_continues", end < file.patch.length && file.patch[end - 1] != '\n')
                addProperty("patch", numberedRows(text, firstLine))
            }, firstLine..lastLine)
            firstLine += text.count { it == '\n' }
            offset = end
        }
        return pages
    }

    override fun describe(node: SummaryNode, item: JsonObject, limit: Int) {
        val file = node.file as? ReviewFile ?: return
        item.addProperty("path", file.path)
        item.addProperty("status", file.status)
        file.previousPath?.let { item.addProperty("previous_path", it) }
        item.addProperty("lines", changedLines(file))
        val cut = patchPageEnd(file.patch, 0, limit)
        item.addProperty("patch", numberedRows(file.patch.take(cut), 1))
        if (cut < file.patch.length) {
            item.addProperty("patch_cut_at", cut)
            item.addProperty("patch_length", file.patch.length)
            item.addProperty("patch_lines", patchRows(file.patch))
        }
    }

    /** File requests share this prefix, with the per-run focus last so the overview stays cached across focus changes. */
    override fun leafSystem(tree: ReviewSummaryTree, focus: String): String {
        val overview = StringBuilder()
        for (node in tree.nodes) {
            val file = node.file as? ReviewFile ?: continue
            val line = "${file.path} (${file.status}, ${changedLines(file)})\n"
            if (overview.length + line.length > options.overviewChars) { overview.append("…\n"); break }
            overview.append(line)
        }
        return "$SYSTEM_PREFIX\n" + """
            Every node in "nodes" is a changed file or a patch part (kind "chunk") with its evidence inline.
            For a part, summarize only the shown changes. Other parts are processed separately and combined by the application.
            first_line_continues and last_line_continues mean a long line crosses a page boundary; do not infer its unseen text.
            The evidence lines are the lines of the patch, hunk headers included, numbered from 1.
            After the "N|" line-number prefix, "-" marks removed text and "+" marks added text; a space marks unchanged
            context. Context may explain a change but is not itself a change. Hunk headers are navigation, not changes.
            Describe before/after behavior only when supported by changed lines; preserve qualifications and exceptions.
            A file with status
            "deleted" no longer exists: describe what was removed, such as "Removes the scorer that …", never what it provides.
        """.trimIndent() + "\n" + LEAF_CITATION_GUIDANCE + "\n" + """
            Answer from the supplied evidence. Cite only the numbered rows shown, never character offsets.

            All files in the change, for context only; summarize just the files in "nodes":
        """.trimIndent() + "\n" + overview.toString().trimEnd() + summaryFocusLine(focus)
    }

    override fun branchSystem(focus: String): String = "$SYSTEM_PREFIX\n" + """
        Every node in "nodes" is a branch of the tree, listed with its children's
        summaries. Using only those summaries, condense each node's changes into their main themes without
        listing the children or repeating subordinate details. Preserve meaningful qualifications and do not
        force unrelated changes into a shared purpose or infer broader guarantees.
        If a node has kind "root", summarize the overall change.
    """.trimIndent() + "\n" + BRANCH_CITATION_GUIDANCE + summaryFocusLine(focus)

    private companion object {
        val SYSTEM_PREFIX = """
            $CODE_AS_DATA
            You write concise summaries for a code change shown as a tree.
            Return exactly required_output_ids, one summary per node, never one per child. Return only JSON matching $SUMMARY_JSON_SHAPE.
        """.trimIndent() + "\n" + SUMMARY_WRITING_GUIDANCE + "\n" + SUMMARY_SEGMENT_GUIDANCE
    }
}

internal fun changedLines(file: ReviewFile): String =
    "+${file.patch.lineSequence().count { it.startsWith('+') }} -${file.patch.lineSequence().count { it.startsWith('-') }}"

/** The lines of [patch] a summary can cite: every line of its text, without the empty one after a final newline. */
internal fun patchRows(patch: String): Int = if (patch.isEmpty()) 0 else patch.removeSuffix("\n").split('\n').size

/**
 * Where a page of [patch] starting at [start] ends when it may hold at most [end] - [start] characters: after its last
 * complete line, or at [end] when a single line is longer than the page.
 */
internal fun patchPageEnd(patch: String, start: Int, end: Int): Int {
    if (end >= patch.length) return patch.length
    val newline = patch.lastIndexOf('\n', end - 1)
    return if (newline >= start) newline + 1 else end
}

/** The 1-based patch line holding the character at [offset], as [patchRows] numbers them. */
internal fun patchRowAt(patch: String, offset: Int): Int = 1 + (0 until minOf(offset, patch.length)).count { patch[it] == '\n' }
