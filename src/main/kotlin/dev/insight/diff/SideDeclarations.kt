package dev.insight.diff

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.util.PsiTreeUtil
import dev.insight.callgraph.ProjectGraphAnalyzer
import dev.insight.callgraph.SymbolKind

/**
 * A declaration on one side of a file, PSI-free. [text] is the declaration's own text with nested functions and classes
 * removed, so editing a local function changes that function and not its owner.
 */
data class SideDeclaration(
    val qualifiedName: String,
    val kind: SymbolKind,
    val header: String,
    val text: String,
    /** One-based, inclusive. */
    val lines: IntRange,
)

/** Named functions, classes and properties of one parsed side, in source order. Run in a read action. */
internal fun sideDeclarations(file: PsiFile): List<Pair<PsiElement, SideDeclaration>> {
    val index = LineIndex(file.text)
    val kinds = setOf(SymbolKind.FUNCTION, SymbolKind.CLASS, SymbolKind.PROPERTY)
    return ProjectGraphAnalyzer.declarations(file).mapNotNull { element ->
        ProgressManager.checkCanceled()
        val kind = ProjectGraphAnalyzer.kind(element)?.takeIf { it in kinds } ?: return@mapNotNull null
        if (element is PsiFile || element !is PsiNamedElement || element.name == null) return@mapNotNull null
        val start = element.textRange.startOffset
        val nested = nestedDeclarations(element)
        val comments = PsiTreeUtil.collectElementsOfType(element, PsiComment::class.java)
            .filter { comment -> nested.none { PsiTreeUtil.isAncestor(it, comment, false) } }.map { it.textRange }
        element to SideDeclaration(qualifiedName(element), kind, declarationHeader(without(element, nested.map { it.textRange } + comments)),
            without(element, nested.map { it.textRange }),
            index.line(start)..index.line((element.textRange.endOffset - 1).coerceAtLeast(start)))
    }
}

/** Outermost functions and classes inside [element]. */
private fun nestedDeclarations(element: PsiElement): List<PsiElement> {
    val result = mutableListOf<PsiElement>()
    element.accept(object : PsiRecursiveElementWalkingVisitor() {
        override fun visitElement(child: PsiElement) {
            if (child !== element && ProjectGraphAnalyzer.kind(child).let { it == SymbolKind.FUNCTION || it == SymbolKind.CLASS } &&
                child.textRange.length > 0) { result += child; return }
            super.visitElement(child)
        }
    })
    return result
}

private fun without(element: PsiElement, ranges: List<TextRange>): String {
    val text = element.text
    val start = element.textRange.startOffset
    val builder = StringBuilder()
    var position = 0
    for (range in ranges.sortedBy { it.startOffset }) {
        val from = (range.startOffset - start).coerceIn(position, text.length)
        builder.append(text, position, from)
        position = (range.endOffset - start).coerceIn(from, text.length)
    }
    return builder.append(text, position, text.length).toString()
}

/** Owners that are functions or classes, then the name: `Outer.Inner.method`. */
private fun qualifiedName(element: PsiElement): String {
    val owners = generateSequence(element.parent) { it.parent }.takeWhile { it !is PsiFile }
        .filter { ProjectGraphAnalyzer.kind(it).let { kind -> kind == SymbolKind.CLASS || kind == SymbolKind.FUNCTION } }
        .mapNotNull { (it as? PsiNamedElement)?.name }.toList().asReversed()
    return (owners + ((element as? PsiNamedElement)?.name ?: "?")).joinToString(".")
}

/** One-based line numbers of offsets in a text. */
internal class LineIndex(text: CharSequence) {
    private val starts = buildList { add(0); text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
    fun line(offset: Int): Int {
        val found = starts.binarySearch(offset)
        return (if (found >= 0) found else -found - 2) + 1
    }
}

/**
 * Pairs declarations of one kind across sides: first identical name and header (so overloads keep their partners), then
 * the remaining same-name declarations in source order (a changed signature). Everything else is added or deleted;
 * a rename reads as one of each.
 */
internal fun matchDeclarations(before: List<SideDeclaration>, after: List<SideDeclaration>): List<Pair<SideDeclaration?, SideDeclaration?>> {
    val pairs = mutableListOf<Pair<SideDeclaration?, SideDeclaration?>>()
    val left = before.toMutableList()
    val right = after.toMutableList()
    fun pass(key: (SideDeclaration) -> Any) {
        val pending = left.groupBy(key).mapValues { it.value.toMutableList() }
        for (declaration in right.toList()) {
            val partner = pending[key(declaration)]?.removeFirstOrNull() ?: continue
            pairs += partner to declaration
            left.remove(partner); right.remove(declaration)
        }
    }
    pass { Triple(it.qualifiedName, it.kind, it.header) }
    pass { it.qualifiedName to it.kind }
    pairs += right.map { null to it }
    pairs += left.map { it to null }
    return pairs
}

/**
 * The declaration header without annotations or decorators: from the first other line up to its body (`{`, an
 * expression body `=` or the end of the line after the parameter list). Whitespace is collapsed.
 */
internal fun declarationHeader(code: String): String {
    val lines = code.lines().map { it.trim() }.dropWhile { it.isEmpty() || it.startsWith("@") }
    val text = lines.joinToString("\n")
    var depth = 0
    var closed = false
    for ((i, c) in text.withIndex()) {
        when (c) {
            '(', '[', '<' -> depth++
            ')', ']', '>' -> { if (depth > 0) depth--; if (depth == 0 && c == ')') closed = true }
            '{' -> if (depth == 0) return clipHeader(text.substring(0, i))
            '=' -> if (depth == 0 && text.getOrNull(i + 1) != '=' && text.getOrNull(i + 1) != '>' &&
                text.getOrNull(i - 1)?.let { it in "=!<>+-*/%&|^" } != true) return clipHeader(text.substring(0, i))
            '\n' -> if (depth == 0 && closed) return clipHeader(text.substring(0, i))
        }
    }
    return clipHeader(text)
}

private fun clipHeader(text: String): String = text.replace(Regex("\\s+"), " ").trim().let { if (it.length <= 200) it else it.take(199) + "…" }
