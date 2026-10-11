package dev.insight.callgraph.python

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.*
import com.jetbrains.python.psi.resolve.PyResolveContext
import com.jetbrains.python.psi.types.TypeEvalContext
import dev.insight.callgraph.CallGraphLanguageSupport
import dev.insight.callgraph.RelationKind
import dev.insight.callgraph.SymbolKind

/**
 * Loaded only when the Python plugin is present (see META-INF/call-graph-python.xml).
 * Repeated assignments to the same name in one scope produce a single VARIABLE/PROPERTY node (the first one).
 */
class PythonCallGraphSupport : CallGraphLanguageSupport {
    override fun supportsFile(file: PsiFile) = file is PyFile

    override fun isFunction(element: PsiElement) = element is PyFunction

    override fun symbolKind(element: PsiElement): SymbolKind? = when (element) {
        is PyClass -> SymbolKind.CLASS
        is PyFunction -> SymbolKind.FUNCTION
        is PyNamedParameter -> SymbolKind.PARAMETER.takeUnless { isImplicit(element) }
        is PyTargetExpression -> targetKind(element)
        else -> null
    }

    override fun relationKind(element: PsiElement): RelationKind? = when {
        element is PyImportStatementBase -> RelationKind.IMPORT
        element is PyArgumentList && element.parent is PyClass -> RelationKind.INHERITS
        else -> null
    }

    /** Callee of a call; constructor calls resolve to `__init__`, or to the class when it has none. Decorators are skipped. */
    override fun resolveCall(element: PsiElement): PsiElement? {
        if (element !is PyCallExpression || element is PyDecorator) return null
        val context = TypeEvalContext.codeAnalysis(element.project, element.containingFile)
        val callee = element.multiResolveCalleeFunction(PyResolveContext.defaultContext(context)).firstOrNull()
        val target = callee ?: element.callee?.reference?.resolve()
        return if (target is PyClass) target.findInitOrNew(true, context) ?: target else target
    }

    // Python's own name/self accessors are experimental API (PyAst*); platform PSI calls stay stable across releases.
    private fun nameOf(element: PsiNamedElement): String? = element.name

    /** The implicit first parameter of a method (`self`/`cls`). */
    private fun isImplicit(parameter: PyNamedParameter): Boolean {
        val function = parameter.parent?.parent as? PyFunction ?: return false
        return nameOf(parameter) in setOf("self", "cls") &&
            PsiTreeUtil.getPrevSiblingOfType(parameter, PyParameter::class.java) == null &&
            PsiTreeUtil.getParentOfType(function, PyClass::class.java, true, PyFunction::class.java) != null
    }

    private fun targetKind(target: PyTargetExpression): SymbolKind? {
        val name = nameOf(target) ?: return null
        if (target.qualifier != null) {
            val owner = target.containingClass ?: return null
            val isFirst = owner.findInstanceAttribute(name, false) === target && owner.findClassAttribute(name, false, null) == null
            return SymbolKind.PROPERTY.takeIf { isFirst }
        }
        val scope = PsiTreeUtil.getParentOfType(target, PyFunction::class.java, PyClass::class.java, PyFile::class.java,
            PyLambdaExpression::class.java, PyComprehensionElement::class.java) ?: return null
        if (scope is PyLambdaExpression || scope is PyComprehensionElement) return null
        if (scope is PyFunction && scope.parameterList.findParameterByName(name) != null) return null
        if (firstTargets(scope)[name] !== target) return null
        return if (scope is PyClass) SymbolKind.PROPERTY else SymbolKind.VARIABLE
    }

    private fun firstTargets(scope: PsiElement): Map<String, PyTargetExpression> =
        CachedValuesManager.getCachedValue(scope) {
            val result = linkedMapOf<String, PyTargetExpression>()
            fun walk(element: PsiElement) {
                var child = element.firstChild
                while (child != null) {
                    when (child) {
                        is PyFunction, is PyClass, is PyLambdaExpression, is PyComprehensionElement -> {}
                        is PyTargetExpression -> if (child.qualifier == null) nameOf(child)?.let { result.putIfAbsent(it, child) }
                        else -> walk(child)
                    }
                    child = child.nextSibling
                }
            }
            walk(scope)
            CachedValueProvider.Result.create<Map<String, PyTargetExpression>>(result, PsiModificationTracker.MODIFICATION_COUNT)
        }
}
