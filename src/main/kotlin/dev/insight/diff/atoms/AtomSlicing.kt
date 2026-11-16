package dev.insight.diff.atoms

import dev.insight.diff.ReviewFile
import dev.insight.diff.SideDeclaration
import dev.insight.diff.diffEvidenceKind
import dev.insight.diff.matchDeclarations
import dev.insight.diff.patchRows
import dev.insight.graph.SymbolKind
import dev.insight.review.reviewFingerprint

/**
 * A row of a unified patch with the file lines it stands for. [row] counts every text line of the patch from 1 (as
 * `patchRows` does), [old] and [new] are null on the side a row does not exist on, and [hunk] numbers the hunk from 1.
 */
internal data class PatchRow(val row: Int, val marker: Char, val old: Int?, val new: Int?, val hunk: Int, val text: String)

/** Context, removed and added rows of [patch] with their old and new line numbers; hunk headers and marker rows such as "no newline" are skipped. */
internal fun patchLineNumbers(patch: String): List<PatchRow> {
    if (patch.isEmpty()) return emptyList()
    val rows = mutableListOf<PatchRow>()
    var old = 0
    var new = 0
    var hunk = 0
    patch.removeSuffix("\n").split('\n').forEachIndexed { index, line ->
        val header = HUNK.matchEntire(line)
        if (header != null) { old = header.groupValues[1].toInt(); new = header.groupValues[2].toInt(); hunk++; return@forEachIndexed }
        if (hunk == 0) return@forEachIndexed
        when (line.firstOrNull()) {
            ' ' -> rows += PatchRow(index + 1, ' ', old++, new++, hunk, line)
            '-' -> rows += PatchRow(index + 1, '-', old++, null, hunk, line)
            '+' -> rows += PatchRow(index + 1, '+', null, new++, hunk, line)
        }
    }
    return rows
}

/**
 * Cuts a file's patch into atoms from the declarations of both sides. Null declarations mean the file has no code
 * analysis, which makes the whole file one [AtomKind.FILE] atom. Pure: no PSI, no IDE.
 *
 * A changed row belongs to the innermost declaration around it on its own side, except that a wholly added or removed
 * declaration absorbs everything nested in it (the outermost such one wins), so a new class is one atom and an edit in
 * a method of an existing class is the method's. Rows outside every declaration form the file's loose atom. Context
 * rows join the atom of the nearest changed row of their hunk that they fit into, so atoms read on their own.
 */
internal fun sliceAtoms(file: ReviewFile, before: List<SideDeclaration>?, after: List<SideDeclaration>?): List<ChangeAtom> {
    if (before == null && after == null) return fileAtom(file)
    val rows = patchLineNumbers(file.patch)
    val owners = KINDS.flatMap { kind ->
        matchDeclarations(before.orEmpty().filter { it.kind == kind }, after.orEmpty().filter { it.kind == kind }).map { (left, right) -> Owner(left, right) }
    }
    val loose = Owner(null, null)
    val onBefore = owners.mapNotNull { owner -> owner.left?.let { it to owner } }
    val onAfter = owners.mapNotNull { owner -> owner.right?.let { it to owner } }
    val ownerOf = HashMap<Int, Owner>()
    for (row in rows) when (row.marker) {
        '+' -> ownerOf[row.row] = innermost(row.new!!, onAfter) { it.left == null } ?: loose
        '-' -> ownerOf[row.row] = innermost(row.old!!, onBefore) { it.right == null } ?: loose
    }
    settleIncidental(rows, ownerOf, loose)
    val owned = LinkedHashMap<Owner, MutableSet<Int>>()
    val changedBy = LinkedHashMap<Owner, MutableList<Int>>()
    for (row in rows) ownerOf[row.row]?.let { owner -> owned.getOrPut(owner) { sortedSetOf() } += row.row; changedBy.getOrPut(owner) { mutableListOf() } += row.row }
    for (hunk in rows.groupBy { it.hunk }.values) {
        val candidates = hunk.filter { it.marker != ' ' }.groupBy({ ownerOf.getValue(it.row) }, { it.row })
        for (context in hunk.filter { it.marker == ' ' }) {
            val free by lazy { onAfter.none { context.new in it.first.lines } && onBefore.none { context.old in it.first.lines } }
            val fitting = candidates.filter { (owner, _) -> if (owner.loose) free else owner.contains(context) }
            fitting.minWithOrNull(compareBy({ (_, changed) -> distance(changed, context.row) }, { (_, changed) -> changed.first() }))
                ?.let { owned.getValue(it.key) += context.row }
        }
    }
    val text = rows.associate { it.row to it.text }
    val atoms = owned.map { (owner, all) ->
        val changed = changedBy.getValue(owner).toSet()
        val added = changed.count { text.getValue(it).startsWith('+') }
        val removed = changed.size - added
        val shown = owner.right ?: owner.left
        val status = when {
            owner.loose -> if (removed == 0 && file.status in ADDED_FILE) AtomStatus.ADDED else if (added == 0 && file.status == "deleted") AtomStatus.REMOVED else AtomStatus.MODIFIED
            owner.left == null -> AtomStatus.ADDED
            owner.right == null -> AtomStatus.REMOVED
            else -> AtomStatus.MODIFIED
        }
        ChangeAtom(
            id = "${file.path}#${shown?.qualifiedName ?: TOP_LEVEL}", path = file.path, name = shown?.qualifiedName ?: "top-level code",
            kind = when (shown?.kind) { SymbolKind.FUNCTION -> AtomKind.FUNCTION; SymbolKind.CLASS -> AtomKind.CLASS; SymbolKind.PROPERTY -> AtomKind.PROPERTY; else -> AtomKind.LOOSE },
            status = status, header = shown?.header.orEmpty(), signatureChanged = owner.left != null && owner.right != null && owner.left.header != owner.right.header,
            rows = ranges(all), changedRows = changed, beforeLines = owner.left?.lines, afterLines = owner.right?.lines, added = added, removed = removed,
            evidenceKind = diffEvidenceKind(file.path), fingerprint = reviewFingerprint(*all.map { text.getValue(it) }.toTypedArray()),
        )
    }.sortedBy { it.rows.first().first }
    val seen = HashMap<String, Int>()
    return atoms.map { atom ->
        val n = seen.merge(atom.id, 1, Int::plus)!!
        if (n == 1) atom else atom.copy(id = "${atom.id}~$n")
    }
}

