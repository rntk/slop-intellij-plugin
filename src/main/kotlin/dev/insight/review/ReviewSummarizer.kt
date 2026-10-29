package dev.insight.review

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.logger
import dev.insight.chat.ChatTool
import dev.insight.chat.ToolCallEvent
import dev.insight.chat.ToolChatRunner
import dev.insight.enrichment.JsonSchemas
import dev.insight.enrichment.clipLine
import dev.insight.enrichment.completeJson
import dev.insight.enrichment.forEachConcurrently
import dev.insight.enrichment.parseJsonObjectLeniently
import dev.insight.llm.CompletionFn
import dev.insight.llm.LlmApiException
import dev.insight.llm.LlmMessage
import dev.insight.llm.LlmTruncatedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val LOG = logger<ReviewSummarizer>()

data class ReviewSummaryOptions(
    /** Evidence characters per page; diff pages are all summarized before reducing the whole file. */
    val patchChars: Int = 6_000,
    /** Inlined evidence characters per request, so small files share one request. */
    val batchChars: Int = 24_000,
    val filesPerRequest: Int = 8,
    val foldersPerRequest: Int = 4,
    val concurrency: Int = 2,
    /** Requests per node before it is given up; the folders above it then keep their names. */
    val attempts: Int = 5,
    /** Tool rounds for a request whose evidence was cut. */
    val maxToolRounds: Int = 100,
    /** Characters of the file overview supplied by an evidence adapter. */
    val overviewChars: Int = 4_000,
)

data class ReviewSummaryRun(val summaries: ReviewSummaries, val requests: Int, val error: String?)

/** What a summary request shows of a leaf (a file, or a part of a large file) and the instructions around it. */
internal interface SummaryEvidence {
    /**
     * Inline characters of a leaf's evidence, for batching. Above [ReviewSummaryOptions.patchChars] the evidence is cut,
     * so the leaf gets a request of its own, with the read tool when there is one.
     */
    fun size(node: SummaryNode): Int
    /** The numbers of a leaf's evidence lines that its summary may cite, as [describe] numbers them; empty when it has none. */
    fun rows(node: SummaryNode): IntRange
    /** Changed patch rows, or null for source evidence where every row describes current code. */
    fun changedRows(node: SummaryNode): Set<Int>? = null
    /** Evidence provenance, retained when children are condensed. */
    fun evidenceKind(node: SummaryNode): String? = null
    /**
     * Adds a leaf's evidence to its request [item], which already holds id, kind and location; inlines at most [limit]
     * characters. Every evidence line starts with its number from [rows] and "|".
     */
    fun describe(node: SummaryNode, item: JsonObject, limit: Int)
    /** Complete, bounded pages for a large leaf; null retains the adapter's normal request path. */
    fun pages(node: SummaryNode, limit: Int): List<SummaryEvidencePage>? = null
    /** Instructions shared by every leaf request of a run, with the per-run focus last so the prefix stays cached. */
    fun leafSystem(tree: ReviewSummaryTree, focus: String): String
    /** Instructions for nodes written from their children's summaries: folders, groups, files with parts, topics, the root. */
    fun branchSystem(focus: String): String
}

internal data class SummaryEvidencePage(val evidence: JsonObject, val rows: IntRange, val changedRows: Set<Int>? = null)

/** Shared writing policy for source and diff summaries at every level of the tree. */
internal val SUMMARY_WRITING_GUIDANCE = """
    State the main purpose, responsibility, or behavioral change in plain text and plain language.
    Select the one or two dominant behaviors or changes, not every edit. Write at most two short sentences, normally
    under 50 words. A phrase suffices for a trivial item. Omit subordinate implementation details even when citable.
    Omit file names, paths, code snippets, and inventories of symbols or steps. Keep only details that materially
    clarify behavior, scope, or constraints; subtle inputs may need more explanation than their size suggests.
    Ground every claim in supplied evidence; do not invent intent, benefits, or guarantees. Stop when the meaning is clear.
""".trimIndent()

/** The answer shape of every summary request, for the instructions; [ReviewSummarizer.SCHEMA] enforces it where supported. */
internal const val SUMMARY_JSON_SHAPE = """{"summaries":[{"id":1,"segments":[{"text":"…","from":["…"]}]}]}"""

