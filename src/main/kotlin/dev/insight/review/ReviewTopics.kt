package dev.insight.review

import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.logger
import dev.insight.chat.ChatTool
import dev.insight.chat.ToolCallEvent
import dev.insight.chat.ToolChatRunner
import dev.insight.chat.ToolInputException
import dev.insight.enrichment.JsonSchemas
import dev.insight.enrichment.completeJson
import dev.insight.enrichment.forEachConcurrently
import dev.insight.enrichment.parseJsonObjectLeniently
import dev.insight.llm.CompletionFn
import dev.insight.llm.LlmApiException
import dev.insight.llm.LlmMessage
import dev.insight.llm.LlmTruncatedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.currentCoroutineContext

private val LOG = logger<ReviewTopics>()

/** One hierarchical subject per reviewed file. File boundaries and coverage belong to code. */
data class ReviewTopics(val files: List<ReviewSource>, val labels: Map<String, String> = emptyMap()) {
    val complete: Boolean get() = files.all { it.path in labels }
    // Computed once per value; delegated properties are not part of the data class's equality.
    val groups: Map<String, List<String>> by lazy {
        files.sortedBy { it.path }.filter { it.path in labels }.groupBy({ labels.getValue(it.path) }, { it.path }).toSortedMap()
    }
    /** Built on first use and handed to the next value by [assign], which keeps the same files. */
    @Volatile private var knownPaths: Set<String>? = null
    private val paths: Set<String> get() = knownPaths ?: files.mapTo(HashSet()) { it.path }.also { knownPaths = it }

    private constructor(files: List<ReviewSource>, labels: Map<String, String>, paths: Set<String>) : this(files, labels) {
        knownPaths = paths
    }

    fun assign(path: String, label: String): ReviewTopics {
        if (path !in paths) throw ToolInputException("$path is not included in this review")
        val normalized = normalizeTopic(label) ?: throw ToolInputException("A topic needs 2–5 nonempty levels of at most 60 characters each")
        val canonical = labels.values.firstOrNull { it.equals(normalized, ignoreCase = true) } ?: normalized
        return ReviewTopics(files, labels + (path to canonical), paths)
    }

    fun retainedIn(next: List<ReviewSource>): ReviewTopics {
        val old = files.associateBy { it.path }
        val unchanged = next.mapTo(HashSet()) { it.path to it.fingerprint }
        return ReviewTopics(next, labels.filterKeys { path -> old[path]?.let { (path to it.fingerprint) in unchanged } == true })
    }
}

/** A rail card: files [first]..[last] of the outline share the topic's first `level + 1` levels. */
data class TopicNode(val level: Int, val path: List<String>, val first: Int, val last: Int) {
    val name: String get() = path.last()
    val fullPath: String get() = path.joinToString(" > ")
    /** Case-insensitive, like topic reuse; keeps selection across renders. */
    val identity: String get() = fullPath.lowercase()
    val size: Int get() = last - first + 1
}

/**
 * The review as one document: files grouped by topic, broad to specific, then by path, so every topic level covers a
 * contiguous run of files and its card can span exactly their diffs, or their rows in the hierarchy. Files still awaiting a topic come last.
 */
class TopicOutline(val files: List<ReviewSource>, val nodes: List<TopicNode>, val labeled: Int) {
    val depth: Int get() = (nodes.maxOfOrNull { it.level } ?: -1) + 1
    val pending: IntRange? get() = (labeled until files.size).takeUnless { it.isEmpty() }
    private val starts by lazy { nodes.associateBy { it.level to it.first } }

    /**
     * The direct entries of [parent], or the top-level topics for null, in outline order: child topics and the files whose
     * topic ends at [parent]. Files awaiting a topic belong to no node.
     */
    fun children(parent: TopicNode?): List<TopicEntry> {
        val level = parent?.let { it.level + 1 } ?: 0
        val range = parent?.let { it.first..it.last } ?: (0 until labeled)
        val entries = mutableListOf<TopicEntry>()
        var index = range.first
        while (index <= range.last) {
            val child = starts[level to index]
            if (child != null) { entries += TopicEntry.Topic(child); index = child.last + 1 }
            else entries += TopicEntry.File(index++)
        }
        return entries
    }
}