private fun fileAtom(file: ReviewFile): List<ChangeAtom> {
    val lines = if (file.patch.isEmpty()) emptyList() else file.patch.removeSuffix("\n").split('\n')
    val changed = lines.indices.filter { lines[it].startsWith('+') || lines[it].startsWith('-') }
    if (changed.isEmpty()) return emptyList()
    val added = changed.count { lines[it].startsWith('+') }
    return listOf(ChangeAtom(
        id = file.path, path = file.path, name = file.path.substringAfterLast('/'), kind = AtomKind.FILE,
        status = when (file.status) { in ADDED_FILE -> AtomStatus.ADDED; "deleted" -> AtomStatus.REMOVED; else -> AtomStatus.MODIFIED },
        header = "", signatureChanged = false, rows = listOf(1..patchRows(file.patch)), changedRows = changed.mapTo(sortedSetOf()) { it + 1 },
        beforeLines = null, afterLines = null, added = added, removed = changed.size - added, evidenceKind = diffEvidenceKind(file.path),
        fingerprint = reviewFingerprint(*lines.toTypedArray()),
    ))
}

/** A declaration matched across sides (or on one side only); the shared [loose] owner has neither. */
private class Owner(val left: SideDeclaration?, val right: SideDeclaration?) {
    val loose: Boolean get() = left == null && right == null
    fun contains(row: PatchRow): Boolean = right?.lines?.contains(row.new) == true || left?.lines?.contains(row.old) == true
}

/** The owner of [line] on one side: the outermost wholly [whole] (added or removed) declaration around it, else the innermost one. */
private fun innermost(line: Int, side: List<Pair<SideDeclaration, Owner>>, whole: (Owner) -> Boolean): Owner? {
    val around = side.filter { line in it.first.lines }
    return around.filter { whole(it.second) }.maxByOrNull { it.first.lines.count() }?.second
        ?: around.asReversed().minByOrNull { it.first.lines.count() }?.second
}

/**
 * Blank changed rows and import-only top-level code say nothing on their own, so they must not become atoms: a blank row
 * follows the nearest changed row of its hunk that has text, and a file's changed imports follow its largest atom.
 */
private fun settleIncidental(rows: List<PatchRow>, ownerOf: MutableMap<Int, Owner>, loose: Owner) {
    for (hunk in rows.filter { it.marker != ' ' }.groupBy { it.hunk }.values) {
        val (blank, telling) = hunk.partition { it.text.drop(1).isBlank() }
        for (row in blank) telling.minByOrNull { kotlin.math.abs(it.row - row.row) }?.let { ownerOf[row.row] = ownerOf.getValue(it.row) }
    }
    val looseRows = rows.filter { ownerOf[it.row] === loose }
    val largest = ownerOf.values.filter { it !== loose }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return
    if (looseRows.all { it.text.drop(1).isBlank() || IMPORT.containsMatchIn(it.text.drop(1)) }) looseRows.forEach { ownerOf[it.row] = largest }
}

private fun distance(sorted: List<Int>, row: Int): Int {
    val at = sorted.binarySearch(row).let { if (it >= 0) it else -it - 1 }
    return minOf(sorted.getOrNull(at)?.let { it - row } ?: Int.MAX_VALUE, sorted.getOrNull(at - 1)?.let { row - it } ?: Int.MAX_VALUE)
}

private fun ranges(rows: Set<Int>): List<IntRange> {
    val result = mutableListOf<IntRange>()
    for (row in rows.sorted()) {
        val last = result.lastOrNull()
        if (last != null && last.last + 1 == row) result[result.lastIndex] = last.first..row else result += row..row
    }
    return result
}

private const val TOP_LEVEL = "<top-level>"
private val KINDS = listOf(SymbolKind.FUNCTION, SymbolKind.CLASS, SymbolKind.PROPERTY)
private val ADDED_FILE = setOf("added", "untracked")
// Hunk starts are one-based, or the line before an empty side, which has no rows to number.
private val HUNK = Regex("""@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@[\s\S]*""")
private val IMPORT = Regex("""^\s*(import|from|using|use|package|#include|require|extern crate)\b""")
