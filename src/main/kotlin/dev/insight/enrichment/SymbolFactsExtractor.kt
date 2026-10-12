package dev.insight.enrichment

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import dev.insight.callgraph.CallGraphResult
import dev.insight.callgraph.RelationKind
import dev.insight.callgraph.SymbolKind
import java.security.MessageDigest

/** Character and entry limits for the text copied into [SymbolFacts]. */
data class FactsBudget(
    val codeChars: Int = 3000,
    val signatureChars: Int = 400,
    val docChars: Int = 600,
    val outlineEntries: Int = 60,
)

/**
 * Turns graph nodes into PSI-free [SymbolFacts]. Language-agnostic by design: the optional language plugins
 * are never referenced, so headers, annotations, docs and imports are derived from declaration text.
 */
object SymbolFactsExtractor {
    private val KINDS = setOf(SymbolKind.FILE, SymbolKind.CLASS, SymbolKind.FUNCTION, SymbolKind.PROPERTY)
    private const val MEMBER_CHARS = 200
    private const val MAX_IMPORTS = 40
    private const val SCAN_LIMIT = 4000

    private class Draft(
        val id: Int,
        val key: String,
        val kind: SymbolKind,
        val name: String,
        val container: String,
        val path: String?,
        val language: String,
        val signature: String,
        var code: String,
        val doc: String?,
        val annotations: List<String>,
        val imports: List<String>,
    )

    /**
     * Must be called inside a read action. Nodes that are outside the project or no longer resolve are skipped, as are
     * nodes not in [only] when it is given (their overload ordinals in [SymbolFacts.key] may then differ).
     */
    fun extract(project: Project, result: CallGraphResult, budget: FactsBudget = FactsBudget(), only: Set<Int>? = null): Map<Int, SymbolFacts> {
        val index = ProjectFileIndex.getInstance(project)
        val importsByFile = HashMap<PsiFile, List<String>>()
        val seen = HashMap<String, Int>()
        val drafts = LinkedHashMap<Int, Draft>()
        for (node in result.graph.nodes.sortedBy { it.id }) {
            ProgressManager.checkCanceled()
            if (!node.inProject || node.kind !in KINDS) continue
            if (only != null && node.id !in only) continue
            val element = result.pointers.getOrNull(node.id)?.element?.takeIf { it.isValid } ?: continue
            val file = element.containingFile ?: continue
            val path = relativePath(project, index, file)
            val language = file.language.id.lowercase()
            val parsed = parse(element.text, language, node.kind, budget)
            val doc = (parsed.doc ?: if (node.kind == SymbolKind.FILE) null else siblingDoc(element, language))
                ?.let { cap(it, budget.docChars) }?.takeIf { it.isNotBlank() }
            var signature = parsed.signature
            if (node.kind == SymbolKind.FUNCTION && signature.startsWith("(")) signature = node.name.substringBefore('(') + signature
            if (node.kind == SymbolKind.FILE) signature = node.name
            val base = "${path.orEmpty()}|${node.kind}|${node.container}|${node.name}"
            val ordinal = seen.merge(base, 0) { old, _ -> old + 1 }!!
            drafts[node.id] = Draft(
                node.id, if (ordinal == 0) base else "$base#$ordinal", node.kind, node.name, node.container, path, language,
                cap(signature, budget.signatureChars), code(element.text, parsed, node.kind, budget), doc, parsed.annotations,
                importsByFile.getOrPut(file) { imports(file.text) },
            )
        }
        val children = result.graph.edges.asSequence()
            .filter { it.kind == RelationKind.CONTAINS && it.callee != it.caller }
            .filter { drafts[it.callee]?.kind in KINDS && drafts[it.callee]?.kind != SymbolKind.FILE }
            .groupBy({ it.caller }, { it.callee }).mapValues { it.value.sorted() }
        for (draft in drafts.values) {
            if (draft.kind != SymbolKind.CLASS && draft.kind != SymbolKind.FILE) continue
            ProgressManager.checkCanceled()
            draft.code = outline(draft, children[draft.id].orEmpty().mapNotNull { drafts[it] }, budget)
        }
        return drafts.mapValues { (_, d) ->
            SymbolFacts(d.id, d.key, d.kind, d.name, d.container, d.path, d.language, d.signature, d.code, d.doc,
                d.annotations, d.imports, hash(d.signature, d.code, d.doc))
        }
    }

    private fun relativePath(project: Project, index: ProjectFileIndex, file: PsiFile): String? {
        val virtual = file.virtualFile ?: return null
        val path = virtual.path
        val base = project.basePath?.trimEnd('/')
        if (!base.isNullOrEmpty() && path.startsWith("$base/")) return path.substring(base.length + 1)
        val root = index.getContentRootForFile(virtual)?.path?.trimEnd('/')
        if (!root.isNullOrEmpty() && path.startsWith("$root/")) return path.substring(root.length + 1)
        return path.replace('\\', '/')
    }

