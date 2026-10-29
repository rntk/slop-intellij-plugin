package dev.insight.review

import dev.insight.agent.ToolInputException

/** The words of a review tab's state errors: "diff" or "code" phrasing of the same checks. */
internal class ReviewStateMessages(
    /** Why a tool waits: "The diff is refreshing. …" */
    val refreshing: String,
    /** Why a tool on an older revision fails: "The diff changed. …" */
    val changed: String,
    /** How many paths a note takes: "Use 1 to 30 included file paths." */
    val notePaths: String,
    /** Why a note's path is rejected: "x is not a file included in this diff." */
    val unknownFile: (String) -> String,
)

/**
 * EDT-owned state shared by the review tabs: canvas notes and source blocks, summaries, topics and their summaries, the
 * reviewed marks and the disk cache that keeps them. Every tool is checked against the same [revision] as its evidence;
 * a subclass owns the snapshot and moves to a new revision with [applyRevision].
 */
abstract class ReviewTabState<S : ReviewSource> internal constructor(
    mode: String,
    paths: Set<String>?,
    cache: ReviewDiskCache?,
    private val summaryTree: (List<S>) -> ReviewSummaryTree,
    private val topicTree: (ReviewTopics) -> ReviewSummaryTree,
    private val messages: ReviewStateMessages,
) {
    private val disk = ReviewCacheSession(cache, mode, paths)
    val reviewed = ReviewedFiles()
    fun setReviewed(node: SummaryNode, value: Boolean) {
        val keys = reviewed.set(node.files, value)
        disk.saveReviewed(keys, value)
    }
    private fun persist() = disk.save(summaries, topics, topicSummaries)

    /** The fixed files and folders of this tab; null follows the whole snapshot. */
    abstract val scopePaths: Set<String>?
    /** The files of the current snapshot. */
    abstract val snapshotFiles: List<S>
    /** Whether [path] is a file of the current snapshot. */
    protected open fun hasFile(path: String): Boolean = snapshotFiles.any { it.path == path }
    /** The snapshot source of [range], or why it cannot be shown. */
    protected abstract fun resolveSource(range: ReviewSourceRange): ReviewSourceBlock

    var revision = 0
        private set
    var refreshing = true
    private val cards = linkedMapOf<ReviewNoteKey, ReviewNote>()
    val notes: List<ReviewNote> get() = cards.values.toList()
    private val sourceCards = linkedMapOf<ReviewSourceRange, ReviewSourceBlock>()
    val sources: List<ReviewSourceBlock> get() = sourceCards.values.toList()

    /** The summary tree of this revision; summaries of unchanged subtrees survive snapshot changes. */
    var summaries = ReviewSummaries(summaryTree(emptyList()))
        private set
    /** Whether the summaries card is on the canvas; hiding it keeps the summaries. */
    var summariesVisible = false
        private set
    var topics = ReviewTopics(emptyList())
        private set
    var topicSummaries = ReviewSummaries(topicTree(topics))
        private set
    var topicsVisible = false
        private set

    /**
     * Moves to a new revision of [next]: drops the notes and source blocks of [changed] paths, keeps the summaries and
     * topics of unchanged files and restores those saved for [slot]. [reset] discards everything, as for a new request.
     */
    protected fun applyRevision(next: List<S>, slot: Any, reset: Boolean, changed: (String) -> Boolean) {
        cards.entries.removeIf { (_, note) -> reset || note.paths.any(changed) }
        sourceCards.entries.removeIf { (range, _) -> reset || changed(range.path) }
        // Keys hold paths and content hashes only, so unchanged files, parts and folders keep their summaries.
        val tree = summaryTree(next)
        summaries = if (reset) ReviewSummaries(tree) else summaries.retainedIn(tree)
        topics = if (reset) ReviewTopics(next) else topics.retainedIn(next)
        val topicSummaryTree = topicTree(topics)
        topicSummaries = if (reset) ReviewSummaries(topicSummaryTree) else topicSummaries.retainedIn(topicSummaryTree)
        // Saved entries are keyed by content, so the slot alone picks them; changed files simply miss their keys.
        disk.load(slot)
        if (reset) reviewed.restore(next, emptySet())
        disk.restoreReviewed(reviewed, next)
        summaries = disk.restoreSummaries(ReviewDiskCache.SUMMARIES, summaries)
        topics = disk.restoreTopics(topics)
        topicSummaries = disk.restoreSummaries(ReviewDiskCache.FILLED, topicSummaries.retainedIn(topicTree(topics)))
        revision++
    }

    fun check(expected: Int) {
        if (refreshing) throw ToolInputException(messages.refreshing)
        if (expected != revision) throw ToolInputException(messages.changed)
    }

    fun addNote(expected: Int, paths: List<String>, text: String): ReviewNote =
        addNotes(expected, listOf(ReviewNote(paths, text))).single()

    /** Validates every note before adding any, so a rejected batch leaves the canvas unchanged. */
    fun addNotes(expected: Int, notes: List<ReviewNote>): List<ReviewNote> {
        check(expected)
        if (notes.isEmpty()) throw ToolInputException("Add at least one note.")
        val added = notes.mapIndexed { index, note ->
            val prefix = if (notes.size > 1) "Note ${index + 1}: " else ""
            if (note.paths.isEmpty() || note.paths.size > MAX_NOTE_PATHS) throw ToolInputException("$prefix${messages.notePaths}")
            note.paths.firstOrNull { !hasFile(it) }?.let { throw ToolInputException("$prefix${messages.unknownFile(it)}") }
            if (note.text.isBlank() || note.text.length > MAX_NOTE_CHARS)
                throw ToolInputException("${prefix}Use a note of 1–$MAX_NOTE_CHARS characters.")
            if (note.symbol != null && (note.symbol.isBlank() || note.symbol.length > MAX_SYMBOL_CHARS))
                throw ToolInputException("${prefix}Use a symbol of 1–$MAX_SYMBOL_CHARS characters.")
            note.copy(paths = note.paths.distinct().sorted(), text = note.text.trim(), symbol = note.symbol?.trim())
        }
        if ((cards.keys + added.map { it.key }).toSet().size > MAX_NOTES)
            throw ToolInputException("The canvas has reached its $MAX_NOTES-note limit.")
        added.forEach { cards[it.key] = it }
        return added
    }

    fun removeNote(paths: List<String>, symbol: String? = null) { cards.remove(ReviewNoteKey(paths.distinct().sorted(), symbol)) }

    fun showSource(expected: Int, range: ReviewSourceRange): ReviewSourceBlock {
        check(expected)
        val block = resolveSource(range)
        if (block.range !in sourceCards && sourceCards.size >= MAX_SOURCES)
            throw ToolInputException("The canvas has reached its $MAX_SOURCES-source-block limit.")
        sourceCards[block.range] = block
        return block
    }

    fun removeSource(range: ReviewSourceRange) { sourceCards.remove(range) }

    /** Empties the canvas; summaries and topics are kept for when their cards are shown again. */
    open fun clearNotes() { cards.clear(); sourceCards.clear(); summariesVisible = false; topicsVisible = false }

    /** Starts /summaries: an incomplete tree is continued, a complete one is written again. */
    fun startSummaries(expected: Int): ReviewSummaries {
        check(expected)
        if (summaries.complete) clearSummaries()
        summariesVisible = true
        return summaries
    }

    fun summarize(expected: Int, entries: Map<Int, Summary>): ReviewSummaries {
        check(expected)
        return summaries.summarize(entries).also { summaries = it; persist() }
    }

    fun clearSummaries() { summaries = ReviewSummaries(summaries.tree); disk.forget(ReviewDiskCache.SUMMARIES); persist() }
    fun showSummaries() { summariesVisible = true }
    fun hideSummaries() { summariesVisible = false }

    /** Starts /topics: an incomplete result is continued, a complete one is generated again. */
    fun startTopics(): ReviewTopics {
        if (topics.complete) {
            topics = ReviewTopics(snapshotFiles)
            topicSummaries = ReviewSummaries(topicTree(topics))
            disk.forget(ReviewDiskCache.TOPICS, ReviewDiskCache.FILLED)
            persist()
        }
        topicsVisible = true
        return topics
    }

    fun showTopics() { topicsVisible = true }
    fun hideTopics() { topicsVisible = false }

    fun assignTopic(expected: Int, path: String, label: String): ReviewTopics {
        check(expected)
        return topics.assign(path, label).also {
            topics = it
            topicSummaries = topicSummaries.retainedIn(topicTree(it))
            persist()
        }
    }

    /** Seeds the topic tree with the summaries of the same files and parts before the missing ones are written. */
    fun startTopicSummaries(expected: Int): ReviewSummaries {
        check(expected)
        topicSummaries = seedTopicSummaries(topicSummaries, summaries)
        persist()
        return topicSummaries
    }

    /** Saves topic summaries and shares the file and part summaries among them with the ordinary Summaries tree. */
    fun summarizeTopics(expected: Int, entries: Map<Int, Summary>): ReviewSummaries {
        check(expected)
        topicSummaries = topicSummaries.summarize(entries)
        val topicNodes = topicSummaries.tree.nodes
        val files = HashMap<String, Summary>()
        val parts = HashMap<String, Summary>()
        for (node in topicNodes) {
            val text = topicSummaries.summary(node.id) ?: continue
            when (node.kind) {
                SummaryKind.FILE -> files[node.path] = text
                SummaryKind.CHUNK -> parts[node.key] = text
                else -> {}
            }
        }
        val cachedKeys = topicNodes.filter { it.id in topicSummaries.cachedIds }.mapTo(hashSetOf()) { it.key }
        val cachedFiles = topicNodes.filter { it.kind == SummaryKind.FILE && it.id in topicSummaries.cachedIds }
            .mapTo(hashSetOf()) { it.path }
        summaries = shareInto(summaries, cached = { node ->
            if (node.kind == SummaryKind.FILE) node.path in cachedFiles else node.key in cachedKeys
        }) { node ->
            when (node.kind) {
                SummaryKind.FILE -> files[node.path]
                SummaryKind.CHUNK -> parts[node.key]
                else -> null
            }
        }
        persist()
        return topicSummaries
    }

    /** Saves what a targeted topic fill or resummarization has written so far; ignored once the topics or the snapshot changed. */
    fun replaceTopicSummaries(updated: ReviewSummaries, ids: Set<Int>): ReviewSummaries {
        if (!refreshing) targetedEntries(topicSummaries, updated, ids).forEach { summarizeTopics(revision, it) }
        return topicSummaries
    }

    private companion object {
        const val MAX_NOTES = 200
        const val MAX_SOURCES = 30
    }
}

/**
 * Adds the [shared] texts to the missing nodes of [target]. A node with children is added only when all of them are
 * summarized or added too, so the bottom-up rule holds; batches go children first.
 */
internal fun shareInto(target: ReviewSummaries, cached: (SummaryNode) -> Boolean = { false },
                       shared: (SummaryNode) -> Summary?): ReviewSummaries {
    val tree = target.tree
    val added = LinkedHashMap<Int, Summary>()
    // Pre-order: walking backwards visits children before their parents.
    for (node in tree.nodes.asReversed()) {
        if (target.text(node.id) != null) continue
        val text = shared(node) ?: continue
        if (node.children.all { target.text(it) != null || it in added }) added[node.id] = text
    }
    var result = target
    added.entries.chunked(ReviewSummaries.MAX_PER_CALL).forEach { batch ->
        val entries = batch.associate { it.key to it.value }
        result = result.share(entries, entries.keys.filterTo(hashSetOf()) { cached(tree[it]) })
    }
    return result
}
