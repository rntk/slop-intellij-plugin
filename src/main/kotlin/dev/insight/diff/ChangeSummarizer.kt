package dev.insight.diff

import com.google.gson.JsonObject
import dev.insight.chat.ChatTool
import dev.insight.enrichment.CODE_AS_DATA
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.review.ReviewSummarizer
import dev.insight.review.ReviewSummaryOptions
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.SUMMARY_WRITING_GUIDANCE
import dev.insight.review.SummaryEvidence
import dev.insight.review.SummaryNode
import dev.insight.review.summaryFocusLine

/** Supplies VCS patch evidence to the shared summary pipeline. */
internal fun diffSummarizer(
    complete: suspend (CompletionRequest) -> Completion,
    readTool: ChatTool?,
    options: ReviewSummaryOptions = ReviewSummaryOptions(),
    evidence: SummaryEvidence = DiffSummaryEvidence(options),
): ReviewSummarizer = ReviewSummarizer(complete, readTool, options, evidence)

/** A changed file's patch, with the changed paths as context. */
internal class DiffSummaryEvidence(private val options: ReviewSummaryOptions) : SummaryEvidence {
    override fun size(node: SummaryNode): Int = (node.file as? ReviewFile)?.patch?.length ?: 0

    override fun describe(node: SummaryNode, item: JsonObject, limit: Int) {
        val file = node.file as? ReviewFile ?: return
        item.addProperty("path", file.path)
        item.addProperty("status", file.status)
        file.previousPath?.let { item.addProperty("previous_path", it) }
        item.addProperty("lines", changedLines(file))
        item.addProperty("patch", file.patch.take(limit))
        if (file.patch.length > limit) {
            item.addProperty("patch_cut_at", limit)
            item.addProperty("patch_length", file.patch.length)
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
            Every node in "nodes" is a changed file with its patch inline. State the main behavioral change
            or purpose of the edit. Include the reason only when the supplied evidence establishes it.
            The inline patch is usually enough, so answer from it directly. When read_diff_files is offered, call it only if
            you cannot tell what a file's change does without more:
            - its patch was cut (patch_cut_at is set): read the rest with that one path and offset: patch_cut_at;
            - its patch is too small to understand alone: read the diff of a related file from the list below.

            All files in the change, for context only; summarize just the files in "nodes":
        """.trimIndent() + "\n" + overview.toString().trimEnd() + summaryFocusLine(focus)
    }

    override fun branchSystem(focus: String): String = "$SYSTEM_PREFIX\n" + """
        Every node in "nodes" is a branch of the tree, listed with its children's
        summaries. Using only those summaries, condense each node's changes into their main themes without
        listing the children or repeating subordinate details. Preserve meaningful qualifications and do not
        force unrelated changes into a shared purpose or infer broader guarantees.
        If a node has kind "root", summarize the overall change.
    """.trimIndent() + summaryFocusLine(focus)

    private companion object {
        val SYSTEM_PREFIX = """
            $CODE_AS_DATA
            You write concise summaries for a code change shown as a tree.
            This request covers only the nodes in "nodes". Return every id in "nodes" exactly once. Return only JSON matching {"summaries":[{"id":1,"summary":"…"}]}.
        """.trimIndent() + "\n" + SUMMARY_WRITING_GUIDANCE
    }
}

internal fun changedLines(file: ReviewFile): String =
    "+${file.patch.lineSequence().count { it.startsWith('+') }} -${file.patch.lineSequence().count { it.startsWith('-') }}"
