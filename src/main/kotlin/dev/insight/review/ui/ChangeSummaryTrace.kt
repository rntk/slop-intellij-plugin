package dev.insight.review.ui

import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewedFiles
import dev.insight.review.SummaryKind
import dev.insight.review.SummaryNode
import dev.insight.review.SummarySource

/** A word of a summary: its position among the summary's words and its character range. */
data class SummaryWord(val index: Int, val start: Int, val end: Int, val text: String) {
    /** Filler words carry no meaning to search for, so they are neither traced nor highlighted on their own. */
    val meaningful: Boolean get() = text.lowercase() !in STOP_WORDS

    companion object {
        private val WORD = Regex("""[\p{L}\p{N}_]+(?:['’.\-/][\p{L}\p{N}_]+)*""")
        private val STOP_WORDS = setOf(
            "a", "an", "the", "and", "or", "but", "nor", "of", "to", "in", "on", "at", "by", "for", "with", "from", "into", "onto",
            "as", "is", "are", "was", "were", "be", "been", "it", "its", "this", "that", "these", "those", "so", "than", "then",
            "via", "per", "also", "both", "each", "all", "any", "which", "who", "what", "when", "where", "while",
        )

        /** Identifiers such as `ChangeSummaryModel.kt` or `read_diff_files` stay one word; punctuation is not a word. */
        fun split(text: String): List<SummaryWord> =
            WORD.findAll(text).mapIndexed { index, match -> SummaryWord(index, match.range.first, match.range.last + 1, match.value) }.toList()
    }
}

/**
 * A summary below the traced one, within [SummaryLinks.LEVELS] levels. [related] when the traced segments cite it, directly
 * or through the levels between. [next] is the search that follows the same citations further down from it: the segments of
 * its summary that the traced ones cite.
 */
data class SummaryMatch(val id: Int, val identity: String, val related: Boolean, val levelsBelow: Int, val next: SummaryContext? = null)

/**
 * The words a trace starts from: the pointed [word], and as [context] the words of the segments it belongs to. [query] is
 * those segments' text, which names the trace.
 */
data class SummaryContext(
    val identity: String,
    val summary: String,
    val word: SummaryWord,
    val context: List<SummaryWord>,
    val query: String,
)

/**
 * The words the reader selected in [summary] (characters [start] until [end]) as a context: the selection widened to whole
 * words is the query. Its trace follows every segment the selection touches. Null when it holds no meaningful word.
 */
internal fun selectionContext(identity: String, summary: String, start: Int, end: Int): SummaryContext? {
    val words = SummaryWord.split(summary).filter { it.end > start && it.start < end }
    val first = words.firstOrNull { it.meaningful } ?: return null
    val from = words.first().start
    val to = words.last().end
    val query = summary.substring(from, to).replace(Regex("""\s+"""), " ").trim()
    return SummaryContext(identity, summary, SummaryWord(first.index, from, to, query), words, query)
}

/**
 * Where one context of a summary leads. [matches] are every summary up to [SummaryLinks.LEVELS] levels below, related ones
 * first, nearest first.
 */
data class SummaryTrace(
    val identity: String,
    val summary: String,
    val word: SummaryWord,
    val context: List<SummaryWord>,
    val query: String,
    val matches: List<SummaryMatch>,
) {
    val related: List<SummaryMatch> get() = matches.filter { it.related }
    val best: SummaryMatch? get() = related.firstOrNull()
    val candidates: Int get() = matches.size
}

/** A segment of the summary of node [id]. */
data class SegmentRef(val id: Int, val segment: Int)

/**
 * The citations of one set of summaries, both ways: what each segment condenses from the summaries of its node's children,
 * and which segments of the parent condense it. Everything here is read from the summaries as written, with no requests.
 * Citations of a missing child summary, or past its segments, are ignored.
 */
class SummaryLinks(val summaries: ReviewSummaries) {
    private val tree = summaries.tree
    private val down = HashMap<SegmentRef, List<SegmentRef>>()
    private val up = HashMap<SegmentRef, MutableList<SegmentRef>>()

    init {
        for (node in tree.nodes) {
            val summary = summaries.summary(node.id) ?: continue
            summary.segments.forEachIndexed { index, segment ->
                val from = SegmentRef(node.id, index)
                val cited = segment.sources.filterIsInstance<SummarySource.Claim>().mapNotNull { claim ->
                    val child = node.children.getOrNull(claim.child) ?: return@mapNotNull null
                    SegmentRef(child, claim.segment).takeIf { claim.segment in summaries.summary(child)?.segments.orEmpty().indices }
                }.distinct()
                if (cited.isNotEmpty()) down[from] = cited
                cited.forEach { up.getOrPut(it) { mutableListOf() } += from }
            }
        }
    }

    private val byIdentity by lazy { tree.nodes.associateBy { it.identity } }

