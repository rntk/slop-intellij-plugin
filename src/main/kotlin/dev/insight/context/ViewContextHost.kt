package dev.insight.context

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.ui.awt.RelativePoint
import dev.insight.canvas.relativePath
import javax.swing.JComponent

/** Opens a picker at a source element, using the live document when it is available. */
internal fun showElementContext(project: Project, element: PsiElement?, owner: JComponent) {
    val source = element?.navigationElement ?: return
    val file = source.containingFile?.virtualFile ?: return
    val document = FileDocumentManager.getInstance().getDocument(file) ?: return
    val offset = source.textRange?.startOffset?.coerceIn(0, document.textLength) ?: return
    val pick = capturedSourcePick(project, ContextOrigin.OTHER, relativePath(project, file.path), file.path,
        document.text, document.getLineNumber(offset) + 1)
    showContextPicker(project, pick, owner, RelativePoint.getCenterOf(owner))
}

/** Opens scopes from the current document of a file at a 1-based line, or the whole file when no line is supplied. */
internal fun showFileContext(project: Project, file: com.intellij.openapi.vfs.VirtualFile, line: Int?, owner: JComponent) {
    val document = FileDocumentManager.getInstance().getDocument(file) ?: return
    val pick = capturedSourcePick(project, ContextOrigin.OTHER, relativePath(project, file.path), file.path, document.text,
        line?.coerceIn(1, document.lineCount))
    showContextPicker(project, pick, owner, RelativePoint.getCenterOf(owner))
}
