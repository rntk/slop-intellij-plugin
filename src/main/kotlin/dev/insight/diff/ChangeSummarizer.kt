package dev.insight.diff

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.insight.chat.ChatTool
import dev.insight.chat.ToolCallEvent
import dev.insight.chat.ToolChatRunner
import dev.insight.enrichment.CODE_AS_DATA
import dev.insight.enrichment.JsonSchemas
import dev.insight.enrichment.clipLine
import dev.insight.enrichment.completeJson
import dev.insight.enrichment.forEachConcurrently
import dev.insight.enrichment.jsonText
import dev.insight.enrichment.parseJsonObjectLeniently
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.LlmApiException
import dev.insight.llm.LlmMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

data class ChangeSummaryOptions(
    /** Patch characters inlined per file; a longer patch is cut and its request may read the rest with the read tool. */
    val patchChars: Int = 6_000,
    /** Inlined patch characters per request, so small files share one request. */
    val batchChars: Int = 24_000,
    val filesPerRequest: Int = 8,
    val foldersPerRequest: Int = 8,
    val concurrency: Int = 2,
    /** Requests per node before it is given up; the folders above it then keep their names. */
    val attempts: Int = 2,
    /** Tool rounds for a request whose patch was cut. */
    val maxToolRounds: Int = 4,
    /** Characters of the changed-path overview that gives file requests the whole change as context. */
    val overviewChars: Int = 4_000,
)

data class ChangeSummaryRun(val summaries: ChangeSummaries, val requests: Int, val error: String?)

/**
 * Writes the summary tree bottom up. Code walks the tree in waves of ready nodes (files, then folders whose children
 * are done, up to the root), batches each wave into JSON requests and saves every answer as it arrives, so the model
 * never pages, tracks progress or picks nodes. Files carry their patch inline; only a request whose patch was cut gets
 * [readTool] to read the rest. Folders, groups and the root are written from their children's summaries alone.
 */
