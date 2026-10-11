package dev.insight.callgraph.java

import com.intellij.psi.PsiCall
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodReferenceExpression
import dev.insight.callgraph.CallGraphLanguageSupport

/** Loaded only when the Java plugin is present (see META-INF/call-graph-java.xml). */
class JavaCallGraphSupport : CallGraphLanguageSupport {
    override fun isFunction(element: PsiElement) = element is PsiMethod

    override fun resolveCall(element: PsiElement): PsiElement? = when (element) {
        is PsiCall -> element.resolveMethod()
        is PsiMethodReferenceExpression -> element.resolve() as? PsiMethod
        else -> null
    }
}
