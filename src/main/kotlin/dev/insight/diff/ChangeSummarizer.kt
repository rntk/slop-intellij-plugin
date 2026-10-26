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
    val attempts: Int = 5,
    /** Tool rounds for a request whose patch was cut. */
    val maxToolRounds: Int = 100,
    /** Characters of the changed-path overview that gives file requests the whole change as context. */
    val overviewChars: Int = 4_000,
)

data class ChangeSummaryRun(val summaries: ChangeSummaries, val requests: Int, val error: String?)

/** What a summary request shows of a leaf (a file, or a part of a large file) and the instructions around it. */
internal interface SummaryEvidence {
    /**
     * Inline characters of a leaf's evidence, for batching. Above [ChangeSummaryOptions.patchChars] the evidence is cut,
     * so the leaf gets a request of its own, with the read tool when there is one.
     */
    fun size(node: SummaryNode): Int
    /** Adds a leaf's evidence to its request [item], which already holds id, kind and location; inlines at most [limit] characters. */
    fun describe(node: SummaryNode, item: JsonObject, limit: Int)
    /** Instructions shared by every leaf request of a run, with the per-run focus last so the prefix stays cached. */
    fun leafSystem(tree: ChangeSummaryTree, focus: String): String
    /** Instructions for nodes written from their children's summaries: folders, groups, files with parts, topics, the root. */
    fun branchSystem(focus: String): String
}

/** A changed file's patch, with the changed paths as context. */
internal class DiffSummaryEvidence(private val options: ChangeSummaryOptions) : SummaryEvidence {
    override fun size(node: SummaryNode): Int = (node.file as? ReviewFile)?.patch?.length ?: 0

    override fun describe(node: SummaryNode, item: JsonObject, limit: Int) {
        val file = node.file as? ReviewFile ?: return
        item.addProperty("path", file.path)
        item.addProperty("status", file.status)
        file.previousPath?.let { item.addProperty("previous_path", it) }
        item.addProperty("lines", changedLines(file))
        item.addProperty("patch", file.patch.take(limit))
        if (file.patch.length > limit) {
            item.addProperty("patch_cut_at", limit)
            item.addProperty("patch_length", file.patch.length)
        }
    }

    /** File requests share this prefix, with the per-run focus last so the overview stays cached across focus changes. */
    override fun leafSystem(tree: ChangeSummaryTree, focus: String): String {
        val overview = StringBuilder()
        for (node in tree.nodes) {
            val file = node.file as? ReviewFile ?: continue
            val line = "${file.path} (${file.status}, ${changedLines(file)})\n"
            if (overview.length + line.length > options.overviewChars) { overview.append("…\n"); break }
            overview.append(line)
        }
        return "$SYSTEM_PREFIX\n" + """
            Every node in "nodes" is a changed file with its patch inline. State the main behavioral change
            or purpose of the edit. Include the reason only when the supplied evidence establishes it.
            The inline patch is usually enough, so answer from it directly. When read_diff_files is offered, call it only if
            you cannot tell what a file's change does without more:
            - its patch was cut (patch_cut_at is set): read the rest with that one path and offset: patch_cut_at;
            - its patch is too small to understand alone: read the diff of a related file from the list below.

            All files in the change, for context only; summarize just the files in "nodes":
        """.trimIndent() + "\n" + overview.toString().trimEnd() + summaryFocusLine(focus)
    }

    override fun branchSystem(focus: String): String = "$SYSTEM_PREFIX\n" + """
        Every node in "nodes" is a branch of the tree, listed with its children's
        summaries. Using only those summaries, condense each node's changes into their main themes without
        listing the children or repeating subordinate details. Preserve meaningful qualifications and do not
        force unrelated changes into a shared purpose or infer broader guarantees.
        If a node has kind "root", summarize the overall change.
    """.trimIndent() + summaryFocusLine(focus)

    private companion object {
        val SYSTEM_PREFIX = """
            $CODE_AS_DATA
            You write concise summaries for a code change shown as a tree.
            This request covers only the nodes in "nodes". Return every id in "nodes" exactly once. Return only JSON matching {"summaries":[{"id":1,"summary":"…"}]}.
        """.trimIndent() + "\n" + SUMMARY_WRITING_GUIDANCE
    }
}

