package dev.insight.review

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import dev.insight.agent.ToolChatRunner
import dev.insight.agent.ToolInputException
import dev.insight.callgraph.CallGraphAnalyzer
import dev.insight.callgraph.ProjectGraphAnalyzer
import dev.insight.graph.RelationKind
import dev.insight.graph.SymbolKind

/** Immutable source evidence; the canvas never holds PSI or accepts model-authored edges. */
data class ReviewSymbol(
    val path: String,
    val absolutePath: String,
    val name: String,
    val qualifiedName: String,
    val kind: SymbolKind,
    val line: Int,
    val offset: Int,
) {
    val selector get() = "$path#$qualifiedName:$line@$offset"
}

data class ReviewSymbolLinks(val label: String, val symbols: List<ReviewSymbol>, val total: Int, val incoming: Boolean = false)
/** [calls] holds the selectors of all resolved outgoing calls, so notes on those targets can join a change flow. */
data class ReviewSymbolEvidence(val symbol: ReviewSymbol, val links: List<ReviewSymbolLinks>, val calls: Set<String> = emptySet())

/** A file whose declarations can be looked up: [text] is the reviewed current source, or null when there is none (deleted or binary). */
internal data class SymbolFile(val path: String, val absolutePath: String, val text: String?)

/** Uses the same declarations and resolved relations as the application graph, without building the whole project.
 * All methods run in a background read action. Each source file must still match its reviewed [SymbolFile.text];
 * [stale] explains a path whose current source differs, [missing] a path outside the review, and [findTool] names the
 * chat tool that lists exact selectors.
 */
