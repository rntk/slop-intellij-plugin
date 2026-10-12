package dev.insight.enrichment

import com.google.gson.JsonObject
import dev.insight.callgraph.CallGraph
import dev.insight.callgraph.CallGraphEdge
import dev.insight.callgraph.RelationKind
import dev.insight.callgraph.SymbolKind
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class FlowOptions(
    /** Minimum [NodeAnnotation.entryPoint] for a function to start a flow; `entry:` heuristic tags also qualify. */
    val entryThreshold: Double = 0.55,
    /** Minimum [EdgeAnnotation.continuesOperation] for a call to stay within the flow. */
    val continueThreshold: Double = 0.4,
    /** Callees with at least this [NodeAnnotation.utility] are generic helpers and end the flow. */
    val utilityThreshold: Double = 0.7,
    val maxFlowNodes: Int = 40,
    val maxDepth: Int = 8,
    val maxFlows: Int = 60,
    val minFlowNodes: Int = 3,
    /** Tests are entry points too, but every test would start its own flow. */
    val includeTests: Boolean = false,
)

data class FlowResult(val flows: List<Flow>, val llmRequests: Int, val failures: Int)

/**
 * Finds semantic flows: from each entry point, a breadth-first walk along CALL edges that stays within the
 * caller's operation. A call is followed unless it is mostly on an error path, does not continue the operation,
 * or targets a generic utility (which is left out). Calls without an edge annotation are followed unless the callee
 * is a known utility. Calls to a base or interface method continue into its implementations (see [overrides]).
 * Another entry point reached this way is included as a leaf but not expanded: its own flow
 * covers what it does. Flows are then filtered, deduplicated and named by the generative model, or after their
 * entry symbol when there is no model or it fails.
 */
class FlowTracer(
    private val complete: (suspend (CompletionRequest) -> Completion)?,
    private val cache: EnrichmentCache,
    private val options: FlowOptions = FlowOptions(),
    private val concurrency: Int = 2,
) {
    suspend fun trace(
        input: EnrichmentInput,
        nodes: Map<Int, NodeAnnotation>,
        edges: Map<CallGraphEdge, EdgeAnnotation>,
        heuristics: Map<Int, Heuristics>,
        progress: EnrichmentProgress = EnrichmentProgress.NONE,
    ): FlowResult {
        val graph = input.graph
        val calls = flowCalls(graph, input.facts)
        val entries = flowEntries(graph, nodes, heuristics, options)
        val follows = flowFilter(graph, nodes, edges, options)

        val traced = entries.mapIndexedNotNull { index, entry ->
            progress.report("Tracing flows", index + 1, entries.size)
            val (members, flowEdges) = walkFlow(entry, calls, follows, entries, options)
            members.takeIf { it.size >= options.minFlowNodes }?.let { Traced(entry, it, flowEdges) }
        }

        val importance = { id: Int -> nodes[id]?.importance?.normalized ?: 0.5 }
        val distinct = mutableListOf<Traced>()
        for (flow in traced.sortedByDescending { it.nodes.size }) {
            val set = flow.nodes.toSet()
            if (distinct.none { kept -> kept.nodes.count(set::contains) >= 0.8 * set.size }) distinct += flow
        }
        val ranked = distinct.sortedByDescending { it.nodes.size * it.nodes.map(importance).average() }.take(options.maxFlows)

        val requests = AtomicInteger()
        val failures = AtomicInteger()
        val names = ConcurrentHashMap<Int, Pair<String, String?>>()
        if (complete != null && ranked.isNotEmpty()) {
            val batches = ranked.chunked(NAMING_BATCH)
            val done = AtomicInteger()
            forEachConcurrently(batches, concurrency) { batch ->
                names += name(input, batch, complete, requests, failures)
                progress.report("Naming flows", done.addAndGet(batch.size), ranked.size)
            }
        }
        val flows = ranked.map { flow ->
            val (name, description) = names[flow.entry] ?: (fallbackName(input, flow.entry) to null)
            Flow(flowId(input, flow.entry), name, description, flow.entry, flow.nodes, flow.edges)
        }
        return FlowResult(flows, requests.get(), failures.get())
    }

    private class Traced(val entry: Int, val nodes: List<Int>, val edges: Set<CallGraphEdge>)

    private suspend fun name(
        input: EnrichmentInput,
        batch: List<Traced>,
        complete: suspend (CompletionRequest) -> Completion,
        requests: AtomicInteger,
        failures: AtomicInteger,
    ): Map<Int, Pair<String, String?>> {
        val user = buildString {
            append("Project: ").append(input.projectName).append("\n\n")
            batch.forEachIndexed { index, flow ->
                append("[").append(index + 1).append("] entry ").append(describe(input, flow.entry)).append('\n')
                for (member in flow.nodes.drop(1)) {
                    if (length > MAX_PROMPT * (index + 1) / batch.size) { append("  - …\n"); break }
                    append("  - ").append(describe(input, member)).append('\n')
                }
                append('\n')
            }
        }
        val answer = cachedJson(cache, "flows", complete, SYSTEM, user, schema, maxTokens = 3000 + 150 * batch.size) {
            requests.incrementAndGet()
        }
        if (answer == null) {
            failures.incrementAndGet()
            return emptyMap()
        }
        return answer.getAsJsonArray("flows")?.mapNotNull { element ->
            val entry = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val flow = entry.jsonText("id")?.toDoubleOrNull()?.toInt()?.let { batch.getOrNull(it - 1) } ?: return@mapNotNull null
            val name = entry.jsonText("name")?.trim('"', '.', ' ')?.clipLine(60)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            flow.entry to (name to entry.jsonText("description")?.clipLine(240))
        }.orEmpty().toMap()
    }

    /** `Container.name — signature` for prompts. */
    private fun describe(input: EnrichmentInput, id: Int): String {
        val facts = input.facts[id]
        val node = input.graph.nodes[id]
        val owner = shortContainer(facts?.container ?: node.container)
        val name = qualified(owner, node.name)
        return facts?.signature?.takeIf { it.isNotBlank() }?.let { "$name — ${it.clipLine(140)}" } ?: name
    }

    private fun fallbackName(input: EnrichmentInput, entry: Int): String {
        val node = input.graph.nodes[entry]
        val owner = shortContainer(input.facts[entry]?.container ?: node.container)
        return qualified(owner, node.name) + " flow"
    }

    private fun qualified(owner: String, name: String) = name.substringBefore('(').let { if (owner.isEmpty()) it else "$owner.$it" }

    private fun shortContainer(container: String) = container.substringAfterLast('.').substringAfterLast('/').trim()

    /** Stable across rebuilds: derived from the entry's [SymbolFacts.key], or its location when there are no facts. */
    private fun flowId(input: EnrichmentInput, entry: Int): String {
        val node = input.graph.nodes[entry]
        val key = input.facts[entry]?.key ?: "${node.filePath}|${node.container}|${node.name}"
        return "flow-" + generativeCacheKey("flow", key).substringAfterLast(':').take(16)
    }

    private companion object {
        const val NAMING_BATCH = 10
        const val MAX_PROMPT = 12_000
        val SYSTEM = """
            $CODE_AS_DATA

            Each numbered item below is a flow: a chain of calls that starts at an entry point (an action, command,
            handler or main function) and performs one logical operation. Its members are listed in call order as
            Container.name — signature. For every flow return {"id": its number, "name": a 2-6 word name of the
            operation as a user or caller would describe it, such as "Show call graph" or "Send chat message",
            "description": one sentence on what the operation achieves and why, not a list of the calls}. Do not use symbol names as the name.
            Answer only with JSON {"flows": [...]}. Think briefly.
        """.trimIndent()
        val schema: JsonObject = JsonSchemas.obj("flows" to JsonSchemas.array(JsonSchemas.obj(
            "id" to JsonSchemas.integer(), "name" to JsonSchemas.string(), "description" to JsonSchemas.string())))
    }
}

