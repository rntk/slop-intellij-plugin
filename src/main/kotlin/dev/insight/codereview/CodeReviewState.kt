package dev.insight.codereview

import dev.insight.agent.ToolInputException
import dev.insight.codereview.map.CodeGraph
import dev.insight.codereview.map.CodeMap
import dev.insight.codereview.map.buildCodeMapTree
import dev.insight.review.ReviewDiskCache
import dev.insight.review.ReviewSummaries
import dev.insight.review.Summary
import dev.insight.review.ReviewSource
import dev.insight.review.ReviewSourceBlock
import dev.insight.review.ReviewSourceRange
import dev.insight.review.ReviewStateMessages
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.ReviewTabState
import dev.insight.review.ReviewTopics
import dev.insight.review.ReviewVocabulary
import dev.insight.review.buildSummaryTree
import dev.insight.review.buildTopicSummaryTree
import dev.insight.review.wall.WallScope

/** The summary tree of code files: folders and files, with the parts of large files beneath them. */
internal fun codeSummaryTree(files: List<CodeFile>): ReviewSummaryTree =
    buildSummaryTree(files, rootLabel = ReviewVocabulary.CODE.rootName, chunks = ::codeChunks)

internal fun codeTopicSummaryTree(topics: ReviewTopics): ReviewSummaryTree = buildTopicSummaryTree(topics, chunks = ::codeChunks)

private fun codeChunks(source: ReviewSource) = (source as? CodeFile)?.chunks.orEmpty()

internal const val CODE_CHANGED = "The files changed. Start a new request to review the current code."

internal val EMPTY_CODE_MAP =CodeMap(CodeGraph(emptyList(), emptyList()), emptyList())
internal val EMPTY_CODE_MAP_SUMMARIES = ReviewSummaries(buildCodeMapTree(EMPTY_CODE_MAP, emptyList()))

/**
 * EDT-owned state of one code review tab. Every tool is checked against the same revision as its evidence. Notes and
 * source blocks belong to file contents: an edit drops those of the edited file. Summaries and topics are kept by
 * content-hash keys, so they survive edits elsewhere, refreshes and scope changes; only Regenerate clears them.
 */
class CodeReviewState(paths: Set<String>? = null, cache: ReviewDiskCache? = null) : ReviewTabState<CodeFile>(
    "code", paths, cache, ::codeSummaryTree, ::codeTopicSummaryTree, ReviewStateMessages(
        refreshing = "The code is refreshing. Retry after refresh completes.",
        changed = CODE_CHANGED,
        notePaths = "Use 1 to 30 file paths in scope.",
        unknownFile = { "$it is not a file in this review's scope." },
    ),
) {
    /** Null follows the whole snapshot; a fixed set of files and folders stays scoped as the snapshot changes. */
    override val scopePaths: Set<String>? = paths?.map { it.trim('/') }?.toSet()
    var snapshot = CodeSnapshot.empty()
        private set
    override val snapshotFiles: List<CodeFile> get() = snapshot.files
    override fun hasFile(path: String): Boolean = path in snapshot.byPath
    override fun resolveSource(range: ReviewSourceRange): ReviewSourceBlock = resolveCodeSource(snapshot, range)

    /** Whether the Words page is shown; its counts follow the snapshot. */
    var wordsVisible = false
        private set
    fun showWords() { wordsVisible = true }
    fun hideWords() { wordsVisible = false }

    /** The scope of the Code wall while it is on the canvas; see [wallFiles]. */
    var wallScope: WallScope? = null
        private set
    fun showWall(scope: WallScope) { wallScope = scope }
    /** The wall's files in the current snapshot, in the scope's order; files that left the scope drop out. */
    internal val wallFiles: List<CodeFile> get() = wallScope?.paths?.let { paths -> paths.mapNotNull(snapshot.byPath::get) } ?: snapshot.files

    /**
     * Whether the code map card is on the canvas. The map needs PSI and models, so the tab builds it on /code-map and
     * delivers it with [setCodeMap]. Map and summaries live in memory only: a new IDE session builds them again.
     */
    var codeMapVisible = false
        private set
    fun showCodeMap() { codeMapVisible = true }
    fun hideCodeMap() { codeMapVisible = false }
    private var codeMapRevision = -1
    private var builtCodeMap: CodeMap? = null
    /** Kept across revisions, so the next map keeps the summaries of its unchanged flows and units. */
    private var mapSummaries = EMPTY_CODE_MAP_SUMMARIES
    /** The code map of the current revision, or null until one is built for it. */
    internal val codeMap: CodeMap? get() = builtCodeMap?.takeIf { codeMapRevision == revision }
    /** The summaries of [codeMap]; an empty tree while there is none. */
    val codeMapSummaries: ReviewSummaries get() = if (codeMap != null) mapSummaries else EMPTY_CODE_MAP_SUMMARIES
    override val codeMapDone: Boolean get() = codeMap != null && mapSummaries.complete

    /** Keeps [map] of the files of revision [expected]; summaries of unchanged nodes stay. */
    internal fun setCodeMap(expected: Int, map: CodeMap) {
        check(expected)
        mapSummaries = mapSummaries.retainedIn(buildCodeMapTree(map, snapshot.files))
        builtCodeMap = map
        codeMapRevision = expected
    }

    /** Forgets the summaries before a complete map is generated again, so a regeneration writes them afresh. */
    internal fun clearCodeMapSummaries() { mapSummaries = ReviewSummaries(mapSummaries.tree) }

    internal fun summarizeCodeMap(expected: Int, entries: Map<Int, Summary>): ReviewSummaries {
        check(expected)
        if (codeMap == null) throw ToolInputException(CODE_CHANGED)
        return mapSummaries.summarize(entries).also { mapSummaries = it }
    }

    override fun clearNotes() {
        super.clearNotes()
        wordsVisible = false
        codeMapVisible = false
        wallScope = null
    }

    /** Whether [path] belongs to this tab: everything without a fixed scope, else a listed file or a file in a listed folder. */
    fun inScope(path: String): Boolean = scopePaths == null || path in scopePaths ||
        scopePaths.any { it.isEmpty() || path.startsWith("$it/") }

    fun update(globalSnapshot: CodeSnapshot) {
        val next = if (scopePaths == null) globalSnapshot else globalSnapshot.copy(
            files = globalSnapshot.files.filter { inScope(it.path) },
            skipped = globalSnapshot.skipped.filter { inScope(it.path) },
        )
        if (next.files != snapshot.files || next.scope != snapshot.scope || revision == 0) {
            val old = snapshot.byPath
            val fresh = next.byPath
            fun changed(path: String) = fresh[path] == null || fresh[path]?.fingerprint != old[path]?.fingerprint
            // The scope alone picks the cache slot; summaries and topics are never reset by a refresh.
            applyRevision(next.files, listOf(next.scope.path, next.scope.entry, next.scope.excludes), reset = false, ::changed)
        }
        snapshot = next
        refreshing = false
    }
}
