package dev.insight.review

import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportRawProgress
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import dev.insight.agent.findSourceFile
import dev.insight.callgraph.CallGraphLanguageSupport
import dev.insight.callgraph.ProjectGraphAnalyzer
import dev.insight.callgraph.callGraphCodeStamp
import dev.insight.graph.RelationKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive

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

    /** Completed one-line brief of how the source uses the target, shown beside Tree rows. Accessed on the EDT. */
    var brief: String? = null
}

internal data class TreeFileConnections(val incoming: Map<String, TreeConnectionSite>, val outgoing: Map<String, TreeConnectionSite>) {
    /** Completed one-line file brief for this graph snapshot. Accessed on the EDT. */
    var brief: String? = null
}

/** Files included in Tree Focus mode: the focused [targets], plus direct [incoming] and [outgoing] neighbors. */
internal data class TreeFocusLinks(
    val targets: Set<String>,
    val incoming: Set<String>,
    val outgoing: Set<String>,
) {
    val visibleFiles: Set<String> get() = targets + incoming + outgoing
    val linkedFiles: Set<String> get() = incoming + outgoing
}

internal fun collectRelatedFiles(
    paths: Collection<String>,
    links: Map<String, TreeFileConnections>,
): TreeFocusLinks? {
    if (paths.none { it in links }) return null
    val targets = paths.toCollection(LinkedHashSet())
    val incoming = LinkedHashSet<String>()
    val outgoing = LinkedHashSet<String>()
    for (path in targets) {
        val fileLinks = links[path] ?: continue
        for (src in fileLinks.incoming.keys) if (src !in targets) incoming += src
        for (dst in fileLinks.outgoing.keys) if (dst !in targets) outgoing += dst
    }
    return TreeFocusLinks(targets, incoming, outgoing)
}

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
internal class TreeFileConnectionIndex(
    private val project: Project,
    private val sources: Map<String, String?>,
    ownerScope: CoroutineScope,
) {
    private data class Stamp(val psi: Long, val roots: Long, val fileStructure: Long)
    private data class Snapshot(
        val stamp: Stamp,
        val codeOnly: Boolean,
        val links: Map<String, TreeFileConnections>,
    )

    private data class RawOccurrence(
        val kind: RelationKind,
        val fromLine: Int?,
        val toLine: Int?,
        val target: PsiElement,
    )

    private val scope = CoroutineScope(ownerScope.coroutineContext + SupervisorJob(ownerScope.coroutineContext[Job]))
    private val requestLock = Any()
    private var loading: Deferred<Unit>? = null
    @Volatile
    private var snapshot: Snapshot? = null

    /** Selection jobs share one build and can stop waiting independently. */
    suspend fun links(): Map<String, TreeFileConnections> {
        while (true) {
            scope.coroutineContext.ensureActive()
            cachedSnapshot()?.let { return it.links }
            val request = synchronized(requestLock) {
                loading?.takeUnless { it.isCompleted } ?: scope.async(start = CoroutineStart.LAZY) {
                    snapshot = cachedSnapshot() ?: buildSnapshot()
                }.also { loading = it }
            }
            request.start()
            // Check the cache stamp again in case PSI changed while the shared request was finishing.
            request.await()
        }
    }

    fun cancel() = scope.cancel()

    fun matchesSources(other: Map<String, String?>): Boolean = sources == other

    private suspend fun cachedSnapshot(): Snapshot? {
        val cached = snapshot ?: return null
        return readSources { cached.takeIf { it.stamp == stamp(it.codeOnly) } }
    }

    private suspend fun buildSnapshot(): Snapshot =
        withBackgroundProgress(project, "Building code connections") {
            reportRawProgress { reporter ->
                reporter.text("Resolving links between files…")
                readSources {
                    val files = resolveFiles()
                    val supports = CallGraphLanguageSupport.EP_NAME.extensionList
                    val supportedPaths = files.filterValues { file -> supports.any { it.supportsFile(file) } }.keys
                    // Unsupported files can still contribute links through the platform's generic references.
                    val codeOnly = supportedPaths.size == sources.size
                    val stamp = stamp(codeOnly)
                    val links = build(files, supportedPaths) { done, total ->
                        reporter.text("Resolving links between files ($done of $total)")
                        reporter.fraction(if (total > 0) done.toDouble() / total else null)
                    }
                    Snapshot(stamp, codeOnly, links)
                }
            }
        }

    private fun stamp(codeOnly: Boolean): Stamp = Stamp(
        psi = if (codeOnly) callGraphCodeStamp(project) else PsiModificationTracker.getInstance(project).modificationCount,
        roots = ProjectRootModificationTracker.getInstance(project).modificationCount,
        fileStructure = VirtualFileManager.getInstance().structureModificationCount,
    )

    private suspend fun <T> readSources(action: () -> T): T = constrainedReadAction(
        ReadConstraint.inSmartMode(project),
        ReadConstraint.withDocumentsCommitted(project),
    ) { action() }

    private fun resolveFiles(): Map<String, PsiFile> {
        val manager = PsiManager.getInstance(project)
        return sources.mapNotNull { (path, absolutePath) ->
            ProgressManager.checkCanceled()
            val file = findSourceFile(project, path, absolutePath)
            if (file == null || file.fileType.isBinary) null else manager.findFile(file)?.let { path to it }
        }.toMap()
    }

    private fun build(
        files: Map<String, PsiFile>,
        supportedPaths: Set<String>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Map<String, TreeFileConnections> {
        val paths = files.entries.associate { (path, file) -> file.virtualFile to path }
        val packageMembers = files.entries.filter { (path, _) -> path in supportedPaths }
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
            // Name-only matches (for example `x.render()` with `x` untyped) are guesses, not links between files.
            ProjectGraphAnalyzer.relationsWithSites(file, inferredOnly = true) { _, target, kind, site ->
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