/** In-project CALL edges by caller, plus an INHERITS edge from each base method to its implementations (see [overrides]). */
internal fun flowCalls(graph: CallGraph, facts: Map<Int, SymbolFacts> = emptyMap()): Map<Int, List<CallGraphEdge>> =
    graph.edges.filter { it.kind == RelationKind.CALL && it.caller != it.callee }.groupBy { it.caller }
        .mergeDispatch(overrides(graph, facts))

/** Functions that start a flow: likely entry points or `entry:` heuristic tags, without tests unless [FlowOptions.includeTests]. */
internal fun flowEntries(graph: CallGraph, nodes: Map<Int, NodeAnnotation>, heuristics: Map<Int, Heuristics>, options: FlowOptions): Set<Int> =
    graph.nodes.filter { node ->
        node.kind == SymbolKind.FUNCTION && node.inProject &&
            (nodes[node.id]?.entryPoint?.let { it >= options.entryThreshold } == true ||
                heuristics[node.id]?.tags.orEmpty().any { it.startsWith("entry:") }) &&
            (options.includeTests || !isTest(nodes[node.id], heuristics[node.id]))
    }.map { it.id }.toSet()

private fun isTest(annotation: NodeAnnotation?, heuristics: Heuristics?) =
    heuristics?.tags.orEmpty().contains("test") || (annotation?.concerns?.get("test") ?: 0.0) > 0.5 ||
        annotation?.phase?.choice == "test"

/**
 * Whether a flow continues along a call: the callee is in-project code and not a generic utility, and the call is
 * not mostly on an error path and continues the caller's operation. Without judgments every in-project call is followed.
 */
