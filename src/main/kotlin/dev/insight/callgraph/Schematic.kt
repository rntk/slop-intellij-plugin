package dev.insight.callgraph

import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import dev.insight.graph.CallGraphEdge
import dev.insight.graph.CallGraphResult
import dev.insight.graph.CallOccurrence
import dev.insight.graph.CallSite
import dev.insight.graph.GraphHierarchy
import dev.insight.graph.RelationKind
import dev.insight.graph.SymbolKind
import java.util.BitSet

/** A run of lines of a schematic; lines are 0-based document lines, both ends inclusive. */
sealed interface SchematicRow {
    val first: Int
    val last: Int
}

/** Source shown as it is: nothing on these lines leads further into the graph. */
data class CodeRow(override val first: Int, override val last: Int) : SchematicRow

/**
 * Lines that lead further into the graph: a declaration of [declared], one block led by its [headline] (the line naming it),
 * a line that calls [calls], or a line naming project types and properties. A line with calls at separate places, or
 * with such names, has one of [links] for each; otherwise its only link is the whole row.
 */
data class BlockRow(override val first: Int, override val last: Int, val headline: Int, val declared: Int?, val calls: List<Int>,
    val occurrences: List<CallOccurrence> = emptyList(),
    val links: List<SchematicLink> = listOf(SchematicLink(listOfNotNull(declared) + calls, null, occurrences))) : SchematicRow {
    val targets: List<Int> get() = listOfNotNull(declared) + calls
    /** The row's links are independent parts of its headline rather than the whole row. */
    val split: Boolean get() = links.size > 1 || links.any { it.reference }
}

/**
 * A clickable part of a block row leading to [targets]: [columns] of its headline (0-based, in the document line's own
 * characters). Null columns mean the whole row, or in a [BlockRow.split] row the calls whose place is unknown. A
 * [reference] link names its targets (types and properties) without calling them.
 */
data class SchematicLink(val targets: List<Int>, val columns: IntRange?, val occurrences: List<CallOccurrence> = emptyList(),
    val reference: Boolean = false)

/**
 * A function declared in a schematic's scope, read as one block; [headline] is the line naming it, and [columns] of
 * that line hold its name, when known.
 */
data class SchematicDeclaration(val id: Int, val first: Int, val last: Int, val headline: Int, val columns: IntRange? = null)

/** A call of graph symbol [callee] starting on [line]; [columns] of that line name the callee, when known. */
data class SchematicCall(val callee: Int, val line: Int, val occurrence: CallOccurrence? = null, val columns: IntRange? = null)

/** A mention of graph symbol [target], a type or property, at [columns] of [line]. */
data class SchematicReference(val target: Int, val line: Int, val columns: IntRange)

/**
 * A lossy reading of the file, class or function [scope]: [lines] are its source lines from document line
 * [firstLine], and [rows] cover them in order. [excerpts] hold the first line of each referenced declaration. [gaps] are
 * the code to summarize on demand; each code row lies in at most one, and a gap may hold blocks.
 */
class Schematic(val scope: Int, val file: VirtualFile, val firstLine: Int, val lines: List<String>, val rows: List<SchematicRow>,
    val excerpts: Map<Int, String> = emptyMap(), val gaps: List<GapUnit> = emptyList()) {
    fun text(line: Int): String = lines[line - firstLine]
    /** Declared and called symbols; referenced types and properties are only linked, never summarized from here. */
    val targets: List<Int> get() = rows.filterIsInstance<BlockRow>().flatMap { it.targets }.distinct()
}

/**
 * Rows covering lines [first]..[last]. Declarations fold into one block each and own the calls inside them; a line
 * with calls of its own becomes a block; the lines in between stay code. Nested or overlapping declarations keep the
 * outermost one. [references] become links of their own on block lines and on the headlines of declarations whose
 * name is placed; a line with only references becomes a block too.
 */
