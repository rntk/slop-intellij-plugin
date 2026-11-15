package dev.insight.context

import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import dev.insight.agent.SourceSide
import dev.insight.diff.ReviewDiffCaret
import dev.insight.diff.reviewDiffCaret

/**
 * `Insight.PickContext`, "Context...": opens the context picker at the caret of an editor, with the selection, line, enclosing
 * symbols, changed lines and file as scopes. In a captured review diff of the IDE's diff viewer the scopes are those of the diff
 * at the caret's side and line; in any other diff viewer they are those of the editor's document.
 */
internal class PickContextAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = e.project != null && editor != null &&
            (e.getData(CommonDataKeys.VIRTUAL_FILE) != null || e.getData(DiffDataKeys.DIFF_REQUEST) != null)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val diff = reviewDiffCaret(e)
        val editor = diff?.editor ?: e.getData(CommonDataKeys.EDITOR) ?: return
        val pick = if (diff != null) reviewDiffPick(project, diff) else {
            val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: FileDocumentManager.getInstance().getFile(editor.document) ?: return
            val origin = if (e.getData(DiffDataKeys.DIFF_REQUEST) != null) ContextOrigin.IDE_DIFF else ContextOrigin.EDITOR
            editorPick(project, origin, editor, file, e.getData(CommonDataKeys.PSI_FILE))
        }
        showContextPicker(project, pick, editor.contentComponent, JBPopupFactory.getInstance().guessBestPopupLocation(editor))
    }

    private fun reviewDiffPick(project: com.intellij.openapi.project.Project, caret: ReviewDiffCaret): ContextPick {
        val side = if (caret.side == 0) SourceSide.BEFORE else SourceSide.AFTER
        return reviewDiffPick(project, ContextOrigin.IDE_DIFF, caret.file, caret.line?.plus(1), side, null,
            ContextData().put(ContextKeys.EDITOR, caret.editor).put(ContextKeys.OFFSET, caret.editor.caretModel.offset))
    }
}