sealed interface TopicEntry {
    data class Topic(val node: TopicNode) : TopicEntry
    /** An index into [TopicOutline.files]. */
    data class File(val index: Int) : TopicEntry
}

internal fun topicOutline(topics: ReviewTopics): TopicOutline {
    val files = topics.files.distinctBy { it.path }
    val parts = topics.labels.mapValues { (_, label) -> label.split(" > ") }
    fun key(path: String, levels: Int) = parts.getValue(path).take(levels).map { it.lowercase() }
    // Element-wise order keeps every shared prefix contiguous, whatever characters the levels contain.
    val byLevels = Comparator<ReviewSource> { a, b ->
        val x = key(a.path, Int.MAX_VALUE)
        val y = key(b.path, Int.MAX_VALUE)
        x.zip(y).firstNotNullOfOrNull { (p, q) -> p.compareTo(q).takeIf { it != 0 } } ?: x.size.compareTo(y.size)
    }
    val labeled = files.filter { it.path in parts }.sortedWith(byLevels.thenBy { it.path })
    val nodes = mutableListOf<TopicNode>()
    for (level in 0 until (parts.values.maxOfOrNull { it.size } ?: 0)) {
        var start = 0
        while (start < labeled.size) {
            val path = parts.getValue(labeled[start].path)
            if (path.size <= level) { start++; continue }
            val prefix = key(labeled[start].path, level + 1)
            var end = start
            while (end + 1 < labeled.size && parts.getValue(labeled[end + 1].path).size > level && key(labeled[end + 1].path, level + 1) == prefix) end++
            nodes += TopicNode(level, path.take(level + 1), start, end)
            start = end + 1
        }
    }
    return TopicOutline(labeled + files.filter { it.path !in parts }.sortedBy { it.path }, nodes, labeled.size)
}

internal fun normalizeTopic(value: String): String? {
    val parts = value.split('>').map { it.trim().replace(Regex("\\s+"), " ") }
    if (parts.size !in 2..5 || parts.any { it.isEmpty() || it.length > 60 || ':' in it || it.any(Char::isISOControl) }) return null
    return parts.joinToString(" > ")
}

internal fun isTopicsRequest(text: String): Boolean = ReviewCommand.parse(text) == ReviewCommand.TOPICS

data class ReviewTopicsRun(val topics: ReviewTopics, val requests: Int, val error: String?)

/**
 * What a topic request shows of one file: [fields] follow the shared focus and vocabulary in the request JSON.
 * [cut] means the evidence is incomplete, so the request gets the read tool when there is one.
 */
internal data class TopicEvidence(val fields: Map<String, Any?>, val cut: Boolean)

/**
 * Deterministic file order, bounded retries, incremental saves and a shared vocabulary of already assigned paths.
 * With [concurrency] above one, files are classified in parallel; each request sees the vocabulary saved when it started.
 */