fun schematicRows(first: Int, last: Int, declarations: List<SchematicDeclaration>, calls: List<SchematicCall>,
    references: List<SchematicReference> = emptyList()): List<SchematicRow> {
    val folded = mutableListOf<SchematicDeclaration>()
    declarations.filter { it.last >= first && it.first <= last }.sortedWith(compareBy({ it.first }, { -it.last })).forEach { declaration ->
        if (folded.lastOrNull()?.let { declaration.first <= it.last } != true) {
            folded += declaration.copy(first = declaration.first.coerceAtLeast(first), last = declaration.last.coerceAtMost(last))
        }
    }
    val callsByLine = calls.filter { call -> call.line in first..last && folded.none { call.line in it.first..it.last } }
        .groupBy { it.line }
    val referencesByLine = references.filter { reference ->
        reference.line in first..last && folded.none { reference.line in it.first..it.last && reference.line != it.headline }
    }.groupBy { it.line }
    val declarationAt = folded.associateBy { it.first }
    val rows = mutableListOf<SchematicRow>()
    var code: Int? = null
    fun endCode(before: Int) {
        code?.let { rows += CodeRow(it, before - 1) }
        code = null
    }
    var line = first
    while (line <= last) {
        val declaration = declarationAt[line]
        val called = callsByLine[line]
        when {
            declaration != null -> {
                endCode(line)
                val headline = declaration.headline.coerceIn(declaration.first, declaration.last)
                // Types on the headline, such as parameter types, are links beside the declared name.
                val named = declaration.columns?.let { SchematicLink(listOf(declaration.id), it) }
                val mentioned = named?.let { referenceLinks(referencesByLine[headline].orEmpty(), listOf(it), listOf(declaration.id)) }.orEmpty()
                rows += if (named != null && mentioned.isNotEmpty())
                    BlockRow(declaration.first, declaration.last, headline, declaration.id, emptyList(), links = inOrder(listOf(named) + mentioned))
                    else BlockRow(declaration.first, declaration.last, headline, declaration.id, emptyList())
                line = declaration.last
            }
            called != null || line in referencesByLine -> {
                endCode(line)
                val callees = called.orEmpty().map { it.callee }.distinct()
                val occurrences = called.orEmpty().mapNotNull { it.occurrence }.distinct()
                val links = callLinks(called.orEmpty())
                val mentioned = referenceLinks(referencesByLine[line].orEmpty(), links, callees)
                rows += if (links.size > 1 || mentioned.isNotEmpty()) BlockRow(line, line, line, null, callees, occurrences, inOrder(links + mentioned))
                    else BlockRow(line, line, line, null, callees, occurrences)
            }
            code == null -> code = line
        }
        line++
    }
    endCode(last + 1)
    return rows
}

/**
 * Links of one line's [calls], in source order: calls whose names overlap share a link, and calls whose place is
 * unknown share a last one.
 */
private fun callLinks(calls: List<SchematicCall>): List<SchematicLink> {
    fun link(columns: IntRange?, group: List<SchematicCall>) =
        SchematicLink(group.map { it.callee }.distinct(), columns, group.mapNotNull { it.occurrence }.distinct())
    return overlapping(calls.filter { it.columns != null }) { it.columns!! }.map { (columns, group) -> link(columns, group) } +
        listOfNotNull(calls.filter { it.columns == null }.takeIf { it.isNotEmpty() }?.let { link(null, it) })
}

/**
 * Links of one line's [references], leaving out names inside [taken] links and mentions of [targets] the line already
 * leads to, such as the class of a constructor call.
 */
private fun referenceLinks(references: List<SchematicReference>, taken: List<SchematicLink>, targets: List<Int>): List<SchematicLink> {
    val free = references.filter { reference ->
        reference.target !in targets && taken.none { link ->
            link.columns?.let { it.first <= reference.columns.last && reference.columns.first <= it.last } == true
        }
    }
    return overlapping(free) { it.columns }.map { (columns, group) -> SchematicLink(group.map { it.target }.distinct(), columns, reference = true) }
}

/** [items] in source order, grouped where their [columns] overlap. */
private fun <T> overlapping(items: List<T>, columns: (T) -> IntRange): List<Pair<IntRange, List<T>>> {
    val groups = mutableListOf<Pair<IntRange, MutableList<T>>>()
    for (item in items.sortedBy { columns(it).first }) {
        val range = columns(item)
        val last = groups.lastOrNull()
        if (last != null && range.first <= last.first.last) {
            groups[groups.lastIndex] = (last.first.first..maxOf(last.first.last, range.last)) to last.second.apply { add(item) }
        } else groups += range to mutableListOf(item)
    }
    return groups
}

/** Placed links in source order, then those whose place is unknown. */
private fun inOrder(links: List<SchematicLink>): List<SchematicLink> =
    links.filter { it.columns != null }.sortedBy { it.columns!!.first } + links.filter { it.columns == null }

/**
 * Columns of the name in [callee] (call source such as `store.save` or `Foo<T>`) within a call site, or null when it
 * is not on the site's first line. [siteStart] and [site] are the call's offset and text; [lineStart] and [lineEnd]
 * bound the line it starts on.
 */
