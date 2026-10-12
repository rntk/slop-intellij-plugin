package dev.insight.enrichment

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.insight.callgraph.CallGraph
import dev.insight.callgraph.CallGraphEdge
import dev.insight.callgraph.RelationKind
import dev.insight.callgraph.SymbolKind
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Closed vocabulary for what a function contributes to its callers' operation; small models choose far better than they write. */
enum class FunctionRole(val id: String) {
    CORE("core"), VALIDATION("validation"), ERROR_HANDLING("error-handling"), LOGGING("logging"), PLUMBING("plumbing"), UTILITY("utility");
    companion object {
        fun of(id: String?): FunctionRole? = entries.firstOrNull { it.id == id }
        /** Roles whose calls the compact chart folds into a count on the caller, unless they reach an [Effects.VISIBLE] effect. */
        val FOLDED = setOf(LOGGING, PLUMBING)
    }
}

/**
 * A model interpretation of one function, written from its source excerpt and its callees' summaries (bottom-up).
 * [single] means callers can read the function as one step; the compact chart does not open it automatically.
 */
data class FunctionSummary(val label: String, val description: String?, val role: FunctionRole?, val single: Boolean)

/**
 * Side effects derived from rule tags, never from the model: [own] by the function's source, [reached] by it or any
 * in-project function it calls (transitively). A summary label may omit a write; these badges cannot.
 */
data class Effects(val own: Set<String>, val reached: Set<String>) {
    companion object {
        /** Effects that propagate to callers; a folded helper that reaches one stays visible. */
        val VISIBLE = listOf("storage", "network")
        const val THROWS = "throws"
        private val throws = Regex("(?<![A-Za-z0-9_])(throw|raise)(?![A-Za-z0-9_])")

        /** Rule effects for every function with facts, propagated along in-project [calls] in callee-first order. */
        fun compute(graph: CallGraph, calls: Map<Int, List<CallGraphEdge>>, facts: Map<Int, SymbolFacts>, heuristics: Map<Int, Heuristics>): Map<Int, Effects> {
            val own = facts.keys.associateWith { id ->
                buildSet {
                    val tags = heuristics[id]?.tags.orEmpty()
                    VISIBLE.forEach { if ("concern:$it" in tags) add(it) }
                    if (facts[id]?.kind == SymbolKind.FUNCTION && throws.containsMatchIn(facts.getValue(id).code)) add(THROWS)
                }
            }
            val functions = own.keys.filter { graph.nodes.getOrNull(it)?.inProject == true }
            val reached = HashMap<Int, Set<String>>()
            for (component in stronglyConnected(functions) { id -> calls[id].orEmpty().map { it.callee }.filter(own::containsKey) }) {
                val union = component.flatMapTo(hashSetOf()) { id -> own.getValue(id).filter { it in VISIBLE } }
                component.forEach { id -> calls[id].orEmpty().forEach { reached[it.callee]?.let(union::addAll) } }
                component.forEach { reached[it] = union }
            }
            return own.mapValues { (id, effects) -> Effects(effects, reached[id].orEmpty() + effects.filter { it in VISIBLE }) }
                .filterValues { it.own.isNotEmpty() || it.reached.isNotEmpty() }
        }
    }
}

/**
 * Strongly connected components of [nodes] (edges by [next]) in reverse topological order: every component comes after
 * the components it reaches, so callees come before callers. Iterative Tarjan, safe for deep call chains.
 */
fun stronglyConnected(nodes: Collection<Int>, next: (Int) -> List<Int>): List<List<Int>> {
    val index = HashMap<Int, Int>()
    val low = HashMap<Int, Int>()
    val onStack = hashSetOf<Int>()
    val stack = ArrayDeque<Int>()
    val result = mutableListOf<List<Int>>()
    val known = nodes.toHashSet()
    for (start in nodes) {
        if (start in index) continue
        // Frames of the explicit DFS: node and the iterator over its successors.
        val work = ArrayDeque<Pair<Int, Iterator<Int>>>()
        fun visit(node: Int) {
            index[node] = index.size; low[node] = index.getValue(node)
            stack.addLast(node); onStack += node
            work.addLast(node to next(node).filter(known::contains).iterator())
        }
        visit(start)
        while (work.isNotEmpty()) {
            val (node, successors) = work.last()
            if (successors.hasNext()) {
                val successor = successors.next()
                if (successor !in index) visit(successor)
                else if (successor in onStack) low[node] = minOf(low.getValue(node), index.getValue(successor))
                continue
            }
            work.removeLast()
            work.lastOrNull()?.let { (parent, _) -> low[parent] = minOf(low.getValue(parent), low.getValue(node)) }
            if (low[node] == index[node]) {
                val component = mutableListOf<Int>()
                do { val member = stack.removeLast(); onStack -= member; component += member } while (member != node)
                result += component.sorted()
            }
        }
    }
    return result
}

