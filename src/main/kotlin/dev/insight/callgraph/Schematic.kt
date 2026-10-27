package dev.insight.callgraph

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager

/** A run of lines of a schematic; lines are 0-based document lines, both ends inclusive. */
sealed interface SchematicRow {
    val first: Int
    val last: Int
}

/** Source shown as it is: nothing on these lines leads further into the graph. */
data class CodeRow(override val first: Int, override val last: Int) : SchematicRow

/**
 * Lines that lead further into the graph: a declaration of [declared] folded to its [headline] (the line naming it),
 * or a line that calls [calls].
 */
data class BlockRow(override val first: Int, override val last: Int, val headline: Int, val declared: Int?, val calls: List<Int>) : SchematicRow {
    val targets: List<Int> get() = listOfNotNull(declared) + calls
}

/** A function declared in a schematic's scope, folded to one block; [headline] is the line naming it. */
data class SchematicDeclaration(val id: Int, val first: Int, val last: Int, val headline: Int)

/** A call of graph symbol [callee] starting on [line]. */
data class SchematicCall(val callee: Int, val line: Int)

/**
 * A lossy reading of the file, class or function [scope]: [lines] are its source lines from document line
 * [firstLine], and [rows] cover them in order.
 */
class Schematic(val scope: Int, val file: VirtualFile, val firstLine: Int, val lines: List<String>, val rows: List<SchematicRow>) {
    fun text(line: Int): String = lines[line - firstLine]
    val targets: List<Int> get() = rows.filterIsInstance<BlockRow>().flatMap { it.targets }.distinct()
}

/**
 * Rows covering lines [first]..[last]. Declarations fold into one block each and own the calls inside them; a line
 * with calls of its own becomes a block; the lines in between stay code. Nested or overlapping declarations keep the
 * outermost one.
 */
fun schematicRows(first: Int, last: Int, declarations: List<SchematicDeclaration>, calls: List<SchematicCall>): List<SchematicRow> {
    val folded = mutableListOf<SchematicDeclaration>()
    declarations.filter { it.last >= first && it.first <= last }.sortedWith(compareBy({ it.first }, { -it.last })).forEach { declaration ->
        if (folded.lastOrNull()?.let { declaration.first <= it.last } != true) {
            folded += declaration.copy(first = declaration.first.coerceAtLeast(first), last = declaration.last.coerceAtMost(last))
        }
    }
    val callsByLine = calls.filter { call -> call.line in first..last && folded.none { call.line in it.first..it.last } }
        .groupBy({ it.line }, { it.callee }).mapValues { it.value.distinct() }
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
                rows += BlockRow(declaration.first, declaration.last, declaration.headline.coerceIn(declaration.first, declaration.last), declaration.id, emptyList())
                line = declaration.last
            }
            called != null -> {
                endCode(line)
                rows += BlockRow(line, line, line, null, called)
            }
            code == null -> code = line
        }
        line++
    }
    endCode(last + 1)
    return rows
}

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
    /** Symbols a schematic opens further: functions, and classes through their constructors. */
    private val TARGETS = setOf(SymbolKind.FUNCTION, SymbolKind.CLASS)

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
            if (result.graph.nodes[id].kind == SymbolKind.FUNCTION) declared += id
            else { own += id; pending.addAll(hierarchy.children[id]) }
        }
        val declarations = declared.mapNotNull { id ->
            val declaration = result.pointers.getOrNull(id)?.element?.takeIf { it.isValid && it.containingFile == psiFile } ?: return@mapNotNull null
            val (start, end) = lines(document, declaration.textRange)
            SchematicDeclaration(id, start, end, document.getLineNumber(declaration.textOffset.coerceIn(0, document.textLength)))
        }

        val callees = own.flatMap { calls[it].orEmpty() }.map { it.callee }
            .filter { result.graph.nodes.getOrNull(it)?.let { node -> node.inProject && node.kind in TARGETS } == true }.distinct()
        // Call sites are collected per class too: walking a function or file skips the classes nested in it.
        val callers = listOf(scope) + own.filter { it != scope && result.graph.nodes[it].kind == SymbolKind.CLASS }
        val sites = if (callees.isEmpty()) emptyList()
        else CallSiteAnalyzer.collect(result, callers, callees).values.flatten().filterIsInstance<CallSite>().map { site ->
            // A site ends at [CallSite.offset]; its untruncated text says where it starts, so a multi-line call shows on its first line.
            val start = site.text?.takeIf { it.length < SITE_TEXT }?.let { site.offset - it.length } ?: site.offset
            SchematicCall(site.callee, document.getLineNumber(start.coerceIn(0, document.textLength)))
        }

        val text = (first..last).map { line ->
            document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
        }
        return Schematic(scope, virtualFile, first, text, schematicRows(first, last, declarations, sites))
    }

    /** Length at which [CallSiteAnalyzer] truncates [CallSite.text]. */
    private const val SITE_TEXT = 600

    private fun lines(document: Document, range: TextRange): Pair<Int, Int> {
        val start = range.startOffset.coerceIn(0, document.textLength)
        val end = (range.endOffset - 1).coerceIn(start, document.textLength)
        return document.getLineNumber(start) to document.getLineNumber(end)
    }
}
