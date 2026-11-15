package dev.insight.context

import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.psi.PsiFile
import dev.insight.agent.SourceSide
import dev.insight.diff.ReviewDiffCaret
import dev.insight.diff.reviewDiffCaret
import dev.insight.diff.REVIEW_DIFF_SESSION
import dev.insight.diff.ReviewDiffSession
import dev.insight.canvas.relativePath
import java.awt.Point

/**
 * `Insight.PickContext`, "Context...": opens the context picker at the caret of an editor, with the selection, line, enclosing
 * symbols, changed lines and file as scopes. In a captured review diff of the IDE's diff viewer the scopes are those of the diff
 * at the caret's side and line; in any other diff viewer they are those of the editor's document.
 */
internal class PickContextAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null &&
            ((editor != null && (file != null || e.getData(DiffDataKeys.DIFF_REQUEST) != null)) ||
                (file != null && !file.isDirectory && !file.fileType.isBinary))
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val diff = reviewDiffCaret(e)
        val editor = diff?.editor ?: e.getData(CommonDataKeys.EDITOR)
        if (editor == null) {
            val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return
            val element = e.getData(CommonDataKeys.PSI_ELEMENT)?.takeIf { it !is PsiFile && it.containingFile?.virtualFile == file }
            val line = element?.textRange?.startOffset?.coerceIn(0, document.textLength)?.let { document.getLineNumber(it) + 1 }
            val pick = capturedSourcePick(project, ContextOrigin.OTHER, relativePath(project, file.path), file.path, document.text, line)
            val owner = e.getData(PlatformDataKeys.CONTEXT_COMPONENT) ?: e.inputEvent?.component ?: return
            showContextPicker(project, pick, owner, RelativePoint(owner, Point(owner.width / 2, owner.height / 2)))
            return
        }
        val pick = if (diff != null) reviewDiffPick(project, diff, e.getData(DiffDataKeys.DIFF_REQUEST)?.getUserData(REVIEW_DIFF_SESSION)) else {
            val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: FileDocumentManager.getInstance().getFile(editor.document) ?: return
            val origin = if (e.getData(DiffDataKeys.DIFF_REQUEST) != null) ContextOrigin.IDE_DIFF else ContextOrigin.EDITOR
            editorPick(project, origin, editor, file, e.getData(CommonDataKeys.PSI_FILE))
        }
        showContextPicker(project, pick, editor.contentComponent, JBPopupFactory.getInstance().guessBestPopupLocation(editor))
    }

    private fun reviewDiffPick(project: com.intellij.openapi.project.Project, caret: ReviewDiffCaret, sessionProvider: (() -> ReviewDiffSession?)?): ContextPick {
        val session = sessionProvider?.invoke()
        val side = if (caret.side == 0) SourceSide.BEFORE else SourceSide.AFTER
        val data = ContextData().put(ContextKeys.EDITOR, caret.editor).put(ContextKeys.OFFSET, caret.editor.caretModel.offset)
        sessionProvider?.let { data.put(dev.insight.diff.REVIEW_CONTEXT_SESSION, it) }
        session?.let { captured ->
            if (captured.destinations.isNotEmpty()) data.put(ContextKeys.REVIEW_DESTINATIONS, captured.destinations)
            data.put(ContextKeys.OPEN_ON_DIFFS_WALL) { context -> captured.openContext(caret.file, context) }
        }
        return reviewDiffPick(project, ContextOrigin.IDE_DIFF, caret.file, caret.line?.plus(1), side, session?.files, data)
    }
}
