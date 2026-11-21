package dev.insight.context.actions

import dev.insight.InsightBundle
import dev.insight.context.ContextAction
import dev.insight.context.ContextKeys
import dev.insight.context.ContextRequest
import dev.insight.context.ReviewDestination

/** Opens a view of the original review session, whose scope may be wider than the picked code. */
internal abstract class SessionViewAction(
    private val destination: ReviewDestination,
    private val titleKey: String,
    override val order: Int,
) : ContextAction {
    override val id = "review.${destination.name.lowercase()}"
    override val title get() = InsightBundle.message(titleKey)
    override val group = "Review views"
    override fun isAvailable(request: ContextRequest) = request.data[ContextKeys.REVIEW_DESTINATIONS]?.containsKey(destination) == true
    override fun perform(request: ContextRequest) { request.data[ContextKeys.REVIEW_DESTINATIONS]?.get(destination)?.invoke() }
}

internal class SessionSummariesAction : SessionViewAction(ReviewDestination.SUMMARIES, "context.action.review.summaries", 0)
internal class SessionSummaryHierarchyAction : SessionViewAction(ReviewDestination.HIERARCHY, "context.action.review.hierarchy", 10)
internal class SessionSummaryOutlineAction : SessionViewAction(ReviewDestination.OUTLINE, "context.action.review.outline", 11)
internal class SessionSummaryExplorerAction : SessionViewAction(ReviewDestination.EXPLORER, "context.action.review.explorer", 12)
internal class SessionSummarySplitAction : SessionViewAction(ReviewDestination.SPLIT, "context.action.review.split", 13)
internal class SessionSummaryTreeAction : SessionViewAction(ReviewDestination.TREE, "context.action.review.tree", 14)
internal class SessionSummaryDiffAction : SessionViewAction(ReviewDestination.DIFF, "context.action.review.diff", 15)
internal class SessionSummaryDocumentAction : SessionViewAction(ReviewDestination.DOCUMENT, "context.action.review.document", 16)
internal class SessionTopicsAction : SessionViewAction(ReviewDestination.TOPICS, "context.action.review.topics", 20)
internal class SessionGroupingAction : SessionViewAction(ReviewDestination.GROUPING, "context.action.review.grouping", 30)
internal class SessionChangeMapAction : SessionViewAction(ReviewDestination.CHANGE_MAP, "context.action.review.change_map", 35)
internal class SessionWordsAction : SessionViewAction(ReviewDestination.WORDS, "context.action.review.words", 40)
internal class SessionDiffOverviewAction : SessionViewAction(ReviewDestination.DIFF_OVERVIEW, "context.action.review.diff_overview", 50)
internal class SessionMetricsAction : SessionViewAction(ReviewDestination.METRICS, "context.action.review.metrics", 60)
internal class SessionMovesAction : SessionViewAction(ReviewDestination.MOVES, "context.action.review.moves", 70)
internal class SessionBoardAction : SessionViewAction(ReviewDestination.BOARD, "context.action.review.board", 80)
internal class SessionReportViewerAction : SessionViewAction(ReviewDestination.REPORT_VIEWER, "context.action.review.report_viewer", 90)
