package dev.insight.review

import dev.insight.chat.SourceSide

/** One-based inclusive coordinates in an immutable side of the review snapshot. */
data class ReviewSourceRange(val path: String, val startLine: Int, val endLine: Int, val side: SourceSide = SourceSide.AFTER)
data class ReviewSourceBlock(
    val range: ReviewSourceRange,
    val text: String,
    /** Keep the original file context for navigation, including content outside the displayed lines. */
    val fullText: String? = null,
    val absolutePath: String? = null,
    val sourcePath: String = range.path,
) {
    internal val key get() = ReviewNoteKey(listOf(range.path), null, range)
}

