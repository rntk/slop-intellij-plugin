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
    data object Grouping : CanvasTabKey
    data object Words : CanvasTabKey
    data object DiffOverview : CanvasTabKey
    data object Semantic : CanvasTabKey
    data object Moves : CanvasTabKey
    /** The code review's code map: classes and files grouped into flows read from their roots, with the shared code they use. */
    data object CodeMap : CanvasTabKey
    /** The review's wall of file blocks: the Diffs wall, or a code review's wall of source files. */
    data object Wall : CanvasTabKey
    sealed interface SummaryPage : CanvasTabKey
    data object Summaries : SummaryPage
    data object Hierarchy : SummaryPage
    data object Document : SummaryPage
    /** Charts that compare how much the folders and files of the review hold, starting with the radar. */
    data object Charts : CanvasTabKey
    /** Markdown and text report viewer with smart clickable project paths and context picker support. */
    data object ReportViewer : CanvasTabKey
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