    private fun outline(draft: Draft, members: List<Draft>, budget: FactsBudget): String {
        val lines = mutableListOf<String>()
        val indent = if (draft.kind == SymbolKind.CLASS) "  " else ""
        if (draft.kind == SymbolKind.CLASS) lines += draft.signature
        members.take(budget.outlineEntries).mapTo(lines) { indent + cap(it.signature, MEMBER_CHARS) }
        if (members.size > budget.outlineEntries) lines += "$indent… ${members.size - budget.outlineEntries} more"
        return cap(lines.joinToString("\n"), budget.codeChars)
    }

    private fun code(text: String, parsed: Parsed, kind: SymbolKind, budget: FactsBudget): String {
        if (kind == SymbolKind.FILE || kind == SymbolKind.CLASS) return ""
        val body = text.substring(parsed.codeStart, minOf(text.length, parsed.codeStart + budget.codeChars * 2))
        val lines = body.lines()
        val indent = lines.drop(1).filter { it.isNotBlank() }.minOfOrNull { line -> line.takeWhile { it == ' ' || it == '\t' }.length } ?: 0
        val dedented = (listOf(lines.first()) + lines.drop(1).map { it.drop(minOf(indent, it.length - it.trimStart().length)) })
        return cap(dedented.joinToString("\n").trim(), budget.codeChars)
    }

    private class Parsed(val codeStart: Int, val doc: String?, val annotations: List<String>, val signature: String)

