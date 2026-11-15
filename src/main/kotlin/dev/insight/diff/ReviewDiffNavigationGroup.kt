package dev.insight.diff

import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.Editor
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

/**
 * The caret of an IDE diff viewer showing a captured [file]: [side] (0 before, 1 after) and the 0-based [line] and [column] in that
 * side's source, or null for both when the caret has no source coordinates (a patch shown as plain text, a line of the other side).
 */
internal class ReviewDiffCaret(val file: ReviewFile, val editor: Editor, val side: Int?, val line: Int?, val column: Int)

/** Where the caret of the captured diff in [e] is, or null when [e] is not a captured diff with an editor. */
internal fun reviewDiffCaret(e: AnActionEvent): ReviewDiffCaret? {
    val request = e.getData(DiffDataKeys.DIFF_REQUEST) as? ContentDiffRequest ?: return null
    val file = request.getUserData(REVIEW_DIFF_FILE) ?: return null
    val editor = e.getData(CommonDataKeys.EDITOR) ?: e.getData(DiffDataKeys.CURRENT_EDITOR) ?: return null
    val position = editor.caretModel.logicalPosition
    val column = editor.caretModel.offset - editor.document.getLineStartOffset(position.line)
    // The fallback request shows a patch as plain text, with no source coordinates.
    if (file.before == null || file.after == null) return ReviewDiffCaret(file, editor, null, null, column)
    val viewer = e.getData(DiffDataKeys.DIFF_VIEWER)
    val side: Int
    val line: Int
    if (viewer is UnifiedDiffViewer) {
        if (!viewer.isContentGood) return ReviewDiffCaret(file, editor, null, null, column)
        val mapped = viewer.transferLineFromOneside(position.line)
        side = mapped.second.index
        line = mapped.second.select(mapped.first)
        if (viewer.transferLineFromOnesideStrict(mapped.second, position.line) < 0) return ReviewDiffCaret(file, editor, null, null, column)
    } else {
        side = request.contents.indexOfFirst { (it as? DocumentContent)?.document === editor.document }
        line = position.line
    }
    if (side !in 0..1 || line < 0) return ReviewDiffCaret(file, editor, null, null, column)
    return ReviewDiffCaret(file, editor, side, line, column)
}

internal fun reviewDiffLocation(e: AnActionEvent): ReviewCodeLocation? {
    val caret = reviewDiffCaret(e) ?: return null
    val side = caret.side ?: return null
    val line = caret.line ?: return null
    val file = caret.file
    return ReviewCodeLocation(
        if (side == 0) file.previousPath ?: file.path else file.path,
        (if (side == 0) file.before else file.after) ?: return null,
        line + 1, caret.column,
        absolutePath = if (side == 0 && file.previousPath != null) null else file.absolutePath,
    )
}
