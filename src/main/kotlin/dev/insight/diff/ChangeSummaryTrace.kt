package dev.insight.diff

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import dev.insight.decisions.DecisionAnswer
import dev.insight.decisions.DecisionQuestion
import dev.insight.decisions.DecisionRequest
import dev.insight.decisions.DecisionResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** A word of a summary: its position among the summary's words and its character range. */
data class SummaryWord(val index: Int, val start: Int, val end: Int, val text: String) {
    /** Filler words carry no meaning to search for, so they are neither traced nor asked about. */
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

/** A summary below the traced one that the pointed words were condensed from, with the Decisions API's probability. */
data class SummaryMatch(val id: Int, val identity: String, val probability: Double, val levelsBelow: Int)

/** The scored words and narrowed query, ready to highlight without searching any other summaries. */
data class SummaryContext(
    val identity: String,
    val summary: String,
    val word: SummaryWord,
    val context: List<SummaryWord>,
    val query: String,
)

/**
 * What one word of a summary refers to. [context] are the word's own context within its summary (the word included), not its
 * siblings; [query] is the summary narrowed to them; [matches] are the summaries below, most probable first.
 */
data class SummaryTrace(
    val identity: String,
    val summary: String,
    val word: SummaryWord,
    val context: List<SummaryWord>,
    val query: String,
    val matches: List<SummaryMatch>,
    val candidates: Int,
) {
    val related: List<SummaryMatch> get() = matches.filter { it.probability >= MATCH_THRESHOLD }
    val best: SummaryMatch? get() = related.firstOrNull()

    companion object {
        /** Starting points, not validated values: tune them on real reviews. */
        const val CONTEXT_THRESHOLD = 0.5
        const val MATCH_THRESHOLD = 0.5
    }
}

/**
 * Traces a word of a summary to the summaries it came from, with typed judgments and no generative model.
 *
 * 1. Every other meaningful word of the summary gets a Noul: is it part of the pointed word's narrow context? In
 *    "deleted views: canvas, hierarchy, summaries", the context of "hierarchy" is "deleted views", not its siblings.
 * 2. The summary narrowed to those words ("deleted views: hierarchy") is the query.
 * 3. Only on an explicit search, every summary up to [levels] levels below gets a Noul: does it describe what the query
 *    names? Deeper levels are not searched; trace a match to go further.
 *
 * Questions over the same state go in one request; requests of up to [maxQuestions] questions run in parallel.
 * Judgments are cached by their inputs, so tracing a word again, or after unrelated summaries change, is free.
 */
class SummaryTracer(
    private val evaluate: suspend (DecisionRequest) -> DecisionResponse,
    private val levels: Int = LEVELS,
    private val maxWords: Int = MAX_WORDS,
    private val maxQuestions: Int = MAX_QUESTIONS,
) {
    init { require(levels >= 1); require(maxWords >= 1); require(maxQuestions >= 1) }

    private val contexts = Lru<List<Any>, Set<Int>>(256)
    private val probabilities = Lru<List<String>, Double>(2048)

    /** Scores only this summary's words; candidate enumeration and ranking are deferred to [search]. */
    suspend fun score(summaries: ChangeSummaries, id: Int, wordIndex: Int): SummaryContext {
        val node = summaries.tree[id]
        val summary = requireNotNull(summaries.text(id)) { "Node $id has no summary" }
        val words = SummaryWord.split(summary)
        val word = words[wordIndex]
        val context = context(summary, words, word)
        val query = query(summary, context)
        return SummaryContext(node.identity, summary, word, context, query)
    }

    /** Uses the already scored context after the reader clicks a highlighted word. */
    suspend fun search(summaries: ChangeSummaries, id: Int, context: SummaryContext): SummaryTrace {
        val node = summaries.tree[id]
        require(node.identity == context.identity && summaries.text(id) == context.summary) { "The scored summary has changed" }
        val candidates = candidates(summaries, id)
        val ranked = rank(context.query, node, context.summary, candidates.map { (candidate, _) -> candidate to summaries.text(candidate.id).orEmpty() })
        val matches = candidates.zip(ranked) { (candidate, depth), probability -> SummaryMatch(candidate.id, candidate.identity, probability, depth) }
            .sortedWith(compareByDescending<SummaryMatch> { it.probability }.thenByDescending { it.levelsBelow }.thenBy { it.id })
        return SummaryTrace(node.identity, context.summary, context.word, context.context, context.query, matches, candidates.size)
    }

    /** The pointed word and the words of the summary that pin down what it is about, in reading order. */
    internal suspend fun context(summary: String, words: List<SummaryWord>, word: SummaryWord): List<SummaryWord> {
        // The nearest words are the likeliest context; a very long summary is cut around the pointed word.
        val others = words.filter { it.index != word.index && it.meaningful }
            .sortedBy { kotlin.math.abs(it.index - word.index) }.take(maxWords)
        if (others.size <= 1) return (others + word).sortedBy { it.index }
        val key = listOf(summary, word.index)
        val chosen = contexts[key] ?: run {
            val state = JsonObject().apply {
                addProperty("summary", summary)
                addProperty("numbered_summary", numbered(summary, words))
                add("pointed_word", JsonObject().apply { addProperty("number", word.index + 1); addProperty("word", word.text) })
            }
            val nouls = ask(state, others) { candidate -> contextQuestion(candidate) }
            others.filterIndexed { index, _ -> nouls[index] >= SummaryTrace.CONTEXT_THRESHOLD }.mapTo(hashSetOf()) { it.index }
                .also { contexts[key] = it }
        }
        return words.filter { it.index == word.index || it.index in chosen }
    }

    /**
     * Probability that each candidate describes what [query] names. The traced summary is context: it was condensed from
     * the candidates, so it tells which reading of the query is meant.
     */
    internal suspend fun rank(query: String, node: SummaryNode, summary: String, candidates: List<Pair<SummaryNode, String>>): List<Double> {
        if (candidates.isEmpty()) return emptyList()
        val keys = candidates.map { (candidate, text) -> listOf(query, summary, candidate.location, text) }
        val missing = candidates.indices.filter { probabilities[keys[it]] == null }
        if (missing.isNotEmpty()) {
            val state = JsonObject().apply {
                addProperty("query", query)
                add("pointed_summary", JsonObject().apply { addProperty("location", node.location); addProperty("summary", summary) })
            }
            val nouls = ask(state, missing.map { candidates[it] }) { (candidate, text) -> matchQuestion(candidate, text) }
            missing.forEachIndexed { index, candidate -> probabilities[keys[candidate]] = nouls[index] }
        }
        return keys.map { probabilities[it] ?: 0.0 }
    }

    /** One Noul per item; items are split into requests of at most [maxQuestions] that run in parallel. */
    private suspend fun <T> ask(state: JsonElement, items: List<T>, question: (T) -> DecisionQuestion.Noul): List<Double> = coroutineScope {
        items.chunked(maxQuestions).map { chunk ->
            async {
                val questions = chunk.withIndex().associate { (index, item) -> "q$index" to question(item) }
                val response = evaluate(DecisionRequest(state, questions))
                // The client already checks that every question has an answer of the requested type.
                chunk.indices.map { (response.answers.getValue("q$it") as DecisionAnswer.Noul).noul }
            }
        }.awaitAll().flatten()
    }

    private fun contextQuestion(candidate: SummaryWord) = DecisionQuestion.Noul(
        JsonObject().apply {
            add("candidate_word", JsonObject().apply { addProperty("number", candidate.index + 1); addProperty("word", candidate.text) })
            addProperty("question", "A reader points at `pointed_word` in `numbered_summary` and wants only the words that pin down what " +
                "that word is about. Is `candidate_word` part of that narrow context: the action, subject, object, owner or qualifier " +
                "of the phrase the pointed word belongs to, or another part of the same name?")
        },
        mapOf(
            true to JsonPrimitive("The candidate says what the pointed word is, what is done to or with it, or where it belongs, or it is " +
                "part of the same multi-word name. For \"hierarchy\" in \"deleted views: canvas, hierarchy, summaries\", " +
                "\"deleted\" and \"views\" are context."),
            false to JsonPrimitive("The candidate is a sibling listed next to the pointed word (\"canvas\" or \"summaries\" in that " +
                "example), belongs to another clause or sentence about something else, or adds no meaning of its own."),
        ),
    )

    private fun matchQuestion(candidate: SummaryNode, text: String) = DecisionQuestion.Noul(
        JsonObject().apply {
            add("candidate", JsonObject().apply {
                addProperty("kind", candidate.kind.id)
                addProperty("location", candidate.location)
                addProperty("summary", text)
            })
            addProperty("question", "`pointed_summary` was condensed from the summaries of its parts, one of which is `candidate`. " +
                "Does `candidate` describe the specific thing that `query` names, so `query` was condensed from it?")
        },
        mapOf(
            true to JsonPrimitive("The candidate covers what the query names: the same change to the same item, or a part of it, " +
                "including when its location or file name identifies the item."),
            false to JsonPrimitive("The candidate is about another item or another change, even on a related topic or in the same " +
                "folder, or it mentions the item only in passing."),
        ),
    )

    /** Summaries up to [levels] levels below [id]. A parent with a single child repeats its summary, so that child adds no level. */
    internal fun candidates(summaries: ChangeSummaries, id: Int): List<Pair<SummaryNode, Int>> {
        val tree = summaries.tree
        val found = mutableListOf<Pair<SummaryNode, Int>>()
        fun visit(parent: SummaryNode, depth: Int) {
            for (child in parent.children.map(tree::get)) {
                val text = summaries.text(child.id)
                val repeats = parent.children.size == 1 && text == summaries.text(parent.id)
                val level = if (repeats) depth else depth + 1
                if (!repeats && text != null) found += child to level
                if (level < levels) visit(child, level)
            }
        }
        visit(tree[id], 0)
        return found
    }

    companion object {
        /** The pointed node's children and grandchildren; the very bottom of a large tree is not searched. */
        const val LEVELS = 2
        const val MAX_WORDS = 96
        const val MAX_QUESTIONS = 32

        /** `deleted[1] views[2]: canvas[3]` — question IDs are not sent to the model, so the words carry their numbers. */
        internal fun numbered(summary: String, words: List<SummaryWord>): String = buildString {
            var at = 0
            for (word in words) {
                append(summary, at, word.end).append('[').append(word.index + 1).append(']')
                at = word.end
            }
            append(summary, at, summary.length)
        }

        /**
         * The summary narrowed to [context]: adjacent words keep the text between them, and a skipped run is replaced by the
         * separator that followed the last kept word, so "deleted views: canvas, hierarchy" narrows to "deleted views: hierarchy".
         */
        internal fun query(summary: String, context: List<SummaryWord>): String = buildString {
            context.forEachIndexed { position, word ->
                if (position > 0) {
                    val previous = context[position - 1]
                    val between = summary.substring(previous.end, word.start)
                    append(if (word.index == previous.index + 1) between else separatorAfter(summary, previous))
                }
                append(word.text)
            }
        }.trim()

        private fun separatorAfter(summary: String, word: SummaryWord): String {
            val next = SummaryWord.split(summary.substring(word.end)).firstOrNull()
            val separator = if (next == null) summary.substring(word.end) else summary.substring(word.end, word.end + next.start)
            return separator.ifBlank { " " }.let { if (it.last().isWhitespace()) it else "$it " }
        }
    }
}

private class Lru<K, V>(private val capacity: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > capacity
    }
    operator fun get(key: K): V? = synchronized(map) { map[key] }
    operator fun set(key: K, value: V) { synchronized(map) { map[key] = value } }
}
