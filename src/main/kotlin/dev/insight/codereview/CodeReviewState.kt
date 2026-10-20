package dev.insight.codereview

import dev.insight.chat.ToolInputException
import dev.insight.diff.ReviewDiskCache
import dev.insight.diff.ReviewCacheSession
import dev.insight.diff.ChangeSummaries
import dev.insight.diff.ChangeSummaryTree
import dev.insight.diff.ChangeTopics
import dev.insight.diff.DiffNote
import dev.insight.diff.DiffSourceBlock
import dev.insight.diff.DiffSourceRange
import dev.insight.diff.ReviewSource
import dev.insight.diff.ReviewVocabulary
import dev.insight.diff.SummaryKind
import dev.insight.diff.SummaryNode
import dev.insight.diff.buildSummaryTree
import dev.insight.diff.buildTopicSummaryTree
import dev.insight.diff.seedTopicSummaries

/** The summary tree of code files: folders and files, with the parts of large files beneath them. */
internal fun codeSummaryTree(files: List<CodeFile>): ChangeSummaryTree =
    buildSummaryTree(files, rootLabel = ReviewVocabulary.CODE.rootName, chunks = ::codeChunks)

internal fun codeTopicSummaryTree(topics: ChangeTopics): ChangeSummaryTree = buildTopicSummaryTree(topics, chunks = ::codeChunks)

private fun codeChunks(source: ReviewSource) = (source as? CodeFile)?.chunks.orEmpty()

/**
 * EDT-owned state of one code review tab. Every tool is checked against the same revision as its evidence. Notes and
 * source blocks belong to file contents: an edit drops those of the edited file. Summaries and topics are kept by
 * content-hash keys, so they survive edits elsewhere, refreshes and scope changes; only Regenerate clears them.
 */
class CodeReviewState(paths: Set<String>? = null, cache: ReviewDiskCache? = null) {
    private val disk = ReviewCacheSession(cache, "code", paths)
    private fun persist() = disk.save(summaries, topics, topicSummaries)
    /** Null follows the whole snapshot; a fixed set of files and folders stays scoped as the snapshot changes. */
    val scopePaths: Set<String>? = paths?.map { it.trim('/') }?.toSet()
    var revision = 0
        private set
    var refreshing = true
    var snapshot = CodeSnapshot.empty()
        private set
    private val cards = linkedMapOf<Pair<List<String>, String?>, DiffNote>()
    val notes: List<DiffNote> get() = cards.values.toList()
    private val sourceCards = linkedMapOf<DiffSourceRange, DiffSourceBlock>()
    val sources: List<DiffSourceBlock> get() = sourceCards.values.toList()

