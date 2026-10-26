package dev.insight.codereview

import dev.insight.review.ReviewDiskCache
import dev.insight.review.ReviewSource
import dev.insight.review.ReviewSourceBlock
import dev.insight.review.ReviewSourceRange
import dev.insight.review.ReviewStateMessages
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.ReviewTabState
import dev.insight.review.ReviewTopics
import dev.insight.review.ReviewVocabulary
import dev.insight.review.buildSummaryTree
import dev.insight.review.buildTopicSummaryTree

/** The summary tree of code files: folders and files, with the parts of large files beneath them. */
internal fun codeSummaryTree(files: List<CodeFile>): ReviewSummaryTree =
    buildSummaryTree(files, rootLabel = ReviewVocabulary.CODE.rootName, chunks = ::codeChunks)

internal fun codeTopicSummaryTree(topics: ReviewTopics): ReviewSummaryTree = buildTopicSummaryTree(topics, chunks = ::codeChunks)

private fun codeChunks(source: ReviewSource) = (source as? CodeFile)?.chunks.orEmpty()

/**
 * EDT-owned state of one code review tab. Every tool is checked against the same revision as its evidence. Notes and
 * source blocks belong to file contents: an edit drops those of the edited file. Summaries and topics are kept by
 * content-hash keys, so they survive edits elsewhere, refreshes and scope changes; only Regenerate clears them.
 */
class CodeReviewState(paths: Set<String>? = null, cache: ReviewDiskCache? = null) : ReviewTabState<CodeFile>(
    "code", paths, cache, ::codeSummaryTree, ::codeTopicSummaryTree, ReviewStateMessages(
        refreshing = "The code is refreshing. Retry after refresh completes.",
        changed = "The files changed. Start a new request to review the current code.",
        notePaths = "Use 1 to 30 file paths in scope.",
        unknownFile = { "$it is not a file in this review's scope." },
    ),
) {
    /** Null follows the whole snapshot; a fixed set of files and folders stays scoped as the snapshot changes. */
    override val scopePaths: Set<String>? = paths?.map { it.trim('/') }?.toSet()
    var snapshot = CodeSnapshot.empty()
        private set
    override val snapshotFiles: List<CodeFile> get() = snapshot.files
    override fun hasFile(path: String): Boolean = path in snapshot.byPath
    override fun resolveSource(range: ReviewSourceRange): ReviewSourceBlock = resolveCodeSource(snapshot, range)

    /** Whether [path] belongs to this tab: everything without a fixed scope, else a listed file or a file in a listed folder. */
    fun inScope(path: String): Boolean = scopePaths == null || path in scopePaths ||
        scopePaths.any { it.isEmpty() || path.startsWith("$it/") }

    fun update(globalSnapshot: CodeSnapshot) {
        val next = if (scopePaths == null) globalSnapshot else globalSnapshot.copy(
            files = globalSnapshot.files.filter { inScope(it.path) },
            skipped = globalSnapshot.skipped.filter { inScope(it.path) },
        )
        if (next.files != snapshot.files || next.scope != snapshot.scope || revision == 0) {
            val old = snapshot.byPath
            val fresh = next.byPath
            fun changed(path: String) = fresh[path] == null || fresh[path]?.fingerprint != old[path]?.fingerprint
            // The scope alone picks the cache slot; summaries and topics are never reset by a refresh.
            applyRevision(next.files, listOf(next.scope.path, next.scope.entry, next.scope.excludes), reset = false, ::changed)
        }
        snapshot = next
        refreshing = false
    }
}
