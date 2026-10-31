package dev.insight.callgraph

import com.intellij.lang.BracePair
import com.intellij.lang.LanguageBraceMatching
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import dev.insight.graph.RelationKind

/** Read action only. Never mix live IDE facts or tool results with stale schematic lines. */
internal fun schematicGapSource(project: Project, schematic: Schematic, unit: GapUnit): GapSource {
    val psi = checkNotNull(PsiManager.getInstance(project).findFile(schematic.file)) { "Source is unavailable" }
    val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(psi)) { "Source is unavailable" }
    val lines = document.text.split('\n')
    check(lines.drop(schematic.firstLine).take(schematic.lines.size) == schematic.lines) {
        "Source changed; reopen the schematic before summarizing gaps"
    }
    val start = document.getLineStartOffset(unit.first)
    val end = document.getLineEndOffset(unit.last)
    val selected = TextRange(start, end)
    val path = schematic.file.path.removePrefix(project.basePath?.trimEnd('/')?.plus('/') ?: "")

    val facts = linkedSetOf("Language: ${psi.language.displayName}")
    fun describe(element: PsiElement): String? {
        val name = (element as? PsiNamedElement)?.name ?: return null
        val kind = ProjectGraphAnalyzer.kind(element) ?: return null
        if (element is PsiFile) return null
        return "${kind.name.lowercase()} ${name.take(160)}"
    }
    // Ancestors above the file (e.g. its directory) have no text range; they are never the owner.
    val owner = generateSequence(psi.findElementAt(start)) { it.parent }
        .firstOrNull { it.textRange?.contains(selected) == true && describe(it) != null }
    owner?.let { facts += "Enclosing: ${describe(it)}" }
    var visited = 0
    psi.accept(object : PsiRecursiveElementWalkingVisitor() {
        override fun visitElement(element: PsiElement) {
            ProgressManager.checkCanceled()
            val range = element.textRange
            if (range == null) {
                // Synthetic elements can have no range but still hold ranged children.
                super.visitElement(element)
                return
            }
            if (range.endOffset <= start || range.startOffset >= end) return
            if (++visited > 2000 || facts.size >= 24) return
            // Imports are already readable in the excerpt; resolving their package chains adds little.
            if (element is PsiComment || ProjectGraphAnalyzer.relationKind(element) == RelationKind.IMPORT) return
            // Folded bodies are separate symbols; their insides are not this gap's facts.
            val lines = document.getLineNumber(range.startOffset)..document.getLineNumber(maxOf(range.startOffset, range.endOffset - 1))
            if (unit.folded.any { lines.first >= it.first && lines.last <= it.last }) return
            // Ancestors supply ownership only, not their unrelated names or references.
            if (selected.contains(range)) describe(element)?.let { facts += "Declared: $it" }
            for (reference in element.references.take(8)) {
                val referenceRange = reference.rangeInElement.shiftRight(range.startOffset)
                if (!selected.intersectsStrict(referenceRange)) continue
                for (target in resolveTargets(reference).take(2)) {
                    val description = describe(target) ?: continue
                    val targetFile = target.containingFile?.virtualFile
                    val location = targetFile?.let {
                        val base = project.basePath?.trimEnd('/')?.plus('/')
                        if (base != null && it.path.startsWith(base)) it.path.removePrefix(base) else it.name
                    }
                    facts += "Reference ${reference.canonicalText.take(120)}: $description" +
                        (location?.let { " ($it)" } ?: "")
                    if (facts.size >= 24) break
                }
                if (facts.size >= 24) break
            }
            super.visitElement(element)
        }
    })
    return GapSource(path, lines, facts.joinToString("\n"))
}

/** Read action only. The gaps of [rows], lines of [scope], as complete statements and declarations its language parses. */
internal fun schematicGapUnits(scope: PsiElement, document: Document, rows: List<SchematicRow>, budget: GapBudget = GapBudget()): List<GapUnit> {
    val file = scope.containingFile ?: return emptyList()
    fun line(line: Int) = TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))
    return gapUnits(rows, { document.getText(line(it)) },
        { document.getText(line(it)).isNotBlank() && !structuralGap(file, line(it)) },
        { gapConstructs(scope, document, it) }, budget)
}

/**
 * Read action only. Complete statements and declarations containing [line], smallest first, inside [scope]:
 * elements that fill their lines and follow a structural opening brace or a sibling that ended on an earlier line.
 * Arguments, operands and fields after a comma never qualify, so they are read with what holds them.
 * The scope itself qualifies only when its language requires the declaration to stay whole.
 */
internal fun gapConstructs(scope: PsiElement, document: Document, line: Int): List<GapConstruct> {
    val file = scope.containingFile ?: return emptyList()
    val text = document.charsSequence
    val start = (document.getLineStartOffset(line) until document.getLineEndOffset(line)).firstOrNull { !text[it].isWhitespace() }
        ?: return emptyList()
    val bounds = scope.textRange ?: return emptyList()
    val supports = CallGraphLanguageSupport.EP_NAME.extensionList.filter { it.supportsFile(file) }
    fun keepWhole(element: PsiElement) = supports.any { it.keepGapWhole(element) }
    // The first visible token (`type` in Go) may precede a named declaration's PSI range on the same line.
    val seed = if (start < bounds.startOffset && line == lineOf(document, bounds.startOffset) && keepWhole(scope))
        bounds.startOffset else start
    return generateSequence(file.findElementAt(seed)) { it.parent }
        .takeWhile { element -> element !is PsiFile && element.textRange?.let { bounds.contains(it) } == true }
        .onEach { ProgressManager.checkCanceled() }
        // Some languages wrap a named declaration in another PSI element with the identical range.
        .filter { it.textRange != bounds || keepWhole(it) }
        // Language-confirmed declarations also qualify inside non-structural groups such as Go's `type (...)`.
        // A named type scope can start after `type`; its whole source line still belongs to this unit.
        .filter { complete(it, document) || (keepWhole(it) && (it === scope || fillsLines(it, document))) }
        .map { GapConstruct(lineOf(document, it.textRange.startOffset), lineOf(document, it.textRange.endOffset - 1), headerLast(it, document), keepWhole(it)) }
        .distinctBy { it.first to it.last }
        .toList()
}

