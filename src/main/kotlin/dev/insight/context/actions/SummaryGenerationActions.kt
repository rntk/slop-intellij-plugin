package dev.insight.context.actions

import dev.insight.InsightBundle
import dev.insight.context.ContextAction
import dev.insight.context.ContextKeys
import dev.insight.context.ContextRequest
import dev.insight.context.ContextScopeKind
import dev.insight.context.EDITOR_DIFF_SUMMARY
import dev.insight.review.SummaryKind

/** Generation always fills the selected directory or the containing file of a source or diff scope. */
internal abstract class SummaryGenerationAction(
    override val id: String,
    private val titleKey: String,
    private val kind: SummaryKind,
    private val matches: (ContextRequest) -> Boolean,
) : ContextAction {
    override val title get() = InsightBundle.message(titleKey)
    override val group = "Explore"
    override val order = 51

    private fun ContextRequest.targetPath(): String? =
        if (kind == SummaryKind.FILE) data[ContextKeys.REVIEW_FILE]?.path ?: context.path else context.path

    private fun ContextRequest.reviewActions() = data[ContextKeys.SUMMARY_GENERATION]?.takeIf { actions ->
        data[ContextKeys.REVIEW_FILE]?.let(actions.matchesDiff) != false
    }

    override fun isAvailable(request: ContextRequest): Boolean {
        val path = request.targetPath() ?: return false
        if (!matches(request)) return false
        val review = request.reviewActions()
        return review?.available?.invoke(path, kind) ?: ContextSummaryGeneration.canGenerate(request)
    }

    override fun perform(request: ContextRequest) {
        if (!isAvailable(request)) return
        val review = request.reviewActions()
        if (review != null) review.generate(request.targetPath() ?: return, kind)
        else ContextSummaryGeneration.generate(request)
    }
}

private fun ContextRequest.isFileContext() = context.kind != ContextScopeKind.DIRECTORY && context.kind != ContextScopeKind.CHANGE

private fun ContextRequest.isDiffContext() = ContextKeys.REVIEW_FILE in data || EDITOR_DIFF_SUMMARY in data || context.isPatch

internal class SummarizeFileContextAction : SummaryGenerationAction(
    "explore.summarizeFile", "context.action.summarize.file", SummaryKind.FILE, { it.isFileContext() && !it.isDiffContext() },
)

internal class SummarizeFileDiffContextAction : SummaryGenerationAction(
    "explore.summarizeFileDiff", "context.action.summarize.file.diff", SummaryKind.FILE, { it.isFileContext() && it.isDiffContext() },
)

internal class SummarizeDirectoryContextAction : SummaryGenerationAction(
    "explore.summarizeDirectory", "context.action.summarize.directory", SummaryKind.DIRECTORY, { it.context.kind == ContextScopeKind.DIRECTORY },
)
