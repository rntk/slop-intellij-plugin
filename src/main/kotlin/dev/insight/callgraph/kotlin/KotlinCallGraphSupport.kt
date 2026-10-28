package dev.insight.callgraph.kotlin

import com.intellij.psi.PsiElement
import dev.insight.callgraph.CallGraphLanguageSupport
import dev.insight.callgraph.SymbolKind
import dev.insight.callgraph.RelationKind
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.asJava.elements.KtLightElement
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import dev.insight.callgraph.CallResolution
import dev.insight.callgraph.CallOperation
import dev.insight.callgraph.resolveTargets
import dev.insight.callgraph.CallGraphAnalyzer

/**
 * Loaded only when the Kotlin plugin is present (see META-INF/call-graph-kotlin.xml).
 * Uses PSI references only, which work in both K1 and K2 modes.
 */
class KotlinCallGraphSupport : CallGraphLanguageSupport {
    override fun supportsFile(file: com.intellij.psi.PsiFile) = file is KtFile

    override fun symbolKind(element: PsiElement): SymbolKind? = when {
        element is KtClassOrObject && element.name != null -> SymbolKind.CLASS
        isFunction(element) -> SymbolKind.FUNCTION
        element is KtProperty -> if (element.isLocal) SymbolKind.VARIABLE else SymbolKind.PROPERTY
        element is KtParameter -> if (element.hasValOrVar()) SymbolKind.PROPERTY else SymbolKind.PARAMETER
        element is KtTypeParameter -> SymbolKind.PARAMETER
        element is KtDestructuringDeclarationEntry -> SymbolKind.VARIABLE
        element is KtTypeAlias -> SymbolKind.SYMBOL
        else -> null
    }

    override fun relationKind(element: PsiElement): RelationKind? = when (element) {
        is KtImportDirective -> RelationKind.IMPORT
        is KtSuperTypeListEntry -> RelationKind.INHERITS
        else -> null
    }

    override fun isFunction(element: PsiElement) = when (element) {
        is KtNamedFunction -> element.name != null
        is KtConstructor<*> -> true
        is KtPropertyAccessor -> element.hasBody()
        else -> false
    }

    override fun resolveCall(element: PsiElement): PsiElement? {
        val callee = when (element) {
            is KtAnnotationEntry -> null
            is KtCallElement -> element.calleeExpression
            is KtCallableReferenceExpression -> element.callableReference
            is KtBinaryExpression -> element.operationReference
            else -> null
        } ?: return null
        return callee.references.firstNotNullOfOrNull { reference ->
            when (val target = reference.resolve()) {
                is KtClass -> target.primaryConstructor
                else -> target
            }
        }
    }

    override fun unwrap(element: PsiElement): PsiElement? = (element as? KtLightElement<*, *>)?.kotlinOrigin

    override fun callSite(element: PsiElement): CallResolution? {
        val callee = when (element) {
            is KtAnnotationEntry -> null
            is KtCallElement -> element.calleeExpression
            is KtCallableReferenceExpression -> element.callableReference
            // Only resolved overloaded operators are calls; ordinary arithmetic is left as source.
            is KtBinaryExpression -> element.operationReference.takeIf { resolveCall(element) != null }
            else -> null
        } ?: return null
        // `value(x)` on a value with `operator fun invoke` is resolved by the call expression's own reference, not the callee's.
        val references = callee.references.toList() + if (element is KtCallExpression) element.references.toList() else emptyList()
        val targets = references.flatMap(::resolveTargets)
            .filter { !isFunctionTypeInvoke(it) && (CallGraphAnalyzer.isFunction(it) || it is KtClassOrObject) }.map { target ->
            if (target is KtClass) target.primaryConstructor ?: target else target
        }.distinct()
        val receiver = if (element is KtCallableReferenceExpression) element.receiverExpression
            else (element.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === element }?.receiverExpression
        return CallResolution(callee, targets, receiver,
            if (element is KtCallableReferenceExpression) CallOperation.CALLABLE_REFERENCE else CallOperation.INVOKE)
    }

    /** `invoke` of a built-in function type (`(String) -> Unit`): the binding called is the useful destination, not the stdlib. */
    private fun isFunctionTypeInvoke(target: PsiElement): Boolean {
        val owner = when (target) {
            is KtNamedFunction -> (target.parent?.parent as? KtClassOrObject)?.fqName?.asString()
            is com.intellij.psi.PsiMethod -> target.containingClass?.qualifiedName
            else -> null
        } ?: return false
        return owner.startsWith("kotlin.jvm.functions.") || owner.matches(Regex("""kotlin\.(reflect\.K|Suspend)?Function\d*"""))
    }

    override fun superMethods(element: PsiElement): List<PsiElement> =
        (element as? KtNamedFunction)?.toLightMethods()?.flatMap { it.findSuperMethods().toList() }.orEmpty()
}
