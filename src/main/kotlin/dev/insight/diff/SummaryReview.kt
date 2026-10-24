package dev.insight.diff

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import dev.insight.decisions.DecisionAnswer
import dev.insight.decisions.DecisionJson
import dev.insight.decisions.DecisionQuestion
import dev.insight.decisions.DecisionRequest
import dev.insight.decisions.DecisionResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Review marks describe evidence, so changed source or patches automatically become unreviewed. */
class ReviewedFiles {
    private var files: Map<String, ReviewSource> = emptyMap()
    private val marks = mutableSetOf<String>()
    val keys: Set<String> get() = marks.toSet()
    var revision: Int = 0
        private set

    internal fun restore(sources: List<ReviewSource>, saved: Set<String> = keys) {
        files = sources.associateBy { it.path }
        marks.clear()
        marks.addAll(saved.intersect(sources.map(::key).toSet()))
        revision++
    }

    fun isReviewed(node: SummaryNode): Boolean = node.files.isNotEmpty() &&
        node.files.all { path -> files[path]?.let { key(it) in marks } == true }

    /** Returns every targeted evidence key, including marks already in the requested local state. */
    fun set(paths: List<String>, reviewed: Boolean): List<String> {
        val targeted = paths.mapNotNull(files::get).map(::key)
        targeted.forEach { if (reviewed) marks.add(it) else marks.remove(it) }
        revision++
        return targeted
    }

    companion object {
        internal fun key(file: ReviewSource): String = reviewFingerprint("reviewed", file.path, file.fingerprint)
    }
}

/** Word indices refer to occurrences in the original prose, never to a global set of spellings. */
internal typealias ReviewDimming = Map<Int, Set<Int>>

/**
 * Each parent word may depend on several children. Noul preserves that many-to-many relationship.
 * A partial child needs a second judgment linking the parent occurrence to the child's word occurrences.
 * All contributors must be reviewed; ambiguous or absent evidence always leaves the parent word bright.
 */