    fun node(identity: String): SummaryNode? = byIdentity[identity]

    fun below(ref: SegmentRef): List<SegmentRef> = down[ref].orEmpty()
    fun above(ref: SegmentRef): List<SegmentRef> = up[ref].orEmpty()

    /** Whether the segment cites anything: child claims, or for a leaf, its lines. */
    fun linked(ref: SegmentRef): Boolean = ref in down || rows(ref).isNotEmpty()

    /** The evidence rows a leaf's segment cites. */
    fun rows(ref: SegmentRef): List<SummarySource.Rows> =
        summaries.summary(ref.id)?.segments?.getOrNull(ref.segment)?.sources?.filterIsInstance<SummarySource.Rows>().orEmpty()

    /** Every segment the [refs] cite, at any depth, without the [refs] themselves. */
    fun descendants(refs: Collection<SegmentRef>): Set<SegmentRef> = closure(refs, ::below)

    /** Every segment that cites the [refs], at any height, without the [refs] themselves. */
    fun ancestors(refs: Collection<SegmentRef>): Set<SegmentRef> = closure(refs, ::above)

    private fun closure(refs: Collection<SegmentRef>, next: (SegmentRef) -> List<SegmentRef>): Set<SegmentRef> {
        val seen = LinkedHashSet<SegmentRef>()
        val queue = ArrayDeque(refs.flatMap(next))
        while (queue.isNotEmpty()) {
            val ref = queue.removeFirst()
            if (ref !in refs && seen.add(ref)) queue.addAll(next(ref))
        }
        return seen
    }

    /** The segments of [id]'s summary that [word] lies in; empty when none of them cites anything. */
    fun segmentsOf(id: Int, words: Collection<SummaryWord>): List<Int> {
        val summary = summaries.summary(id) ?: return emptyList()
        return summary.ranges.indices.filter { index ->
            val range = summary.ranges[index]
            words.any { it.start < range.last + 1 && it.end > range.first } && linked(SegmentRef(id, index))
        }
    }

    /** The words of [refs] in [id]'s summary. */
    fun words(id: Int, segments: Collection<Int>): List<SummaryWord> {
        val summary = summaries.summary(id) ?: return emptyList()
        val ranges = segments.mapNotNull { summary.ranges.getOrNull(it) }
        return SummaryWord.split(summary.text).filter { word -> ranges.any { word.start < it.last + 1 && word.end > it.first } }
    }

    /** The context of [word] in [node]'s summary: the words of its cited segment, or null when it is in no such segment. */
    fun context(node: SummaryNode, word: Int): SummaryContext? {
        val summary = summaries.summary(node.id) ?: return null
        val pointed = SummaryWord.split(summary.text).getOrNull(word)?.takeIf { it.meaningful } ?: return null
        val segments = segmentsOf(node.id, listOf(pointed)).ifEmpty { return null }
        return context(node, pointed, segments)
    }

    private fun context(node: SummaryNode, word: SummaryWord, segments: List<Int>): SummaryContext {
        val summary = summaries.summary(node.id)!!
        val query = segments.joinToString(" … ") { summary.segments[it].text.trim() }
        return SummaryContext(node.identity, summary.text, word, words(node.id, segments), query)
    }

    /**
     * The summaries up to [levels] below [id] that [context]'s segments cite, directly or through the levels between, and
     * the rest of that window as unrelated. A related match whose cited segments cite further down carries them on as its
     * [SummaryMatch.next] context.
     */
    fun search(id: Int, context: SummaryContext, levels: Int = LEVELS): SummaryTrace {
        val node = tree[id]
        val start = segmentsOf(id, context.context.ifEmpty { listOf(context.word) })
        val reached = LinkedHashMap<Int, MutableSet<Int>>()
        var frontier = start.map { SegmentRef(id, it) }
        for (level in 1..levels) {
            frontier = frontier.flatMap(::cited).distinct()
            frontier.forEach { ref -> reached.getOrPut(ref.id) { linkedSetOf() } += ref.segment }
            if (frontier.isEmpty()) break
        }
        val window = generateSequence(shownChildren(id)) { ids -> ids.flatMap(::shownChildren).takeIf { it.isNotEmpty() } }
            .take(levels).withIndex().flatMap { (level, ids) -> ids.map { it to level + 1 } }
            .filter { (child, _) -> summaries.summary(child) != null }.toList()
        val matches = window.map { (child, level) ->
            val segments = reached[child]
            val target = tree[child]
            val next = segments?.toList()?.filter { linked(SegmentRef(child, it)) }
                ?.takeIf { level == levels && it.isNotEmpty() && target.children.isNotEmpty() }
                ?.let { context(target, words(child, it).firstOrNull { word -> word.meaningful } ?: return@let null, it) }
            SummaryMatch(child, target.identity, segments != null, level, next)
        }.sortedWith(compareBy<SummaryMatch> { !it.related }.thenBy { it.levelsBelow })
        return SummaryTrace(node.identity, context.summary, context.word, context.context, context.query, matches)
    }

