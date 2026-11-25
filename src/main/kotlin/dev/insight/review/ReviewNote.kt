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
    data object Semantic : CanvasTabKey
    data object Moves : CanvasTabKey
    /** The walkthrough of a diff: steps of linked hunks on a canvas of their own. */
    data object Walkthrough : CanvasTabKey
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
    /** The claims, ledger and reports of the project's knowledge base, with the runs that fill and check it. */
    data object Knowledge : CanvasTabKey
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