data class SummaryOptions(
    /** Most visible functions summarized per run; the rest keep their names. Cached summaries are reused regardless. */
    val maxFunctions: Int = 4000,
    /** Functions per request: amortizes the system prompt, small enough for a local model's context. */
    val batchSize: Int = 6,
    val sourceChars: Int = 1200,
    val maxCallees: Int = 12,
    /** Functions without in-project callees and with at most this many non-blank source lines are not worth a request. */
    val trivialLines: Int = 3,
    val concurrency: Int = 2,
)

data class SummaryResult(val summaries: Map<Int, FunctionSummary>, val requests: Int, val cacheHits: Int, val failures: Int, val skipped: Int)

/**
 * Summarizes functions bottom-up: strongly connected components in callee-first order, grouped into waves whose
 * callees are all done, so every prompt carries the callees' summaries instead of their source. Prompts stay small
 * however deep the code goes. Each function is cached by its full source hash plus the summaries of its callees, so
 * an edit re-summarizes the function and only those callers whose callee summary actually changed. The model only
 * writes text and picks from closed vocabularies; ids are validated and nothing structural is taken from it.
 */
class FunctionSummarizer(
    private val complete: suspend (CompletionRequest) -> Completion,
    private val cache: EnrichmentCache,
    private val options: SummaryOptions = SummaryOptions(),
) {
    suspend fun summarize(
        input: EnrichmentInput,
        calls: Map<Int, List<CallGraphEdge>>,
        heuristics: Map<Int, Heuristics>,
        visibility: Map<Int, Double>,
        progress: EnrichmentProgress = EnrichmentProgress.NONE,
    ): SummaryResult {
        val graph = input.graph
        val functions = input.facts.values.filter { facts ->
            facts.kind == SymbolKind.FUNCTION && graph.nodes.getOrNull(facts.id)?.inProject == true &&
                "test" !in heuristics[facts.id]?.tags.orEmpty()
        }.map { it.id }
        val callees = functions.associateWith { id -> callees(calls, id).filter { it in input.facts } }
        val trivial = functions.filterTo(hashSetOf()) { id ->
            callees.getValue(id).none { it in callees } &&
                input.facts.getValue(id).code.lines().count { it.isNotBlank() } <= options.trivialLines
        }
        val chosen = (functions - trivial).sortedByDescending { visibility[it] ?: 0.0 }.take(options.maxFunctions).toHashSet()

        // Wave of a component: one more than the deepest wave among the chosen functions it calls.
        val wave = HashMap<Int, Int>()
        val waves = sortedMapOf<Int, MutableList<List<Int>>>()
        for (component in stronglyConnected(chosen) { id -> callees.getValue(id).filter(chosen::contains) }) {
            val members = component.toSet()
            val level = component.flatMap { callees.getValue(it) }.filter { it in chosen && it !in members }
                .maxOfOrNull { wave.getValue(it) + 1 } ?: 0
            component.forEach { wave[it] = level }
            waves.getOrPut(level) { mutableListOf() } += component
        }

        val summaries = ConcurrentHashMap<Int, FunctionSummary>()
        val requests = AtomicInteger()
        val hits = AtomicInteger()
        val failures = AtomicInteger()
        val done = AtomicInteger()
        for (components in waves.values) {
            val pending = mutableListOf<Pending>()
            // Only callees of earlier waves: summaries within a cycle would depend on order and destabilize cache keys.
            val lower = summaries.toMap()
            for (id in components.flatten()) {
                val item = item(graph, input.facts.getValue(id), callees.getValue(id), lower)
                val key = generativeCacheKey("function-summary", SYSTEM, SCHEMA.toString(), item.toString())
                val cached = cache.text(key)?.let(::parseJsonObjectLeniently)?.let(::decodeOne)
                if (cached != null) { summaries[id] = cached; hits.incrementAndGet(); done.incrementAndGet() }
                else pending += Pending(id, key, item)
            }
            progress.report("Summarizing functions", done.get(), chosen.size)
            // Pending functions stay in component order, so mutually recursive functions usually share a request.
            forEachConcurrently(pending.chunked(options.batchSize), options.concurrency) { batch ->
                val answer = request(batch, requests)
                for ((index, entry) in batch.withIndex()) {
                    val summary = answer?.get(index + 1)
                    if (summary == null) { failures.incrementAndGet(); continue }
                    summaries[entry.id] = summary
                    cache.putText(entry.key, encode(summary).toString())
                }
                progress.report("Summarizing functions", done.addAndGet(batch.size), chosen.size)
            }
        }
        cache.flush()
        return SummaryResult(summaries.toMap(), requests.get(), hits.get(), failures.get(), trivial.size + (functions.size - trivial.size - chosen.size))
    }

    private class Pending(val id: Int, val key: String, val item: JsonObject)

    /** Answers by 1-based position in [batch]; null when the request failed altogether. */
    private suspend fun request(batch: List<Pending>, requests: AtomicInteger): Map<Int, FunctionSummary>? {
        val user = JsonObject().apply {
            add("functions", JsonArray().apply {
                batch.forEachIndexed { index, entry -> add(entry.item.deepCopy().apply { addProperty("id", index + 1) }) }
            })
        }.toString()
        return try {
            requests.incrementAndGet()
            decode(completeJson(complete, SYSTEM, user, SCHEMA, maxTokens = 200 + 120 * batch.size, temperature = 0.0), batch.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** What the model sees of one function; also the cache identity, so callee summaries chain invalidation upwards. */
    private fun item(graph: CallGraph, facts: SymbolFacts, callees: List<Int>, known: Map<Int, FunctionSummary>) = JsonObject().apply {
        addProperty("name", FlowChartLens.title(graph, facts.id, facts))
        addProperty("path", facts.path)
        addProperty("signature", facts.signature.clipLine(300))
        facts.doc?.let { addProperty("doc", it.clipLine(300)) }
        addProperty("source", facts.code.take(options.sourceChars))
        addProperty("partial", facts.codeTruncated || facts.code.length > options.sourceChars)
        addProperty("hash", facts.sourceHash)
        add("calls", JsonArray().apply {
            callees.take(options.maxCallees).forEach { id ->
                val summary = known[id]
                val name = FlowChartLens.title(graph, id)
                add(if (summary == null) name else "$name: ${summary.label}" + (summary.role?.let { " [${it.id}]" } ?: ""))
            }
            if (callees.size > options.maxCallees) add("… ${callees.size - options.maxCallees} more")
        })
    }

    companion object {
        private fun callees(calls: Map<Int, List<CallGraphEdge>>, id: Int) =
            calls[id].orEmpty().filter { it.kind == RelationKind.CALL || it.kind == RelationKind.INHERITS }.map { it.callee }
                .filter { it != id }.distinct()

        internal val SYSTEM = """
            $CODE_AS_DATA
            Summarize each function for a flow chart of the application. "calls" lists the in-project functions it calls,
            with their own summaries where known; build on them rather than repeating them. For every function return:
            label: a short action phrase of 2 to 6 words, such as "Store user data" or "Validate order"; no code names.
            description: one sentence on what it achieves, supported by the source.
            role: core (does the work of a feature), validation (checks input or state), error-handling (reports or
            recovers from failures), logging (logs or traces), plumbing (wiring, delegation, conversion, getters),
            utility (generic reusable helper).
            single: true when callers can read it as one step without seeing inside it.
            Source may be partial; be conservative and never invent behavior that is not in the source or calls.
            Return every id exactly once. Return only JSON matching the schema.
        """.trimIndent()

        internal val SCHEMA = JsonSchemas.obj("functions" to JsonSchemas.array(JsonSchemas.obj(
            "id" to JsonSchemas.integer(),
            "label" to JsonSchemas.string(),
            "description" to JsonSchemas.string(),
            "role" to JsonSchemas.enum(FunctionRole.entries.map { it.id }),
            "single" to JsonSchemas.boolean(),
        )))

        /** Summaries by 1-based id; unknown, duplicate and unlabeled entries are dropped. */
        internal fun decode(answer: JsonObject, size: Int): Map<Int, FunctionSummary> {
            val entries = answer.get("functions")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyMap()
            val result = HashMap<Int, FunctionSummary>()
            val duplicates = hashSetOf<Int>()
            for (element in entries) {
                val entry = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val id = entry.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
                    ?.takeIf { it % 1.0 == 0.0 && it >= 1 && it <= size }?.toInt() ?: continue
                val summary = decodeOne(entry) ?: continue
                if (result.put(id, summary) != null) duplicates += id
            }
            // Two answers for one function cannot both describe it; neither is trusted.
            duplicates.forEach(result::remove)
            return result
        }

        internal fun decodeOne(entry: JsonObject): FunctionSummary? {
            val label = entry.jsonText("label")?.clipLine(60) ?: return null
            return FunctionSummary(label, entry.jsonText("description")?.clipLine(200), FunctionRole.of(entry.jsonText("role")),
                entry.get("single")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false)
        }

        internal fun encode(summary: FunctionSummary) = JsonObject().apply {
            addProperty("label", summary.label)
            summary.description?.let { addProperty("description", it) }
            summary.role?.let { addProperty("role", it.id) }
            addProperty("single", summary.single)
        }
    }
}
