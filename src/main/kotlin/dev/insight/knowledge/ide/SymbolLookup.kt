package dev.insight.knowledge.ide

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import dev.insight.graph.SymbolKind
import dev.insight.links.ProjectSymbols
import dev.insight.links.SymbolCandidate
import dev.insight.links.SymbolHints
import dev.insight.links.SymbolMention
import dev.insight.links.rankSymbolCandidates

/** A declaration found for a symbol ref, with where it sits. All PSI access needs a read action. */
internal class FoundSymbol(
    val element: PsiElement,
    val candidate: SymbolCandidate,
    val path: String,
    val kind: SymbolKind,
    val pkg: List<String>,
    val owners: List<String>,
    val match: RefMatch,
) {
    val name: String get() = candidate.name

    /** `pkg.Owner.name`, the ref the knowledge base stores for this declaration. */
    fun canonicalRef(): String = (pkg + owners + name).joinToString(".")

    /** `path#Owner.name`, the file-pinned form. */
    fun pinnedRef(): String = "$path#${(owners + name).joinToString(".")}"

    /** 1-based inclusive line range of the declaration. */
    fun lines(project: Project): IntRange? = lineRange(project, element)
}

/** The project's FQN-to-declaration lookup, built on [ProjectSymbols] (which knows names, not qualified names). */
internal object SymbolLookup {
    private const val MAX_RANKED = 60

    /**
     * Declarations that [ref] names: exact matches first, then those that moved (same name and owner types elsewhere),
     * each group ranked most likely first. Call inside a smart read action.
     */
    fun find(project: Project, ref: SymbolRef): List<FoundSymbol> {
        val candidates = ProjectSymbols.findNow(project, ref.name)
        if (candidates.isEmpty()) return emptyList()
        val mention = SymbolMention(0, ref.name.length, ref.name, ref.qualifiers)
        val pinned = ref.path
        val hints = SymbolHints(files = if (pinned == null) emptyList() else candidates.filter { it.absolutePath == pinned || it.absolutePath.endsWith("/$pinned") }.map { it.path })
        val ranked = rankSymbolCandidates(candidates, mention, hints, MAX_RANKED)
        val found = ranked.mapNotNull { candidate ->
            val located = locate(project, candidate) ?: return@mapNotNull null
            val match = matchRef(ref, located.path, located.pkg, located.owners, candidate.fileName.substringBeforeLast('.'))
            if (match == RefMatch.NONE) null else FoundSymbol(located.element, candidate, located.path, located.kind, located.pkg, located.owners, match)
        }
        return found.filter { it.match == RefMatch.EXACT } + found.filter { it.match == RefMatch.DRIFT }
    }

    class Located(val element: PsiElement, val path: String, val kind: SymbolKind, val pkg: List<String>, val owners: List<String>)

    /** The declaration [candidate] stands for, or null when it cannot be resolved to a class, function or property. */
    fun locate(project: Project, candidate: SymbolCandidate): Located? {
        val virtual = ProjectPaths.findAbsolute(project, candidate.absolutePath) ?: ProjectPaths.find(project, candidate.path) ?: return null
        val file = PsiManager.getInstance(project).findFile(virtual) ?: return null
        val offset = offsetOf(project, file, candidate) ?: return null
        val leaf = file.findElementAt(offset) ?: return null
        val chain = generateSequence(leaf) { it.parent }.takeWhile { it !is PsiFile }.toList()
        val element = chain.firstOrNull { Declarations.isMemberKind(Declarations.kindOf(it)) && it.textOffset == offset }
            ?: chain.firstOrNull { Declarations.isMemberKind(Declarations.kindOf(it)) } ?: return null
        val kind = Declarations.kindOf(element)?.takeIf { Declarations.isMemberKind(it) } ?: return null
        return Located(element, ProjectPaths.relative(project, virtual), kind, Declarations.packageOf(file), Declarations.owners(element))
    }

    private fun offsetOf(project: Project, file: PsiFile, candidate: SymbolCandidate): Int? {
        val document = PsiDocumentManager.getInstance(project).getDocument(file)
        if (document != null) {
            val line = (candidate.line - 1).coerceAtLeast(0)
            if (line >= document.lineCount) return null
            return (document.getLineStartOffset(line) + candidate.column - 1).coerceIn(0, document.textLength)
        }
        val lines = file.text.lines()
        val line = (candidate.line - 1).coerceAtLeast(0)
        if (line >= lines.size) return null
        return lines.take(line).sumOf { it.length + 1 } + candidate.column - 1
    }
}

/** 1-based inclusive lines of [element] in its file, or null when it has no document. */
internal fun lineRange(project: Project, element: PsiElement): IntRange? {
    val file = element.containingFile ?: return null
    val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
    val range = element.textRange
    if (range.endOffset > document.textLength) return null
    val first = document.getLineNumber(range.startOffset) + 1
    val last = document.getLineNumber(maxOf(range.startOffset, range.endOffset - 1)) + 1
    return first..last
}
