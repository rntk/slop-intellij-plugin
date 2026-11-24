package dev.insight.review

import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import dev.insight.agent.findSourceFile
import dev.insight.callgraph.CallGraphLanguageSupport
import dev.insight.callgraph.ProjectGraphAnalyzer
import dev.insight.graph.RelationKind

/** Short source snippet around [highlightLine], with 1-based [firstLine] and common indentation stripped. */
internal data class CodeSnippet(
    val firstLine: Int,
    val highlightLine: Int,
    val lines: List<String>,
)

/** One resolved code connection occurrence between two files, with short snippets from both sides. */
internal data class TreeConnectionOccurrence(
    val kind: RelationKind,
    val fromLine: Int?,
    val toLine: Int?,
    val fromSnippet: CodeSnippet? = null,
    val toSnippet: CodeSnippet? = null,
)

/**
 * Link between two files in the review: [fromLine]/[toLine] hold the primary representative site for navigation,
 * while [occurrences] holds all resolved occurrences (capped) and [totalOccurrences] records the full count.
 */
internal data class TreeConnectionSite(
    val fromLine: Int?,
    val toLine: Int?,
    val occurrences: List<TreeConnectionOccurrence> = emptyList(),
    val totalOccurrences: Int = occurrences.size,
) {
    /** Completed Markdown for this directed edge in the live-source index. Accessed on the EDT. */
    var explanation: String? = null
}

internal data class TreeFileConnections(val incoming: Map<String, TreeConnectionSite>, val outgoing: Map<String, TreeConnectionSite>)

internal fun extractCodeSnippet(
    text: CharSequence,
    line: Int,
    contextLines: Int = 1,
    maxLineChars: Int = 100,
): CodeSnippet? {
    if (line < 1 || text.isEmpty()) return null
    val allLines = text.split('\n')
    val count = if (allLines.size > 1 && allLines.last().isEmpty()) allLines.size - 1 else allLines.size
    if (line > count) return null
    val start = (line - contextLines).coerceAtLeast(1)
    val end = (line + contextLines).coerceAtMost(count)
    return buildCodeSnippet(start, line, (start..end).map { allLines[it - 1] }, maxLineChars)
}

internal fun extractCodeSnippet(
    document: Document,
    line: Int,
    contextLines: Int = 1,
    maxLineChars: Int = 100,
): CodeSnippet? {
    val count = document.lineCount
    if (line < 1 || line > count) return null
    val start = (line - contextLines).coerceAtLeast(1)
    val end = (line + contextLines).coerceAtMost(count)
    val slice = (start..end).map { lineNum ->
        val lineStart = document.getLineStartOffset(lineNum - 1)
        val lineEnd = document.getLineEndOffset(lineNum - 1)
        document.charsSequence.subSequence(lineStart, lineEnd).toString()
    }
    return buildCodeSnippet(start, line, slice, maxLineChars)
}

private fun buildCodeSnippet(
    firstLine: Int,
    highlightLine: Int,
    rawLines: List<String>,
    maxLineChars: Int,
): CodeSnippet {
    val expanded = rawLines.map { it.trimEnd('\r').replace("\t", "    ") }
    val minIndent = expanded.filter { it.isNotBlank() }
        .minOfOrNull { line -> line.indexOfFirst { it != ' ' }.let { if (it < 0) line.length else it } }
        ?: 0
    val dedented = expanded.map { line ->
        val trimmed = when {
            minIndent == 0 -> line
            line.length >= minIndent -> line.substring(minIndent)
            else -> line.trimStart()
        }
        if (maxLineChars > 1 && trimmed.length > maxLineChars) trimmed.take(maxLineChars - 1) + "…" else trimmed
    }
    return CodeSnippet(firstLine, highlightLine, dedented)
}

private fun PsiElement.sourceLine(): Int? {
    val document = containingFile?.viewProvider?.document ?: return null
    val offset = textOffset.takeIf { it in 0..document.textLength } ?: return null
    return document.getLineNumber(offset) + 1
}

/** Direct file links in one review scope, resolved against the IDE's current source. */
internal class TreeFileConnectionIndex(private val project: Project, private val sources: List<ReviewSource>) {
    private data class Snapshot(val stamp: Long, val links: Map<String, TreeFileConnections>)