private fun lineOf(document: Document, offset: Int) = document.getLineNumber(offset.coerceIn(0, document.textLength))

private fun pairs(element: PsiElement): List<BracePair> =
    LanguageBraceMatching.INSTANCE.forLanguage(element.language)?.pairs?.toList().orEmpty()

private fun significant(element: PsiElement) = element !is PsiWhiteSpace && element !is PsiComment && element.textLength > 0

/** Only whitespace before [element] on its first line, and only whitespace, separators or comments after it on its last. */
private fun fillsLines(element: PsiElement, document: Document): Boolean {
    val range = element.textRange ?: return false
    if (range.isEmpty) return false
    val text = document.charsSequence
    val lineStart = document.getLineStartOffset(lineOf(document, range.startOffset))
    if ((lineStart until range.startOffset).any { !text[it].isWhitespace() }) return false
    val lineEnd = document.getLineEndOffset(lineOf(document, range.endOffset - 1))
    var leaf = PsiTreeUtil.nextLeaf(element)
    while (leaf != null && leaf.textRange.startOffset < lineEnd) {
        if (significant(leaf) && leaf.text !in SEPARATORS) return false
        leaf = PsiTreeUtil.nextLeaf(leaf)
    }
    return true
}

private val SEPARATORS = setOf(";", ",")

private fun complete(element: PsiElement, document: Document): Boolean {
    if (!significant(element) || !fillsLines(element, document)) return false
    if (element.parent is PsiFile) return true
    val previous = generateSequence(element.prevSibling) { it.prevSibling }.firstOrNull(::significant)
    if (previous == null) {
        // A first child is as complete as the statements after it; otherwise its parent decides.
        val next = generateSequence(element.nextSibling) { it.nextSibling }.firstOrNull(::significant) ?: return false
        return lineOf(document, next.textRange.startOffset) > lineOf(document, element.textRange.endOffset - 1) && fillsLines(next, document)
    }
    val type = previous.node?.elementType
    if (pairs(previous).any { it.isStructural && it.leftBraceType == type }) return true
    return lineOf(document, previous.textRange.endOffset - 1) < lineOf(document, element.textRange.startOffset) &&
        fillsLines(previous, document)
}

/**
 * The last line of [element]'s header: up to its first structural opening brace outside parentheses, or before the
 * first line that starts its body. Null when it has no shorter header.
 */
private fun headerLast(element: PsiElement, document: Document): Int? {
    val range = element.textRange
    val first = lineOf(document, range.startOffset)
    val last = lineOf(document, range.endOffset - 1)
    if (first == last) return null
    val text = document.charsSequence
    var depth = 0
    var leaf = PsiTreeUtil.firstChild(element).takeIf { it.textRange.startOffset == range.startOffset }
    var steps = 0
    while (leaf != null && leaf.textRange.startOffset < range.endOffset && steps++ < HEADER_LEAVES) {
        if (significant(leaf)) {
            val start = leaf.textRange.startOffset
            val line = lineOf(document, start)
            val type = leaf.node?.elementType
            val pairs = pairs(leaf)
            val startsLine = (document.getLineStartOffset(line) until start).all { text[it].isWhitespace() }
            when {
                depth == 0 && pairs.any { it.isStructural && it.leftBraceType == type } -> return line.takeIf { it < last }
                depth == 0 && line > first && startsLine -> return line - 1
                pairs.any { !it.isStructural && it.leftBraceType == type } -> depth++
                pairs.any { !it.isStructural && it.rightBraceType == type } -> depth = (depth - 1).coerceAtLeast(0)
            }
        }
        leaf = PsiTreeUtil.nextLeaf(leaf)
    }
    return null
}

private const val HEADER_LEAVES = 2_000

/** Only whitespace and complete closing structural tokens, confirmed by the language plugin. */
internal fun structuralGap(file: PsiFile, range: TextRange): Boolean {
    var offset = range.startOffset
    while (offset < range.endOffset) {
        ProgressManager.checkCanceled()
        val leaf = file.findElementAt(offset) ?: return false
        val leafRange = leaf.textRange ?: return false
        if (leafRange.endOffset <= offset) return false
        if (leaf !is PsiWhiteSpace) {
            if (!range.contains(leafRange) || generateSequence(leaf) { it.parent }.any { it is PsiErrorElement }) return false
            val pairs = LanguageBraceMatching.INSTANCE.forLanguage(leaf.language)?.pairs ?: return false
            if (pairs.none { it.isStructural && it.rightBraceType == leaf.node.elementType }) return false
        }
        offset = leafRange.endOffset
    }
    return true
}