    var summaries = ChangeSummaries(codeSummaryTree(emptyList()))
        private set
    /** Whether the summaries card is on the canvas; hiding it keeps the summaries. */
    var summariesVisible = false
        private set
    var topics = ChangeTopics(emptyList())
        private set
    var topicSummaries = ChangeSummaries(codeTopicSummaryTree(topics))
        private set
    var topicsVisible = false
        private set

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
            cards.entries.removeIf { (_, note) -> note.paths.any(::changed) }
            sourceCards.entries.removeIf { (range, _) -> changed(range.path) }
            // Keys hold paths and content hashes only, so unchanged files, parts and folders keep their summaries.
            summaries = summaries.retainedIn(codeSummaryTree(next.files))
            topics = topics.retainedIn(next.files)
            topicSummaries = topicSummaries.retainedIn(codeTopicSummaryTree(topics))
            // Saved entries are keyed by content, so the scope alone picks the slot; changed files simply miss their keys.
            disk.load(listOf(next.scope.path, next.scope.entry, next.scope.excludes))
            summaries = disk.restoreSummaries(ReviewDiskCache.SUMMARIES, summaries)
            topics = disk.restoreTopics(topics)
            topicSummaries = disk.restoreSummaries(ReviewDiskCache.FILLED, topicSummaries.retainedIn(codeTopicSummaryTree(topics)))
            revision++
        }
        snapshot = next
        refreshing = false
    }

    fun check(expected: Int) {
        if (refreshing) throw ToolInputException("The code is refreshing. Retry after refresh completes.")
        if (expected != revision) throw ToolInputException("The files changed. Start a new request to review the current code.")
    }

    /** Validates every note before adding any, so a rejected batch leaves the canvas unchanged. */
    fun addNotes(expected: Int, notes: List<DiffNote>): List<DiffNote> {
        check(expected)
        if (notes.isEmpty()) throw ToolInputException("Add at least one note.")
        val known = snapshot.byPath
        val added = notes.mapIndexed { index, note ->
            val prefix = if (notes.size > 1) "Note ${index + 1}: " else ""
            if (note.paths.isEmpty() || note.paths.size > 30) throw ToolInputException("${prefix}Use 1 to 30 file paths in scope.")
            note.paths.firstOrNull { it !in known }?.let { throw ToolInputException("${prefix}$it is not a file in this review's scope.") }
            if (note.text.isBlank() || note.text.length > 2000) throw ToolInputException("${prefix}Use a note of 1–2000 characters.")
            if (note.symbol != null && (note.symbol.isBlank() || note.symbol.length > 500))
                throw ToolInputException("${prefix}Use a symbol of 1–500 characters.")
            note.copy(paths = note.paths.distinct().sorted(), text = note.text.trim(), symbol = note.symbol?.trim())
        }
        if ((cards.keys + added.map(::key)).toSet().size > MAX_NOTES) throw ToolInputException("The canvas has reached its $MAX_NOTES-note limit.")
        added.forEach { cards[key(it)] = it }
        return added
    }

    fun removeNote(paths: List<String>, symbol: String? = null) { cards.remove(paths.distinct().sorted() to symbol) }

    fun showSource(expected: Int, range: DiffSourceRange): DiffSourceBlock {
        check(expected)
        val block = resolveCodeSource(snapshot, range)
        if (block.range !in sourceCards && sourceCards.size >= MAX_SOURCES)
            throw ToolInputException("The canvas has reached its $MAX_SOURCES-source-block limit.")
        sourceCards[block.range] = block
        return block
    }

    fun removeSource(range: DiffSourceRange) { sourceCards.remove(range) }
    fun clearNotes() { cards.clear(); sourceCards.clear(); summariesVisible = false; topicsVisible = false }

    /** Starts /summaries: an incomplete tree is continued, a complete one is written again. */
    fun startSummaries(expected: Int): ChangeSummaries {
        check(expected)
        if (summaries.complete) clearSummaries()
        summariesVisible = true
        return summaries
    }

    fun summarize(expected: Int, entries: Map<Int, String>): ChangeSummaries {
        check(expected)
        return summaries.summarize(entries).also { summaries = it; persist() }
    }

    fun clearSummaries() { summaries = ChangeSummaries(summaries.tree); disk.forget(ReviewDiskCache.SUMMARIES); persist() }
    fun showSummaries() { summariesVisible = true }
    fun hideSummaries() { summariesVisible = false }

    /** Starts /topics: an incomplete result is continued, a complete one is generated again. */
    fun startTopics(): ChangeTopics {
        if (topics.complete) {
            topics = ChangeTopics(snapshot.files)
            topicSummaries = ChangeSummaries(codeTopicSummaryTree(topics))
            disk.forget(ReviewDiskCache.TOPICS, ReviewDiskCache.FILLED)
            persist()
        }
        topicsVisible = true
        return topics
    }

    fun showTopics() { topicsVisible = true }
    fun hideTopics() { topicsVisible = false }

    fun assignTopic(expected: Int, path: String, label: String): ChangeTopics {
        check(expected)
        return topics.assign(path, label).also {
            topics = it
            topicSummaries = topicSummaries.retainedIn(codeTopicSummaryTree(it))
            persist()
        }
    }

    /** Seeds the topic tree with the summaries of the same files and parts before the missing ones are written. */
    fun startTopicSummaries(expected: Int): ChangeSummaries {
        check(expected)
        topicSummaries = seedTopicSummaries(topicSummaries, summaries)
        persist()
        return topicSummaries
    }

    /** Saves topic summaries and shares the file and part summaries among them with the ordinary Summaries tree. */
    fun summarizeTopics(expected: Int, entries: Map<Int, String>): ChangeSummaries {
        check(expected)
        topicSummaries = topicSummaries.summarize(entries)
        val topicTree = topicSummaries.tree
        val files = HashMap<String, String>()
        val parts = HashMap<String, String>()
        for (node in topicTree.nodes) {
            val text = topicSummaries.text(node.id) ?: continue
            when (node.kind) {
                SummaryKind.FILE -> files[node.path] = text
                SummaryKind.CHUNK -> parts[node.key] = text
                else -> {}
            }
        }
        summaries = shareInto(summaries) { node ->
            when (node.kind) {
                SummaryKind.FILE -> files[node.path]
                SummaryKind.CHUNK -> parts[node.key]
                else -> null
            }
        }
        persist()
        return topicSummaries
    }

    private fun key(note: DiffNote) = note.paths to note.symbol

    private companion object {
        const val MAX_NOTES = 200
        const val MAX_SOURCES = 30
    }
}

/**
 * Adds the [shared] texts to the missing nodes of [target]. A node with children is added only when all of them are
 * summarized or added too, so the bottom-up rule holds; batches go children first.
 */
internal fun shareInto(target: ChangeSummaries, shared: (SummaryNode) -> String?): ChangeSummaries {
    val tree = target.tree
    val added = LinkedHashMap<Int, String>()
    // Pre-order: walking backwards visits children before their parents.
    for (node in tree.nodes.asReversed()) {
        if (target.text(node.id) != null) continue
        val text = shared(node) ?: continue
        if (node.children.all { target.text(it) != null || it in added }) added[node.id] = text
    }
    var result = target
    added.entries.chunked(ChangeSummaries.MAX_PER_CALL).forEach { batch ->
        result = result.summarize(batch.associate { it.key to it.value })
    }
    return result
}
