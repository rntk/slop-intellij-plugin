package dev.insight.review.ui

import dev.insight.review.ExplainResult
import dev.insight.review.ReviewNote
import dev.insight.review.ReviewSourceBlock

/**
 * Complete content of the shared canvas pages. Partial updates copy this snapshot so unrelated tabs and their inputs survive.
 * A review's own pages keep their content in its [CanvasExtension].
 */
internal data class CanvasState(
    val notes: List<ReviewNote> = emptyList(),
    val sources: List<ReviewSourceBlock> = emptyList(),
    val summaries: SummariesCard? = null,
    val topics: TopicsCard? = null,
    val fileView: FileViewCard? = null,
    val explanation: ExplainResult? = null,
)