/** How a summary is split into segments, shared by every request. */
internal val SUMMARY_SEGMENT_GUIDANCE = """
    Return one to three cited segments whose texts join into one brief summary, including spaces and punctuation.
    Split only where the supporting evidence changes. Keep conjunctions and punctuation inside cited clauses.
    Substantive claims need citations; connecting words or punctuation alone may have empty "from".
""".trimIndent()

/** Citations of a leaf's own evidence lines. */
internal val LEAF_CITATION_GUIDANCE = """
    Evidence lines start with their number and "|". Each claim's "from" must cite supporting line numbers or
    "first-last" ranges, not the whole node. Describe and cite only the node's own evidence; other nodes are separate
    items, even when related.
    Always return the complete response envelope, even for one node. Example:
    {"summaries":[{"id":1,"segments":[{"text":"Retries failed uploads up to three times","from":["12-18"]},
    {"text":" and logs each failure.","from":["20"]}]}]}
""".trimIndent()

/** Citations of the children's claims, which link a summary to the summaries it condenses. */
internal val BRANCH_CITATION_GUIDANCE = """
    Return exactly the ids in "required_output_ids", one answer per object directly inside "nodes".
    Children are evidence, never output nodes. Their source labels C1, C2, etc. are local to their enclosing node.
    Cite a supplied claim label such as "C1.S2" for a specific claim, or "C1" only when condensing that child's
    whole summary. Every substantive segment needs support; it need not cover or cite every child.
    Copy labels exactly from the input. Never invent labels, fill gaps, or expand a numbered sequence.
    Cite only the claims needed to support the selected themes; do not enumerate all sources.
    Select one or two dominant themes. Omit helpers, imports, renames and routine test updates unless they are
    the only changes. Preserve whether tests are added, updated or removed; do not infer comprehensive coverage.
    Test evidence describes coverage, and documentation evidence describes documentation; neither establishes a new
    runtime feature. A folder label names a location, not the component that performs the described behavior.
""".trimIndent()

/** The focus line after cacheable summary instructions, before optional query context; empty without a focus. */
internal fun summaryFocusLine(focus: String): String = if (focus.isEmpty()) "" else "\n\nThe user asked to focus on: ${focus.clipLine(300)}"

/**
 * Writes the summary tree bottom up. Code walks the tree in waves of ready nodes (leaves, then branches whose children
 * are done, up to the root), batches each wave into JSON requests and saves every answer as it arrives, so the model
 * never pages, tracks progress or picks nodes. Leaves (files, or parts of large files) carry their [evidence] inline;
 * adapters may supply complete pages for a large leaf, reduced to a file summary with citations to original rows.
 * Other cut evidence may use [readTool]. Branches are written from their children's summaries alone.
 */
