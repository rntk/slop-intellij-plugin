package dev.insight.diff

import dev.insight.callgraph.LineChange
import dev.insight.review.ReviewDiskCache
import dev.insight.review.PatchSource
import dev.insight.review.ReviewSourceBlock
import dev.insight.review.ReviewSourceRange
import dev.insight.review.ReviewStateMessages
import dev.insight.review.ReviewTabState
import dev.insight.review.buildSummaryTree
import dev.insight.review.buildTopicSummaryTree
import dev.insight.review.diffCardKey

/** Immutable review evidence, independent of PSI, graph construction and the Git plugin. */
data class ReviewFile(
    override val path: String,
    override val absolutePath: String,
    override val previousPath: String?,
    val status: String,
    override val patch: String,
    override val fingerprint: String,
    override val before: String? = null,
    override val after: String? = null,
    val beforeLabel: String = "Before",
    val afterLabel: String = "Working tree",
) : PatchSource {
    override val facts: List<String> get() = listOfNotNull(status, previousPath?.let { "renamed from $it" })
    /** Deleted files have an empty after side; their complete source is on the before side. */
    override val fullFileText: String? get() = if (status == "deleted") before else after
    internal val diffKey get() = diffCardKey(path)
    // Swing tree renderers call toString before applying their custom label.
    override fun toString(): String = path.substringAfterLast('/')
}

data class UntrackedFile(val path: String, val absolutePath: String)
data class ReviewSnapshot(
    val files: List<ReviewFile>,
    val untracked: List<UntrackedFile>,
    val comparison: ReviewComparison = ReviewComparison(),
)
/** EDT-owned state. Every tool is checked against the same revision as its evidence. */
class DiffReviewState(paths: Set<String>? = null, cache: ReviewDiskCache? = null) : ReviewTabState<ReviewFile>(
    "diff", paths, cache, { buildSummaryTree(it) }, { buildTopicSummaryTree(it) }, ReviewStateMessages(
        refreshing = "The diff is refreshing. Retry after refresh completes.",
        changed = "The diff changed. Start a new request to review the current snapshot.",
        notePaths = "Use 1 to 30 included file paths.",
        unknownFile = { "$it is not a file included in this diff." },
    ),
) {
    /** Null follows all changes; a fixed set stays scoped even when its files leave the diff. */
    override val scopePaths: Set<String>? = paths?.toSet()
    var snapshot = ReviewSnapshot(emptyList(), emptyList())
        private set
    override val snapshotFiles: List<ReviewFile> get() = snapshot.files
    override fun resolveSource(range: ReviewSourceRange): ReviewSourceBlock = resolveSourceBlock(snapshot, range)
    private val diffCards = linkedMapOf<String, ReviewFile>()
    val diffs: List<ReviewFile> get() = diffCards.values.toList()

    /** Whether the word-changes chart is on the canvas; it is counted from the current snapshot when first needed. */
    var wordsVisible = false
        private set
    private var wordsOf: List<ReviewFile>? = null
    private var counted: WordChanges? = null
    internal val words: WordChanges get() = counted?.takeIf { wordsOf === snapshot.files } ?: wordChanges(snapshot.files).also {
        counted = it; wordsOf = snapshot.files
    }
    fun showWords() { wordsVisible = true }
    fun hideWords() { wordsVisible = false }
    var diffOverviewVisible = false
        private set
    fun showDiffOverview() { diffOverviewVisible = true }
    fun hideDiffOverview() { diffOverviewVisible = false }
    /** Whether the semantic changes card is on the canvas; counting needs PSI, so the tab delivers it with [setSemantic]. */
    var semanticVisible = false
        private set
    private var semanticOf: List<ReviewFile>? = null
    private var semanticCounted: SemanticResult? = null
    /** The result for the current snapshot; [SemanticResult.Counting] until one is delivered. */
    internal val semantic: SemanticResult get() = semanticCounted?.takeIf { semanticOf === snapshot.files } ?: SemanticResult.Counting
    internal val semanticPending: Boolean get() = semantic === SemanticResult.Counting
    /** Keeps [result] unless the snapshot changed since [files] were counted. */
    internal fun setSemantic(files: List<ReviewFile>, result: SemanticResult): Boolean {
        if (files !== snapshot.files) return false
        semanticCounted = result; semanticOf = files
        return true
    }
    fun showSemantic() {
        if (semantic is SemanticResult.Failed) semanticCounted = null
        semanticVisible = true
    }
    fun hideSemantic() { semanticVisible = false }

    fun update(globalSnapshot: ReviewSnapshot) {
        val next = scopePaths?.let { paths -> globalSnapshot.copy(files = globalSnapshot.files.filter { it.path in paths }) } ?: globalSnapshot
        val comparisonChanged = next.comparison != snapshot.comparison
        val requestChanged = next.comparison.request != snapshot.comparison.request
        // Moving refs invalidate captured tool metadata; only a new request discards unchanged file evidence.
        if (next.files != snapshot.files || comparisonChanged || revision == 0) {
            val old = snapshot.files.associateBy { it.path }
            val fresh = next.files.associateBy { it.path }
            fun changed(path: String) = fresh[path] == null || fresh[path]?.fingerprint != old[path]?.fingerprint
            diffCards.entries.removeIf { (path, _) -> requestChanged || changed(path) }
            diffCards.replaceAll { path, _ -> fresh.getValue(path) }
            applyRevision(next.files, next.comparison.request, reset = requestChanged, ::changed)
        }
        snapshot = next
        refreshing = false
    }

    fun showDiff(path: String): ReviewFile? {
        val file = snapshot.files.firstOrNull { it.path == path } ?: return null
        diffCards[path] = file
        return file
    }

    fun removeDiff(path: String) { diffCards.remove(path) }
    override fun clearNotes() {
        super.clearNotes()
        diffCards.clear()
        wordsVisible = false; diffOverviewVisible = false; semanticVisible = false
    }
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