class ChangeSummarizer(
    private val complete: suspend (CompletionRequest) -> Completion,
    private val readTool: ChatTool?,
    private val options: ChangeSummaryOptions = ChangeSummaryOptions(),
) {
    /**
     * [save] stores a batch and returns the current summaries; it throws when the snapshot changed, which ends the run.
     * [onProgress] reports saved nodes initially and after each batch, serialized across concurrent requests.
     */
    suspend fun run(
        start: ChangeSummaries,
        focus: String,
        save: suspend (Map<Int, String>) -> ChangeSummaries,
        onToolCall: suspend (ToolCallEvent) -> Unit = {},
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ChangeSummaryRun {
        var current = start
        val tries = HashMap<Int, Int>()
        val requests = AtomicInteger()
        var error: String? = null
        val mutex = Mutex()
        val fileSystem = fileSystem(start.tree, focus)
        val folderSystem = folderSystem(focus)
        onProgress(current.count, current.total)
        while (true) {
            val ready = current.ready().filter { (tries[it] ?: 0) < options.attempts }.sorted()
            if (ready.isEmpty()) break
            val (reused, asked) = ready.map { current.tree[it] }.partition { it.children.size == 1 }
            if (reused.isNotEmpty()) {
                val texts = reused.associate { it.id to current.text(it.children.single())!! }
                mutex.withLock {
                    current = save(texts)
                    onProgress(current.count, current.total)
                }
                continue
            }
            asked.forEach { tries.merge(it.id, 1, Int::plus) }
            val before = current.count
            val (files, folders) = asked.partition { it.kind == SummaryKind.FILE }
            val batches = fileBatches(files).map { it to fileSystem } + folders.chunked(options.foldersPerRequest).map { it to folderSystem }
            forEachConcurrently(batches, options.concurrency) { (batch, system) ->
                val texts = try {
                    request(system, batch, current, requests, onToolCall)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LlmApiException) {
                    // The server rejected the request; the next one would fail the same way.
                    throw e
                } catch (e: Exception) {
                    mutex.withLock { error = e.message ?: e.javaClass.simpleName }
                    emptyMap()
                }
                if (texts.isNotEmpty()) mutex.withLock {
                    current = save(texts)
                    onProgress(current.count, current.total)
                }
            }
            // A wave without any answer would fail again; stop rather than resend it.
            if (current.count == before) break
        }
        return ChangeSummaryRun(current, requests.get(), error.takeIf { !current.complete })
    }

    /**
     * Files in tree order, so siblings share a request. Cut patches get requests of their own at the end, the only
     * ones with tools, so the tool-free requests run together without tool definitions breaking their cached prefix.
     */
    private fun fileBatches(files: List<SummaryNode>): List<List<SummaryNode>> {
        val batches = mutableListOf<List<SummaryNode>>()
        val cut = mutableListOf<List<SummaryNode>>()
        var batch = mutableListOf<SummaryNode>()
        var chars = 0
        for (node in files) {
            val size = node.file?.patch?.length ?: 0
            if (size > options.patchChars) { cut += listOf(node); continue }
            if (batch.isNotEmpty() && (batch.size >= options.filesPerRequest || chars + size > options.batchChars)) {
                batches += batch; batch = mutableListOf(); chars = 0
            }
            batch += node; chars += size
        }
        if (batch.isNotEmpty()) batches += batch
        return batches + cut
    }

    /** Answers by node id; nodes the model skipped or answered twice are left for the next wave. */
    private suspend fun request(
        system: String,
        batch: List<SummaryNode>,
        summaries: ChangeSummaries,
        requests: AtomicInteger,
        onToolCall: suspend (ToolCallEvent) -> Unit,
    ): Map<Int, String> {
        val user = JsonObject().apply {
            add("nodes", JsonArray().apply { batch.forEachIndexed { index, node -> add(item(index + 1, node, summaries)) } })
        }.toString()
        val counted: suspend (CompletionRequest) -> Completion = { requests.incrementAndGet(); complete(it) }
        val cut = batch.any { (it.file?.patch?.length ?: 0) > options.patchChars }
        val answer = if (cut && readTool != null) {
            val reply = ToolChatRunner(counted, listOf(readTool), maxToolRounds = options.maxToolRounds)
                .run(listOf(LlmMessage.system(system), LlmMessage.user("$user\n\n$JSON_ONLY")), onToolCall).reply
            parseJsonObjectLeniently(reply) ?: throw IllegalStateException("The summary answer is not a JSON object: ${reply.take(200)}")
        } else completeJson(counted, system, user, SCHEMA, maxTokens = 200 + 160 * batch.size)
        return decode(answer, batch.size).mapKeys { (position, _) -> batch[position - 1].id }
    }

    /** What the model sees of one node: a file's patch, or a folder's children with their summaries. */
    private fun item(position: Int, node: SummaryNode, summaries: ChangeSummaries) = JsonObject().apply {
        addProperty("id", position)
        addProperty("kind", node.kind.id)
        addProperty("location", node.location)
        val file = node.file
        if (file != null) {
            addProperty("status", file.status)
            file.previousPath?.let { addProperty("previous_path", it) }
            addProperty("lines", changedLines(file))
            addProperty("patch", file.patch.take(options.patchChars))
            if (file.patch.length > options.patchChars) {
                addProperty("patch_cut_at", options.patchChars)
                addProperty("patch_length", file.patch.length)
            }
        } else {
            addProperty("files", node.files.size)
            add("children", JsonArray().apply {
                node.children.forEach { id ->
                    val child = summaries.tree[id]
                    add(JsonObject().apply {
                        addProperty("kind", child.kind.id)
                        addProperty("label", child.label)
                        addProperty("summary", summaries.text(id))
                    })
                }
            })
        }
    }

    /** File requests share this prefix, with the per-run focus last so the overview stays cached across focus changes. */
    private fun fileSystem(tree: ChangeSummaryTree, focus: String): String {
        val overview = StringBuilder()
        for (node in tree.nodes) {
            val file = node.file ?: continue
            val line = "${file.path} (${file.status}, ${changedLines(file)})\n"
            if (overview.length + line.length > options.overviewChars) { overview.append("…\n"); break }
            overview.append(line)
        }
        return "$SYSTEM_PREFIX\n" + """
            Every node in "nodes" is a changed file with its patch inline. Briefly describe what changed
            and its role in the change. Name behavior rather than file names.
            The inline patch is usually enough, so answer from it directly. When read_diff_files is offered, call it only if
            you cannot tell what a file's change does without more:
            - its patch was cut (patch_cut_at is set): read the rest with that one path and offset: patch_cut_at;
            - its patch is too small to understand alone: read the diff of a related file from the list below.

            All files in the change, for context only; summarize just the files in "nodes":
        """.trimIndent() + "\n" + overview.toString().trimEnd() + focusLine(focus)
    }

    private fun folderSystem(focus: String): String = "$SYSTEM_PREFIX\n" + """
        Every node in "nodes" is a folder, a group of a folder's entries, or the root, listed with its children's
        summaries. Briefly describe what each part contributes to the change, using its children's summaries.
        If a node has kind "root", summarize the overall change.
    """.trimIndent() + focusLine(focus)

    private fun focusLine(focus: String) = if (focus.isEmpty()) "" else "\n\nThe user asked to focus on: ${focus.clipLine(300)}"

    companion object {
        private val SYSTEM_PREFIX = """
            $CODE_AS_DATA
            You write concise summaries for a code change shown as a tree of files and folders.
            This request covers only the nodes in "nodes". Return every id in "nodes" exactly once. Return only JSON matching {"summaries":[{"id":1,"summary":"…"}]}.
            Keep summaries brief and use plain text.
        """.trimIndent()
        private const val JSON_ONLY = "Answer with only the JSON object, no other text."

        internal val SCHEMA = JsonSchemas.obj("summaries" to JsonSchemas.array(JsonSchemas.obj(
            "id" to JsonSchemas.integer(),
            "summary" to JsonSchemas.string(),
        )))

        /** Summaries by 1-based position; unknown, duplicate and empty entries are dropped. */
        internal fun decode(answer: JsonObject, size: Int): Map<Int, String> {
            val entries = answer.get("summaries")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyMap()
            val result = HashMap<Int, String>()
            val duplicates = hashSetOf<Int>()
            for (element in entries) {
                val entry = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val id = entry.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
                    ?.takeIf { it % 1.0 == 0.0 && it >= 1 && it <= size }?.toInt() ?: continue
                val text = entry.jsonText("summary") ?: continue
                if (result.put(id, text) != null) duplicates += id
            }
            // Two answers for one node cannot both describe it; neither is trusted.
            duplicates.forEach(result::remove)
            return result
        }
    }
}

internal fun changedLines(file: ReviewFile): String =
    "+${file.patch.lineSequence().count { it.startsWith('+') }} -${file.patch.lineSequence().count { it.startsWith('-') }}"

internal fun summariesFocus(text: String): String = text.trim().substringAfter(" ", "").trim()

/** The chat reply to /summaries, written by code: the root summary, or what is still missing and why. */
internal fun summariesReply(run: ChangeSummaryRun): String {
    val summaries = run.summaries
    val requests = "${run.requests} request${if (run.requests == 1) "" else "s"}"
    if (summaries.total == 0) return "There are no changed files to summarize."
    if (summaries.complete) return "${summaries.text(summaries.tree.root.id)}\n\n[${summaries.total} summaries · $requests]"
    val missing = summaries.missing().size
    return "Summarized ${summaries.count} of ${summaries.total} tree nodes with $requests.\n\n[No summary for $missing nodes. " +
        "They show their names in the Summaries card.${run.error?.let { " Last failure: ${it.take(300)}" } ?: ""}]"
}
