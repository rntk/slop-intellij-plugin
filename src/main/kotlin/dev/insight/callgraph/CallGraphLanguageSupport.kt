package dev.insight.callgraph

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement

/**
 * Language-specific knowledge for the call graph. Implementations are registered from optional
 * plugin descriptors, so the plugin still loads in IDEs without a given language.
 * All methods are called inside a read action on a background thread.
 */
interface CallGraphLanguageSupport {
    /** True for declarations that are shown as call graph nodes (methods, functions, constructors…). */
    fun isFunction(element: PsiElement): Boolean

    /** The function called by [element], or null when [element] is not a call site. */
    fun resolveCall(element: PsiElement): PsiElement?

    /** Maps synthetic elements (for example Kotlin light methods seen from Java) to their source declaration. */
    fun unwrap(element: PsiElement): PsiElement? = null

    fun supportsFile(file: com.intellij.psi.PsiFile): Boolean = false

    fun symbolKind(element: PsiElement): SymbolKind? = if (isFunction(element)) SymbolKind.FUNCTION else null

    fun relationKind(element: PsiElement): RelationKind? = null

    companion object {
        val EP_NAME = ExtensionPointName<CallGraphLanguageSupport>("dev.insight.callGraphLanguageSupport")
    }
}
