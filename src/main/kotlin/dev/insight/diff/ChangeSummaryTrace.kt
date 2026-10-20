package dev.insight.diff

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import dev.insight.decisions.DecisionAnswer
import dev.insight.decisions.DecisionJson
import dev.insight.decisions.DecisionQuestion
import dev.insight.decisions.DecisionRequest
import dev.insight.decisions.DecisionResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * 1. Every other meaningful word of the summary gets a Noul: is it part of what the summary says about the pointed
 *    thing? That covers the pointed word's own phrase and every other phrase about the same thing, but not its siblings.
 *    In "moves caching into the parser and client, adding retries to the client", the context of the first "client" is
 *    "moves caching" and "adding retries to the client", not "parser".
 * 2. The summary narrowed to those words ("moves caching client, adding retries to the client") is the query.
 * 3. Only on an explicit search, every summary up to [levels] levels below gets a Noul: does it describe what the query
 *    says about the pointed word? Deeper levels are not searched; trace a match to go further.
 *
 * Questions over the same state go in one request, with the rules they share in that state; requests of up to [maxQuestions]
 * questions and [maxRequestBytes] of JSON run in parallel.
 * Judgments are cached by their inputs, so tracing a word again, or after unrelated summaries change, is free.
 */
class SummaryTracer(
    private val evaluate: suspend (DecisionRequest) -> DecisionResponse,
    private val levels: Int = LEVELS,
    private val maxWords: Int = MAX_WORDS,
    private val maxQuestions: Int = MAX_QUESTIONS,
    private val maxRequestBytes: Int = MAX_REQUEST_BYTES,
    private val cache: ReviewDiskCache? = null,
) {
    init { require(levels >= 1); require(maxWords >= 1); require(maxQuestions >= 1); require(maxRequestBytes >= 1) }

    private val contexts = Lru<List<Any>, Set<Int>>(256)
    private val probabilities = Lru<List<String>, Double>(2048)
    private val scoreWrites = Mutex()

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
        val ranked = rank(context.query, context.word.text, node, context.summary, candidates.map { (candidate, _) -> candidate to summaries.text(candidate.id).orEmpty() })
        val matches = candidates.zip(ranked) { (candidate, depth), probability -> SummaryMatch(candidate.id, candidate.identity, probability, depth) }
            .sortedWith(compareByDescending<SummaryMatch> { it.probability }.thenByDescending { it.levelsBelow }.thenBy { it.id })
        return SummaryTrace(node.identity, context.summary, context.word, context.context, context.query, matches, candidates.size)
    }

    /** The pointed word and the words of the summary that say what it is and what is done to it, in reading order. */
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
                add("pointed_word", JsonObject().apply {
                    addProperty("number", word.index + 1)
                    addProperty("word", word.text)
                    // A repeated word is the cheapest sign of another phrase about the same thing; the model judges the rest.
                    add("other_mentions", JsonArray().apply {
                        words.filter { it.index != word.index && it.text.equals(word.text, ignoreCase = true) }.forEach { add(it.index + 1) }
                    })
                })
                // Shared by every question, so it is sent once per request rather than once per word.
                add("query_rules", queryRules())
            }
            val nouls = ask(state, others) { candidate -> contextQuestion(candidate) }
            others.filterIndexed { index, _ -> nouls[index] >= SummaryTrace.CONTEXT_THRESHOLD }.mapTo(hashSetOf()) { it.index }
                .also { contexts[key] = it }
        }
        return words.filter { it.index == word.index || it.index in chosen }
    }

    /**
     * Probability that each candidate describes what [query] says about [focus]. The query can name other items around the
     * focus, so the focus tells which of them is searched; the traced summary was condensed from the candidates, so it tells
     * which reading of the query is meant.
     */
    internal suspend fun rank(
        query: String,
        focus: String,
        node: SummaryNode,
        summary: String,
        candidates: List<Pair<SummaryNode, String>>,
    ): List<Double> {
        if (candidates.isEmpty()) return emptyList()
        val keys = candidates.map { (candidate, text) -> listOf(query, focus, node.location, summary, candidate.kind.id, candidate.location, text) }
        val missing = candidates.indices.filter { probabilities[keys[it]] == null }
        if (missing.isNotEmpty()) {
            val state = JsonObject().apply {
                addProperty("query", query)
                addProperty("query_focus", focus)
                add("pointed_summary", JsonObject().apply { addProperty("location", node.location); addProperty("summary", summary) })
                add("match_rules", matchRules())
            }
            val nouls = ask(state, missing.map { candidates[it] }) { (candidate, text) -> matchQuestion(candidate, text) }
            missing.forEachIndexed { index, candidate -> probabilities[keys[candidate]] = nouls[index] }
        }
        return keys.map { probabilities[it] ?: 0.0 }
    }

    /**
     * One Noul per item; items are packed into requests that run in parallel, see [batches]. Every judgment is saved under its
     * own question, so a changed candidate or a different packing asks again only for the questions that changed.
     */
    private suspend fun <T> ask(state: JsonElement, items: List<T>, question: (T) -> DecisionQuestion.Noul): List<Double> = coroutineScope {
        val questions = items.map(question)
        // A key covers the model, the state with its rules and evidence, and the question; the slot only groups a state's questions.
        val keys = questions.map { reviewFingerprint(DecisionJson.encode(DecisionRequest(state, mapOf("q0" to it)))) }
        val slot = state.toString()
        val scores = savedScores(slot)
        val missing = questions.indices.filter { scores[keys[it]] == null }
        val requests = batches(state, missing.map(questions::get))
        val offsets = requests.runningFold(0) { offset, request -> offset + request.questions.size }
        requests.mapIndexed { index, request ->
            async {
                val response = evaluate(request)
                // The client already checks that every question has an answer of the requested type.
                request.questions.keys.map { (response.answers.getValue(it) as DecisionAnswer.Noul).noul }.also { nouls ->
                    saveScores(slot, missing.subList(offsets[index], offsets[index + 1]).map(keys::get).zip(nouls))
                }
            }
        }.awaitAll().flatten().forEachIndexed { index, noul -> scores[keys[missing[index]]] = noul }
        keys.map(scores::getValue)
    }

    private fun savedScores(slot: String): MutableMap<String, Double> = runCatching {
        cache?.read("scores", slot, slot)?.asJsonObject?.entrySet()
            ?.mapNotNull { (key, value) -> value.asDouble.takeIf { it.isFinite() && it in 0.0..1.0 }?.let { key to it } }?.toMap(HashMap())
    }.getOrNull() ?: HashMap()

    /** Only successful, typed judgments are saved; batches of one state merge, so parallel requests keep each other's scores. */
    private suspend fun saveScores(slot: String, scores: List<Pair<String, Double>>) {
        val cache = cache ?: return
        scoreWrites.withLock {
            val saved = savedScores(slot).apply { putAll(scores) }
            cache.write("scores", slot, slot, JsonObject().apply { saved.forEach { (key, score) -> addProperty(key, score) } })
        }
    }

    /**
     * Consecutive questions packed into requests of at most [maxQuestions] questions and [maxRequestBytes] of encoded JSON,
     * since the server rejects an input larger than its batch. A question too large on its own still gets its own request.
     */
    internal fun batches(state: JsonElement, questions: List<DecisionQuestion.Noul>): List<DecisionRequest> {
        fun request(chunk: List<DecisionQuestion.Noul>) = DecisionRequest(state, chunk.withIndex().associate { (index, question) -> "q$index" to question })
        val requests = mutableListOf<DecisionRequest>()
        var chunk = listOf<DecisionQuestion.Noul>()
        for (question in questions) {
            val grown = chunk + question
            chunk = if (chunk.isEmpty() || grown.size <= maxQuestions && bytes(request(grown)) <= maxRequestBytes) grown
                else listOf(question).also { requests += request(chunk) }
        }
        if (chunk.isNotEmpty()) requests += request(chunk)
        return requests
    }

    private fun contextQuestion(candidate: SummaryWord) = DecisionQuestion.Noul(
        JsonObject().apply {
            add("candidate_word", JsonObject().apply { addProperty("number", candidate.index + 1); addProperty("word", candidate.text) })
            addProperty("question", "Following `query_rules`, does `candidate_word` help preserve the complete phrase containing " +
                "`pointed_word` or identify its specific meaning and change in `numbered_summary`?")
        },
        mapOf(
            true to JsonPrimitive("`candidate_word` fits a case of `query_rules.include`."),
            false to JsonPrimitive("`candidate_word` fits a case of `query_rules.exclude`."),
        ),
    )

    private fun matchQuestion(candidate: SummaryNode, text: String) = DecisionQuestion.Noul(
        JsonObject().apply {
            add("candidate", JsonObject().apply {
                addProperty("kind", candidate.kind.id)
                addProperty("location", candidate.location)
                addProperty("summary", text)
            })
            addProperty("question", "Following `match_rules`, does `candidate` describe what `query` says about `query_focus`, so " +
                "part of `query` was condensed from it?")
        },
        mapOf(
            true to JsonPrimitive("`candidate` fits a case of `match_rules.include`."),
            false to JsonPrimitive("`candidate` fits a case of `match_rules.exclude`."),
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
        /**
         * About 3.5 bytes of this JSON make a token, so this stays near 4,600 tokens: well under a local server's batch of
         * 10,240, with room for the model's own prompt. A starting point; tune it against the server's context.
         */
        const val MAX_REQUEST_BYTES = 16_000

        private fun bytes(request: DecisionRequest) = DecisionJson.encode(request).toByteArray(Charsets.UTF_8).size

        private fun strings(vararg values: String) = JsonArray().apply { values.forEach(::add) }

        /** What belongs to the query for the pointed word; every context question refers to it. */
        private fun queryRules() = JsonObject().apply {
            addProperty("goal", "A reader points at `pointed_word` in `numbered_summary` to find the parts of the change it refers " +
                "to. The word alone is too vague to search for, so the chosen words, with the pointed word, become the search " +
                "query. First identify the complete phrase containing this occurrence of the pointed word; then keep the context " +
                "that identifies what that phrase refers to and what changes. Judge each candidate as part of that meaning, " +
                "not by its similarity to the isolated pointed word.")
            add("include", strings(
                "The word completes the phrase containing the pointed word: its head, modifiers, compound terms or complements. " +
                    "Keep words specifying its kind, purpose or object, even when they are not synonyms or a formal name. " +
                    "A generic word must retain the words that make its meaning specific here.",
                "The word supplies the action, owner, feature or scope needed to interpret that phrase. Shared context can " +
                    "apply to the pointed phrase even when it also applies to other items in a list.",
                "The word belongs to another phrase of the summary about the same thing: a later mention of it (listed in " +
                    "`pointed_word.other_mentions` when the word repeats), another name or reference for it, or what that phrase " +
                    "says is done to it. Repeated spelling is only a clue: include it only when it refers to the same thing.",
            ))
            add("exclude", strings(
                "The word is a sibling listed next to the pointed word, or next to another mention of it, that the shared action " +
                    "applies to as well.",
                "The word belongs to a different item or is only broadly related to the overall topic. Exclude general commentary " +
                    "that does not help identify or describe the pointed phrase. Do not infer relationships absent from the summary.",
            ))
            add("examples", JsonArray().apply {
                add(example("Adds[1] account[2] recovery[3], including[4] token[5] validation[6] and[7] email[8] templates[9]", "validation[6]",
                    include = strings("Adds[1]", "account[2]", "recovery[3]", "including[4]", "token[5]"),
                    exclude = strings("email[8]", "templates[9]")))
                add(example("deleted[1] views[2]: canvas[3], hierarchy[4], summaries[5]", "hierarchy[4]",
                    include = strings("deleted[1]", "views[2]"), exclude = strings("canvas[3]", "summaries[5]")))
                add(example("Moves[1] caching[2] into[3] the[4] parser[5] and[6] client[7], adding[8] retries[9] to[10] the[11] " +
                    "client[12], with[13] tests[14]", "client[7]",
                    include = strings("Moves[1]", "caching[2]", "adding[8]", "retries[9]", "client[12]"), exclude = strings("parser[5]", "tests[14]")))
            })
        }

        private fun example(summary: String, pointed: String, include: JsonArray, exclude: JsonArray) = JsonObject().apply {
            addProperty("numbered_summary", summary)
            addProperty("pointed_word", pointed)
            add("include", include)
            add("exclude", exclude)
        }

        /** When a summary below was condensed into the query; every match question refers to it. */
        private fun matchRules() = JsonObject().apply {
            addProperty("goal", "`pointed_summary` was condensed from the summaries of its parts, one of which is each `candidate`. " +
                "`query_focus` marks the hovered word, not a standalone search term. Interpret it as the complete phrase it " +
                "belongs to in `query`, using `pointed_summary` to resolve its meaning. The reader wants the parts that change " +
                "that specific thing.")
            add("include", strings(
                "The candidate covers any change the query states about the focus item, or a part of that item, including when its " +
                    "location or file name identifies the item. It need not cover every change in the query.",
            ))
            add("exclude", strings(
                "The candidate is about another item, including another item the query also names, or about a change the query " +
                    "does not state for the focus, even on a related topic or in the same folder.",
                "The candidate mentions the focus only in passing, or shares only the isolated word while describing a different " +
                    "kind, purpose or object. Matching a generic head word does not match the qualified phrase.",
            ))
        }

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
         * The summary narrowed to [context]: words separated only by filler words keep the text between them, and a skipped
         * meaningful run is replaced by the separator that followed the last kept word, so "deleted views: canvas, hierarchy"
         * narrows to "deleted views: hierarchy" and "deleted the views of hierarchy" stays whole.
         */
        internal fun query(summary: String, context: List<SummaryWord>): String = buildString {
            context.forEachIndexed { position, word ->
                if (position > 0) {
                    val previous = context[position - 1]
                    val between = summary.substring(previous.end, word.start)
                    append(if (SummaryWord.split(between).none { it.meaningful }) between else separatorAfter(summary, previous))
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
