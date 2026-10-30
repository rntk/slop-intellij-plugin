package dev.insight.review

data class ReviewNote(
    val paths: List<String>,
    val text: String,
    val symbol: String? = null,
    val evidence: ReviewSymbolEvidence? = null,
) {
    internal val key get() = ReviewNoteKey(paths, symbol)
}

/** One tab of a review canvas: a fixed view, a summary search, or a note, source or diff by its [ReviewNoteKey]. */
internal sealed interface CanvasTabKey {
    data object Explanation : CanvasTabKey
    data object Topics : CanvasTabKey
    data object Words : CanvasTabKey
    data object DiffOverview : CanvasTabKey
    data object Semantic : CanvasTabKey
    data object Moves : CanvasTabKey
    data object Summaries : CanvasTabKey
    data object FileView : CanvasTabKey
    data object SymbolFlow : CanvasTabKey
    data class SummarySearch(val id: Int) : CanvasTabKey
}

internal data class ReviewNoteKey(
    val paths: List<String>,
    val symbol: String?,
    val source: ReviewSourceRange? = null,
    val diff: String? = null,
) : CanvasTabKey
internal fun diffCardKey(path: String) = ReviewNoteKey(listOf(path), null, diff = path)

