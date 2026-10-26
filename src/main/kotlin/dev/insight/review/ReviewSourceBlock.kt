package dev.insight.review

/** One-based inclusive coordinates in an immutable side of the review snapshot. */
data class ReviewSourceRange(val path: String, val startLine: Int, val endLine: Int, val side: String = "after")
data class ReviewSourceBlock(val range: ReviewSourceRange, val text: String) {
    internal val key get() = ReviewNoteKey(listOf(range.path), null, range)
}

