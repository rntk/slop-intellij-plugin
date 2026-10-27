package dev.insight.review

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * A summary as consecutive segments whose texts join into the prose a reader sees. Each segment cites what it was written
 * from, so every part of a summary links to the summaries below it, and a file's summary to its lines, without any matching
 * afterwards. A segment without sources only connects the others.
 */
data class Summary(val segments: List<SummarySegment>) {
    val text: String = segments.joinToString("") { it.text }

    /** The character range of each segment in [text], in order. */
    val ranges: List<IntRange> by lazy {
        var start = 0
        segments.map { segment -> (start until start + segment.text.length).also { start += segment.text.length } }
    }

    /** The segment holding the character at [offset], or the one ending there. */
    fun segmentAt(offset: Int): Int? = ranges.indexOfFirst { offset in it }.takeIf { it >= 0 }
        ?: ranges.indexOfLast { it.last + 1 == offset }.takeIf { it >= 0 }

    /** The segments a parent can cite: those with sources, or every one when nothing in the summary cites anything. */
    val claims: List<Int> get() = segments.indices.filter { segments[it].sources.isNotEmpty() }
        .ifEmpty { segments.indices.filter { segments[it].text.any(Char::isLetterOrDigit) } }

    /** The same prose written by a parent whose children have identical text and claim positions. */
    fun relay(childCount: Int = 1): Summary {
        val claims = claims.toSet()
        return Summary(segments.mapIndexed { index, segment ->
            SummarySegment(segment.text, if (index in claims) (0 until childCount).map { SummarySource.Claim(it, index) } else emptyList())
        })
    }

    /** The same prose with every source dropped, for text placed where its sources do not exist. */
    fun unlinked(): Summary = Summary(segments.map { SummarySegment(it.text) })

    /** Compact JSON for the review cache: `[["text",["2.1","12-18"]],…]` with 0-based claims and 1-based rows. */
    fun encode(): String = JsonArray().apply {
        segments.forEach { segment ->
            add(JsonArray().apply {
                add(segment.text)
                add(JsonArray().apply { segment.sources.forEach { add(it.encode()) } })
            })
        }
    }.toString()

    companion object {
        /** Text with no sources, as one segment. */
        fun plain(text: String): Summary = Summary(listOf(SummarySegment(text)))

        /** Reads [encode]'s form; null for anything else, such as text saved before summaries had segments. */
        fun decode(value: String): Summary? = runCatching {
            val segments = JsonParser.parseString(value).asJsonArray.map { element ->
                val pair = element.asJsonArray
                SummarySegment(pair[0].asString, pair[1].asJsonArray.map { SummarySource.decode(it.asString) ?: error("bad source") })
            }
            Summary(segments).takeIf { it.text.isNotBlank() }
        }.getOrNull()

        /**
         * Joins model segments into readable prose: empty segments are dropped, a missing space after a word or punctuation
         * is added, a sentence period before a comma or semicolon of the next segment is dropped, and the whole is trimmed.
         * Each fix edits only the text, so every segment keeps its sources. Null when nothing is left.
         */
        fun of(segments: List<SummarySegment>): Summary? {
            val kept = mutableListOf<SummarySegment>()
            for (segment in segments) {
                if (segment.text.isEmpty()) continue
                val previous = kept.lastOrNull()?.text
                if (previous != null && previous.endsWith('.') && segment.text.first() in ",;" && !ABBREVIATION.containsMatchIn(previous))
                    kept[kept.lastIndex] = kept.last().copy(text = previous.dropLast(1))
                val last = kept.lastOrNull()?.text?.lastOrNull()
                val next = segment.text.first()
                val space = last != null && when (last) {
                    in ".!?" -> next.isLetter()
                    in ",;:)" -> next.isLetterOrDigit()
                    else -> last.isLetterOrDigit() && next.isLetterOrDigit()
                }
                kept += segment.copy(text = if (space) " " + segment.text else segment.text)
            }
            while (kept.isNotEmpty() && kept.first().text.isBlank()) kept.removeAt(0)
            while (kept.isNotEmpty() && kept.last().text.isBlank()) kept.removeAt(kept.lastIndex)
            if (kept.isEmpty()) return null
            kept[0] = kept[0].copy(text = kept[0].text.trimStart())
            kept[kept.lastIndex] = kept.last().copy(text = kept.last().text.trimEnd())
            return Summary(kept)
        }

        /** A period that belongs to the word before it, so a following comma keeps it: "e.g.,". */
        private val ABBREVIATION = Regex("""\b(?:etc|e\.g|i\.e|vs)\.$""", RegexOption.IGNORE_CASE)
    }
}

