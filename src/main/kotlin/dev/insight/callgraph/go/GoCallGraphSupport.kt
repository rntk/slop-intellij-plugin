package dev.insight.callgraph.go

import com.goide.psi.*
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.insight.callgraph.CallGraphLanguageSupport
import dev.insight.callgraph.CallResolution
import dev.insight.callgraph.resolveTargets
import dev.insight.graph.RelationKind
import dev.insight.graph.SymbolKind

/**
 * Loaded only when the Go plugin is present (see META-INF/call-graph-go.xml).
 * Function literals are not functions: their calls belong to the enclosing declaration.
 */
class GoCallGraphSupport : CallGraphLanguageSupport {
    override fun supportsFile(file: PsiFile) = file is GoFile

    override fun keepGapWhole(element: PsiElement): Boolean = when (element) {
        is GoTypeSpec -> true
        // Include the `type` keyword, but never widen to an entire grouped declaration.
        is GoTypeDeclaration -> element.lparen == null && element.typeSpecList.singleOrNull()?.let(::keepGapWhole) == true
        else -> false
    }

    override fun symbolKind(element: PsiElement): SymbolKind? = when (element) {
        is GoTypeSpec -> SymbolKind.CLASS
        is GoFieldDefinition -> SymbolKind.PROPERTY
        is GoVarDefinition, is GoConstDefinition -> SymbolKind.VARIABLE
        is GoParamDefinition, is GoReceiver, is GoTypeParamDefinition -> SymbolKind.PARAMETER
        else -> if (isFunction(element)) SymbolKind.FUNCTION else null
    }

    override fun relationKind(element: PsiElement): RelationKind? = when {
        element is GoImportDeclaration || element is GoImportSpec -> RelationKind.IMPORT
        // Embedded struct fields and embedded interfaces (method specs without a name) promote the outer type's methods.
        element is GoAnonymousFieldDefinition -> RelationKind.INHERITS
        element is GoMethodSpec && element.identifier == null -> RelationKind.INHERITS
        else -> null
    }

    /** Interface method specs count as functions, like abstract Java methods, so interface calls stay visible. */
    override fun isFunction(element: PsiElement) = when (element) {
        is GoFunctionOrMethodDeclaration -> element.name != null
        is GoMethodSpec -> element.identifier != null
        else -> false
    }

    override fun resolveCall(element: PsiElement): PsiElement? {
        val callee = (element as? GoCallExpr)?.expression as? GoReferenceExpression ?: return null
        return callee.reference.resolve()?.takeIf(::isFunction)
    }

    override fun callSite(element: PsiElement): CallResolution? {
        val callee = (element as? GoCallExpr)?.expression ?: return null
        return CallResolution(callee, callee.references.flatMap(::resolveTargets).filter { isFunction(it) || it is GoTypeSpec })
    }
}
