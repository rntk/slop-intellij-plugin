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
import com.jetbrains.python.pyi.PyiUtil
import dev.insight.callgraph.CallGraphLanguageSupport
import dev.insight.callgraph.RelationKind
import dev.insight.callgraph.SymbolKind
import dev.insight.callgraph.CallResolution
import dev.insight.callgraph.resolveTargets

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

    /** References to later assignments still belong to the binding's first declaration in this scope. */
    override fun unwrap(element: PsiElement): PsiElement? {
        val target = element as? PyTargetExpression ?: return null
        val name = nameOf(target) ?: return null
        val canonical = if (target.qualifier != null) target.containingClass?.let { owner ->
            owner.findClassAttribute(name, false, null) ?: owner.findInstanceAttribute(name, false)
        } else {
            val scope = PsiTreeUtil.getParentOfType(target, PyFunction::class.java, PyClass::class.java, PyFile::class.java,
                PyLambdaExpression::class.java, PyComprehensionElement::class.java)
            if (scope == null || scope is PyLambdaExpression || scope is PyComprehensionElement) null
            else (scope as? PyFunction)?.parameterList?.findParameterByName(name) ?: firstTargets(scope)[name]
        }
        return canonical?.takeUnless { it === target }
    }

    /** Callee of a call; constructor calls resolve to `__init__`, or to the class when it has none. Decorators are skipped. */
    override fun resolveCall(element: PsiElement): PsiElement? = callSite(element)?.targets?.firstOrNull()

    override fun callSite(element: PsiElement): CallResolution? {
        if (element !is PyCallExpression || element is PyDecorator) return null
        val expression = element.callee ?: return null
        // Graph exploration is a user-initiated navigation action. codeAnalysis limits inference
        // outside this file, losing e.g. the return type of an unannotated imported factory.
        val context = TypeEvalContext.userInitiated(element.project, element.containingFile)
        fun targets(resolveContext: PyResolveContext) = element.multiResolveCalleeFunction(resolveContext).toList().ifEmpty {
            if (expression is PyReferenceOwner) resolveTargets(expression.getReference(resolveContext))
            else expression.references.flatMap(::resolveTargets)
        }
            .filter { it is PyFunction || it is PyClass }.map {
            // Navigate to the implementation behind a .pyi stub, as Go to Declaration does; keep the stub when there is none.
            val declaration = if (it is PyClass) it.findInitOrNew(true, context) ?: it else it
            (declaration as? PyElement)?.let(PyiUtil::getOriginalElement) ?: declaration
        }.distinct()
        val strict = targets(PyResolveContext.defaultContext(context))
        // The editor's references (PyBaseElementImpl.findReferenceAt) and Call Hierarchy use implicitContext: when the receiver's
        // type is unknown, members are matched by name. Use it only as a fallback; generic names become choices for the user.
        val implicit = if (strict.isEmpty() && expression is PyQualifiedExpression && expression.isQualified)
            targets(PyResolveContext.implicitContext(context)) else emptyList()
        val specific = implicit.size <= MAX_BY_NAME
        val receiver = (expression as? PyReferenceExpression)?.qualifier
        return CallResolution(expression, strict.ifEmpty { if (specific) implicit else emptyList() }, receiver,
            receiverType = receiver?.let { context.getType(it)?.name }, byName = strict.isEmpty() && specific && implicit.isNotEmpty(),
            choices = if (specific) emptyList() else implicit)
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

    private companion object {
        /** Name matches beyond this are too generic (`get`, `run`) to present as a call target. */
        const val MAX_BY_NAME = 3
    }
}
