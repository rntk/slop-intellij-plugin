package dev.insight.callgraph.java

import com.intellij.psi.*
import com.intellij.psi.PsiCall
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.util.PsiTreeUtil
import dev.insight.callgraph.CallGraphLanguageSupport
import dev.insight.callgraph.CallResolution
import dev.insight.callgraph.resolveTargets
import dev.insight.graph.CallOperation
import dev.insight.graph.RelationKind
import dev.insight.graph.SymbolKind

/** Loaded only when the Java plugin is present (see META-INF/call-graph-java.xml). */
class JavaCallGraphSupport : CallGraphLanguageSupport {
    override fun supportsFile(file: PsiFile) = file is PsiJavaFile

    override fun keepGapWhole(element: PsiElement): Boolean =
        element is PsiClass && element !is PsiTypeParameter && element !is PsiAnonymousClass && isDeclarationOnly(element)

    private fun isDeclarationOnly(element: PsiClass): Boolean =
        element.initializers.isEmpty() &&
            element.methods.all { it is SyntheticElement || it.body == null } &&
            element.fields.none {
                PsiTreeUtil.findChildOfAnyType(it, PsiLambdaExpression::class.java, PsiAnonymousClass::class.java) != null
            } &&
            element.innerClasses.all(::isDeclarationOnly)

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

    override fun callSite(element: PsiElement): CallResolution? = when (element) {
        is PsiMethodCallExpression -> CallResolution(element.methodExpression,
            resolveTargets(element.methodExpression), element.methodExpression.qualifierExpression)
        is PsiNewExpression -> CallResolution(element.classReference ?: element, listOfNotNull(element.resolveConstructor()))
        is PsiMethodReferenceExpression -> CallResolution(element, resolveTargets(element), element.qualifierExpression,
            CallOperation.CALLABLE_REFERENCE)
        else -> null
    }
}