    private data class RawOccurrence(
        val kind: RelationKind,
        val fromLine: Int?,
        val toLine: Int?,
        val target: PsiElement,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    /** [onProgress] reports file counts while the index is (re)built; it stays silent when the snapshot is current. */
    suspend fun connections(path: String, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): TreeFileConnections? = constrainedReadAction(
        ReadConstraint.inSmartMode(project),
        ReadConstraint.withDocumentsCommitted(project),
    ) {
        val stamp = PsiModificationTracker.getInstance(project).modificationCount
        val current = snapshot?.takeIf { it.stamp == stamp } ?: Snapshot(stamp, build(onProgress)).also { snapshot = it }
        current.links[path]
    }

    private fun build(onProgress: (done: Int, total: Int) -> Unit): Map<String, TreeFileConnections> {
        val manager = PsiManager.getInstance(project)
        val files = sources.mapNotNull { source ->
            ProgressManager.checkCanceled()
            val file = findSourceFile(project, source.path, source.absolutePath)
            if (file == null || file.fileType.isBinary) null else manager.findFile(file)?.let { source.path to it }
        }.toMap()
        val paths = files.entries.associate { (path, file) -> file.virtualFile to path }
        val supports = CallGraphLanguageSupport.EP_NAME.extensionList
        val packageMembers = files.entries.filter { (_, file) -> supports.any { it.supportsFile(file) } }
            .groupBy({ (_, file) -> file.virtualFile.parent }, { (path, _) -> path })
        val incoming = mutableMapOf<String, MutableMap<String, TreeConnectionSite>>()
        val outgoing = mutableMapOf<String, MutableMap<String, TreeConnectionSite>>()
        for ((done, entry) in files.entries.withIndex()) {
            val (from, file) = entry
            ProgressManager.checkCanceled()
            onProgress(done, files.size)
            val priorities = mutableMapOf<String, Int>()
            val primaries = mutableMapOf<String, Pair<Int?, Int?>>()
            val collected = mutableMapOf<String, MutableList<RawOccurrence>>()
            ProjectGraphAnalyzer.relationsWithSites(file) { _, target, kind, site ->
                if (kind == RelationKind.CONTAINS) return@relationsWithSites
                // Keep the first call in traversal order, then a usage, then an import for the primary site.
                val priority = when (kind) {
                    RelationKind.CALL -> 2
                    RelationKind.IMPORT -> 0
                    else -> 1
                }
                fun connect(to: String) {
                    if (from == to) return
                    val fromLine = site.sourceLine()
                    val toLine = if (target is PsiDirectory || target is PsiFile) null else target.sourceLine()
                    if (priorities.getOrDefault(to, -1) < priority) {
                        priorities[to] = priority
                        primaries[to] = fromLine to toLine
                    }
                    collected.getOrPut(to, ::mutableListOf) += RawOccurrence(kind, fromLine, toLine, target)
                }
                if (target is PsiDirectory) {
                    // A package import can resolve to a directory rather than a declaration.
                    if (kind == RelationKind.IMPORT) packageMembers[target.virtualFile]?.forEach(::connect)
                } else {
                    target.containingFile?.virtualFile?.let(paths::get)?.let(::connect)
                }
            }
            val fromDocument = file.viewProvider.document
            for ((to, rawList) in collected) {
                val finalized = finalizeOccurrences(rawList)
                val occurrences = finalized.take(MAX_OCCURRENCES).map { occ ->
                    val fromSnippet = fromDocument?.let { doc -> occ.fromLine?.let { extractCodeSnippet(doc, it) } }
                    val toDoc = occ.target.containingFile?.viewProvider?.document
                    val toSnippet = toDoc?.let { doc -> occ.toLine?.let { extractCodeSnippet(doc, it) } }
                    TreeConnectionOccurrence(occ.kind, occ.fromLine, occ.toLine, fromSnippet, toSnippet)
                }
                val (primaryFrom, primaryTo) = primaries.getValue(to)
                val site = TreeConnectionSite(primaryFrom, primaryTo, occurrences, finalized.size)
                outgoing.getOrPut(from, ::linkedMapOf)[to] = site
                incoming.getOrPut(to, ::linkedMapOf)[from] = site
            }
        }
        return files.keys.associateWith { TreeFileConnections(incoming[it].orEmpty(), outgoing[it].orEmpty()) }
    }

    private fun finalizeOccurrences(rawList: List<RawOccurrence>): List<RawOccurrence> {
        val byLocation = LinkedHashMap<Pair<Int?, Int?>, RawOccurrence>()
        for (occ in rawList) {
            val key = occ.fromLine to occ.toLine
            val existing = byLocation[key]
            if (existing == null || occurrencePriority(occ.kind) > occurrencePriority(existing.kind)) {
                byLocation[key] = occ
            }
        }
        val deduplicated = byLocation.values.toList()
        // Drop qualifier references (e.g. `Api` in `Api.run()`) when a more specific member occurrence sits on the same line.
        val byFromLine = deduplicated.groupBy { it.fromLine }
        val filtered = deduplicated.filter { occ ->
            if (occ.kind != RelationKind.REFERENCE || occ.fromLine == null) return@filter true
            val siblings = byFromLine[occ.fromLine].orEmpty()
            siblings.none { other ->
                other !== occ && other.kind != RelationKind.IMPORT &&
                    PsiTreeUtil.isAncestor(occ.target, other.target, true)
            }
        }
        return filtered.sortedWith(
            compareBy<RawOccurrence>(
                { it.kind == RelationKind.IMPORT },
                { it.fromLine ?: Int.MAX_VALUE },
                { it.toLine ?: Int.MAX_VALUE },
                { -occurrencePriority(it.kind) },
            )
        )
    }

    private fun occurrencePriority(kind: RelationKind): Int = when (kind) {
        RelationKind.CALL -> 3
        RelationKind.INHERITS -> 2
        RelationKind.REFERENCE -> 1
        RelationKind.IMPORT -> 0
        RelationKind.CONTAINS -> -1
    }

    private companion object {
        const val MAX_OCCURRENCES = 30
    }
}