data class SummarySegment(val text: String, val sources: List<SummarySource> = emptyList())

sealed interface SummarySource {
    fun encode(): String

    /** Segment [segment] of the summary of the node's child at position [child]; both 0-based. */
    data class Claim(val child: Int, val segment: Int) : SummarySource {
        override fun encode() = "$child.$segment"
    }

    /**
     * Rows [first] to [last] (1-based, inclusive) of a leaf's evidence: the file's lines for source code, the patch's own
     * lines for a diff.
     */
    data class Rows(val first: Int, val last: Int) : SummarySource {
        override fun encode() = "$first-$last"
    }

    companion object {
        private val CLAIM = Regex("""(\d+)\.(\d+)""")
        private val ROWS = Regex("""(\d+)-(\d+)""")

        fun decode(value: String): SummarySource? {
            CLAIM.matchEntire(value)?.let { return Claim(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            ROWS.matchEntire(value)?.let { return Rows(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            return null
        }
    }
}

/**
 * [summary] of [from] in [fromTree], rewritten for [to] in [toTree], which covers the same files. Claims follow the child
 * with the same files (a part: the same key); a claim of a child [to] partitions differently is expanded through
 * [fromSummaries] to the files beneath it and mapped to the children of [to] covering those files. Rows need no change.
 */
internal fun retarget(
    summary: Summary,
    fromTree: ReviewSummaryTree,
    from: SummaryNode,
    toTree: ReviewSummaryTree,
    to: SummaryNode,
    fromSummaries: ReviewSummaries? = null,
    toSummaries: ReviewSummaries? = null,
): Summary {
    if (fromTree === toTree && from.id == to.id) return summary
    fun match(child: SummaryNode) = if (child.kind == SummaryKind.CHUNK) child.key else child.files.toSet()
    val positions = to.children.withIndex().associate { (index, id) -> match(toTree[id]) to index }
    val childrenByFile = HashMap<String, MutableList<Int>>()
    to.children.forEachIndexed { index, id ->
        toTree[id].files.forEach { file -> childrenByFile.getOrPut(file) { mutableListOf() } += index }
    }
    fun expandFrom(node: SummaryNode, source: SummarySource, depth: Int): List<Pair<SummaryNode, Int>> = when (source) {
        is SummarySource.Rows -> emptyList()
        is SummarySource.Claim -> {
            val child = node.children.getOrNull(source.child)?.let(fromTree::get)
            when {
                child == null -> emptyList()
                match(child) in positions || child.kind == SummaryKind.FILE -> listOf(child to source.segment)
                fromSummaries == null || depth > fromTree.nodes.size -> emptyList()
                else -> {
                    val sources = fromSummaries.summary(child.id)?.segments?.getOrNull(source.segment)?.sources.orEmpty()
                    if (sources.isEmpty()) child.files.mapNotNull { path ->
                        fromTree.nodes.firstOrNull { it.kind == SummaryKind.FILE && it.path == path }
                    }.flatMap { file ->
                        fromSummaries.summary(file.id)?.claims.orEmpty().map { file to it }
                    } else sources.flatMap { expandFrom(child, it, depth + 1) }
                }
            }
        }
    }
    fun citesInTo(node: SummaryNode, segment: Int, targetFile: String, targetSegment: Int, depth: Int): Boolean {
        if (node.kind == SummaryKind.FILE) return node.path == targetFile && segment == targetSegment
        if (toSummaries == null || depth > toTree.nodes.size) return false
        val sources = toSummaries.summary(node.id)?.segments?.getOrNull(segment)?.sources.orEmpty()
        return sources.filterIsInstance<SummarySource.Claim>().any { claim ->
            val child = node.children.getOrNull(claim.child)?.let(toTree::get) ?: return@any false
            targetFile in child.files && citesInTo(child, claim.segment, targetFile, targetSegment, depth + 1)
        }
    }
    fun mapSource(source: SummarySource): List<SummarySource> = when (source) {
        is SummarySource.Rows -> listOf(source)
        is SummarySource.Claim -> expandFrom(from, source, 0).flatMap { (child, segment) ->
            val exact = positions[match(child)]
            if (exact != null) listOf(SummarySource.Claim(exact, segment))
            else child.files.flatMap { path -> childrenByFile[path].orEmpty() }.distinct().flatMap { childPos ->
                val toChild = toTree[to.children[childPos]]
                if (child.kind == SummaryKind.FILE && toChild.kind == SummaryKind.FILE) listOf(SummarySource.Claim(childPos, segment))
                else if (toSummaries != null) {
                    val claims = toSummaries.summary(toChild.id)?.claims.orEmpty()
                    val citing = if (child.kind == SummaryKind.FILE) claims.filter { citesInTo(toChild, it, child.path, segment, 0) } else emptyList()
                    citing.ifEmpty { claims }.map { SummarySource.Claim(childPos, it) }
                } else emptyList()
            }
        }
    }
    return Summary(summary.segments.map { segment ->
        segment.copy(sources = segment.sources.flatMap(::mapSource).distinct())
    })
}

/**
 * The summary of [id] for a view that skips some of the nodes below it. [position] gives a kept child's position in the
 * view; a claim of a skipped child is replaced by that child segment's own sources, recursively, so it reaches the kept
 * nodes or the lines beneath the skipped ones.
 */
internal fun ReviewSummaries.flattened(id: Int, position: (SummaryNode) -> Int?): Summary? {
    val summary = summary(id) ?: return null
    fun expand(node: SummaryNode, source: SummarySource, depth: Int): List<SummarySource> = when (source) {
        is SummarySource.Rows -> listOf(source)
        is SummarySource.Claim -> {
            val child = node.children.getOrNull(source.child)?.let(tree::get)
            val kept = child?.let(position)
            when {
                child == null -> emptyList()
                kept != null -> listOf(SummarySource.Claim(kept, source.segment))
                depth > tree.nodes.size -> emptyList()
                else -> summary(child.id)?.segments?.getOrNull(source.segment)?.sources.orEmpty()
                    .flatMap { expand(child, it, depth + 1) }
            }
        }
    }
    val node = tree[id]
    return Summary(summary.segments.map { segment -> segment.copy(sources = segment.sources.flatMap { expand(node, it, 0) }.distinct()) })
}

/**
 * The request form of a summary as a child's claims, `[{"cite":"2.1","text":"Adds retries"}]`: each claim with the
 * reference a parent cites it by, numbered from 1 like the children. Connecting segments are left out.
 */
internal fun Summary.claimsJson(child: Int, labeled: Boolean = false): JsonArray = JsonArray().apply {
    claims.forEach { index ->
        add(JsonObject().apply {
            addProperty("cite", if (labeled) "C${child + 1}.S${index + 1}" else "${child + 1}.${index + 1}")
            addProperty("text", segments[index].text.trim())
        })
    }
}

/** [text]'s lines, each led by its number and "|", counting from [first]: the rows a leaf summary cites. */
internal fun numberedRows(text: String, first: Int): String =
    text.removeSuffix("\n").split('\n').withIndex().joinToString("\n") { (index, line) -> "${first + index}|$line" }
