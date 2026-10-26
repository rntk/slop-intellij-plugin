package dev.insight.diff

import dev.insight.review.ExplainResult
import dev.insight.review.ReviewNote
import dev.insight.review.ReviewSourceBlock

/** Complete canvas content. Partial updates copy this snapshot so unrelated tabs and their inputs survive. */
internal data class CanvasState(
    val notes: List<ReviewNote> = emptyList(),
    val sources: List<ReviewSourceBlock> = emptyList(),
    val diffs: List<ReviewFile> = emptyList(),
    val summaries: SummariesCard? = null,
    val topics: TopicsCard? = null,
    val fileView: FileViewCard? = null,
    val words: WordChanges? = null,
    val wordFiles: List<ReviewFile> = emptyList(),
    val diffOverview: WordChanges? = null,
    val semantic: SemanticResult? = null,
    val explanation: ExplainResult? = null,
)