fun calleeColumns(siteStart: Int, site: String, callee: String, lineStart: Int, lineEnd: Int): IntRange? {
    if (callee.isEmpty()) return null
    val at = site.indexOf(callee).takeIf { it >= 0 } ?: return null
    val name = IDENTIFIER.findAll(callee.substringBefore('<')).lastOrNull()?.range ?: callee.indices
    val start = siteStart + at + name.first
    val end = siteStart + at + name.last
    return if (start >= lineStart && end < lineEnd) (start - lineStart)..(end - lineStart) else null
}

private val IDENTIFIER = Regex("[\\p{L}_$][\\p{L}\\p{N}_$]*")

/**
 * Functions reachable from [roots] along [next], breadth first, with their distance from the nearest root (roots are
 * 0). Stops once [limit] functions are found, so the nearest are kept.
 */
fun reachable(roots: Collection<Int>, limit: Int, next: (Int) -> List<Int>): Map<Int, Int> {
    val depths = LinkedHashMap<Int, Int>()
    val pending = ArrayDeque<Int>()
    for (root in roots.distinct()) {
        if (depths.size >= limit) return depths
        depths[root] = 0
        pending += root
    }
    while (pending.isNotEmpty()) {
        val id = pending.removeFirst()
        for (other in next(id)) {
            if (other in depths) continue
            if (depths.size >= limit) return depths
            depths[other] = depths.getValue(id) + 1
            pending += other
        }
    }
    return depths
}

object SchematicBuilder {
    /**
     * Read action only. The schematic of [scope], or null when its source is gone. [calls] are CALL edges by caller,
     * with calls from locals attributed to their function (see `liftCalls`).
     */
    fun build(result: CallGraphResult, hierarchy: GraphHierarchy, calls: Map<Int, List<CallGraphEdge>>, scope: Int): Schematic? {
        val element = result.pointers.getOrNull(scope)?.element?.takeIf { it.isValid } ?: return null
        val psiFile = element.containingFile ?: return null
        val virtualFile = psiFile.virtualFile ?: return null
        val document = PsiDocumentManager.getInstance(psiFile.project).getDocument(psiFile) ?: return null
        val (first, last) = lines(document, element.textRange)

        // Functions below the scope fold into declarations; everything between (classes, properties, locals) is read as
        // part of the scope, so their calls appear on its own lines.
        val declared = mutableListOf<Int>()
        val own = mutableListOf(scope)
        val pending = ArrayDeque(hierarchy.children[scope])
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (id !in result.graph.nodes.indices) continue
            if (result.graph.nodes[id].unresolved) continue
            if (result.graph.nodes[id].kind == SymbolKind.FUNCTION) declared += id
            else { own += id; pending.addAll(hierarchy.children[id]) }
        }
        val declarations = declared.mapNotNull { id ->
            val declaration = result.pointers.getOrNull(id)?.element?.takeIf { it.isValid && it.containingFile == psiFile } ?: return@mapNotNull null
            val (start, end) = lines(document, declaration.textRange)
            val headline = document.getLineNumber(declaration.textOffset.coerceIn(0, document.textLength))
            val name = (declaration as? PsiNameIdentifierOwner)?.nameIdentifier?.textRange
            SchematicDeclaration(id, start, end, headline, name?.let { columns(document, it, headline) })
        }

        // Call sites are collected per class too: calls in methods belong to those methods.
        val callers = listOf(scope) + own.filter { it != scope && result.graph.nodes[it].kind == SymbolKind.CLASS }
        val sites = if (result.graph.projectGraph) result.occurrences.filter {
            it.filePath == virtualFile.path && it.scope in callers && it.startOffset >= element.textRange.startOffset &&
                it.startOffset < element.textRange.endOffset
        }.flatMap { occurrence ->
            val line = document.getLineNumber(occurrence.startOffset.coerceIn(0, document.textLength))
            val columns = calleeColumns(occurrence.startOffset, occurrence.text, occurrence.expression,
                document.getLineStartOffset(line), document.getLineEndOffset(line))
            occurrence.displayTargets.map { SchematicCall(it, line, occurrence, columns) }
        } else {
            val callees = own.flatMap { calls[it].orEmpty() }.map { it.callee }.distinct()
            CallSiteAnalyzer.collect(result, callers, callees).values.flatten().filterIsInstance<CallSite>().map { site ->
                val start = site.startOffset ?: site.text?.takeIf { it.length < SITE_TEXT }?.let { site.offset - it.length } ?: site.offset
                val line = document.getLineNumber(start.coerceIn(0, document.textLength))
                // Only the callee is known here: find its simple name in the call's text.
                val name = result.graph.nodes[site.callee].name.substringBefore('(').substringAfterLast('.')
                val columns = site.text?.takeIf { site.startOffset == start }?.let { text ->
                    calleeColumns(start, text, name, document.getLineStartOffset(line), document.getLineEndOffset(line))
                }
                SchematicCall(site.callee, line, columns = columns)
            }
        }

