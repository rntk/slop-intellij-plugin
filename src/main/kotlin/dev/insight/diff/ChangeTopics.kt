package dev.insight.diff

import com.google.gson.JsonObject
import dev.insight.chat.ChatTool
import dev.insight.chat.ToolCallEvent
import dev.insight.chat.ToolChatRunner
import dev.insight.chat.ToolInputException
import dev.insight.enrichment.CODE_AS_DATA
import dev.insight.enrichment.JsonSchemas
import dev.insight.enrichment.completeJson
import dev.insight.enrichment.parseJsonObjectLeniently
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.LlmApiException
import dev.insight.llm.LlmMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** One hierarchical subject per changed file. File boundaries and coverage belong to code. */
data class ChangeTopics(val files: List<ReviewFile>, val labels: Map<String, String> = emptyMap()) {
    val complete: Boolean get() = files.all { it.path in labels }
    val groups: Map<String, List<String>> get() = files.sortedBy { it.path }
        .filter { it.path in labels }.groupBy({ labels.getValue(it.path) }, { it.path }).toSortedMap()

    fun assign(path: String, label: String): ChangeTopics {
        if (files.none { it.path == path }) throw ToolInputException("$path is not included in this diff")
        val normalized = normalizeTopic(label) ?: throw ToolInputException("A topic needs 2–5 nonempty levels of at most 60 characters each")
        val canonical = labels.values.firstOrNull { it.equals(normalized, ignoreCase = true) } ?: normalized
        return copy(labels = labels + (path to canonical))
    }