/** Shared writing policy for source and diff summaries at every level of the tree. */
internal val SUMMARY_WRITING_GUIDANCE = """
    Describe the main purpose, responsibility, or behavioral change in plain text and plain language.
    Prefer one short sentence; a short phrase is enough for a trivial item. Add another sentence only when
    it conveys an important distinction that would otherwise be lost.
    Omit file names, paths, code snippets, and inventories of symbols or implementation steps. Include a
    detail only when it materially changes the reader's understanding of the behavior, scope, or constraint.
    Match the explanation to the substance of the input. Small or simple inputs usually need very little
    text. A subtle change may need more explanation than its code length suggests.
    Ground every claim in the supplied evidence. Explain intent only when supported; do not invent
    motivations, benefits, or guarantees. Stop once the essential meaning is clear.
""".trimIndent()

/** The focus line after cacheable summary instructions, before optional query context; empty without a focus. */
internal fun summaryFocusLine(focus: String): String = if (focus.isEmpty()) "" else "\n\nThe user asked to focus on: ${focus.clipLine(300)}"

/**
 * Writes the summary tree bottom up. Code walks the tree in waves of ready nodes (leaves, then branches whose children
 * are done, up to the root), batches each wave into JSON requests and saves every answer as it arrives, so the model
 * never pages, tracks progress or picks nodes. Leaves (files, or parts of large files) carry their [evidence] inline;
 * only a request whose evidence was cut gets [readTool] to read the rest. Folders, groups, files with parts and the root
 * are written from their children's summaries alone.
 */
