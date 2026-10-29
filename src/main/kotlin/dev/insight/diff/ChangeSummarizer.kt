package dev.insight.diff

import com.google.gson.JsonObject
import dev.insight.agent.ChatTool
import dev.insight.llm.CODE_AS_DATA
import dev.insight.llm.CompletionFn
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
import dev.insight.prompts.PromptTemplates

/** Supplies VCS patch evidence to the shared summary pipeline. */
internal fun diffSummarizer(
    complete: CompletionFn,
    readTool: ChatTool?,
    options: ReviewSummaryOptions = ReviewSummaryOptions(),
    evidence: SummaryEvidence = DiffSummaryEvidence(),
): ReviewSummarizer = ReviewSummarizer(complete, readTool, options, evidence)

/** A changed file's patch and provenance. A summary cites the patch's own lines, numbered from 1. */
internal class DiffSummaryEvidence : SummaryEvidence {
    override fun size(node: SummaryNode): Int = (node.file as? ReviewFile)?.patch?.length ?: 0

    override fun rows(node: SummaryNode): IntRange = 1..((node.file as? ReviewFile)?.let { patchRows(it.patch) } ?: 0)

    override fun changedRows(node: SummaryNode): Set<Int>? = (node.file as? ReviewFile)?.patch?.lineSequence()
        ?.mapIndexedNotNull { index, line -> (index + 1).takeIf { line.startsWith('+') || line.startsWith('-') } }?.toSet()

    override fun evidenceKind(node: SummaryNode): String = node.files.map(::diffEvidenceKind).distinct().singleOrNull() ?: "mixed"

    override fun pages(node: SummaryNode, limit: Int): List<SummaryEvidencePage>? {
        val file = node.file as? ReviewFile ?: return null
        if (file.patch.length <= limit) return null
        require(limit > 0) { "Patch page size must be positive" }
        val pages = mutableListOf<SummaryEvidencePage>()
        val lines = file.patch.lineSequence().toList()
        val changed = changedRows(node).orEmpty()
        val hunks = java.util.TreeMap<Int, String>().apply {
            lines.forEachIndexed { index, line -> if (line.startsWith("@@")) put(index + 1, line) }
        }
        var offset = 0
        var firstLine = 1
        while (offset < file.patch.length) {
            val end = patchPageEnd(file.patch, offset, (offset + limit).coerceAtMost(file.patch.length))
            val text = file.patch.substring(offset, end)
            val lastLine = firstLine + patchRows(text) - 1
            val pageChanges = (firstLine..lastLine).filterTo(mutableSetOf()) { it in changed }
            val item = JsonObject().apply {
                addProperty("path", file.path)
                addProperty("status", file.status)
                addProperty("first_line", firstLine)
                addProperty("last_line", lastLine)
                addProperty("first_line_continues", offset > 0 && file.patch[offset - 1] != '\n')
                addProperty("last_line_continues", end < file.patch.length && file.patch[end - 1] != '\n')
                addProperty("patch", numberedRows(text, firstLine))
                addPatchContext(file.path, lines, firstLine..lastLine, hunks.floorEntry(pageChanges.minOrNull() ?: firstLine)?.value)
            }
            // A page of unchanged context cannot establish a change of its own.
            if (pageChanges.isNotEmpty() || changed.isEmpty())
                pages += SummaryEvidencePage(item, firstLine..lastLine, pageChanges)
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
        val lines = file.patch.lineSequence().toList()
        item.addPatchContext(file.path, lines, 1..patchRows(file.patch.take(cut)), lines.firstOrNull()?.takeIf { it.startsWith("@@") })
        if (cut < file.patch.length) {
            item.addProperty("patch_cut_at", cut)
            item.addProperty("patch_length", file.patch.length)
            item.addProperty("patch_lines", patchRows(file.patch))
        }
    }

    /** Fixed instructions stay cacheable across files and focus changes; each node supplies its own context. */
    override fun leafSystem(tree: ReviewSummaryTree, focus: String): String {
        return "$SYSTEM_PREFIX\n" + PromptTemplates.load("diff-summary-leaf.md") + "\n" + LEAF_CITATION_GUIDANCE + summaryFocusLine(focus)
    }

    override fun branchSystem(focus: String): String = "$SYSTEM_PREFIX\n" + PromptTemplates.load("diff-summary-branch.md") + "\n" + BRANCH_CITATION_GUIDANCE + summaryFocusLine(focus)

    private companion object {
        val SYSTEM_PREFIX = PromptTemplates.render(
            "diff-summary-prefix.md", mapOf("CODE_AS_DATA" to CODE_AS_DATA, "SUMMARY_JSON_SHAPE" to SUMMARY_JSON_SHAPE),
        ) + "\n" + SUMMARY_WRITING_GUIDANCE + "\n" + SUMMARY_SEGMENT_GUIDANCE
    }
}

/** Path hints describe the evidence, not proof that a runtime feature changed. */
private fun diffEvidenceKind(path: String): String {
    val name = path.substringAfterLast('/').lowercase()
    return when {
        path.split('/').any { it.lowercase() in setOf("test", "tests", "testfixtures", "androidtest") } ||
            name.substringBeforeLast('.').endsWith("test") || name.startsWith("test_") || name.contains(".test.") || name.contains(".spec.") -> "test"
        name.substringAfterLast('.') in setOf("md", "rst", "adoc") -> "documentation"
        else -> "implementation"
    }
}

private fun JsonObject.addPatchContext(path: String, lines: List<String>, rows: IntRange, hunkHeader: String?) {
    addProperty("evidence_kind", diffEvidenceKind(path))
    val shown = lines.subList((rows.first - 1).coerceAtLeast(0), rows.last.coerceAtMost(lines.size))
    addProperty("added_lines", shown.count { it.startsWith('+') })
    addProperty("removed_lines", shown.count { it.startsWith('-') })
    // Carry the nearest hunk header even when a page starts in the middle of a removed declaration.
    hunkHeader?.let { addProperty("hunk_header", it.take(400)) }
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
