package dev.insight.diff

/** Source and file navigation supplied by the review hosting the canvas. */
internal data class SourceCanvasActions(
    val openSymbol: (DiffSymbol) -> Unit = {},
    val removeSource: (DiffSourceRange) -> Unit = {},
    val showDiffCard: (String) -> Unit = {},
    val removeDiff: (String) -> Unit = {},
    val removeFlows: () -> Unit = {},
    /** Open a file at a line; null falls back to the canvas file opener. */
    val openFileAt: ((String, Int?) -> Unit)? = null,
    /** Put file lines on the canvas; null falls back to showing the file by path. */
    val showFileLines: ((String, IntRange) -> Unit)? = null,
    val removeFileView: () -> Unit = {},
)

/** Summary generation, tracing, review and entry actions supplied by the review hosting the canvas. */
internal data class SummaryCanvasActions(
    val summarize: () -> Unit = {},
    val removeSummaries: () -> Unit = {},
    /** Launch summary word scoring and search; null hides tracing actions. */
    val traceSummaries: SummaryTraceLauncher? = null,
    /** Open a summary node at its source; null opens its file by path. */
    val openNode: ((SummaryNode) -> Unit)? = null,
    /** Put a summary node on the canvas; null shows its file by path. */
    val showNode: ((SummaryNode) -> Unit)? = null,
    /** Open traced lines; null opens the summary node. */
    val openLines: ((SummaryNode, LineTrace) -> Unit)? = null,
    val contextSummaries: ContextSummaryLauncher? = null,
    val deleteOriginalSummaries: (() -> Unit)? = null,
    val openWholeFile: ((ReviewFile) -> Unit)? = null,
    val openDirectoryDiff: ((List<ReviewFile>) -> Unit)? = null,
    /** Ask the review chat to explain an entry; null hides Explain. */
    val explainNode: ((SummaryNode) -> Unit)? = null,
    /** Open a chat with an entry’s displayed hierarchy and summary version; null hides To Chat. */
    val chatNode: ((SummaryNode, ChangeSummaries) -> Unit)? = null,
    val markReviewed: ((SummaryNode, Boolean) -> Unit)? = null,
    val reviewSummaries: SummaryReviewLauncher? = null,
)

/** Topic classification and summary updates supplied by the review hosting the canvas. */
internal data class TopicCanvasActions(
    val generateTopics: () -> Unit = {},
    val fillTopicSummaries: () -> Unit = {},
    val removeTopics: () -> Unit = {},
    /** Persist topic fills and resummarizations as they arrive; null hides those entry actions. */
    val saveTopicSummaries: ((ChangeSummaries, Set<Int>) -> Unit)? = null,
)

/** Metric card removal supplied by the review hosting the canvas. */
internal data class MetricCardActions(
    val removeWords: () -> Unit = {},
    val removeDiffOverview: () -> Unit = {},
    val removeSemantic: () -> Unit = {},
)
