package dev.insight.diff.atoms

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiWhiteSpace
import dev.insight.callgraph.CallGraphAnalyzer
import dev.insight.callgraph.CallGraphLanguageSupport
import dev.insight.callgraph.ProjectGraphAnalyzer
import dev.insight.callgraph.navigationReferences
import dev.insight.callgraph.referenceRange
import dev.insight.callgraph.resolveTargets
import dev.insight.diff.LineIndex
import dev.insight.diff.SemanticMetrics
import dev.insight.graph.CallOperation
import dev.insight.graph.RelationKind
import dev.insight.graph.SymbolKind
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

/**
 * References from the code each atom owns on the after side to declarations that are atoms, resolved with the same
 * language supports as the call graph: [CallGraphLanguageSupport.callSite] for calls, platform references for the rest,
 * [CallGraphLanguageSupport.superMethods] for overrides. One walk per file tracks the owning atom, so code of unchanged
 * declarations is never resolved; imports are skipped. Resolution stops after [SemanticMetrics.MAX_RESOLVED] per file.
 */
internal fun afterReferences(sides: List<AfterSide>, atoms: Map<String, ChangeAtom>): List<AtomReference> {
    val resolver = TargetResolver(sides)
    val found = LinkedHashMap<Triple<String, String, ReferenceKind>, Boolean>()
    fun add(from: String, to: String?, kind: ReferenceKind, changed: Boolean) {
        if (to != null && to != from && to in atoms) found.merge(Triple(from, to, kind), changed, Boolean::or)
    }
    for (side in sides) try {
        walk(side, resolver, ::add)
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Exception) {
        if (e is ControlFlowException || e is CancellationException) throw e
        ATOM_LOG.warn("Change atoms: reference resolution failed in ${side.file.path}", e)
    }
    return found.map { (key, changed) -> AtomReference(key.first, key.second, key.third, changed) }
}

private fun walk(side: AfterSide, resolver: TargetResolver, add: (String, String?, ReferenceKind, Boolean) -> Unit) {
    val supports = CallGraphLanguageSupport.EP_NAME.extensionList
    val index = LineIndex(side.psi.text)
    fun changed(owner: String, offset: Int) = index.line(offset) in side.added[owner].orEmpty()
    // An argument added on a later line of a short call changes the call; a long call is mostly a lambda body, which is not the site.
    fun changedCall(owner: String, call: PsiElement, callee: Int): Boolean {
        val lines = index.line(call.textRange.startOffset)..index.line((call.textRange.endOffset - 1).coerceAtLeast(call.textRange.startOffset))
        return changed(owner, callee) || lines.count() <= MAX_CALL_LINES && side.added[owner].orEmpty().any { it in lines }
    }
    var resolved = 0
    // Callees of calls already seen: their references are the call's, which must not count again as plain references.
    val callees = Collections.newSetFromMap(IdentityHashMap<PsiElement, Boolean>())
    val owners = ArrayDeque<Pair<PsiElement?, String?>>().apply { add(null to side.loose) }
    val inherits = ArrayDeque<PsiElement>()

    fun overrides(declaration: PsiElement, owner: String) {
        if (!CallGraphAnalyzer.isFunction(declaration)) return
        resolved++
        val site = changed(owner, declaration.textOffset)
        for (method in safely { supports.flatMap { it.superMethods(declaration) } }.orEmpty()) add(owner, resolver.atom(method), ReferenceKind.OVERRIDE, site)
    }

    fun resolve(element: PsiElement, owner: String) {
        val call = safely { supports.firstNotNullOfOrNull { it.callSite(element) } }
        if (call != null) {
            resolved++
            callees += call.callee
            val kind = when {
                inherits.isNotEmpty() -> ReferenceKind.TYPE
                call.operation == CallOperation.INVOKE -> ReferenceKind.CALL
                else -> ReferenceKind.REFERENCE
            }
            val site = changedCall(owner, element, call.callee.textRange.startOffset)
            for (target in call.targets) add(owner, resolver.atom(target), kind, site)
        }
        if (callees.remove(element)) return
        val references = safely { element.navigationReferences() }.orEmpty()
        if (references.isEmpty()) return
        resolved += references.size
        for (reference in references) {
            val site = changed(owner, referenceRange(reference)?.startOffset ?: element.textRange.startOffset)
            for (target in resolveTargets(reference)) {
                val kind = if (inherits.isNotEmpty() || safely { ProjectGraphAnalyzer.kind(ProjectGraphAnalyzer.source(target)) } == SymbolKind.CLASS)
                    ReferenceKind.TYPE else ReferenceKind.REFERENCE
                add(owner, resolver.atom(target), kind, site)
            }
        }
    }

    side.psi.accept(object : PsiRecursiveElementWalkingVisitor() {
        override fun visitElement(element: PsiElement) {
            ProgressManager.checkCanceled()
            if (element is PsiComment || element is PsiWhiteSpace) return
            val relation = if (element is PsiFile) null else ProjectGraphAnalyzer.relationKind(element)
            if (relation == RelationKind.IMPORT) return
            if (side.owners.containsKey(element)) {
                owners += element to side.owners[element]
                side.owners[element]?.takeIf { resolved < MAX_RESOLVED }?.let { overrides(element, it) }
            }
            if (relation == RelationKind.INHERITS) inherits += element
            val owner = owners.last().second
            if (owner != null && resolved < MAX_RESOLVED) resolve(element, owner)
            super.visitElement(element)
        }

        override fun elementFinished(element: PsiElement) {
            if (owners.last().first === element) owners.removeLast()
            if (inherits.lastOrNull() === element) inherits.removeLast()
        }
    })
}

/**
 * Maps resolved declarations to the atoms owning them. Resolution from a parsed copy returns elements of the project's
 * file rather than of the copy; those map by offset into the copy, which holds only while the project file's text is
 * the reviewed after text.
 */
private class TargetResolver(sides: List<AfterSide>) {
    private val byFile = IdentityHashMap<PsiFile, AfterSide>().apply { sides.forEach { put(it.psi, it) } }
    private val copies = sides.filter { !it.live }.associateBy { it.file.absolutePath }
    private val current = HashMap<PsiFile, Boolean>()

    fun atom(resolved: PsiElement): String? = safely {
        val target = ProjectGraphAnalyzer.source(resolved)
        val file = target.containingFile ?: return@safely null
        var side = byFile[file]
        var element: PsiElement? = target
        if (side == null) {
            side = file.virtualFile?.path?.let(copies::get) ?: return@safely null
            if (!current.getOrPut(file) { file.text == side.file.after }) return@safely null
            element = side.psi.findElementAt(target.textOffset)
        }
        // The nearest declaration decides: an unchanged one has no atom, even inside a changed class.
        while (element != null && element !is PsiFile) {
            if (side.owners.containsKey(element)) return@safely side.owners[element]
            element = element.parent
        }
        null
    }
}

private inline fun <T> safely(block: () -> T): T? = try {
    block()
} catch (e: Throwable) {
    if (e is ControlFlowException || e is CancellationException || e is VirtualMachineError) throw e
    // Expected for constructs a language plugin cannot resolve; this runs per reference, so a warning would flood the log.
    ATOM_LOG.debug("Change atom reference resolution failed", e)
    null
}

private const val MAX_RESOLVED = SemanticMetrics.MAX_RESOLVED
private const val MAX_CALL_LINES = 8