internal fun flowFilter(graph: CallGraph, nodes: Map<Int, NodeAnnotation>, edges: Map<CallGraphEdge, EdgeAnnotation>, options: FlowOptions) =
    { edge: CallGraphEdge ->
        val callee = graph.nodes.getOrNull(edge.callee)
        when {
            callee == null || !callee.inProject || callee.kind == SymbolKind.FILE || callee.kind == SymbolKind.DIRECTORY -> false
            (nodes[edge.callee]?.utility ?: 0.0) >= options.utilityThreshold -> false
            else -> edges[edge]?.let { it.errorPath < 0.5 && it.continuesOperation >= options.continueThreshold } ?: true
        }
    }

/**
 * Breadth-first walk from [entry] along the [calls] accepted by [follows], up to [FlowOptions.maxDepth] levels and
 * [FlowOptions.maxFlowNodes] members. Members in [stops] (other entry points) are included but not expanded.
 */
internal fun walkFlow(
    entry: Int,
    calls: Map<Int, List<CallGraphEdge>>,
    follows: (CallGraphEdge) -> Boolean,
    stops: Set<Int>,
    options: FlowOptions,
): Pair<List<Int>, Set<CallGraphEdge>> {
    val members = linkedSetOf(entry)
    val flowEdges = linkedSetOf<CallGraphEdge>()
    var frontier = listOf(entry)
    for (depth in 1..options.maxDepth) {
        val next = mutableListOf<Int>()
        for (caller in frontier) for (edge in calls[caller].orEmpty()) {
            if (!follows(edge)) continue
            if (edge.callee !in members) {
                if (members.size >= options.maxFlowNodes) continue
                members += edge.callee
                if (edge.callee !in stops) next += edge.callee
            }
            flowEdges += edge
        }
        if (next.isEmpty()) break
        frontier = next
    }
    return members.toList() to flowEdges
}

/**
 * Implementations of each method, using resolved method inheritance where available.
 * Name matching remains a fallback for languages without method override resolution.
 * A call to an interface or base method can run any of them, so a flow continues into each implementation.
 */
internal fun overrides(graph: CallGraph, facts: Map<Int, SymbolFacts> = emptyMap()): Map<Int, List<Int>> {
    fun resolvesOverrides(id: Int): Boolean {
        val language = facts[id]?.language ?: when (graph.nodes[id].filePath?.substringAfterLast('.')) {
            "java" -> "java"
            "kt", "kts" -> "kotlin"
            else -> null
        }
        return language in setOf("java", "kotlin")
    }
    val members = HashMap<Int, MutableList<Int>>()
    val subclasses = HashMap<Int, MutableList<Int>>()
    val methodOverrides = HashMap<Int, MutableList<Int>>()
    for (edge in graph.edges) when (edge.kind) {
        RelationKind.CONTAINS -> if (graph.nodes[edge.callee].kind == SymbolKind.FUNCTION) members.getOrPut(edge.caller) { mutableListOf() } += edge.callee
        RelationKind.INHERITS -> when (graph.nodes[edge.caller].kind) {
            SymbolKind.CLASS -> subclasses.getOrPut(edge.callee) { mutableListOf() } += edge.caller
            SymbolKind.FUNCTION -> methodOverrides.getOrPut(edge.callee) { mutableListOf() } += edge.caller
            else -> {}
        }
        else -> {}
    }
    val result = HashMap<Int, List<Int>>()
    for (method in methodOverrides.keys) {
        val implementations = LinkedHashSet<Int>()
        val queue = ArrayDeque(methodOverrides[method].orEmpty())
        while (queue.isNotEmpty()) queue.removeFirst().let {
            if (it != method && implementations.add(it)) queue += methodOverrides[it].orEmpty()
        }
        if (implementations.isNotEmpty()) result[method] = implementations.toList()
    }
    for ((base, methods) in members) {
        val descendants = LinkedHashSet<Int>()
        val queue = ArrayDeque(subclasses[base].orEmpty())
        while (queue.isNotEmpty()) queue.removeFirst().let { if (descendants.add(it)) queue += subclasses[it].orEmpty() }
        if (descendants.isEmpty()) continue
        for (method in methods) {
            if (resolvesOverrides(method)) continue
            val name = graph.nodes[method].name
            val implementations = descendants.flatMap { members[it].orEmpty() }
                .filter { !resolvesOverrides(it) && graph.nodes[it].name == name }
            if (implementations.isNotEmpty()) result[method] = (result[method].orEmpty() + implementations).distinct()
        }
    }
    return result
}

/** Adds an INHERITS edge from each called base method to its implementations, as if the base method called them. */
private fun Map<Int, List<CallGraphEdge>>.mergeDispatch(overrides: Map<Int, List<Int>>): Map<Int, List<CallGraphEdge>> =
    toMutableMap().apply {
        overrides.forEach { (method, implementations) ->
            merge(method, implementations.map { CallGraphEdge(method, it, RelationKind.INHERITS) }) { a, b -> a + b }
        }
    }
