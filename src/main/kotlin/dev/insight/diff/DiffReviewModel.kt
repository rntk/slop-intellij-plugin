package dev.insight.diff

import dev.insight.callgraph.LineChange
import dev.insight.chat.ToolInputException
import java.security.MessageDigest

/** Immutable review evidence, independent of PSI, graph construction and the Git plugin. */
data class ReviewFile(
    val path: String,
    val absolutePath: String,
    val previousPath: String?,
    val status: String,
    val patch: String,
    val fingerprint: String,
    val before: String? = null,
    val after: String? = null,
    val beforeLabel: String = "Before",
    val afterLabel: String = "Working tree",
) {
    internal val diffKey get() = diffNoteKey(path)
    // Swing tree renderers call toString before applying their custom label.
    override fun toString(): String = path.substringAfterLast('/')
}

data class UntrackedFile(val path: String, val absolutePath: String)
data class ReviewSnapshot(
    val files: List<ReviewFile>,
    val untracked: List<UntrackedFile>,
    val comparison: ReviewComparison = ReviewComparison(),
)
data class DiffNote(
    val paths: List<String>,
    val text: String,
    val symbol: String? = null,
    val evidence: DiffSymbolEvidence? = null,
) {
    internal val key get() = DiffNoteKey(paths, symbol)
}

internal data class DiffNoteKey(
    val paths: List<String>,
    val symbol: String?,
    val source: DiffSourceRange? = null,
    val diff: String? = null,
)
internal fun diffNoteKey(path: String) = DiffNoteKey(listOf(path), null, diff = path)

/** EDT-owned state. Every tool is checked against the same revision as its evidence. */
class DiffReviewState(paths: Set<String>? = null) {
    /** Null follows all changes; a fixed set stays scoped even when its files leave the diff. */
    val scopePaths: Set<String>? = paths?.toSet()
    var revision = 0
        private set
    var refreshing = true
    var snapshot = ReviewSnapshot(emptyList(), emptyList())
        private set
    private val cards = linkedMapOf<DiffNoteKey, DiffNote>()
    val notes: List<DiffNote> get() = cards.values.toList()
    private val sourceCards = linkedMapOf<DiffSourceRange, DiffSourceBlock>()
    val sources: List<DiffSourceBlock> get() = sourceCards.values.toList()
    private val diffCards = linkedMapOf<String, ReviewFile>()
    val diffs: List<ReviewFile> get() = diffCards.values.toList()

    fun update(globalSnapshot: ReviewSnapshot) {
        val next = scopePaths?.let { paths -> globalSnapshot.copy(files = globalSnapshot.files.filter { it.path in paths }) } ?: globalSnapshot
        val comparisonChanged = next.comparison != snapshot.comparison
        val requestChanged = next.comparison.request != snapshot.comparison.request
        // Moving refs invalidate captured tool metadata; only a new request discards unchanged file evidence.
        if (next.files != snapshot.files || comparisonChanged || revision == 0) {
            val old = snapshot.files.associateBy { it.path }
            val fresh = next.files.associateBy { it.path }
            cards.entries.removeIf { (_, note) -> note.paths.any { path ->
                requestChanged || fresh[path] == null || fresh[path]?.fingerprint != old[path]?.fingerprint
            } }
            sourceCards.entries.removeIf { (range, _) ->
                requestChanged || fresh[range.path] == null || fresh[range.path]?.fingerprint != old[range.path]?.fingerprint
            }
            diffCards.entries.removeIf { (path, _) ->
                requestChanged || fresh[path] == null || fresh[path]?.fingerprint != old[path]?.fingerprint
            }
            diffCards.replaceAll { path, _ -> fresh.getValue(path) }
            revision++
        }
        snapshot = next
        refreshing = false
    }

    fun check(expected: Int) {
        if (refreshing) throw ToolInputException("The diff is refreshing. Retry after refresh completes.")
        if (expected != revision) throw ToolInputException("The diff changed. Start a new request to review the current snapshot.")
    }

    fun addNote(expected: Int, paths: List<String>, text: String): DiffNote =
        addNotes(expected, listOf(DiffNote(paths, text))).single()

