package dev.insight.callgraph.kotlin

import com.intellij.psi.PsiElement
import dev.insight.callgraph.CallGraphLanguageSupport
import org.jetbrains.kotlin.asJava.elements.KtLightElement
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPropertyAccessor

/**
 * Loaded only when the Kotlin plugin is present (see META-INF/call-graph-kotlin.xml).
 * Uses PSI references only, which work in both K1 and K2 modes.
 */
class KotlinCallGraphSupport : CallGraphLanguageSupport {
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
}