    /**
     * Skips leading comments and annotations (recording the doc comment and annotation names), then reads the
     * declaration header up to the start of its body.
     */
    private fun parse(text: String, language: String, kind: SymbolKind, budget: FactsBudget): Parsed {
        val n = minOf(text.length, SCAN_LIMIT.coerceAtLeast(budget.codeChars * 2))
        var i = 0
        var codeStart = -1
        var doc: String? = null
        val annotations = mutableListOf<String>()
        while (true) {
            while (i < n && text[i].isWhitespace()) i++
            if (i >= n) break
            val c = text[i]
            val comment = text.startsWith("/*", i) || text.startsWith("//", i) || (c == '#' && language == "python")
            if (!comment && codeStart < 0) codeStart = i
            when {
                text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    if (doc == null && text.startsWith("/**", i)) doc = cleanComment(text.substring(i, end))
                    i = end
                }
                comment -> i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                c == '@' && kind != SymbolKind.FILE -> {
                    var j = i + 1
                    while (j < text.length && (text[j].isLetterOrDigit() || text[j] in "_.$:")) j++
                    val raw = text.substring(i + 1, j)
                    if (raw.isEmpty() || raw == "interface") break
                    annotations += simpleName(raw, language)
                    i = if (j < text.length && text[j] == '(') skipBalanced(text, j) else j
                }
                else -> break
            }
        }
        if (codeStart < 0) codeStart = minOf(i, text.length)
        if (kind == SymbolKind.FILE) {
            return Parsed(0, doc ?: docstring(text, i, language), annotations, "")
        }
        val end = headerEnd(text, i, language, kind)
        val signature = text.substring(minOf(i, end), end).replace(Regex("\\s+"), " ").trim()
        if (doc == null && language == "python" && kind != SymbolKind.PROPERTY) doc = docstring(text, end + 1, language)
        return Parsed(codeStart, doc, annotations, signature)
    }

    private fun simpleName(raw: String, language: String): String {
        val name = raw.substringAfterLast(':')
        return if (language == "java" || language == "kotlin") name.substringAfterLast('.') else name
    }

    /** Index just after the parenthesis group opening at [start]. */
    private fun skipBalanced(text: String, start: Int): Int {
        var depth = 0
        var i = start
        while (i < text.length) {
            when (text[i]) {
                '"', '\'' -> i = skipString(text, i)
                '(' -> depth++
                ')' -> if (--depth == 0) return i + 1
            }
            i++
        }
        return text.length
    }

    /** Index of the closing quote of the string starting at [start]. */
    private fun skipString(text: String, start: Int): Int {
        val quote = text[start]
        val triple = text.startsWith("$quote$quote$quote", start)
        var i = start + (if (triple) 3 else 1)
        while (i < text.length) {
            when {
                text[i] == '\\' -> i++
                triple && text.startsWith("$quote$quote$quote", i) -> return i + 2
                !triple && text[i] == quote -> return i
                !triple && text[i] == '\n' -> return i - 1
            }
            i++
        }
        return text.length - 1
    }

    /** Exclusive end of the declaration header: the first body start (`{`, `:` in Python, `=` in Kotlin) outside brackets. */
    private fun headerEnd(text: String, start: Int, language: String, kind: SymbolKind): Int {
        var depth = 0
        var i = start
        val limit = minOf(text.length, start + SCAN_LIMIT)
        while (i < limit) {
            val c = text[i]
            when {
                c == '"' || c == '\'' || c == '`' -> i = skipString(text, i)
                c == '(' || c == '[' -> depth++
                c == ')' || c == ']' -> depth = maxOf(0, depth - 1)
                depth > 0 -> {}
                c == '{' || c == ';' -> return i
                c == ':' && language == "python" && kind != SymbolKind.PROPERTY -> return i
                c == '=' -> {
                    val next = text.getOrNull(i + 1)
                    val previous = text.getOrNull(i - 1)
                    when {
                        next == '>' -> return i + 2
                        next == '=' -> i++
                        previous != null && previous in "=!<>:" -> {}
                        // Only Kotlin has expression-bodied functions; elsewhere `=` just precedes a function literal.
                        kind == SymbolKind.FUNCTION && language != "kotlin" -> {}
                        else -> return i
                    }
                }
            }
            i++
        }
        return limit
    }

    private fun docstring(text: String, from: Int, language: String): String? {
        if (language != "python") return null
        var i = from
        while (i < text.length) {
            if (text[i].isWhitespace()) i++ else if (text[i] == '#') i = skipLine(text, i) else break
        }
        while (i < text.length && text[i] in "rRuUbB") i++
        if (!text.startsWith("\"\"\"", i) && !text.startsWith("'''", i)) return null
        val end = skipString(text, i)
        val inner = text.substring(i + 3, maxOf(i + 3, end - 2))
        val lines = inner.lines()
        return (listOf(lines.first().trim()) + lines.drop(1).joinToString("\n").trimIndent().lines()).joinToString("\n").trim()
    }

    private fun skipLine(text: String, from: Int) = text.indexOf('\n', from).let { if (it < 0) text.length else it + 1 }

    /** Comments directly above a declaration that its PSI does not own (Go, JavaScript, Python-style files…). */
    private fun siblingDoc(element: PsiElement, language: String): String? {
        var start = element
        while (start.prevSibling == null) {
            val parent = start.parent ?: return null
            if (parent is PsiFile || parent.textRange.startOffset != start.textRange.startOffset) return null
            start = parent
        }
        val parts = ArrayDeque<String>()
        var current = start.prevSibling
        while (current != null) {
            if (current is PsiWhiteSpace) {
                if (current.text.count { it == '\n' } > 1) break
            } else {
                if (current !is PsiComment) break
                val text = current.text
                val block = text.startsWith("/**")
                if (!block && !(text.startsWith("//") && language != "java" && language != "kotlin")) break
                parts.addFirst(cleanComment(text))
                if (block) break
            }
            current = current.prevSibling
        }
        return parts.joinToString("\n").trim().ifEmpty { null }
    }

    private fun cleanComment(comment: String): String {
        val lines = comment.lines().map { raw ->
            raw.trim().removePrefix("/**").removePrefix("/*").trim().removeSuffix("*/").trim()
                .removePrefix("///").removePrefix("//").removePrefix("*").trim()
        }
        val result = mutableListOf<String>()
        for (line in lines) if (line.isNotEmpty() || result.isNotEmpty() && result.last().isNotEmpty()) result += line
        return result.joinToString("\n").trim()
    }

    /** Import statements without their keyword, from lines at column 0 (including Go import blocks). */
    internal fun imports(text: String): List<String> {
        val result = mutableListOf<String>()
        val lines = text.lineSequence().iterator()
        var block = false
        while (lines.hasNext() && result.size < MAX_IMPORTS) {
            var line = lines.next().trimEnd()
            val entry: String = when {
                block -> {
                    if (line.trim().startsWith(")")) { block = false; continue }
                    line.trim()
                }
                line.startsWith("import (") -> { block = true; continue }
                line.startsWith("import ") -> line.removePrefix("import ")
                line.startsWith("from ") && " import " in line -> line.removePrefix("from ")
                else -> continue
            }
            var statement = entry
            if (!block && statement.contains('(') && !statement.contains(')')) {
                while (lines.hasNext() && !statement.contains(')')) { line = lines.next(); statement += " " + line.trim() }
            }
            statement = statement.removeSuffix(";").trim().replace(Regex("\\s+"), " ")
            if (statement.length > 1 && statement.startsWith("\"") && statement.endsWith("\"")) statement = statement.substring(1, statement.length - 1)
            statement = statement.replace(Regex("^(\\w+) \"(.*)\"$"), "$1 $2")
            if (statement.isNotEmpty() && !statement.startsWith("//")) result += statement
        }
        return result
    }

    private fun cap(text: String, limit: Int) = if (text.length <= limit) text else text.take(maxOf(0, limit - 1)) + "…"

    private fun hash(signature: String, code: String, doc: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$signature\u0000$code\u0000${doc.orEmpty()}".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
