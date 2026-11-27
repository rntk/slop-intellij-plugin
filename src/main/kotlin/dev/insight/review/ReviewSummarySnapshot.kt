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

    fun isPending(node: SummaryNode): Boolean = running || node.id in pendingIds

    fun fillTarget(node: SummaryNode): SummaryNode? = summaries.fillTarget(node)
}