class ReviewTopicGenerator internal constructor(
    private val complete: CompletionFn,
    private val readTool: ChatTool?,
    patchChars: Int = 12_000,
    private val attempts: Int = 5,
    private val system: String,
    private val evidence: (ReviewSource, ReviewTopics) -> TopicEvidence,
    private val concurrency: Int = 1,
) {
    init { require(patchChars > 0 && attempts > 0 && concurrency > 0) }

    suspend fun run(
        start: ReviewTopics,
        focus: String,
        save: suspend (String, String) -> ReviewTopics,
        onToolCall: suspend (ToolCallEvent) -> Unit = {},
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> },
    ): ReviewTopicsRun {
        var current = start
        val requests = AtomicInteger()
        val error = AtomicReference<String?>()
        val mutex = Mutex()
        val counted: CompletionFn = { requests.incrementAndGet(); complete(it) }
        onProgress(current.labels.size, current.files.size)
        suspend fun visit(file: ReviewSource) {
            // The topics saved so far, including the vocabulary this file's requests offer.
            val seen = mutex.withLock { current }
            if (file.path in seen.labels) return
            val topic = classify(file, seen, focus, counted, onToolCall, error) ?: return
            // Save outside the retry handler: stale snapshots must terminate the run immediately.
            mutex.withLock {
                current = save(file.path, topic)
                onProgress(current.labels.size, current.files.size)
            }
        }
        val files = start.files.sortedBy { it.path }
        if (concurrency == 1) files.forEach { visit(it) } else forEachConcurrently(files, concurrency) { visit(it) }
        return ReviewTopicsRun(current, requests.get(), error.get().takeUnless { current.complete })
    }

    /** The topic of [file], or null once its attempts are spent; [error] keeps the last failure. */
    private suspend fun classify(
        file: ReviewSource,
        seen: ReviewTopics,
        focus: String,
        counted: CompletionFn,
        onToolCall: suspend (ToolCallEvent) -> Unit,
        error: AtomicReference<String?>,
    ): String? {
        // The output allowance includes reasoning, even though the final label is tiny.
        // Keep the raised budget across retries instead of restarting at the original limit.
        var outputBudget = dev.insight.llm.DEFAULT_OUTPUT_TOKENS
        repeat(attempts) {
            currentCoroutineContext().ensureActive()
            val topic = try {
                val shown = evidence(file, seen)
                val user = reviewJson.toJson(mapOf(
                    "focus" to focus.take(300),
                    "existing_topics" to topicVocabulary(seen),
                ) + shown.fields)
                val budgeted: CompletionFn = { request ->
                    counted(request.copy(maxTokens = outputBudget))
                }
                val answer = if (shown.cut && readTool != null) {
                    val reply = ToolChatRunner(budgeted, listOf(readTool), maxToolRounds = 100,
                        maxContextChars = 48_000, evictionTarget = 0.75)
                        .run(listOf(LlmMessage.system(system), LlmMessage.user(user)), onToolCall)
                    parseJsonObjectLeniently(reply.reply) ?: throw
                        if (reply.finishReason == dev.insight.llm.FinishReason.TRUNCATED) LlmTruncatedException("Topic answer was truncated")
                        else IOException("Topic answer is not JSON")
                } else completeJson(budgeted, system, user, SCHEMA, maxTokens = outputBudget, attempts = 1, retryTruncated = false)
                decode(answer) ?: throw IllegalStateException("Topic answer needs a valid 2–5 level path")
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmApiException) {
                throw e
            } catch (e: IOException) {
                LOG.warn("Topic request failed", e)
                error.set(e.message ?: e.javaClass.simpleName)
                if (e is LlmTruncatedException) outputBudget = (outputBudget * 2).coerceAtMost(32768)
                null
            } catch (e: Exception) {
                LOG.warn("Topic request failed", e)
                error.set(e.message ?: e.javaClass.simpleName)
                null
            }
            if (topic != null) return topic
        }
        return null
    }

    companion object {
        /** Bound the serialized vocabulary as well as its count; long labels must not crowd out evidence. */
        internal fun topicVocabulary(topics: ReviewTopics): List<String> {
            val vocabulary = mutableListOf<String>()
            var chars = 2 // JSON array brackets
            for (label in topics.groups.keys.take(100)) {
                val size = reviewJson.toJson(label).length + 1
                if (chars + size > 8000) break
                vocabulary += label
                chars += size
            }
            return vocabulary
        }

        internal val SCHEMA = JsonSchemas.obj("topic" to JsonSchemas.string())
        internal fun decode(answer: JsonObject): String? = answer.get("topic")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.let(::normalizeTopic)

    }
}

internal fun topicsReply(run: ReviewTopicsRun, empty: String = "There are no changed files to classify."): String {
    if (run.topics.files.isEmpty()) return empty
    return "${run.topics.groups.size} topics on the canvas · ${run.topics.labels.size} of ${run.topics.files.size} files · ${run.requests} requests." +
        if (run.topics.complete) "" else "\n\nSome files still need topics. Run /topics to continue." +
            (run.error?.let { " Last failure: ${it.take(300)}" } ?: "")
}