internal class SummaryReviewLinker(
    private val evaluate: suspend (DecisionRequest) -> DecisionResponse,
    private val cache: ReviewDiskCache? = null,
) {
    private val memory = mutableMapOf<String, Map<String, Double>>()
    private val lock = Mutex()

    /** [onProgress] reports how many partially reviewed summaries have been judged, out of all that need it. */
    suspend fun dimming(
        summaries: ChangeSummaries,
        reviewed: Set<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ReviewDimming = lock.withLock {
        val tree = summaries.tree
        val sources = tree.nodes.mapNotNull { it.file }.associateBy { it.path }
        val full = tree.nodes.associate { node -> node.id to (node.files.isNotEmpty() &&
            node.files.all { path -> sources[path]?.let { ReviewedFiles.key(it) in reviewed } == true }) }
        val any = tree.nodes.associate { node -> node.id to node.files.any { path ->
            sources[path]?.let { ReviewedFiles.key(it) in reviewed } == true
        } }
        val result = mutableMapOf<Int, Set<Int>>()
        val total = tree.nodes.count { node -> !full.getValue(node.id) && any.getValue(node.id) && node.children.isNotEmpty() &&
            summaries.text(node.id) != null && node.children.all { summaries.text(it) != null } }
        var done = 0
        onProgress(0, total)
        for (node in tree.nodes.asReversed()) {
            val text = summaries.text(node.id) ?: continue
            val words = SummaryWord.split(text)
            if (full.getValue(node.id)) { result[node.id] = words.mapTo(hashSetOf()) { it.index }; continue }
            if (!any.getValue(node.id) || node.children.isEmpty()) continue
            if (node.children.any { summaries.text(it) == null }) continue
            val only = node.children.singleOrNull()
            if (only != null && summaries.text(only) == text) {
                result[node.id] = result[only].orEmpty(); onProgress(++done, total); continue
            }
            val meaningful = words.filter { it.meaningful }
            val links = node.children.associateWith { child ->
                val childText = summaries.text(child)!!
                scores(text, childText, meaningful, null)
            }
            val possible = meaningful.filter { word -> node.children.all { child ->
                val p = links.getValue(child).getValue("w${word.index}")
                p <= NO || p >= YES && (full.getValue(child) || !result[child].isNullOrEmpty())
            } }
            // All word pairs sharing these two summaries are batched together, irrespective of review state.
            val wordLinks = node.children.filter { !full.getValue(it) && !result[it].isNullOrEmpty() }.associateWith { child ->
                scores(text, summaries.text(child)!!,
                    possible.filter { links.getValue(child).getValue("w${it.index}") >= YES },
                    SummaryWord.split(summaries.text(child)!!).filter { it.meaningful })
            }
            val dim = hashSetOf<Int>()
            for (word in possible) {
                var supported = false
                var covered = true
                for (child in node.children) {
                    val probability = links.getValue(child).getValue("w${word.index}")
                    if (probability <= NO) continue
                    if (probability < YES) { covered = false; break }
                    supported = true
                    if (full.getValue(child)) continue
                    val childDim = result[child].orEmpty()
                    if (childDim.isEmpty()) { covered = false; break }
                    val childText = summaries.text(child)!!
                    val childWords = SummaryWord.split(childText).filter { it.meaningful }
                    val matches = wordLinks.getValue(child)
                    val contributors = childWords.filter { matches.getValue("w${word.index}_c${it.index}") >= YES }
                    if (contributors.isEmpty() || childWords.any {
                        val p = matches.getValue("w${word.index}_c${it.index}")
                        p > NO && (p < YES || it.index !in childDim)
                    }) { covered = false; break }
                }
                if (supported && covered) dim += word.index
            }
            result[node.id] = dim
            onProgress(++done, total)
        }
        result
    }

    private suspend fun scores(parent: String, child: String, words: List<SummaryWord>, childWords: List<SummaryWord>?): Map<String, Double> {
        val state = JsonObject().apply {
            addProperty("parent_summary", numbered(parent))
            addProperty("child_summary", numbered(child))
        }
        val questions = linkedMapOf<String, DecisionQuestion>()
        for (word in words) {
            if (childWords == null) questions["w${word.index}"] = DecisionQuestion.Noul(
                "Does child_summary contribute to the meaning of parent word ${word.index + 1} (${word.text}) in parent_summary?",
                mapOf(true to JsonPrimitive("The child describes part of the behavior or responsibility expressed by this parent word in context, including paraphrases. A generic verb can be supported by multiple children."),
                    false to JsonPrimitive("The child describes a different behavior or responsibility. A shared spelling alone does not establish a contribution.")))
            else for (candidate in childWords) questions["w${word.index}_c${candidate.index}"] = DecisionQuestion.Noul(
                "Does child word ${candidate.index + 1} (${candidate.text}) in child_summary carry meaning condensed into parent word ${word.index + 1} (${word.text}) in parent_summary?",
                mapOf(true to JsonPrimitive("This child word contributes to the parent occurrence's behavior or responsibility in context, including paraphrases and shared actions."),
                    false to JsonPrimitive("This child word belongs to another detail or behavior; a matching spelling alone is insufficient.")))
        }
        val slot = reviewFingerprint(VERSION, MODEL, state.toString())
        val saved = memory.getOrPut(slot) {
            runCatching { cache?.read("review-links", slot, slot)?.asJsonObject?.entrySet()?.associate { it.key to it.value.asDouble }
                ?.takeIf { entries -> entries.values.all { it.isFinite() && it in 0.0..1.0 } } }.getOrNull().orEmpty()
        }.toMutableMap()
        val missing = questions.filterKeys { it !in saved }
        var batch = linkedMapOf<String, DecisionQuestion>()
        suspend fun flush() {
            if (batch.isEmpty()) return
            val response = evaluate(DecisionRequest(state, batch, MODEL))
            // Validate before saving even when an injected transport bypasses DecisionsClient's decoder.
            require(response.answers.keys == batch.keys) { "Missing summary review judgments" }
            val values = batch.keys.associateWith { id ->
                val p = (response.answers[id] as? DecisionAnswer.Noul)?.noul
                require(p != null && p.isFinite() && p in 0.0..1.0) { "Invalid summary review judgment" }
                p
            }
            saved.putAll(values)
            memory[slot] = saved.toMap()
            cache?.let { disk -> synchronized(disk) {
                // Other review tabs may have filled different questions for this same summary pair.
                val merged = disk.read("review-links", slot, slot)?.takeIf { it.isJsonObject }?.asJsonObject?.deepCopy() ?: JsonObject()
                saved.forEach { (id, p) -> merged.addProperty(id, p) }
                disk.write("review-links", slot, slot, merged)
            } }
            batch = linkedMapOf()
        }
        for ((id, question) in missing) {
            val grown = batch + (id to question)
            if (batch.isNotEmpty() && (grown.size > 32 || DecisionJson.encode(DecisionRequest(state, grown, MODEL)).toByteArray().size > 16_000)) flush()
            batch[id] = question
        }
        flush()
        return saved
    }

    private fun numbered(text: String): String {
        val words = SummaryWord.split(text)
        return buildString {
            var offset = 0
            for (word in words) { append(text, offset, word.start); append("[${word.index + 1}]${word.text}"); offset = word.end }
            append(text, offset, text.length)
        }
    }

    companion object {
        private const val VERSION = "summary-review-links-v1"
        private const val MODEL = "jev-latest"
        private const val YES = 0.7
        private const val NO = 0.3
    }
}
