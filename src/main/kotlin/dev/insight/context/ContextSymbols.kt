package dev.insight.context

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiNamedElement
import dev.insight.callgraph.CallGraphLanguageSupport
import dev.insight.callgraph.ProjectGraphAnalyzer
import dev.insight.diff.LineIndex
import dev.insight.graph.SymbolKind

/** Declarations a context can cover; parameters and locals are part of their function. */
private val SYMBOL_KINDS = setOf(SymbolKind.CLASS, SymbolKind.FUNCTION, SymbolKind.PROPERTY, SymbolKind.SYMBOL)

/**
 * The declarations around [offset] in [file], innermost first, with their 1-based lines. Languages with call-graph support use its
 * declarations; other languages fall back to named elements spanning several lines. Takes a read action.
 */
fun enclosingSymbols(file: PsiFile, offset: Int): List<SymbolSpan> = ReadAction.compute<List<SymbolSpan>, RuntimeException> {
    val supported = CallGraphLanguageSupport.EP_NAME.extensionList.any { it.supportsFile(file) }
    val index = LineIndex(file.text)
    val found = mutableListOf<Pair<PsiElement, SymbolKind?>>()
    var current: PsiElement? = file.findElementAt(offset.coerceIn(0, (file.textLength - 1).coerceAtLeast(0)))
    while (current != null && current !is PsiFile) {
        ProgressManager.checkCanceled()
        if (supported) ProjectGraphAnalyzer.kind(current)?.takeIf { it in SYMBOL_KINDS }?.let { found += current!! to it }
        else if (current is PsiNamedElement && current.name != null && current.text.contains('\n')) found += current to null
        current = current.parent
    }
    val names = found.map { (it.first as? PsiNamedElement)?.name }
    found.mapIndexedNotNull { i, (element, kind) ->
        val name = names[i] ?: return@mapIndexedNotNull null
        val range = element.textRange
        // Outer names come later in the innermost-first list.
        val qualified = (names.subList(i, names.size).filterNotNull().asReversed()).joinToString(".")
        SymbolSpan(ContextSymbol(name, qualified, kind?.name?.lowercase() ?: "symbol"),
            index.line(range.startOffset)..index.line((range.endOffset - 1).coerceAtLeast(range.startOffset)))
    }
}

/**
 * [enclosingSymbols] at 1-based [line] of [text] parsed as the file at [path], for a side with no PSI on disk, such as a diff's
 * before side. Empty when the file type is not a language. Takes a read action.
 */
fun symbolsInText(project: Project, path: String, text: String, line: Int): List<SymbolSpan> {
    val name = path.substringAfterLast('/')
    val type = FileTypeManager.getInstance().getFileTypeByFileName(name) as? LanguageFileType ?: return emptyList()
    val file = ReadAction.compute<PsiFile, RuntimeException> { PsiFileFactory.getInstance(project).createFileFromText(name, type, text) }
    return enclosingSymbols(file, lineOffset(text, line))
}

/** The offset of the first non-blank character of 1-based [line] in [text], so an indented caret lands inside its declaration. */
internal fun lineOffset(text: CharSequence, line: Int): Int {
    var start = 0
    repeat(line - 1) { start = text.indexOf('\n', start).takeIf { it >= 0 }?.plus(1) ?: return text.length }
    var offset = start
    while (offset < text.length && text[offset] != '\n' && text[offset].isWhitespace()) offset++
    return if (offset < text.length && text[offset] != '\n') offset else start
}
