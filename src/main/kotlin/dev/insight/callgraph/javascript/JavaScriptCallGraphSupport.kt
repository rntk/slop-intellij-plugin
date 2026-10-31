package dev.insight.callgraph.javascript

import com.intellij.lang.ecmascript6.psi.ES6ExportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ExportDefaultAssignment
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ImportExportSpecifier
import com.intellij.lang.ecmascript6.psi.ES6ImportedBinding
import com.intellij.lang.ecmascript6.psi.JSClassExpression
import com.intellij.lang.javascript.psi.*
import com.intellij.lang.javascript.psi.ecma6.TypeScriptEnum
import com.intellij.lang.javascript.psi.ecma6.TypeScriptImportStatement
import com.intellij.lang.javascript.psi.ecma6.TypeScriptInterface
import com.intellij.lang.javascript.psi.ecma6.TypeScriptTypeAlias
import com.intellij.lang.javascript.psi.ecma6.TypeScriptTypeParameter
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeList
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.lang.javascript.psi.ecmal4.JSReferenceList
import com.intellij.lang.javascript.psi.stubs.JSImplicitElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import dev.insight.callgraph.CallGraphLanguageSupport
import dev.insight.callgraph.CallResolution
import dev.insight.callgraph.resolveTargets
import dev.insight.graph.RelationKind
import dev.insight.graph.SymbolKind

/**
 * Loaded only when the JavaScript plugin is present (see META-INF/call-graph-javascript.xml).
 * One class covers JS, JSX, TS and TSX, which all share the [JSFile] PSI.
 *
 * Functions are declarations, methods, constructors, accessors and signatures, plus variables, fields and
 * object properties initialised with an arrow or function expression (`const f = () => …`). The binding is the
 * node; the function expression itself is not, so the two never count twice. Anonymous callbacks are not
 * functions: their calls belong to the enclosing function.
 */
class JavaScriptCallGraphSupport : CallGraphLanguageSupport {
    override fun supportsFile(file: PsiFile) = file is JSFile

    override fun keepGapWhole(element: PsiElement): Boolean = when (element) {
        is TypeScriptInterface, is TypeScriptEnum, is TypeScriptTypeAlias -> true
        is JSClass -> element !is JSClassExpression && element.name != null && isDeclarationOnly(element)
        is ES6ExportDefaultAssignment -> element.namedElement?.let(::keepGapWhole) == true
        else -> false
    }

    private fun isDeclarationOnly(element: JSClass): Boolean =
        element.functions.all { it.block == null } &&
            element.fields.none { field ->
                isBinding(field) ||
                    PsiTreeUtil.findChildOfAnyType(field.initializer, JSFunctionExpression::class.java, JSClassExpression::class.java) != null
            } &&
            element.members.none { it is JSStaticBlock || (it is JSClass && !keepGapWhole(it)) }

    /** The function expression held by a variable, field or property, looking through parentheses. */
    private fun functionValue(element: PsiElement): JSFunctionExpression? {
        var value = when (element) {
            is JSParameter -> null
            is JSVariable -> element.initializer
            is JSProperty -> if (element is JSFunction) null else element.value
            else -> null
        }
        while (value is JSParenthesizedExpression) value = value.innerExpression
        return value as? JSFunctionExpression
    }

    private fun isBinding(element: PsiElement) = functionValue(element) != null

    override fun isFunction(element: PsiElement) = when (element) {
        is JSFunctionExpression -> false
        is JSFunction -> true
        else -> isBinding(element)
    }

    override fun symbolKind(element: PsiElement): SymbolKind? = when {
        isFunction(element) -> SymbolKind.FUNCTION
        element is TypeScriptTypeParameter -> SymbolKind.PARAMETER
        element is JSParameter -> if (isParameterProperty(element)) SymbolKind.PROPERTY else SymbolKind.PARAMETER
        element is TypeScriptTypeAlias -> SymbolKind.SYMBOL
        element is JSClass -> if (element.name != null) SymbolKind.CLASS else null
        element is JSField -> SymbolKind.PROPERTY
        element is JSVariable -> SymbolKind.VARIABLE
        element is JSProperty -> SymbolKind.PROPERTY
        else -> null
    }

    /** `constructor(private x: number)` also declares a field. */
    private fun isParameterProperty(parameter: JSParameter) = parameter.attributeList?.let {
        it.explicitAccessType != null || it.findModifierElement(JSAttributeList.ModifierType.READONLY) != null
    } == true

    override fun relationKind(element: PsiElement): RelationKind? = when (element) {
        is ES6ImportDeclaration, is TypeScriptImportStatement -> RelationKind.IMPORT
        is ES6ExportDeclaration -> if (element.isReExport) RelationKind.IMPORT else null
        is JSCallExpression -> if (element !is JSNewExpression && element.isRequireCall) RelationKind.IMPORT else null
        is JSReferenceList -> RelationKind.INHERITS
        else -> null
    }

    override fun resolveCall(element: PsiElement): PsiElement? {
        if (element !is JSCallExpression) return null
        val callee = element.methodExpression as? JSReferenceExpression ?: return null
        val targets = callee.multiResolve(false).mapNotNull { it.element }
        return targets.firstNotNullOfOrNull { callTarget(it) }
    }

    override fun callSite(element: PsiElement): CallResolution? {
        val callee = (element as? JSCallExpression)?.methodExpression ?: return null
        val targets = callee.references.flatMap(::resolveTargets).flatMap(::declarations).mapNotNull(::callTarget)
            .filter { isFunction(it) || it is JSClass }.distinct()
        return CallResolution(callee, targets, (callee as? JSReferenceExpression)?.qualifier)
    }

    private fun declarations(element: PsiElement): List<PsiElement> = when (element) {
        is ES6ImportedBinding -> element.findReferencedElements().toList()
        is ES6ImportExportSpecifier -> element.references.flatMap(::resolveTargets)
        else -> listOf(element)
    }.filter { it !is ES6ImportedBinding && it !is ES6ImportExportSpecifier }

    /** Follows imports to the declaration, then maps classes to their constructor when they have one. */
    private fun callTarget(resolved: PsiElement): PsiElement? {
        val target = declaration(resolved) ?: return null
        val unwrapped = unwrap(target) ?: target
        return if (unwrapped is JSClass) unwrapped.constructor ?: unwrapped else unwrapped
    }

    /** Resolves import bindings (`import { f } from './a'`) to what they import. */
    private fun declaration(element: PsiElement): PsiElement? = when (element) {
        is ES6ImportedBinding -> element.findReferencedElements().firstOrNull()
        is ES6ImportExportSpecifier -> element.references.firstNotNullOfOrNull { it.resolve() }
        else -> element
    }?.takeIf { it !is ES6ImportedBinding && it !is ES6ImportExportSpecifier }

    override fun unwrap(element: PsiElement): PsiElement? = when (element) {
        // Implicit elements are index-level stand-ins for the declaration that provides them.
        is JSImplicitElement -> element.parent?.takeIf { it !is PsiFile }
        // A function expression is represented by the variable, field or property that holds it.
        is JSFunctionExpression -> (element.parent as? JSVariable ?: element.parent as? JSProperty)
            ?.takeIf { isBinding(it) }
        else -> null
    }
}
