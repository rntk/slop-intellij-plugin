package dev.insight.codereview

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.util.PsiTreeUtil
import dev.insight.callgraph.CallGraphLanguageSupport
import dev.insight.graph.RelationKind
import dev.insight.graph.SymbolKind
import dev.insight.review.OutlineDeclaration
import dev.insight.review.RadarSymbolKind
import dev.insight.review.RadarSymbols
import dev.insight.review.SourceOutline
import dev.insight.review.SourceStructure

/** Parse the exact captured text in a background read action, including unsaved edits, without resolving references. */
internal fun codeSymbolCounts(project: Project, name: String, type: FileType, text: String): RadarSymbols? =
    codeStructure(project, name, type, text, outline = false)?.symbols

/**
 * Counts every [RadarSymbolKind] in one PSI walk and, with [outline], nests the declarations a reader explores: types,
 * callables, other declarations, and the properties and variables outside function bodies (constructors keep theirs).
 * Call in a read action; null for a language without [CallGraphLanguageSupport].
 */
internal fun codeStructure(project: Project, name: String, type: FileType, text: String, outline: Boolean = true): SourceStructure? {
    val supports = CallGraphLanguageSupport.EP_NAME.extensionList
    val language = (type as? LanguageFileType)?.language ?: return null
    if (supports.none { it.supportsLanguage(language) }) return null
    val psi = PsiFileFactory.getInstance(project).createFileFromText(name, type, text)
    val support = supports.firstOrNull { it.supportsFile(psi) } ?: return null
    val lines = LineIndex(text)
    val root = Frame(null, null, inFunction = false, keepsMembers = false)
    val stack = ArrayDeque(listOf(root))
    psi.accept(object : PsiRecursiveElementWalkingVisitor() {
        override fun visitElement(element: PsiElement) {
            ProgressManager.checkCanceled()
            val top = stack.last()
            if (element is PsiComment) {
                top.add(RadarSymbolKind.COMMENT)
                return // Documentation tags and nested PSI are part of the same comment block.
            }
            if (support.relationKind(element) == RelationKind.IMPORT) {
                // A grouped import (Go's import block) counts its specs; imports hold no declarations to visit.
                val nested = PsiTreeUtil.collectElements(element) { it !== element && support.relationKind(it) == RelationKind.IMPORT }
                top.add(RadarSymbolKind.IMPORT, maxOf(1, nested.size))
                return
            }
            val base = baseKind(support, element)
            val traits = support.symbolTraits(element).map { RadarSymbolKind.valueOf(it.name) }
            val frame = if (outline && base != null && isOutlined(base, top)) {
                val constructor = RadarSymbolKind.CONSTRUCTOR in traits
                Frame(element, TRAIT_PRIORITY.firstOrNull { it in traits } ?: base,
                    inFunction = top.inFunction || (base == RadarSymbolKind.FUNCTION && !constructor),
                    keepsMembers = constructor).also(stack::addLast)
            } else top
            base?.let(frame::add)
            traits.forEach(frame::add)
            super.visitElement(element)
        }

        override fun elementFinished(element: PsiElement) {
            val frame = stack.last()
            if (frame.element !== element) return
            stack.removeLast()
            stack.last().finish(frame, lines)
        }
    })
    return SourceStructure(root.symbols(), SourceOutline(root.children.toList()))
}

private fun baseKind(support: CallGraphLanguageSupport, element: PsiElement): RadarSymbolKind? = when (support.symbolKind(element)) {
    SymbolKind.FUNCTION -> RadarSymbolKind.FUNCTION
    SymbolKind.CLASS -> RadarSymbolKind.CLASS
    SymbolKind.PROPERTY -> RadarSymbolKind.PROPERTY
    SymbolKind.VARIABLE -> RadarSymbolKind.VARIABLE
    SymbolKind.PARAMETER -> RadarSymbolKind.PARAMETER
    SymbolKind.SYMBOL -> RadarSymbolKind.OTHER
    else -> null
}?.let {
    if ((it == RadarSymbolKind.VARIABLE || it == RadarSymbolKind.PROPERTY) && support.isConstant(element)) RadarSymbolKind.CONSTANT else it
}

/** Locals and parameters stay counts; declarations a reader navigates to become outline entries. */
private fun isOutlined(kind: RadarSymbolKind, parent: Frame): Boolean = when (kind) {
    RadarSymbolKind.CLASS, RadarSymbolKind.FUNCTION, RadarSymbolKind.OTHER -> true
    RadarSymbolKind.PROPERTY, RadarSymbolKind.CONSTANT -> !parent.inFunction || parent.keepsMembers
    RadarSymbolKind.VARIABLE -> !parent.inFunction && !parent.keepsMembers
    else -> false
}

/** The kind an outline entry shows: the most specific trait that names a declaration. */
private val TRAIT_PRIORITY = listOf(RadarSymbolKind.CONSTRUCTOR, RadarSymbolKind.TEST, RadarSymbolKind.INTERFACE, RadarSymbolKind.ENUM,
    RadarSymbolKind.STRUCT, RadarSymbolKind.OBJECT, RadarSymbolKind.TYPE_ALIAS, RadarSymbolKind.IMPL)

private class Frame(val element: PsiElement?, val kind: RadarSymbolKind?, val inFunction: Boolean, val keepsMembers: Boolean) {
    val counts = IntArray(RadarSymbolKind.entries.size)
    val children = mutableListOf<OutlineDeclaration>()

    fun add(kind: RadarSymbolKind) = add(kind, 1)
    fun add(kind: RadarSymbolKind, count: Int) { counts[kind.ordinal] += count }

    fun symbols() = RadarSymbols(RadarSymbolKind.entries.filter { counts[it.ordinal] > 0 }.associateWith { counts[it.ordinal] })

    /** Adds [child]'s counts to this frame and records it as an outline entry. */
    fun finish(child: Frame, lines: LineIndex) {
        for (i in counts.indices) counts[i] += child.counts[i]
        val element = child.element!!
        val range = element.textRange
        children += OutlineDeclaration(child.kind!!, label(element), lines.line(range.startOffset)..lines.line(maxOf(range.startOffset,
            range.endOffset - 1)), utf8Length(lines.text, range.startOffset, range.endOffset), child.symbols(), child.children.toList())
    }
}

/** The declared name, else the first line of the declaration, such as `impl Display for Point`. */
private fun label(element: PsiElement): String {
    (element as? PsiNamedElement)?.name?.takeIf { it.isNotBlank() }?.let { return it }
    val line = element.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("@") && !it.startsWith("#[") }
        .orEmpty().removeSuffix("{").trim()
    return if (line.length > MAX_LABEL) line.take(MAX_LABEL - 1) + "…" else line.ifEmpty { "anonymous" }
}

private const val MAX_LABEL = 60

private fun utf8Length(text: String, start: Int, end: Int): Int {
    var bytes = 0
    var i = start
    while (i < end) {
        val c = text[i]
        bytes += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            Character.isHighSurrogate(c) && i + 1 < end && Character.isLowSurrogate(text[i + 1]) -> { i++; 4 }
            else -> 3
        }
        i++
    }
    return bytes
}

/** 1-based line numbers of offsets in [text]. */
private class LineIndex(val text: String) {
    private val starts: IntArray = buildList {
        add(0)
        text.forEachIndexed { index, c -> if (c == '\n') add(index + 1) }
    }.toIntArray()

    fun line(offset: Int): Int {
        val found = starts.binarySearch(offset.coerceIn(0, text.length))
        return if (found >= 0) found + 1 else -found - 1
    }
}