internal class ReviewSymbols(
    private val project: Project,
    files: List<SymbolFile>,
    private val stale: (String) -> String,
    private val missing: (String) -> String = { "$it is not a file included in this review" },
    private val findTool: String,
) {
    private data class Source(val file: PsiFile, val declarations: List<Pair<PsiElement, ReviewSymbol>>)
    private val sources = mutableMapOf<String, Source>()
    // The first file wins, like a lookup in list order.
    private val byPath = LinkedHashMap<String, SymbolFile>().apply { files.forEach { putIfAbsent(it.path, it) } }
    private val byAbsolutePath = HashMap<String, SymbolFile>().apply { files.forEach { putIfAbsent(it.absolutePath, it) } }

    private fun file(path: String): SymbolFile = byPath[path] ?: throw ToolInputException(missing(path))

    private fun describe(element: PsiElement, path: String): ReviewSymbol? {
        val target = ProjectGraphAnalyzer.source(element)
        val kind = ProjectGraphAnalyzer.kind(target) ?: return null
        if (target is PsiFile) return null
        val name = (target as? PsiNamedElement)?.name ?: return null
        val file = target.containingFile ?: return null
        val virtual = file.virtualFile ?: return null
        if (!CallGraphAnalyzer.isInProject(project, target)) return null
        val owners = generateSequence(target.parent) { it.parent }.takeWhile { it !is PsiFile }
            .filter { ProjectGraphAnalyzer.kind(it) != null }.mapNotNull { (it as? PsiNamedElement)?.name }.toList().asReversed()
        val qualified = (owners + name).joinToString(".")
        val line = (file.viewProvider.document?.getLineNumber(target.textOffset)
            ?: file.text.take(target.textOffset).count { it == '\n' }) + 1
        return ReviewSymbol(path, virtual.path, name, qualified, kind, line, target.textOffset)
    }

    private fun source(path: String): Source = sources.getOrPut(path) {
        val review = file(path)
        val text = review.text ?: throw ToolInputException("$path has no readable current source; use a file note without symbol")
        val virtual = LocalFileSystem.getInstance().findFileByPath(review.absolutePath)
            ?: throw ToolInputException("$path is no longer available")
        val psi = PsiManager.getInstance(project).findFile(virtual)
            ?: throw ToolInputException("$path has no parsed source")
        if (psi.text != text) throw ToolInputException(stale(path))
        val declarations = ProjectGraphAnalyzer.declarations(psi).mapNotNull { element -> describe(element, path)?.let { element to it } }
        Source(psi, declarations)
    }

    fun find(paths: List<String>, query: String, offset: Int): String {
        val unavailable = mutableMapOf<String, String>()
        val symbols = paths.distinct().flatMap { path ->
            try { source(path).declarations.map { it.second } }
            catch (e: ToolInputException) { unavailable[path] = e.message.orEmpty(); emptyList() }
        }.filter { query.isEmpty() || it.qualifiedName.contains(query, ignoreCase = true) }
        if (offset > symbols.size) throw ToolInputException("Offset is outside the symbol list")
        val page = mutableListOf<Map<String, Any>>()
        fun result() = reviewJson.toJson(buildMap {
            put("symbols", page)
            put("total_symbols", symbols.size)
            if (unavailable.isNotEmpty()) put("unavailable", unavailable)
            if (offset + page.size < symbols.size) put("next_offset", offset + page.size)
        })
        for (symbol in symbols.drop(offset).take(30)) {
            page += mapOf("path" to symbol.path, "symbol" to symbol.selector, "name" to symbol.qualifiedName,
                "kind" to symbol.kind.name.lowercase(), "line" to symbol.line)
            if (result().length > ToolChatRunner.DEFAULT_MAX_TOOL_RESULT_CHARS) { page.removeAt(page.lastIndex); break }
        }
        if (result().length > ToolChatRunner.DEFAULT_MAX_TOOL_RESULT_CHARS || (page.isEmpty() && offset < symbols.size))
            throw ToolInputException("Symbol metadata exceeds the result limit. Request fewer paths or a narrower query.")
        return result()
    }

    /** Resolve the whole batch before mutation; a missing or ambiguous declaration rejects every note. */
    fun resolve(notes: List<ReviewNote>): List<ReviewNote> {
        val resolved = notes.map { note ->
            val query = note.symbol?.trim() ?: return@map note to null
            val candidates = note.paths.distinct().flatMap { path ->
                val review = file(path)
                // A coherent change can group a live declaration with a deleted or binary file.
                if (review.text == null) emptyList() else source(path).declarations
            }
            val matches = candidates.filter { (_, symbol) ->
                query == symbol.selector || query == symbol.name || query == symbol.qualifiedName
            }
            if (matches.size != 1) {
                val reason = if (matches.isEmpty()) "was not found" else "is ambiguous"
                val choices = (if (matches.isEmpty()) candidates else matches).take(5).joinToString { it.second.selector }
                throw ToolInputException("Symbol '$query' $reason in the note's paths. Use $findTool for an exact selector." +
                    if (choices.isEmpty()) " No supported declarations found." else " Candidates: $choices")
            }
            note to matches.single()
        }
        // Resolve relations once per owning file, only when a symbol note needs them.
        val relations = mutableMapOf<String, List<Triple<PsiElement, PsiElement, RelationKind>>>()
        return resolved.map { (note, match) ->
            // Plain notes bypass source parsing entirely (including deleted files).
            if (match == null) return@map note.copy(evidence = null)
            val (element, symbol) = match
            val edges = relations.getOrPut(symbol.path) {
                buildList { ProjectGraphAnalyzer.relations(source(symbol.path).file) { from, to, kind -> add(Triple(from, to, kind)) } }
            }
            val groups = linkedMapOf<Pair<RelationKind, Boolean>, LinkedHashMap<String, ReviewSymbol>>()
            edges.forEach { (from, to, kind) ->
                ProgressManager.checkCanceled()
                val incoming = to == element && from != element && kind == RelationKind.CALL
                if (from != element && !incoming) return@forEach
                if (kind == RelationKind.IMPORT) return@forEach
                val target = if (incoming) from else to
                val absolute = target.containingFile?.virtualFile?.path
                val path = absolute?.let(byAbsolutePath::get)?.path
                    ?: absolute?.removePrefix(project.basePath.orEmpty() + "/") ?: return@forEach
                val other = describe(target, path) ?: return@forEach
                groups.getOrPut(kind to incoming) { linkedMapOf() }[other.selector] = other
            }
            // A call is more informative than a generic reference to the same declaration.
            groups[RelationKind.CALL to false]?.keys?.let { calls -> groups[RelationKind.REFERENCE to false]?.keys?.removeAll(calls) }
            val links = groups.entries.sortedBy { (key, _) -> if (key.second) -1 else key.first.ordinal }.mapNotNull { (key, values) ->
                if (values.isEmpty()) return@mapNotNull null
                val label = if (key.second) "Callers in this file" else when (key.first) {
                    RelationKind.CALL -> "Calls"
                    RelationKind.CONTAINS -> "Contains"
                    RelationKind.INHERITS -> "Inherits / implements"
                    else -> "References"
                }
                ReviewSymbolLinks(label, values.values.take(3), values.size, key.second)
            }
            val calls = groups[RelationKind.CALL to false]?.keys?.take(MAX_CALLS)?.toSet().orEmpty()
            note.copy(symbol = symbol.selector, evidence = ReviewSymbolEvidence(symbol, links, calls))
        }
    }

    private companion object {
        const val MAX_CALLS = 500
    }
}