    /** A folder whose only entry is shown in its place: it repeats that entry's summary, so a search passes through it. */
    private fun repeats(id: Int): Boolean =
        tree[id].children.size == 1 && summaries.text(id) == summaries.text(tree[id].children.single())

    private fun shownChildren(id: Int): List<Int> = tree[id].children.flatMap { if (repeats(it)) shownChildren(it) else listOf(it) }

    /** The segments [ref] cites, passing through summaries that only repeat their single child's. */
    private fun cited(ref: SegmentRef): List<SegmentRef> = below(ref).flatMap { if (repeats(it.id)) cited(it) else listOf(it) }

    /**
     * The files ([SummaryKind.FILE]) or folders ([SummaryKind.DIRECTORY]) whose summaries [context]'s segments in [id]'s
     * summary cite, at any depth, in tree order. A folder merged into the one it holds stands for that folder.
     */
    fun targets(id: Int, context: SummaryContext, kind: SummaryKind): List<SummaryNode> {
        val refs = segmentsOf(id, context.context.ifEmpty { listOf(context.word) }).map { SegmentRef(id, it) }
        return descendants(refs).asSequence().map { tree.shown(it.id) }
            .filter { it.id != id && it.kind == kind && !it.isTopicBranch }
            .distinctBy { it.id }.sortedBy { it.id }.toList()
    }

    /**
     * A trace from [context] in [id]'s summary to [targets], all related, with [best] first: what To file and To directory
     * open, select and scroll to.
     */
    fun reach(id: Int, context: SummaryContext, targets: List<SummaryNode>, best: SummaryNode): SummaryTrace {
        val depth = tree.depth(id)
        val matches = (listOf(best) + targets.filter { it.id != best.id })
            .map { SummaryMatch(it.id, it.identity, true, tree.depth(it.id) - depth) }
        return SummaryTrace(tree[id].identity, context.summary, context.word, context.context, context.query, matches)
    }

    /** The lines of a file or part that [context]'s segments cite, one block per citation. */
    fun searchLines(id: Int, context: SummaryContext): LineTrace {
        val node = tree[id]
        val rows = segmentsOf(id, context.context.ifEmpty { listOf(context.word) }).flatMap { rows(SegmentRef(id, it)) }.distinct()
        val blocks = rows.map { LineMatch(citedLines(node, listOf(it))) }.filter { it.lines.isNotEmpty() }
        return LineTrace(node.identity, node.path, context.query, blocks, blocks.flatMap { it.lines }.distinct())
    }

    /**
     * The words of each summary that dim for the [reviewed] files: all of a summary whose files are all reviewed, and else
     * each segment whose citations reach only reviewed files. Connecting text dims with the segments around it.
     */
    fun dimming(reviewed: Set<String>): ReviewDimming {
        val sources = tree.nodes.mapNotNull { it.file }.associateBy { it.path }
        val full = tree.nodes.associate { node -> node.id to (node.files.isNotEmpty() &&
            node.files.all { path -> sources[path]?.let { ReviewedFiles.key(it) in reviewed } == true }) }
        val result = HashMap<Int, Set<Int>>()
        for (node in tree.nodes) {
            val summary = summaries.summary(node.id) ?: continue
            val words = SummaryWord.split(summary.text)
            if (full.getValue(node.id)) { result[node.id] = words.mapTo(hashSetOf()) { it.index }; continue }
            if (node.children.isEmpty()) continue
            val dimmed = summary.segments.indices.map { index ->
                val ref = SegmentRef(node.id, index)
                // Where the citations end: a leaf's lines, or a summary citing nothing below, such as one restored without
                // citations. Its node stands for all its files then, so it dims only once they are all reviewed.
                if (!linked(ref)) null
                else (descendants(listOf(ref)) + ref).filter { below(it).isEmpty() }
                    .let { ends -> ends.isNotEmpty() && ends.all { full.getValue(it.id) } }
            }
            val segments = dimmed.indices.filter { index ->
                dimmed[index] ?: run {
                    val before = (index - 1 downTo 0).firstNotNullOfOrNull { dimmed[it] }
                    val after = (index + 1 until dimmed.size).firstNotNullOfOrNull { dimmed[it] }
                    (before ?: after) == true && (after ?: before) == true
                }
            }
            if (segments.isNotEmpty()) result[node.id] = words(node.id, segments).mapTo(hashSetOf()) { it.index }
        }
        return result
    }

    companion object {
        /** Levels a search reaches below the traced summary; follow a match to go further. */
        const val LEVELS = 2
    }
}

/** Word indices refer to occurrences in the summary's prose. */
internal typealias ReviewDimming = Map<Int, Set<Int>>
