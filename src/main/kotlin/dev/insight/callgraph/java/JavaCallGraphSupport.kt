package dev.insight.callgraph.java

import com.intellij.psi.PsiCall
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodReferenceExpression
import dev.insight.callgraph.CallGraphLanguageSupport
import dev.insight.callgraph.SymbolKind
import dev.insight.callgraph.RelationKind
import com.intellij.psi.*

/** Loaded only when the Java plugin is present (see META-INF/call-graph-java.xml). */
class JavaCallGraphSupport : CallGraphLanguageSupport {
    override fun supportsFile(file: PsiFile) = file is PsiJavaFile

    override fun symbolKind(element: PsiElement): SymbolKind? = when (element) {
        is PsiTypeParameter -> SymbolKind.PARAMETER
        is PsiClass -> SymbolKind.CLASS
        is PsiMethod -> SymbolKind.FUNCTION
        is PsiField -> SymbolKind.PROPERTY
        is PsiParameter -> SymbolKind.PARAMETER
        is PsiVariable -> SymbolKind.VARIABLE
        else -> null
    }

    override fun relationKind(element: PsiElement): RelationKind? = when (element) {
        is PsiImportStatementBase -> RelationKind.IMPORT
        is PsiReferenceList -> if (element.role == PsiReferenceList.Role.EXTENDS_LIST ||
            element.role == PsiReferenceList.Role.IMPLEMENTS_LIST) RelationKind.INHERITS else null
        else -> null
    }

    override fun isFunction(element: PsiElement) = element is PsiMethod

    override fun superMethods(element: PsiElement): List<PsiElement> =
        (element as? PsiMethod)?.findSuperMethods()?.toList().orEmpty()

    override fun resolveCall(element: PsiElement): PsiElement? = when (element) {
        is PsiCall -> element.resolveMethod()
        is PsiMethodReferenceExpression -> element.resolve() as? PsiMethod
        else -> null
    }
}
