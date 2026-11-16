package dev.insight.context.actions

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.StatusBar
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.util.concurrency.AppExecutorUtil
import dev.insight.agent.findSourceFile
import dev.insight.context.ContextKeys
import dev.insight.context.ContextRequest
import dev.insight.context.ContextSymbol
import dev.insight.context.lineOffset
import java.util.concurrent.Callable

/** The file of the request's context: the host's, else the one [findSourceFile] finds for its path. */
internal fun ContextRequest.sourceFile(): VirtualFile? =
    data[ContextKeys.VIRTUAL_FILE]?.takeIf { it.isValid && !it.isDirectory }
        ?: context.path?.let { findSourceFile(project, it, context.absolutePath) }

/** Tells the user in the status bar, for an action that could not find what it was asked to open. */
internal fun report(project: Project, text: String) {
    if (!project.isDisposed) StatusBar.Info.set(text, project)
}

/** Selects the whole 1-based [lines] of [editor] and scrolls to them; lines past the document are clamped. */
internal fun selectLines(editor: Editor, lines: IntRange) {
    val document = editor.document
    if (document.lineCount == 0) return
    val first = (lines.first - 1).coerceIn(0, document.lineCount - 1)
    val last = (lines.last - 1).coerceIn(first, document.lineCount - 1)
    editor.caretModel.moveToOffset(document.getLineStartOffset(first))
    editor.selectionModel.setSelection(document.getLineStartOffset(first), document.getLineEndOffset(last))
    editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
}

/**
 * Finds an enclosing declaration named [symbol] at 1-based [line] of [file] that [accept] accepts. Call under a read action.
 * Returns null if the file is not project content, has no PSI, or no matching declaration is found.
 */
internal fun declarationAt(project: Project, file: VirtualFile, line: Int, symbol: ContextSymbol, accept: (PsiElement) -> PsiElement?): PsiElement? {
    if (!ProjectFileIndex.getInstance(project).isInContent(file)) return null
    val psi: PsiFile = PsiManager.getInstance(project).findFile(file) ?: return null
    var current: PsiElement? = psi.findElementAt(lineOffset(psi.text, line).coerceAtMost((psi.textLength - 1).coerceAtLeast(0)))
    while (current != null && current !is PsiFile) {
        val found = accept(current)
        if (found != null && (found as? PsiNamedElement)?.name == symbol.name) return found
        current = current.parent
    }
    return null
}

/**
 * Computes [resolve] in a non-blocking read action once indexes are ready, then calls [show] on the EDT with its result, or reports
 * [notFound] in the status bar when it is null.
 */
internal fun <T : Any> resolveThenShow(project: Project, notFound: String, resolve: () -> T?, show: (T) -> Unit) {
    ReadAction.nonBlocking(Callable<T?> { resolve() })
        .inSmartMode(project)
        .expireWith(project)
        .finishOnUiThread(ModalityState.defaultModalityState()) { found -> if (found != null) show(found) else report(project, notFound) }
        .submit(AppExecutorUtil.getAppExecutorService())
}
