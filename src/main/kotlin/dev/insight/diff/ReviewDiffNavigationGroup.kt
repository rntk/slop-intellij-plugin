package dev.insight.diff

import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.util.Key
import dev.insight.review.ReviewCodeLocation
import dev.insight.review.ReviewCodeNavigation

/** Only our captured comparisons participate; other IDE diffs keep their usual menus. */
internal class ReviewDiffNavigationGroup : ActionGroup() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(DiffDataKeys.DIFF_REQUEST)?.getUserData(REVIEW_DIFF_FILE) != null
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> =
        if (e == null) emptyArray() else ReviewCodeNavigation.actions(e.project, ::reviewDiffLocation).toTypedArray()
}

internal val REVIEW_DIFF_FILE = Key.create<ReviewFile>("insight.review.diff.source")

internal fun reviewDiffLocation(e: AnActionEvent): ReviewCodeLocation? {
    val request = e.getData(DiffDataKeys.DIFF_REQUEST) as? ContentDiffRequest ?: return null
    val file = request.getUserData(REVIEW_DIFF_FILE) ?: return null
    // The fallback request shows a patch as plain text, with no source coordinates.
    if (file.before == null || file.after == null) return null
    val editor = e.getData(CommonDataKeys.EDITOR) ?: e.getData(DiffDataKeys.CURRENT_EDITOR) ?: return null
    val position = editor.caretModel.logicalPosition
    val column = editor.caretModel.offset - editor.document.getLineStartOffset(position.line)
    val viewer = e.getData(DiffDataKeys.DIFF_VIEWER)
    val side: Int
    val line: Int
    if (viewer is UnifiedDiffViewer) {
        if (!viewer.isContentGood) return null
        val mapped = viewer.transferLineFromOneside(position.line)
        side = mapped.second.index
        line = mapped.second.select(mapped.first)
        if (viewer.transferLineFromOnesideStrict(mapped.second, position.line) < 0) return null
    } else {
        side = request.contents.indexOfFirst { (it as? DocumentContent)?.document === editor.document }
        line = position.line
    }
    if (side !in 0..1 || line < 0) return null
    return ReviewCodeLocation(
        if (side == 0) file.previousPath ?: file.path else file.path,
        if (side == 0) file.before else file.after,
        line + 1, column,
        absolutePath = if (side == 0 && file.previousPath != null) null else file.absolutePath,
    )
}
