package dev.insight.review.ui

import dev.insight.review.CanvasTabKey
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummarySnapshot
import dev.insight.review.ReviewTopics

internal fun SummariesCard(
    summaries: ReviewSummaries,
    running: Boolean,
    canRun: Boolean,
    pages: Set<CanvasTabKey.SummaryPage> = setOf(CanvasTabKey.Summaries),
) = SummariesCard(ReviewSummarySnapshot(summaries, running), canRun, pages)

internal fun ChartsCard(
    summaries: ReviewSummaries,
    pendingEntries: Set<Int> = emptySet(),
    revision: Int = 0,
    topics: ReviewTopics? = null,
    topicSummaries: ReviewSummaries? = null,
    grouping: ReviewTopics? = null,
    groupingSummaries: ReviewSummaries? = null,
) = ChartsCard(ReviewSummarySnapshot(summaries, pendingIds = pendingEntries), revision, topics, topicSummaries, grouping, groupingSummaries)