        // Only lines the reader sees are searched for references: folded bodies are never resolved.
        val shown = BitSet().apply {
            schematicRows(first, last, declarations, sites).forEach { row -> if (row is BlockRow) set(row.headline) else set(row.first, row.last + 1) }
        }
        val references = references(result, document, element, shown, scope)
        val excerpts = references.map { it.target }.distinct().mapNotNull { id -> excerpt(result, id)?.let { id to it } }.toMap()

        val text = (first..last).map { line ->
            document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
        }
        // Gaps follow the language's statements and declarations, so code rows are cut where they begin and end.
        val rows = schematicRows(first, last, declarations, sites, references)
        val gaps = schematicGapUnits(element, document, rows)
        return Schematic(scope, virtualFile, first, text, splitAtUnits(rows, gaps), excerpts, gaps)
    }

    /** Symbols whose mentions are links: what a type or a property is can be read from its declaration. */
    private val REFERENCED = setOf(SymbolKind.CLASS, SymbolKind.PROPERTY, SymbolKind.SYMBOL)

    /** Length at which [CallSiteAnalyzer] truncates [CallSite.text]. */
    private const val SITE_TEXT = 600

    private const val EXCERPT = 200

    /**
     * Mentions of project types and properties, other than [scope] itself, on [shown] lines of [element]. Imports are
     * skipped; locals and parameters are read where they are.
     */
    private fun references(result: CallGraphResult, document: Document, element: PsiElement, shown: BitSet, scope: Int): List<SchematicReference> {
        val candidates by lazy {
            result.graph.nodes.filter { it.kind in REFERENCED && it.inProject && !it.unresolved }.groupBy { it.filePath }
        }
        // Graph ids by declaration, indexed one file at a time as references reach it.
        val ids = HashMap<String, Map<Pair<Int, SymbolKind>, Int>>()
        fun id(target: PsiElement): Int? {
            val (path, offset, kind) = ProjectGraphAnalyzer.key(target) ?: return null
            if (kind !in REFERENCED) return null
            return ids.getOrPut(path) {
                candidates[path].orEmpty().mapNotNull { node ->
                    val key = result.pointers.getOrNull(node.id)?.element?.takeIf { it.isValid }?.let(ProjectGraphAnalyzer::key)
                    key?.let { (_, at, symbol) -> (at to symbol) to node.id }
                }.toMap()
            }[offset to kind]
        }
        val found = linkedSetOf<SchematicReference>()
        element.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                ProgressManager.checkCanceled()
                if (element is PsiComment || ProjectGraphAnalyzer.relationKind(element) == RelationKind.IMPORT) return
                val (start, end) = lines(document, element.textRange)
                if (shown.nextSetBit(start).let { it < 0 || it > end }) return
                for (reference in element.references) {
                    val range = reference.rangeInElement.shiftRight(element.textRange.startOffset)
                    val line = document.getLineNumber(range.startOffset.coerceIn(0, document.textLength))
                    if (!shown[line]) continue
                    val columns = columns(document, range, line) ?: continue
                    for (target in resolveTargets(reference)) {
                        id(target)?.takeIf { it != scope }?.let { found += SchematicReference(it, line, columns) }
                    }
                }
                super.visitElement(element)
            }
        })
        return found.toList()
    }

    /** The line declaring [id], trimmed, so a reader sees a constant's value or a type's shape without unfolding. */
    private fun excerpt(result: CallGraphResult, id: Int): String? {
        val element = result.pointers.getOrNull(id)?.element?.takeIf { it.isValid } ?: return null
        val file = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
        val line = document.getLineNumber(element.textOffset.coerceIn(0, document.textLength))
        return document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))).trim()
            .let { if (it.length > EXCERPT) it.take(EXCERPT) + "…" else it }.ifEmpty { null }
    }

    /** Columns of a non-empty [range] that lies on [line], or null. */
    private fun columns(document: Document, range: TextRange, line: Int): IntRange? {
        if (range.isEmpty || range.startOffset < 0 || range.endOffset > document.textLength) return null
        val lineStart = document.getLineStartOffset(line)
        if (range.startOffset < lineStart || range.endOffset > document.getLineEndOffset(line)) return null
        return (range.startOffset - lineStart) until (range.endOffset - lineStart)
    }

    private fun lines(document: Document, range: TextRange): Pair<Int, Int> {
        val start = range.startOffset.coerceIn(0, document.textLength)
        val end = (range.endOffset - 1).coerceIn(start, document.textLength)
        return document.getLineNumber(start) to document.getLineNumber(end)
    }
}
