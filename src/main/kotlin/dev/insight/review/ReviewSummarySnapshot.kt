package dev.insight.review

/**
 * Immutable summary projection shared across review views (Summaries, Charts, Walls, File view).
 * Combines the [summaries] tree with generation progress state so every view queries pending status,
 * file/node texts, and fill targets uniformly.
 */
internal data class ReviewSummarySnapshot(
    val summaries: ReviewSummaries,
    val running: Boolean = false,
    val canGenerate: Boolean = true,
    val pendingIds: Set<Int> = emptySet(),
    val pendingFiles: Set<String> = emptySet(),
) {
    val tree: ReviewSummaryTree get() = summaries.tree
    val reading: ReviewSummaries get() = summaries.reading
    val fileTexts: Map<String, String> get() = summaries.fileTexts
    val nodeTexts: Map<String, String> get() = summaries.nodeTexts
    /** The per-file slice, for views that show files rather than the tree. */
    val files: FileSummaries by lazy { FileSummaries(fileTexts, pendingFiles) }

    fun isPending(node: SummaryNode): Boolean = running || node.id in pendingIds

    fun fillTarget(node: SummaryNode): SummaryNode? = summaries.fillTarget(node)
}

/** File summary prose by path, and the paths whose summary is being written: what a view of files needs from the summaries. */
internal data class FileSummaries(
    val texts: Map<String, String> = emptyMap(),
    val pending: Set<String> = emptySet(),
) {
    fun text(path: String): String? = texts[path]
    fun isPending(path: String): Boolean = text(path) == null && path in pending
    /** The file has a summary or one is on its way. */
    fun covers(path: String): Boolean = path in texts || path in pending
}
