package dev.insight.context

import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.awt.RelativePoint
import com.intellij.psi.PsiFile
import dev.insight.agent.SourceSide
import dev.insight.diff.ReviewDiffCaret
import dev.insight.diff.reviewDiffCaret
import dev.insight.diff.reviewDiffLocation
import dev.insight.diff.REVIEW_DIFF_SESSION
import dev.insight.diff.ReviewDiffSession
import dev.insight.canvas.relativePath
import dev.insight.review.ReviewCodeLocation
import dev.insight.review.ReviewCodeNavigation
import java.awt.Point

/**
 * Opens the context picker at the caret, offering the selection, line, enclosing symbols, changed lines and file.
 * Diff summaries use the comparison's captured contents, including when the file on disk has changed.
 */
internal class PickContextAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: e.getData(DiffDataKeys.CURRENT_EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && when {
            file?.isDirectory == true -> ProjectFileIndex.getInstance(project).isInContent(file) ||
                ProjectRootManager.getInstance(project).contentRoots.any { VfsUtilCore.isAncestor(file, it, false) }
            editor != null -> file != null || e.getData(DiffDataKeys.DIFF_REQUEST) != null
            else -> file != null && !file.fileType.isBinary
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val diffRequest = e.getData(DiffDataKeys.DIFF_REQUEST)
        val diff = reviewDiffCaret(e)
        val editor = diff?.editor ?: e.getData(CommonDataKeys.EDITOR) ?: e.getData(DiffDataKeys.CURRENT_EDITOR)
        val selectedFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (editor == null || selectedFile?.isDirectory == true && diffRequest == null) {
            val file = selectedFile ?: return
            val path = if (file.path == project.basePath) "" else relativePath(project, file.path)
            val pick = if (file.isDirectory) {
                val data = ContextData().put(ContextKeys.VIRTUAL_FILE, file)
                val preview = file.children.joinToString("\n", limit = 250) { it.name + if (it.isDirectory) "/" else "" }
                val scope = CodeContext(ContextScopeKind.DIRECTORY, "Directory ${path.ifEmpty { "." }}", path, file.path,
                    null, SourceSide.CURRENT, capContext(preview), files = emptyList())
                ContextPick(ContextOrigin.OTHER, listOf(scope), data)
            } else {
                val document = FileDocumentManager.getInstance().getDocument(file) ?: return
                val element = e.getData(CommonDataKeys.PSI_ELEMENT)?.takeIf { it !is PsiFile && it.containingFile?.virtualFile == file }
                val line = element?.textRange?.startOffset?.coerceIn(0, document.textLength)?.let { document.getLineNumber(it) + 1 }
                capturedSourcePick(project, ContextOrigin.OTHER, path, file.path, document.text, line)
            }
            val owner = e.getData(PlatformDataKeys.CONTEXT_COMPONENT) ?: e.inputEvent?.component ?: return
            showContextPicker(project, pick, owner, RelativePoint(owner, Point(owner.width / 2, owner.height / 2)))
            return
        }
        val pick = if (diff != null) reviewDiffPick(project, diff, diffRequest?.getUserData(REVIEW_DIFF_SESSION)) else {
            val psi = e.getData(CommonDataKeys.PSI_FILE)
            val standaloneDiff = (diffRequest as? ContentDiffRequest)?.let { request ->
                standaloneDiffPick(project, e, editor, request) { file ->
                    editorPick(project, ContextOrigin.IDE_DIFF, editor, file, psi?.takeIf { it.virtualFile == file })
                }
            }
            val origin = if (diffRequest != null) ContextOrigin.IDE_DIFF else ContextOrigin.EDITOR
            standaloneDiff ?: (e.getData(CommonDataKeys.VIRTUAL_FILE) ?: FileDocumentManager.getInstance().getFile(editor.document))
                ?.let { editorPick(project, origin, editor, it, psi) } ?: return
        }
        showContextPicker(project, pick, editor.contentComponent, JBPopupFactory.getInstance().guessBestPopupLocation(editor))
    }

    private fun reviewDiffPick(project: com.intellij.openapi.project.Project, caret: ReviewDiffCaret, sessionProvider: (() -> ReviewDiffSession?)?): ContextPick {
        val session = sessionProvider?.invoke()
        val side = if (caret.side == 0) SourceSide.BEFORE else SourceSide.AFTER
        val location = reviewDiffLocation(caret) ?: ReviewCodeLocation(caret.file.path, caret.file.fullFileText.orEmpty(),
            1, 0, caret.file.absolutePath, side, reviewFile = caret.file)
        val data = if (session == null) ReviewCodeNavigation.reviewContext(caret.editor.contentComponent, location) else ContextData()
        data.put(ContextKeys.EDITOR, caret.editor).put(ContextKeys.OFFSET, caret.editor.caretModel.offset)
        sessionProvider?.let { data.put(dev.insight.diff.REVIEW_CONTEXT_SESSION, it) }
        session?.let { captured ->
            if (captured.destinations.isNotEmpty()) data.put(ContextKeys.REVIEW_DESTINATIONS, captured.destinations)
            captured.summaryActionsFor(caret.file)?.let { data.put(ContextKeys.SUMMARY_GENERATION, it) }
            data.put(ContextKeys.OPEN_ON_DIFFS_WALL) { context -> captured.openContext(caret.file, context) }
        }
        return reviewDiffPick(project, ContextOrigin.IDE_DIFF, caret.file, caret.line?.plus(1), side, session?.files, data)
    }
}
