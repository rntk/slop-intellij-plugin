package dev.insight.callgraph

import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

/** PSI queries used by the call graph. Every function must be called inside a read action. */
object CallGraphAnalyzer {
    private val supports get() = CallGraphLanguageSupport.EP_NAME.extensionList

    fun isFunction(element: PsiElement) = supports.any { it.isFunction(element) }

    /** The function declared by [element] or enclosing it, mapped to its source declaration. */
    fun findFunction(element: PsiElement?): PsiElement? {
        var current = element
        while (current != null && current !is PsiFile) {
            val unwrapped = unwrap(current)
            if (isFunction(unwrapped)) return unwrapped
            current = current.parent
        }
        return null
    }

    fun callees(function: PsiElement, includeLibraries: Boolean): List<PsiElement> {
        if (!function.isValid) return emptyList()
        val project = function.project
        // Library code is compiled or decompiled; its bodies are not analysed.
        if (!isInProject(project, function)) return emptyList()
        val result = LinkedHashSet<PsiElement>()
        function.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                // Calls inside local functions belong to those functions.
                if (element !== function && isFunction(element)) return
                supports.firstNotNullOfOrNull { it.callSite(element) }?.targets.orEmpty()
                    .map(::unwrap)
                    .filter { isFunction(it) && (includeLibraries || isInProject(project, it)) }
                    .forEach(result::add)
                super.visitElement(element)
            }
        })
        return result.toList()
    }

    /** Functions containing a reference to [function]; stops after [limit] distinct callers. */
    fun callers(function: PsiElement, limit: Int): List<PsiElement> {
        if (!function.isValid) return emptyList()
        val result = LinkedHashSet<PsiElement>()
        ReferencesSearch.search(function, GlobalSearchScope.projectScope(function.project)).forEach { reference ->
            val site = reference.element
            if (PsiTreeUtil.getParentOfType(site, PsiComment::class.java, false) == null) {
                findFunction(site)?.let(result::add)
            }
            result.size < limit
        }
        return result.toList()
    }

    fun describe(project: Project, element: PsiElement, id: Int, level: Int): CallGraphNode {
        if (!element.isValid) return CallGraphNode(id, "(edited during search)", "", "", level, false)
        val presentation = (element as? NavigationItem)?.presentation
        val name = presentation?.presentableText ?: (element as? PsiNamedElement)?.name ?: element.text.take(40)
        val container = presentation?.locationString.orEmpty().trim().removeSurrounding("(", ")").removePrefix("in ")
        val file = element.containingFile
        val line = file?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
            ?.takeIf { element.textOffset in 0..it.textLength }
            ?.let { it.getLineNumber(element.textOffset) + 1 }
        val location = listOfNotNull(file?.name, line).joinToString(":")
        return CallGraphNode(id, name, container, location, level, isInProject(project, element), filePath = file?.virtualFile?.path)
    }

    fun isInProject(project: Project, element: PsiElement): Boolean {
        if (!element.isValid) return false
        val file = element.containingFile?.virtualFile ?: return false
        return ProjectFileIndex.getInstance(project).isInContent(file)
    }

    private fun unwrap(element: PsiElement) = supports.firstNotNullOfOrNull { it.unwrap(element) } ?: element
}