class ReviewSummarizer internal constructor(
    private val complete: CompletionFn,
    private val readTool: ChatTool?,
    private val options: ReviewSummaryOptions = ReviewSummaryOptions(),
    internal val evidence: SummaryEvidence,
) {
    /**
     * [save] stores a batch and returns the current summaries; it throws when the snapshot changed, which ends the run.
     * [onProgress] reports saved nodes initially and after each batch, serialized across concurrent requests.
     */
    suspend fun run(
        start: ReviewSummaries,
        focus: String,
        save: suspend (Map<Int, Summary>) -> ReviewSummaries,
        onToolCall: suspend (ToolCallEvent) -> Unit = {},
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
        /** Limits generation to these nodes; existing text outside them is only context. */
        nodeIds: Set<Int>? = null,
        /** Adds query context without changing the normal summary instructions or the explicit /summaries focus. */
        queryContext: String = "",
        /** Optional instructions for this regeneration, preserved in full including paragraph breaks. */
        instructions: String = "",
        reuseSingleChildren: Boolean = true,
    ): ReviewSummaryRun {
        var current = start
        val tries = HashMap<Int, Int>()
        // Why a node's last answer was rejected, shown on its next request so a retry knows what to fix.
        val feedback = ConcurrentHashMap<Int, String>()
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
            val reusable = if (!reuseSingleChildren) emptyMap() else ready.mapNotNull { id ->
                val children = current.tree[id].children.map { current.summary(it)!! }
                val first = children.firstOrNull() ?: return@mapNotNull null
                // Identical prose with identical segment boundaries can cite every child without another rewrite.
                if (children.all { child -> child.segments.map { it.text } == first.segments.map { it.text } && child.claims == first.claims })
                    id to first.relay(children.size) else null
            }.toMap()
            val (reused, asked) = ready.map { current.tree[it] }.partition { it.id in reusable }
            if (reused.isNotEmpty()) {
                mutex.withLock {
                    reusable.entries.chunked(ReviewSummaries.MAX_PER_CALL).forEach { batch ->
                        current = save(batch.associate { it.key to it.value })
                    }
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
            suspend fun processBatch(batch: List<SummaryNode>, system: String) {
                val texts = try {
                    request(system, branchSystem, batch, current, requests, feedback, onToolCall)
                } catch (e: LlmTruncatedException) {
                    if (batch.size > 1) {
                        LOG.debug("Summary output truncated; retrying in halves", e)
                        // Save each successful half immediately, even if a later half fails or is cancelled.
                        for (part in batch.chunked((batch.size + 1) / 2)) processBatch(part, system)
                        return
                    }
                    // A singleton cannot shrink; let the outer loop consume its remaining attempts.
                    LOG.warn("Summary request failed", e)
                    mutex.withLock { error = e.message ?: e.javaClass.simpleName }
                    emptyMap()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LlmApiException) {
                    // The server rejected the request; the next one would fail the same way.
                    throw e
                } catch (e: Exception) {
                    LOG.warn("Summary request failed", e)
                    mutex.withLock { error = e.message ?: e.javaClass.simpleName }
                    emptyMap()
                }
                if (texts.isNotEmpty()) mutex.withLock {
                    current = save(texts)
                    progress()
                }
            }
            forEachConcurrently(batches, options.concurrency) { (batch, system) -> processBatch(batch, system) }
            // Missing nodes remain eligible until their attempt budget is spent, even after an empty wave.
        }
        val complete = nodeIds?.all { current.text(it) != null } ?: current.complete
        return ReviewSummaryRun(current, requests.get(), error.takeIf { !complete })
    }

    private fun cut(node: SummaryNode): Boolean = evidence.size(node) > options.patchChars

    /** Read every page before reducing its claims; intermediate citations resolve back to original evidence rows. */
    private suspend fun summarizePages(
        system: String,
        branchSystem: String,
        node: SummaryNode,
        pages: List<SummaryEvidencePage>,
        complete: CompletionFn,
    ): Summary {
        suspend fun ask(prompt: String, item: JsonObject, allowed: Citations): Summary {
            var problem = "No usable summary returned."
            repeat(options.attempts) {
                val input = JsonObject().apply {
                    add("required_output_ids", JsonArray().apply { add(1) })
                    add("nodes", JsonArray().apply { add(item) })
                }
                val answer = completeJson(complete, prompt, input.toString(), schema(1), maxTokens = 4864)
                val decoded = decode(answer, listOf(allowed))
                decoded.summaries[1]?.let { return it }
                problem = decoded.problems[1] ?: "Missing summary for id 1."
                item.addProperty("previous_answer_rejected", problem)
            }
            error(problem)
        }
        var summaries = pages.mapIndexed { index, page ->
            val item = page.evidence.deepCopy().apply {
                addProperty("id", 1)
                addProperty("kind", "chunk")
                addProperty("location", node.location)
                addProperty("part", "${index + 1} of ${pages.size}")
            }
            ask(system, item, Citations.Lines(page.rows, page.changedRows))
        }
        // Bound reduction requests too: a very large patch must not produce an unbounded final prompt.
        while (summaries.size > 1) {
            summaries = summaries.chunked(options.foldersPerRequest.coerceAtLeast(2)).map { children ->
                if (children.size == 1) children.single() else {
                    val item = JsonObject().apply {
                        addProperty("id", 1)
                        addProperty("kind", "file")
                        addProperty("location", node.location)
                        evidence.evidenceKind(node)?.let { addProperty("evidence_kind", it) }
                        add("children", JsonArray().apply {
                            children.forEachIndexed { index, summary -> add(JsonObject().apply {
                                addProperty("child", "C${index + 1}")
                                evidence.evidenceKind(node)?.let { addProperty("evidence_kind", it) }
                                add("claims", summary.claimsJson(index, labeled = true))
                            }) }
                        })
                    }
                    val combined = ask(branchSystem, item, Citations.Children(children.map { it.claims }))
                    Summary(combined.segments.map { segment ->
                        segment.copy(sources = segment.sources.flatMap { source ->
                            val claim = source as SummarySource.Claim
                            children[claim.child].segments[claim.segment].sources
                        }.distinct())
                    })
                }
            }
        }
        return summaries.single()
    }

    /**
     * Leaves in tree order, so siblings share a request. Large leaves are handled separately at the end,
     * using adapter pages when available and otherwise the read tool.
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

    /**
     * Answers by node id; nodes the model skipped, answered twice or with an uncited claim are left for the next wave, and
     * [feedback] records why for their next request.
     */
    private suspend fun request(
        system: String,
        branchSystem: String,
        batch: List<SummaryNode>,
        summaries: ReviewSummaries,
        requests: AtomicInteger,
        feedback: MutableMap<Int, String>,
        onToolCall: suspend (ToolCallEvent) -> Unit,
    ): Map<Int, Summary> {
        val counted: CompletionFn = { requests.incrementAndGet(); complete(it) }
        if (batch.size == 1 && batch.single().children.isEmpty()) {
            val node = batch.single()
            evidence.pages(node, options.patchChars)?.let { pages ->
                return mapOf(node.id to summarizePages(system, branchSystem, node, pages, counted))
            }
        }
        val user = JsonObject().apply {
            add("required_output_ids", JsonArray().apply { batch.indices.forEach { add(it + 1) } })
            add("nodes", JsonArray().apply { batch.forEachIndexed { index, node -> add(item(index + 1, node, summaries, feedback[node.id])) } })
        }.toString()
        val answer = if (batch.any(::cut) && readTool != null) {
            val reply = ToolChatRunner(counted, listOf(readTool), maxToolRounds = options.maxToolRounds)
                .run(listOf(LlmMessage.system(system), LlmMessage.user("$user\n\n$JSON_ONLY")), onToolCall).reply
            parseJsonObjectLeniently(reply) ?: throw IllegalStateException("The summary answer is not a JSON object: ${reply.take(200)}")
        } else completeJson(counted, system, user, schema(batch.size), maxTokens = 4096 + 768 * batch.size,
            retryTruncated = batch.size == 1)
        val decoded = decode(answer, batch.map { node -> citations(node, summaries) })
        batch.forEachIndexed { index, node ->
            when (val problem = decoded.problems[index + 1]) {
                null -> if (index + 1 in decoded.summaries) feedback.remove(node.id)
                    else feedback[node.id] = "Missing usable summary for id ${index + 1}; return every required_output_id with nonempty segments."
                else -> feedback[node.id] = problem
            }
        }
        return decoded.summaries.mapKeys { (position, _) -> batch[position - 1].id }
    }

    /** How a node's answer may cite: its own evidence lines for a leaf, its children's claims for a branch. */
    private fun citations(node: SummaryNode, summaries: ReviewSummaries): Citations =
        if (node.children.isEmpty()) Citations.Lines(evidence.rows(node), evidence.changedRows(node))
        else Citations.Children(node.children.map { summaries.summary(it)?.claims.orEmpty() })

    /** What the model sees of one node: a leaf's numbered evidence, or a branch's children with their claims, and why its last answer was [rejected]. */
    private fun item(position: Int, node: SummaryNode, summaries: ReviewSummaries, rejected: String?) = JsonObject().apply {
        addProperty("id", position)
        addProperty("kind", node.requestKind)
        evidence.evidenceKind(node)?.let { addProperty("evidence_kind", it) }
        if (node.isTopicBranch) addProperty("topic_path", node.path)
        else addProperty("location", node.location)
        rejected?.let { addProperty("previous_answer_rejected", it) }
        if (node.children.isEmpty()) evidence.describe(node, this, options.patchChars)
        else {
            addProperty("files", node.files.size)
            add("children", JsonArray().apply {
                node.children.forEachIndexed { index, id ->
                    val child = summaries.tree[id]
                    add(JsonObject().apply {
                        addProperty("child", "C${index + 1}")
                        addProperty("kind", child.requestKind)
                        evidence.evidenceKind(child)?.let { addProperty("evidence_kind", it) }
                        if (child.isTopicBranch) addProperty("topic_path", child.path)
                        addProperty("label", child.label)
                        summaries.summary(id)?.let { add("claims", it.claimsJson(index, labeled = true)) }
                    })
                }
            })
        }
    }

    /** What one node's "from" entries may refer to. */
    internal sealed interface Citations {
        /** Evidence lines numbered [rows]; empty when the leaf has nothing to cite. */
        data class Lines(val rows: IntRange, val changedRows: Set<Int>? = null) : Citations
        /** The claim segments of each child, by position. */
        data class Children(val claims: List<List<Int>>) : Citations

        val citable: Boolean get() = when (this) {
            is Lines -> !rows.isEmpty()
            is Children -> claims.any { it.isNotEmpty() }
        }

        /** The sources one "from" entry names; nothing for an entry outside the evidence or the children's claims. */
        fun parse(entry: String): List<SummarySource> {
            val value = entry.trim().removePrefix("[").removeSuffix("]").trim()
            return when (this) {
                is Lines -> {
                    val match = LINES.matchEntire(value) ?: return emptyList()
                    val first = match.groupValues[1].toIntOrNull() ?: return emptyList()
                    val last = if (match.groupValues[2].isEmpty()) first else match.groupValues[2].toIntOrNull() ?: return emptyList()
                    if (first in rows && last in rows && first <= last) listOf(SummarySource.Rows(first, last)) else emptyList()
                }
                is Children -> {
                    val match = CLAIM.matchEntire(value) ?: return emptyList()
                    val child = (match.groupValues[1].toIntOrNull() ?: return emptyList()) - 1
                    val claims = claims.getOrNull(child) ?: return emptyList()
                    val segment = if (match.groupValues[2].isEmpty()) null else
                        (match.groupValues[2].toIntOrNull() ?: return emptyList()) - 1
                    if (segment == null) claims.map { SummarySource.Claim(child, it) }
                    else if (segment in claims) listOf(SummarySource.Claim(child, segment)) else emptyList()
                }
            }
        }

        private companion object {
            val LINES = Regex("""(\d+)(?:\s*[-–]\s*(\d+))?""")
            val CLAIM = Regex("""C?(\d+)(?:\.S?(\d+))?""")
        }
    }

    companion object {
        private const val JSON_ONLY = "Answer with only the JSON object, no other text."

        internal val SCHEMA = JsonSchemas.obj("summaries" to JsonSchemas.array(JsonSchemas.obj(
            "id" to JsonSchemas.integer(),
            "segments" to JsonSchemas.array(JsonSchemas.obj(
                "text" to JsonSchemas.string(),
                "from" to JsonSchemas.array(JsonSchemas.string()),
            )),
        )))

        internal fun schema(count: Int): JsonObject = SCHEMA.deepCopy().apply {
            val summaries = getAsJsonObject("properties").getAsJsonObject("summaries")
            summaries.addProperty("minItems", count)
            summaries.addProperty("maxItems", count)
            summaries.getAsJsonObject("items").getAsJsonObject("properties").getAsJsonObject("id")
                .add("enum", JsonArray().apply { (1..count).forEach { add(it) } })
        }

        /** Accepted summaries and, for rejected answers, what was wrong; both by 1-based position. */
        internal data class Decoded(val summaries: Map<Int, Summary>, val problems: Map<Int, String>)

        /**
         * Summaries by 1-based position. Unexpected output ids reject the batch; duplicate ids, invalid citations and
         * uncited claims reject the affected nodes. Only punctuation and connectors may go uncited.
         */
        internal fun decode(answer: JsonObject, citations: List<Citations>): Decoded {
            val entries = answer.get("summaries")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: return Decoded(emptyMap(), citations.indices.associate { (it + 1) to
                    "Missing summaries array; return the complete envelope: $SUMMARY_JSON_SHAPE." })
            val unexpected = entries.any { element ->
                val id = element.takeIf { it.isJsonObject }?.asJsonObject?.get("id")
                val number = id?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
                number == null || number % 1.0 != 0.0 || number < 1 || number > citations.size
            }
            if (unexpected) return Decoded(emptyMap(), citations.indices.associate { (it + 1) to
                "Unexpected output id. Return only required_output_ids ${(1..citations.size).toList()}; children are evidence, not output nodes." })
            val result = HashMap<Int, Summary>()
            val problems = HashMap<Int, String>()
            val duplicates = hashSetOf<Int>()
            for (element in entries) {
                val entry = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val id = entry.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
                    ?.takeIf { it % 1.0 == 0.0 && it >= 1 && it <= citations.size }?.toInt() ?: continue
                val allowed = citations[id - 1]
                val uncited = mutableListOf<String>()
                val segments = entry.get("segments")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { item ->
                    val segment = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    val text = segment.get("text")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                    val from = segment.get("from")?.takeIf { it.isJsonArray }?.asJsonArray
                        ?.mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString }.orEmpty()
                    val invalid = from.filter { allowed.parse(it).isEmpty() }
                    if (invalid.isNotEmpty()) uncited += "Invalid citations: " + invalid.joinToString() +
                        ". Use only supplied source labels or ascending line ranges entirely within this page."
                    val sources = from.flatMap(allowed::parse).distinct()
                    if (allowed is Citations.Lines && !allowed.changedRows.isNullOrEmpty() && sources.isNotEmpty() && !connector(text) &&
                        sources.none { source -> source is SummarySource.Rows && allowed.changedRows.any { it in source.first..source.last } })
                        uncited += "\"${text.trim().clipLine(80)}\" cites only unchanged context; cite an added or removed patch row establishing the change"
                    if (allowed.citable && sources.isEmpty() && !connector(text)) uncited += if (from.isEmpty())
                        "\"${text.trim().clipLine(80)}\" cites nothing" else "\"${text.trim().clipLine(80)}\" cites ${from.joinToString()}, " +
                            "which ${if (allowed is Citations.Lines) "are not lines of this node's evidence" else "are not claims of this node's children"}"
                    SummarySegment(text, sources)
                } ?: continue
                val summary = Summary.of(segments) ?: continue
                if (uncited.isEmpty() && allowed.citable && summary.segments.all { it.sources.isEmpty() }) uncited += "no segment cites anything"
                if (uncited.isNotEmpty()) {
                    problems[id] = "Every claim must cite this node's own ${if (allowed is Citations.Lines) "evidence lines" else "child claims"}, " +
                        "but ${uncited.joinToString("; ")}."
                    continue
                }
                if (result.put(id, summary) != null) duplicates += id
            }
            // Two answers for one node cannot both describe it; neither is trusted.
            duplicates.forEach { result.remove(it); problems[it] = "The node was answered more than once; answer it exactly once." }
            return Decoded(result, problems)
        }

        /** Punctuation alone, or a few connecting words such as " and " or ", while " that end no sentence. */
        internal fun connector(text: String): Boolean {
            val words = WORD.findAll(text).map { it.value.lowercase() }.toList()
            if (words.isEmpty()) return true
            return words.size <= 3 && words.all { it in CONNECTOR_WORDS } && text.trimEnd().lastOrNull()?.let { it in ".!?" } != true
        }

        private val WORD = Regex("""[\p{L}\p{N}]+""")
        private val CONNECTOR_WORDS = setOf(
            "a", "an", "the", "and", "or", "but", "nor", "of", "to", "in", "on", "at", "by", "for", "with", "from", "into", "onto",
            "as", "is", "are", "was", "were", "be", "been", "it", "its", "this", "that", "these", "those", "so", "than", "then",
            "via", "per", "also", "both", "each", "all", "any", "which", "who", "what", "when", "where", "while", "yet", "not",
        )
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

internal fun summariesFocus(text: String): String = text.trim().substringAfter(" ", "").trim()

/** The [summariesReply] hint for /topic-summaries, whose unsummarized topics show in Hierarchy rather than the Summaries card. */
internal const val TOPIC_SUMMARIES_MISSING = "Topics without summaries show file names in Hierarchy. Click Fill summaries to continue."

/**
 * The chat reply to /summaries, written by code: the root summary, or what is still missing and why.
 * [empty] answers a tree without files; [missing] tells where unsummarized nodes show.
 */
internal fun summariesReply(run: ReviewSummaryRun, empty: String = "There are no changed files to summarize.",
    missing: String = "They show their names in the Summaries card."): String {
    val summaries = run.summaries
    val requests = "${run.requests} request${if (run.requests == 1) "" else "s"}"
    if (summaries.total == 0) return empty
    if (summaries.complete) return "${summaries.text(summaries.tree.root.id)}\n\n[${summaries.total} summaries · $requests]"
    return "Summarized ${summaries.count} of ${summaries.total} tree nodes with $requests.\n\n[No summary for ${summaries.missing().size} nodes. " +
        "$missing${run.error?.let { " Last failure: ${it.take(300)}" } ?: ""}]"
}