class ChangeSummarizer internal constructor(
    private val complete: suspend (CompletionRequest) -> Completion,
    private val readTool: ChatTool?,
    private val options: ChangeSummaryOptions = ChangeSummaryOptions(),
    internal val evidence: SummaryEvidence = DiffSummaryEvidence(options),
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
        /** Limits generation to these nodes; existing text outside them is only context. */
        nodeIds: Set<Int>? = null,
        /** Adds query context without changing the normal summary instructions or the explicit /summaries focus. */
        queryContext: String = "",
        /** Optional instructions for this regeneration, preserved in full including paragraph breaks. */
        instructions: String = "",
        reuseSingleChildren: Boolean = true,
    ): ChangeSummaryRun {
        var current = start
        val tries = HashMap<Int, Int>()
        val requests = AtomicInteger()
        var error: String? = null
        val mutex = Mutex()
        val context = if (queryContext.isBlank()) "" else "\n\n" + """
            The reader is exploring this query: ${queryContext.clipLine(2_000)}
            Use the query as context to ground these summaries in the responsibilities and behavior relevant to it.
            Keep writing summaries of the supplied evidence, rather than answering the query as a question.
            Include query-relevant details only when essential to understanding the main behavior; keep the
            summary brief and do not invent a connection.
        """.trimIndent()
        val custom = instructions.trim().takeIf { it.isNotEmpty() }
            ?.let { "\n\nUser instructions for this summary generation:\n$it" }.orEmpty()
        val leafSystem = evidence.leafSystem(start.tree, focus) + context + custom
        val branchSystem = evidence.branchSystem(focus) + context + custom
        val topicSystem = evidence.branchSystem("") + "\n\n" + TOPIC_SUMMARY_GUIDANCE + summaryFocusLine(focus) + context + custom
        suspend fun progress() = onProgress(
            nodeIds?.count { current.text(it) != null } ?: current.count, nodeIds?.size ?: current.total)
        progress()
        while (true) {
            val ready = current.ready().filter { (nodeIds == null || it in nodeIds) && (tries[it] ?: 0) < options.attempts }.sorted()
            if (ready.isEmpty()) break
            val (reused, asked) = ready.map { current.tree[it] }.partition { reuseSingleChildren && it.children.size == 1 }
            if (reused.isNotEmpty()) {
                val texts = reused.associate { it.id to current.text(it.children.single())!! }
                mutex.withLock {
                    current = save(texts)
                    progress()
                }
                continue
            }
            asked.forEach { tries.merge(it.id, 1, Int::plus) }
            val (leaves, branches) = asked.partition { it.children.isEmpty() }
            val (topics, otherBranches) = branches.partition { it.isTopicBranch }
            val batches = leafBatches(leaves).map { it to leafSystem } +
                otherBranches.chunked(options.foldersPerRequest).map { it to branchSystem } +
                topics.chunked(options.foldersPerRequest).map { it to topicSystem }
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
                    progress()
                }
            }
            // Missing nodes remain eligible until their attempt budget is spent, even after an empty wave.
        }
        val complete = nodeIds?.all { current.text(it) != null } ?: current.complete
        return ChangeSummaryRun(current, requests.get(), error.takeIf { !complete })
    }

    private fun cut(node: SummaryNode): Boolean = evidence.size(node) > options.patchChars

    /**
     * Leaves in tree order, so siblings share a request. Cut evidence gets requests of its own at the end, the only
     * ones with tools, so the tool-free requests run together without tool definitions breaking their cached prefix.
     */
    private fun leafBatches(leaves: List<SummaryNode>): List<List<SummaryNode>> {
        val batches = mutableListOf<List<SummaryNode>>()
        val cut = mutableListOf<List<SummaryNode>>()
        var batch = mutableListOf<SummaryNode>()
        var chars = 0
        for (node in leaves) {
            val size = evidence.size(node)
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
        val answer = if (batch.any(::cut) && readTool != null) {
            val reply = ToolChatRunner(counted, listOf(readTool), maxToolRounds = options.maxToolRounds)
                .run(listOf(LlmMessage.system(system), LlmMessage.user("$user\n\n$JSON_ONLY")), onToolCall).reply
            parseJsonObjectLeniently(reply) ?: throw IllegalStateException("The summary answer is not a JSON object: ${reply.take(200)}")
        } else completeJson(counted, system, user, SCHEMA, maxTokens = 4096 + 512 * batch.size)
        return decode(answer, batch.size).mapKeys { (position, _) -> batch[position - 1].id }
    }

    /** What the model sees of one node: a leaf's evidence, or a branch's children with their summaries. */
    private fun item(position: Int, node: SummaryNode, summaries: ChangeSummaries) = JsonObject().apply {
        addProperty("id", position)
        addProperty("kind", node.requestKind)
        if (node.isTopicBranch) addProperty("topic_path", node.path)
        else addProperty("location", node.location)
        if (node.children.isEmpty()) evidence.describe(node, this, options.patchChars)
        else {
            addProperty("files", node.files.size)
            add("children", JsonArray().apply {
                node.children.forEach { id ->
                    val child = summaries.tree[id]
                    add(JsonObject().apply {
                        addProperty("kind", child.requestKind)
                        if (child.isTopicBranch) addProperty("topic_path", child.path)
                        addProperty("label", child.label)
                        addProperty("summary", summaries.text(id))
                    })
                }
            })
        }
    }

    companion object {
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

private val SummaryNode.requestKind: String
    get() = if (isTopicBranch && kind == SummaryKind.DIRECTORY) "topic" else kind.id

private val TOPIC_SUMMARY_GUIDANCE = """
    This request describes an existing topic hierarchy. A topic (kind "topic") groups files by code
    responsibility or subject. topic_path gives its levels from broad to specific, separated by >;
    groups within a topic carry that topic_path too. An empty topic_path denotes the top level:
    the root covers all topics, while groups there cover only their listed children.
    Summarize the children's main responsibilities or changes using their summaries as evidence.
    Topic labels provide context, not additional evidence. Do not create or assign topics.
""".trimIndent()

internal fun changedLines(file: ReviewFile): String =
    "+${file.patch.lineSequence().count { it.startsWith('+') }} -${file.patch.lineSequence().count { it.startsWith('-') }}"

internal fun summariesFocus(text: String): String = text.trim().substringAfter(" ", "").trim()

/** The [summariesReply] hint for /topic-summaries, whose unsummarized topics show in Hierarchy rather than the Summaries card. */
internal const val TOPIC_SUMMARIES_MISSING = "Topics without summaries show file names in Hierarchy. Click Fill summaries to continue."

/**
 * The chat reply to /summaries, written by code: the root summary, or what is still missing and why.
 * [empty] answers a tree without files; [missing] tells where unsummarized nodes show.
 */
internal fun summariesReply(run: ChangeSummaryRun, empty: String = "There are no changed files to summarize.",
    missing: String = "They show their names in the Summaries card."): String {
    val summaries = run.summaries
    val requests = "${run.requests} request${if (run.requests == 1) "" else "s"}"
    if (summaries.total == 0) return empty
    if (summaries.complete) return "${summaries.text(summaries.tree.root.id)}\n\n[${summaries.total} summaries · $requests]"
    return "Summarized ${summaries.count} of ${summaries.total} tree nodes with $requests.\n\n[No summary for ${summaries.missing().size} nodes. " +
        "$missing${run.error?.let { " Last failure: ${it.take(300)}" } ?: ""}]"
}
