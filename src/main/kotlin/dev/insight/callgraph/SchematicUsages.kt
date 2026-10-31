package dev.insight.callgraph

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import dev.insight.graph.CallGraphEdge
import dev.insight.graph.CallGraph
import dev.insight.graph.CallGraphResult
import dev.insight.graph.CallOperation
import dev.insight.graph.RelationKind
import dev.insight.graph.SymbolKind

internal data class SchematicUsage(val owner: Int, val filePath: String, val offset: Int, val line: Int,
    val kind: RelationKind)

internal data class SchematicUsageSearch(val result: CallGraphResult, val usages: List<SchematicUsage>, val truncated: Boolean)

/** Indexed project references, including uses outside function bodies. Read action only; bodies stay folded. */
internal fun findSchematicUsages(project: Project, initial: CallGraphResult, id: Int,
    limit: Int = 100): SchematicUsageSearch {
    require(limit > 0)
    val target = initial.pointers.getOrNull(id)?.element?.takeIf { it.isValid }
        ?: error("Source is no longer available. Reopen the schematic.")
    val sites = linkedMapOf<Pair<String, Int>, Pair<PsiElement, RelationKind>>()
    ReferencesSearch.search(target, GlobalSearchScope.projectScope(project)).forEach { reference ->
        ProgressManager.checkCanceled()
        val site = reference.element
        val parents = generateSequence(site) { it.parent }.toList()
        val path = site.containingFile?.virtualFile?.path
        if (path != null && parents.none { it is PsiComment }) {
            val referenceRange = reference.rangeInElement.shiftRight(site.textRange.startOffset)
            val call = parents.asSequence().takeWhile { it !== site.containingFile }
                .mapNotNull { element -> CallGraphLanguageSupport.EP_NAME.extensionList.firstNotNullOfOrNull { it.callSite(element) } }
                .firstOrNull { resolution -> resolution.callee.textRange.contains(referenceRange) &&
                    resolution.targets.any { ProjectGraphAnalyzer.source(it) == ProjectGraphAnalyzer.source(target) } }
            val kind = if (call?.operation == CallOperation.INVOKE) RelationKind.CALL else RelationKind.REFERENCE
            sites[path to (site.textRange.startOffset + reference.rangeInElement.startOffset)] = site to kind
        }
        sites.size <= limit
    }
    var result = initial
    val selected = sites.entries.take(limit)
    selected.map { it.value.first.containingFile }.distinct().forEach { file ->
        val declarations = ProjectGraphAnalyzer.buildScope(project, file, declarationsOnly = true)
        // Other IDE language plugins may contribute indexed references too. Keep those source locations readable.
        val branch = if (declarations.graph.nodes.isNotEmpty()) declarations else CallGraphResult(
            CallGraph(listOf(CallGraphAnalyzer.describe(project, file, 0, 0).copy(kind = SymbolKind.FILE)), emptySet(), false,
                projectGraph = true), listOf(SmartPointerManager.getInstance(project).createSmartPsiElementPointer(file)))
        result = mergeSchematicResults(result, branch) { snapshot, node ->
            Triple(node.filePath, snapshot.pointers.getOrNull(node.id)?.element?.textOffset, node.kind) to node.unresolved
        }
    }
    val ids = result.graph.nodes.associate { node ->
        result.pointers.getOrNull(node.id)?.element?.let(ProjectGraphAnalyzer::key) to node.id
    }
    val usages = selected.mapNotNull { (location, value) ->
        val (site, kind) = value
        val owner = generateSequence(site) { it.parent }.firstOrNull { ProjectGraphAnalyzer.kind(it) != null }
            ?.let(ProjectGraphAnalyzer::key)?.let(ids::get) ?: return@mapNotNull null
        val document = PsiDocumentManager.getInstance(project).getDocument(site.containingFile)
        SchematicUsage(owner, location.first, location.second, document?.getLineNumber(location.second)?.plus(1) ?: 1, kind)
    }
    val edges = result.graph.edges + usages.map { CallGraphEdge(it.owner, id, it.kind) }
    result = CallGraphResult(result.graph.copy(edges = edges.toSet()), result.pointers, result.occurrences)
    return SchematicUsageSearch(result, usages, sites.size > limit)
}
