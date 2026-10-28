package dev.insight.callgraph.rust

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import dev.insight.callgraph.CallGraphLanguageSupport
import dev.insight.callgraph.CallResolution
import dev.insight.callgraph.RelationKind
import dev.insight.callgraph.SymbolKind
import dev.insight.callgraph.resolveTargets
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.superItem

/** Loaded only with the JetBrains Rust plugin (META-INF/call-graph-rust.xml). */
class RustCallGraphSupport : CallGraphLanguageSupport {
    override fun supportsFile(file: PsiFile) = file is RsFile

    // Closures have no declaration node; their calls belong to the enclosing function.
    override fun isFunction(element: PsiElement) = element is RsFunction

    override fun symbolKind(element: PsiElement): SymbolKind? = when (element) {
        is RsFunction -> SymbolKind.FUNCTION
        is RsStructItem, is RsEnumItem, is RsTraitItem, is RsTypeAlias -> SymbolKind.CLASS
        is RsNamedFieldDecl, is RsEnumVariant -> SymbolKind.PROPERTY
        is RsConstant -> SymbolKind.VARIABLE
        is RsImplItem, is RsModItem -> SymbolKind.SYMBOL
        is RsTypeParameter, is RsConstParameter -> SymbolKind.PARAMETER
        // Bindings cover destructured parameters and locals without duplicating parameter wrappers.
        is RsPatBinding -> if (PsiTreeUtil.getParentOfType(element, RsValueParameter::class.java) != null)
            SymbolKind.PARAMETER else SymbolKind.VARIABLE
        else -> null
    }

    override fun relationKind(element: PsiElement): RelationKind? = when (element) {
        is RsUseItem, is RsExternCrateItem, is RsModDeclItem -> RelationKind.IMPORT
        is RsTraitRef -> RelationKind.INHERITS.takeIf { element.parent is RsImplItem }
        is RsTypeParamBounds -> RelationKind.INHERITS.takeIf { element.parent is RsTraitItem }
        else -> null
    }

    override fun resolveCall(element: PsiElement): PsiElement? = callSite(element)?.targets?.firstOrNull()

    override fun superMethods(element: PsiElement): List<PsiElement> =
        (element as? RsFunction)?.superItem?.let(::listOf).orEmpty()

    override fun callSite(element: PsiElement): CallResolution? {
        val callee: PsiElement
        val receiver: PsiElement?
        when (element) {
            is RsCallExpr -> {
                callee = (element.expr as? RsPathExpr)?.path ?: element.expr
                receiver = null
            }
            is RsMethodCallExpr -> {
                callee = element.methodCall
                receiver = element.receiver
            }
            is RsStructLiteral -> {
                callee = element.path
                receiver = null
            }
            else -> return null
        }
        val targets = callee.references.flatMap(::resolveTargets).filter {
            isFunction(it) || it is RsStructItem || it is RsEnumVariant
        }.distinct()
        // Keep unresolved calls as source occurrences; the shared graph also follows callable bindings.
        return CallResolution(callee, targets, receiver)
    }
}