    /** Validates every note before adding any, so a rejected batch leaves the canvas unchanged. */
    fun addNotes(expected: Int, notes: List<DiffNote>): List<DiffNote> {
        check(expected)
        if (notes.isEmpty()) throw ToolInputException("Add at least one note.")
        val known = snapshot.files.mapTo(hashSetOf()) { it.path }
        val added = notes.mapIndexed { index, note ->
            val prefix = if (notes.size > 1) "Note ${index + 1}: " else ""
            if (note.paths.isEmpty() || note.paths.size > 30) throw ToolInputException("${prefix}Use 1 to 30 included file paths.")
            note.paths.firstOrNull { it !in known }?.let { throw ToolInputException("${prefix}$it is not a file included in this diff.") }
            if (note.text.isBlank() || note.text.length > 2000)
                throw ToolInputException("${prefix}Use a note of 1–2000 characters.")
            if (note.symbol != null && (note.symbol.isBlank() || note.symbol.length > 500))
                throw ToolInputException("${prefix}Use a symbol of 1–500 characters.")
            note.copy(paths = note.paths.distinct().sorted(), text = note.text.trim(), symbol = note.symbol?.trim())
        }
        if ((cards.keys + added.map { it.key }).toSet().size > 200) throw ToolInputException("The canvas has reached its 200-note limit.")
        added.forEach { cards[it.key] = it }
        return added
    }

    fun uncovered(): List<String> = snapshot.files.map { it.path }.filter { path -> cards.keys.none { path in it.paths } }

    fun removeNote(paths: List<String>, symbol: String? = null) { cards.remove(DiffNoteKey(paths.distinct().sorted(), symbol)) }
    fun showSource(expected: Int, range: DiffSourceRange): DiffSourceBlock {
        check(expected)
        val block = resolveSourceBlock(snapshot, range)
        if (range !in sourceCards && sourceCards.size >= 30)
            throw ToolInputException("The canvas has reached its 30-source-block limit.")
        sourceCards[range] = block
        return block
    }

    fun removeSource(range: DiffSourceRange) { sourceCards.remove(range) }
    fun showDiff(path: String): ReviewFile? {
        val file = snapshot.files.firstOrNull { it.path == path } ?: return null
        diffCards[path] = file
        return file
    }

    fun removeDiff(path: String) { diffCards.remove(path) }
    fun clearNotes() { cards.clear(); sourceCards.clear(); diffCards.clear() }
}

internal fun reviewFingerprint(vararg values: String): String {
    val hash = MessageDigest.getInstance("SHA-256")
    values.forEach { hash.update(it.toByteArray(Charsets.UTF_8)); hash.update(0.toByte()) }
    return hash.digest().joinToString("") { "%02x".format(it) }
}

/** Unified evidence with three context lines; deleted lines retain their old-side line numbers. */
internal fun reviewPatch(before: String, after: String, changes: List<LineChange>): String {
    val old = if (before.isEmpty()) emptyList() else before.split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it }
    val new = if (after.isEmpty()) emptyList() else after.split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it }
    data class Region(var a: Int, var b: Int, var c: Int, var d: Int)
    val regions = mutableListOf<Region>()
    // The IDE comparator includes a final empty line after a newline; unified patches do not.
    val normalized = changes.map { LineChange(it.start1.coerceIn(0, old.size), it.end1.coerceIn(0, old.size),
        it.start2.coerceIn(0, new.size), it.end2.coerceIn(0, new.size)) }
    normalized.forEach { change ->
        val region = Region((change.start1 - 3).coerceAtLeast(0), (change.end1 + 3).coerceAtMost(old.size),
            (change.start2 - 3).coerceAtLeast(0), (change.end2 + 3).coerceAtMost(new.size))
        val last = regions.lastOrNull()
        if (last != null && region.a <= last.b && region.c <= last.d) {
            last.b = maxOf(last.b, region.b); last.d = maxOf(last.d, region.d)
        } else regions += region
    }
    return buildString {
        for (region in regions) {
            append("@@ -${if (region.a == region.b) region.a else region.a + 1},${region.b - region.a} +${if (region.c == region.d) region.c else region.c + 1},${region.d - region.c} @@\n")
            var a = region.a
            var c = region.c
            for (change in normalized.filter { it.start1 >= region.a && it.end1 <= region.b && it.start2 >= region.c && it.end2 <= region.d }) {
                while (a < change.start1 && c < change.start2) { append(' ').append(old[a++]).append('\n'); c++ }
                while (a < minOf(change.end1, old.size)) append('-').append(old[a++]).append('\n')
                while (c < minOf(change.end2, new.size)) append('+').append(new[c++]).append('\n')
            }
            while (a < region.b && c < region.d) { append(' ').append(old[a++]).append('\n'); c++ }
        }
        if (before.isNotEmpty() && !before.endsWith('\n')) append("[Before: no newline at end of file]\n")
        if (after.isNotEmpty() && !after.endsWith('\n')) append("[After: no newline at end of file]\n")
    }
}