    fun retainedIn(next: List<ReviewFile>): ChangeTopics {
        val old = files.associateBy { it.path }
        return ChangeTopics(next, labels.filterKeys { path ->
            next.any { it.path == path && it.fingerprint == old[path]?.fingerprint }
        })
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
 * The change as one document: files grouped by topic, broad to specific, then by path, so every topic level covers a
 * contiguous run of files and its card can span exactly their diffs. Files still awaiting a topic come last.
 */
class TopicOutline(val files: List<ReviewFile>, val nodes: List<TopicNode>, val labeled: Int) {
    val depth: Int get() = (nodes.maxOfOrNull { it.level } ?: -1) + 1
    val pending: IntRange? get() = (labeled until files.size).takeUnless { it.isEmpty() }
}

internal fun topicOutline(topics: ChangeTopics): TopicOutline {
    val files = topics.files.distinctBy { it.path }
    val parts = topics.labels.mapValues { (_, label) -> label.split(" > ") }
    fun key(path: String, levels: Int) = parts.getValue(path).take(levels).map { it.lowercase() }
    // Element-wise order keeps every shared prefix contiguous, whatever characters the levels contain.
    val byLevels = Comparator<ReviewFile> { a, b ->
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

internal fun isTopicsRequest(text: String): Boolean = text.trim().lowercase().let {
    it == "topics" || it == "/topics" || it.startsWith("/topics ")
}

data class ChangeTopicsRun(val topics: ChangeTopics, val requests: Int, val error: String?)

/** Deterministic file order, bounded retries, incremental saves and a shared vocabulary of already assigned paths. */
class ChangeTopicGenerator(
    private val complete: suspend (CompletionRequest) -> Completion,
    private val readTool: ChatTool?,
    private val patchChars: Int = 12_000,
    private val attempts: Int = 2,
) {
    init { require(patchChars > 0 && attempts > 0) }

    suspend fun run(
        start: ChangeTopics,
        focus: String,
        save: suspend (String, String) -> ChangeTopics,
        onToolCall: suspend (ToolCallEvent) -> Unit = {},
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> },
    ): ChangeTopicsRun {
        var current = start
        var requests = 0
        var error: String? = null
        val counted: suspend (CompletionRequest) -> Completion = { requests++; complete(it) }
        onProgress(current.labels.size, current.files.size)
        for (file in start.files.sortedBy { it.path }) {
            if (file.path in current.labels) continue
            // The output allowance includes reasoning, even though the final label is tiny.
            // Keep the raised budget across retries instead of restarting at the original limit.
            var outputBudget = 1024
            for (attempt in 1..attempts) {
                coroutineContext.ensureActive()
                val topic = try {
                    val user = reviewJson.toJson(mapOf(
                        "path" to file.path, "previous_path" to file.previousPath, "status" to file.status,
                        "patch" to file.patch.take(patchChars), "patch_length" to file.patch.length,
                        "patch_cut_at" to patchChars.takeIf { file.patch.length > patchChars },
                        "existing_topics" to topicVocabulary(current),
                        "focus" to focus.take(300),
                    ))
                    val budgeted: suspend (CompletionRequest) -> Completion = { request ->
                        counted(request.copy(maxTokens = outputBudget))
                    }
                    val answer = if (file.patch.length > patchChars && readTool != null) {
                        val reply = ToolChatRunner(budgeted, listOf(readTool), maxToolRounds = 4,
                            maxContextChars = 48_000, evictionTarget = 0.75)
                            .run(listOf(LlmMessage.system(SYSTEM), LlmMessage.user(user)), onToolCall).reply
                        parseJsonObjectLeniently(reply) ?: throw IllegalStateException("Topic answer is not JSON")
                    } else completeJson(budgeted, SYSTEM, user, SCHEMA, maxTokens = outputBudget, attempts = 1)
                    decode(answer) ?: throw IllegalStateException("Topic answer needs a valid 2–5 level path")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LlmApiException) {
                    throw e
                } catch (e: IOException) {
                    error = e.message ?: e.javaClass.simpleName
                    if ("truncated" in e.message.orEmpty()) outputBudget = (outputBudget * 2).coerceAtMost(8192)
                    null
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                    null
                }
                if (topic != null) {
                    // Save outside the retry handler: stale snapshots must terminate the run immediately.
                    current = save(file.path, topic)
                    onProgress(current.labels.size, current.files.size)
                    break
                }
            }
        }
        return ChangeTopicsRun(current, requests, error.takeUnless { current.complete })
    }

    companion object {
        /** Bound the serialized vocabulary as well as its count; long labels must not crowd out evidence. */
        internal fun topicVocabulary(topics: ChangeTopics): List<String> {
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
        internal val SYSTEM = """
            $CODE_AS_DATA
            Name the subject of this changed file's diff with exactly one hierarchical code topic.
            Choose a concise label directly; a short classification is sufficient.
            File boundaries are fixed by code. Do not split, merge, select or skip files.
            Use 2–5 levels, broad to specific, separated by >. Each level is a short noun phrase,
            at most 60 characters, without colons. Return only JSON: {"topic":"Storage>User>Wallet"}.
            Top level: a code responsibility or subsystem such as Storage, Security, UI, Networking,
            Build or Observability. Infer fitting domains from the code; these are examples, not a fixed taxonomy.
            Middle levels name the affected domain/entity; the leaf names its concrete capability.
            Examples: Storage>User>Wallet, Security>User>Auth, UI>Checkout>Payment Form.
            Name what the change concerns, not its directory, filename, language, or edit type.
            Avoid news categories (Technology, Politics), generic labels (Miscellaneous, Changes), and headlines.
            Each child adds specificity beyond its parent. Preserve canonical code/domain names.
            Reuse an existing_topics path exactly when it describes the same subject; do not force unrelated changes together.
            If the file touches several subjects, name their most specific shared responsibility.
            Classify tests and documentation by the capability they concern when it is evident.
            Base the topic on the patch and status (including deletions/renames), not on an imagined implementation.
            For binary or empty patches use the available path/status evidence conservatively.
            When patch_cut_at is set and read_diff_files is available, read further with this path and offset
            patch_cut_at if needed to identify the subject. A cut patch is incomplete evidence.
            The focus field is an optional user preference; every file still needs a topic.
        """.trimIndent()
    }
}

internal fun topicsReply(run: ChangeTopicsRun): String {
    if (run.topics.files.isEmpty()) return "There are no changed files to classify."
    return "${run.topics.groups.size} topics on the canvas · ${run.topics.labels.size} of ${run.topics.files.size} files · ${run.requests} requests." +
        if (run.topics.complete) "" else "\n\nSome files still need topics. Run /topics to continue." +
            (run.error?.let { " Last failure: ${it.take(300)}" } ?: "")
}
