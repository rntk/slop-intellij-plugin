package dev.insight.review.ui

import dev.insight.canvas.SymbolSummaryCache
import dev.insight.context.ContextSummaryActions
import dev.insight.review.CanvasTabKey
import dev.insight.review.PatchSource
import dev.insight.review.ContextSummaryLauncher
import dev.insight.review.ReviewSource
import dev.insight.review.ReviewSourceRange
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSymbol
import dev.insight.review.SummaryNode
import dev.insight.llm.CompletionFn
import kotlinx.coroutines.CoroutineScope

/** Source and file navigation supplied by the review hosting the canvas. */
internal data class SourceCanvasActions(
    val openSymbol: (ReviewSymbol) -> Unit = {},
    val removeSource: (ReviewSourceRange) -> Unit = {},
    val showDiffCard: (String) -> Unit = {},
    /** Open a file at a line; null falls back to the canvas file opener. */
    val openFileAt: ((String, Int?) -> Unit)? = null,
    /** Put file lines on the canvas; null falls back to showing the file by path. */
    val showFileLines: ((String, IntRange) -> Unit)? = null,
    val removeFileView: () -> Unit = {},
    /** Parses files into declarations for the Charts hierarchy; null keeps files as leaves. */
    val outlines: dev.insight.review.SourceOutlines? = null,
)

internal class SymbolSummaryActions(
    val scope: CoroutineScope,
    val cache: SymbolSummaryCache,
    val complete: () -> CompletionFn,
)

/** Summary generation, tracing, review and entry actions supplied by the review hosting the canvas. */
internal data class SummaryCanvasActions(
    val summarize: () -> Unit = {},
    val removeSummaries: (CanvasTabKey.SummaryPage) -> Unit = {},
    /** Open a summary node at its source; null opens its file by path. */
    val openNode: ((SummaryNode) -> Unit)? = null,
    /** Put a summary node on the canvas; null shows its file by path. */
    val showNode: ((SummaryNode) -> Unit)? = null,
    /** Open traced lines; null opens the summary node. */
    val openLines: ((SummaryNode, LineTrace) -> Unit)? = null,
    val contextSummaries: ContextSummaryLauncher? = null,
    val deleteOriginalSummaries: (() -> Unit)? = null,
    val openWholeFile: ((PatchSource) -> Unit)? = null,
    val openDirectoryDiff: ((List<PatchSource>) -> Unit)? = null,
    /** Lay out several files, titled by the directory, group or topic they belong to, on the review's wall; null hides the menu item. */
    val openOnWall: ((files: List<ReviewSource>, title: String) -> Unit)? = null,
    /** Open a summary node's file on a canvas session of the review; null leaves a project file to open its own session. */
    val openOnCanvas: ((SummaryNode) -> Unit)? = null,
    /** Ask the review chat to explain an entry; null hides Explain. */
    val explainNode: ((SummaryNode) -> Unit)? = null,
    /** Open a chat with an entry’s displayed hierarchy and summary version; null hides To Chat. */
    val chatNode: ((SummaryNode, ReviewSummaries) -> Unit)? = null,
    val markReviewed: ((SummaryNode, Boolean) -> Unit)? = null,
    val contextSummaryActions: ContextSummaryActions? = null,
    val symbolSummaries: SymbolSummaryActions? = null,
)

/** Topic classification and summary updates supplied by the review hosting the canvas. */
internal data class TopicCanvasActions(
    val generateTopics: () -> Unit = {},
    val fillTopicSummaries: () -> Unit = {},
    val removeTopics: () -> Unit = {},
    /** Persist topic fills and resummarizations as they arrive; null hides those entry actions. */
    val saveTopicSummaries: ((ReviewSummaries, Set<Int>) -> Unit)? = null,
)
