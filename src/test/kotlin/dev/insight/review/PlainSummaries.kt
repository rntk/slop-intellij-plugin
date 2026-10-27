package dev.insight.review

/** Text as summaries without citations, for tests that only need what a summary says. */
internal fun Map<Int, String>.plain(): Map<Int, Summary> = mapValues { Summary.plain(it.value) }

internal fun ReviewSummaries(tree: ReviewSummaryTree, texts: Map<Int, String>, cachedIds: Set<Int> = emptySet()): ReviewSummaries =
    ReviewSummaries(tree, texts.plain(), cachedIds)

internal fun ReviewSummaries.summarize(entries: Map<Int, String>): ReviewSummaries = summarize(entries.plain())

internal fun ReviewSummaries.summarize(entries: Map<Int, String>, reuseSingleChildren: Boolean): ReviewSummaries =
    summarize(entries.plain(), reuseSingleChildren)

internal fun ReviewTabState<*>.summarize(expected: Int, entries: Map<Int, String>): ReviewSummaries = summarize(expected, entries.plain())

internal fun ReviewTabState<*>.summarizeTopics(expected: Int, entries: Map<Int, String>): ReviewSummaries =
    summarizeTopics(expected, entries.plain())
