package dev.insight.context

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.ex.LineStatusTrackerI
import com.intellij.openapi.vcs.impl.LineStatusTrackerManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.ui.awt.RelativePoint
import dev.insight.agent.SourceSide
import dev.insight.agent.findSourceFile
import dev.insight.canvas.relativePath
import dev.insight.diff.ReviewFile
import java.awt.Component

/** Full source behind the picker scopes, whose previews may be truncated. */
internal val SUMMARY_SOURCE_TEXT = ContextKey<String>("summarySourceText")

/** What a host hands the picker: the candidate scopes, the one to select first, and the host's extras. */
internal class ContextPick(val origin: ContextOrigin, val candidates: List<CodeContext>, val data: ContextData) {
    val defaultIndex: Int get() = defaultScopeIndex(candidates)
}

/** Opens the picker for [pick] over [owner] at [at]; does nothing for an empty pick. */
internal fun showContextPicker(project: Project, pick: ContextPick, owner: Component, at: RelativePoint) {
    if (pick.candidates.isEmpty()) return
    showContextPicker(project, pick.candidates, pick.defaultIndex, pick.origin, pick.data, owner, at)
}

/**
 * The declarations around 1-based [line] of [text], the captured text of the project file [path]. The file's PSI is used while the
 * file on disk still holds [text]; otherwise (a diff's before side, an edited file) [text] is parsed on its own. Takes a read action.
 */
internal fun symbolsForCapturedText(project: Project, path: String, absolutePath: String?, text: String, line: Int): List<SymbolSpan> =
    ReadAction.compute<List<SymbolSpan>, RuntimeException> {
        val onDisk = findSourceFile(project, path, absolutePath)?.let { PsiManager.getInstance(project).findFile(it) }
            ?.takeIf { StringUtil.convertLineSeparators(it.text) == StringUtil.convertLineSeparators(text) }
        if (onDisk != null) enclosingSymbols(onDisk, lineOffset(text, line)) else symbolsInText(project, path, text, line)
    }

/**
 * The picker's scopes for a captured diff of [file] at 1-based [line] of [side] (null for none), with the whole change when
 * [allFiles] holds several files. A side without text has no symbols.
 */
internal fun reviewDiffPick(project: Project, origin: ContextOrigin, file: ReviewFile, line: Int?, side: SourceSide,
    allFiles: List<ReviewFile>?, data: ContextData = ContextData()): ContextPick {
    val text = if (side == SourceSide.BEFORE) file.before else file.after
    val symbols = if (line != null && text != null) {
        val path = if (side == SourceSide.BEFORE) file.previousPath ?: file.path else file.path
        val disk = if (side == SourceSide.BEFORE && file.previousPath != null) null else file.absolutePath
        symbolsForCapturedText(project, path, disk, text, line)
    } else emptyList()
    data.put(ContextKeys.REVIEW_FILE, file)
    data.put(dev.insight.diff.REVIEW_CONTEXT_FILES, allFiles ?: listOf(file))
    return ContextPick(origin, diffScopes(file, line, side, symbols, allFiles), data)
}

/**
 * The picker's scopes for a project file's captured [text] at 1-based [line] (null for the whole file only), as from the Code wall.
 */
internal fun capturedSourcePick(project: Project, origin: ContextOrigin, path: String, absolutePath: String?, text: String,
    line: Int?, data: ContextData = ContextData()): ContextPick {
    data.put(SUMMARY_SOURCE_TEXT, text)
    val scopes = if (line == null) sourceScopes(path, absolutePath, text, 1, null, emptyList(), null).filter { it.kind == ContextScopeKind.FILE }
    else sourceScopes(path, absolutePath, text, line, null, symbolsForCapturedText(project, path, absolutePath, text, line), null)
    findSourceFile(project, path, absolutePath)?.let { data.put(ContextKeys.VIRTUAL_FILE, it) }
    return ContextPick(origin, scopes, data)
}

/**
 * The picker's scopes at the caret of [editor] showing [file]: its selection, line, enclosing symbols from [psi], the lines the VCS
 * changed around the caret, and the whole file. Works on the editor's current text, saved or not.
 */
internal fun editorPick(project: Project, origin: ContextOrigin, editor: Editor, file: VirtualFile, psi: PsiFile?): ContextPick {
    val document = editor.document
    val caret = editor.caretModel.currentCaret
    val offset = caret.offset
    val line = document.getLineNumber(offset) + 1
    val selection = caret.takeIf { it.hasSelection() }?.let {
        val first = document.getLineNumber(it.selectionStart) + 1
        // A selection ending at the start of a line does not cover that line.
        val endLine = document.getLineNumber(it.selectionEnd)
        val last = if (it.selectionEnd > it.selectionStart && document.getLineStartOffset(endLine) == it.selectionEnd) endLine else endLine + 1
        first..maxOf(first, last)
    }
    // Offsets of the editor and of its PSI agree only once the document is committed.
    val psiDocuments = PsiDocumentManager.getInstance(project)
    if (psi != null && psiDocuments.isUncommited(document)) psiDocuments.commitDocument(document)
    val symbols = psi?.let { enclosingSymbols(it, offset) }.orEmpty()
    val text = document.text
    val scopes = sourceScopes(relativePath(project, file.path), file.path, text, line, selection, symbols, vcsHunk(project, editor, line))
    val data = ContextData().put(ContextKeys.VIRTUAL_FILE, file).put(ContextKeys.EDITOR, editor).put(ContextKeys.OFFSET, offset)
        .put(SUMMARY_SOURCE_TEXT, text)
    psi?.let { data.put(ContextKeys.PSI_FILE, it) }
    return ContextPick(origin, scopes, data)
}

/** The 1-based lines of the VCS change around 1-based [line], when the editor's file is tracked and changed there. */
private fun vcsHunk(project: Project, editor: Editor, line: Int): IntRange? {
    val tracker: LineStatusTrackerI<*>? = LineStatusTrackerManager.getInstance(project).getLineStatusTracker(editor.document)
    return tracker?.getRangeForLine(line - 1)?.takeIf { it.line2 > it.line1 }?.let { (it.line1 + 1)..it.line2 }
}
