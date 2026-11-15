package dev.insight.codereview

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import dev.insight.callgraph.CallGraphLanguageSupport
import dev.insight.graph.SymbolKind
import dev.insight.review.RadarSymbolKind
import dev.insight.review.RadarSymbols

/** Parse the exact captured text in a background read action, including unsaved edits, without resolving references. */
internal fun codeSymbolCounts(project: Project, name: String, type: FileType, text: String): RadarSymbols? {
    val supports = CallGraphLanguageSupport.EP_NAME.extensionList
    val language = (type as? LanguageFileType)?.language ?: return null
    if (supports.none { it.supportsLanguage(language) }) return null
    val psi = PsiFileFactory.getInstance(project).createFileFromText(name, type, text)
    val support = supports.firstOrNull { it.supportsFile(psi) } ?: return null
    val counts = mutableMapOf<RadarSymbolKind, Int>()
    psi.accept(object : PsiRecursiveElementWalkingVisitor() {
        override fun visitElement(element: PsiElement) {
            ProgressManager.checkCanceled()
            if (element is PsiComment) {
                counts.merge(RadarSymbolKind.COMMENT, 1, Int::plus)
                return // Documentation tags and nested PSI are part of the same comment block.
            }
            val kind = when (support.symbolKind(element)) {
                SymbolKind.FUNCTION -> RadarSymbolKind.FUNCTION
                SymbolKind.CLASS -> RadarSymbolKind.CLASS
                SymbolKind.PROPERTY -> RadarSymbolKind.PROPERTY
                SymbolKind.VARIABLE -> RadarSymbolKind.VARIABLE
                SymbolKind.PARAMETER -> RadarSymbolKind.PARAMETER
                SymbolKind.SYMBOL -> RadarSymbolKind.OTHER
                else -> null
            }?.let {
                if ((it == RadarSymbolKind.VARIABLE || it == RadarSymbolKind.PROPERTY) && support.isConstant(element))
                    RadarSymbolKind.CONSTANT else it
            }
            if (kind != null) counts.merge(kind, 1, Int::plus)
            super.visitElement(element)
        }
    })
    return RadarSymbols(counts.toMap())
}
