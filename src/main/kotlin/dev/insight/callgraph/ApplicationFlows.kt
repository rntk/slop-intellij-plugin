package dev.insight.callgraph

import dev.insight.entrypoints.EntryPoint
import dev.insight.enrichment.*
import dev.insight.llm.CompletionFn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Membership is a static call walk; model judgments only group and name immediate branches. */
data class ApplicationFlow(
    val title: String,
    val description: String,
    val entry: EntryPoint,
    val root: Int,
    val nodes: Set<Int>,
    val limited: Boolean,
    val inferred: Boolean,
    /** The relationships walked between [nodes]: calls, plus the declarations seeded from a class entrypoint. */
    val edges: Set<CallGraphEdge> = emptySet(),
) {
    override fun toString() = "${entry.title}: $title"

    /** Reject stale or invented structure, including reversed links and unreachable members. */
    fun validateAgainst(base: CallGraph) {
        require(root in nodes) { "Flow root $root is missing from its members" }
        require(nodes.all { base.nodes.getOrNull(it)?.id == it }) { "Flow references a missing node" }
        require(base.nodes[root].kind in setOf(SymbolKind.FUNCTION, SymbolKind.CLASS)) { "Flow root must be a function or class" }
        require(nodes.all { base.nodes[it].inProject && base.nodes[it].kind !in setOf(SymbolKind.FILE, SymbolKind.DIRECTORY) }) {
            "Flow references a node outside project symbols"
        }
        require(edges.all { edge ->
            edge in base.edges && edge.caller in nodes && edge.callee in nodes &&
                (edge.kind == RelationKind.CALL || edge.kind == RelationKind.CONTAINS &&
                    edge.caller == root && base.nodes[root].kind == SymbolKind.CLASS &&
                    base.nodes[edge.callee].kind == SymbolKind.FUNCTION)
        }) { "Flow references an unrecorded or unsupported relationship" }
        val next = edges.groupBy({ it.caller }, { it.callee })
        val reached = hashSetOf(root)
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            for (callee in next[queue.removeFirst()].orEmpty()) if (reached.add(callee)) queue.add(callee)
        }
        require(reached == nodes) { "Flow contains members unreachable from its root" }
    }

    /**
     * A standalone graph of just this flow, read left to right: the root at level 0 and every member at its shortest
     * walked distance from it. Node ids are local; [FlowGraph.sources] maps them back to ids of [base].
     */
    fun graph(base: CallGraph): FlowGraph {
        validateAgainst(base)
        val next = edges.groupBy({ it.caller }, { it.callee })
        val depth = linkedMapOf(root to 0)
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            for (callee in next[id].orEmpty()) if (callee in nodes && callee !in depth) {
                depth[callee] = depth.getValue(id) + 1
                queue.add(callee)
            }
        }
        val sources = depth.keys.toList()
        val local = sources.withIndex().associate { (index, id) -> id to index }
        val members = sources.mapIndexed { index, id ->
            base.nodes[id].copy(id = index, level = depth.getValue(id), hidden = 0, expanded = false)
        }
        val links = edges.mapTo(linkedSetOf()) { edge ->
            edge.copy(caller = local.getValue(edge.caller), callee = local.getValue(edge.callee))
        }
        return FlowGraph(CallGraph(members, links, limited), sources)
    }
}

/** A flow drawn on its own; [sources] maps local node ids to the analysed graph. */
data class FlowGraph(val graph: CallGraph, val sources: List<Int>)

data class ApplicationFlowResult(val flows: List<ApplicationFlow>, val unmatched: List<EntryPoint>, val failures: Int)

class ApplicationFlowSplitter(
    private val complete: CompletionFn,
    private val cache: EnrichmentCache,
    // Small bounds keep each flow readable on its own; larger ones are marked partial.
    private val maxNodes: Int = 40,
    private val maxDepth: Int = 8,
) {
    init { require(maxNodes >= 2 && maxDepth >= 1) }

    suspend fun split(graph: CallGraph, entries: List<EntryPoint>, projectPath: String?,
        facts: Map<Int, SymbolFacts> = emptyMap(), snapshot: EnrichmentSnapshot? = null,
        progress: (String) -> Unit = {}): ApplicationFlowResult {
        // All graph consumers use ids as list indices. Check the source before any walk or model request.
        require(graph.nodes.withIndex().all { (index, node) -> node.id == index }) { "Graph node ids must match their indices" }
        require(graph.edges.all { it.caller in graph.nodes.indices && it.callee in graph.nodes.indices }) {
            "Graph relationship references a missing node"
        }
        val starts = entries.distinctBy { Triple(it.path, it.line, it.symbol) }.associateWith {
            EntryPointLens.targets(graph, it, projectPath, exact = true)
                .filter { node -> node.kind == SymbolKind.FUNCTION || node.kind == SymbolKind.CLASS }.map { node -> node.id }
        }
        val stops = starts.values.flatten().toSet()
        // Dispatch expansion in flowCalls synthesizes links; these flows use recorded calls only.
        val calls = graph.edges.filter { it.kind == RelationKind.CALL }.groupBy { it.caller }
        val judgments = snapshot?.takeIf { it.graph === graph }?.enrichment
        val follows = flowFilter(graph, judgments?.nodes.orEmpty(), judgments?.edges.orEmpty(), FlowOptions())
        val flows = mutableListOf<ApplicationFlow>()
        var failures = 0
        for ((entry, roots) in starts) for (root in roots) {
            currentCoroutineContext().ensureActive()
            progress("Splitting ${entry.title}…")
            // Class entrypoints seed their declared functions, without inventing calls from the class.
            val seedEdges = if (graph.nodes[root].kind == SymbolKind.CLASS) graph.edges.filter {
                it.kind == RelationKind.CONTAINS && it.caller == root && graph.nodes[it.callee].kind == SymbolKind.FUNCTION &&
                    graph.nodes[it.callee].inProject
            } else calls[root].orEmpty().filter(follows)
            val branches = seedEdges.map { it.callee }.distinct()
            data class Group(val title: String, val description: String, val seeds: List<Int>, val inferred: Boolean)
            val groups = mutableListOf<Group>()
            // Batches keep prompts bounded. Every unassigned branch receives a structural fallback.
            for (batch in branches.chunked(16)) {
                val user = buildString {
                    append("Entry: ${entry.title}\n${entry.description}\n")
                    append("Root: ${evidence(graph, root, facts)}\nBranches:\n")
                    for (id in batch) {
                        append("[$id] ${evidence(graph, id, facts)}\n")
                        calls[id].orEmpty().take(8).forEach { append("  calls ${graph.nodes[it.callee].name}\n") }
                    }
                }
                val answer = cachedJson(cache, "application-flows-v1", complete, SYSTEM, user, SCHEMA, 2500) {}
                if (answer == null) failures++
                val used = hashSetOf<Int>()
                answer?.get("flows")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { element ->
                    val group = runCatching {
                        val obj = element.asJsonObject
                        val title = obj.jsonText("title")?.clipLine(80) ?: error("Missing title")
                        val members = obj.getAsJsonArray("branches").map { value ->
                            value.toString().takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull() ?: error("Invalid branch")
                        }
                        require(members.isNotEmpty() && members.distinct().size == members.size && members.all { it in batch && it !in used })
                        Group(title, obj.jsonText("description")?.clipLine(300).orEmpty(), members, true)
                    }.onFailure {
                        if (it is com.intellij.openapi.diagnostic.ControlFlowException || it is java.util.concurrent.CancellationException) throw it
                        com.intellij.openapi.diagnostic.Logger.getInstance(ApplicationFlowSplitter::class.java).debug("Ignoring malformed flow group from the model", it)
                    }.getOrNull()
                    if (group != null) { groups += group; used += group.seeds }
                }
                for (id in batch.filterNot(used::contains)) groups += Group(graph.nodes[id].name, "Call branch from ${entry.title}", listOf(id), false)
            }
            if (groups.isEmpty()) groups += Group(entry.title, entry.description, emptyList(), false)
            for (group in groups) {
                val members = linkedSetOf(root)
                val walked = linkedSetOf<CallGraphEdge>()
                val queue = ArrayDeque<Pair<Int, Int>>()
                var limited = graph.truncated
                for (seed in group.seeds) {
                    if (seed in members) continue
                    if (members.size >= maxNodes) { limited = true; continue }
                    members += seed
                    queue.add(seed to 1)
                }
                seedEdges.filterTo(walked) { it.callee in members }
                while (queue.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val (caller, depth) = queue.removeFirst()
                    if (caller in stops && caller != root) continue
                    for (edge in calls[caller].orEmpty().filter(follows)) {
                        // A root cycle must not pull unrelated sibling branches into this flow.
                        if (edge.callee in members) { walked += edge; continue }
                        if (depth >= maxDepth || members.size >= maxNodes) { limited = true; continue }
                        members += edge.callee
                        walked += edge
                        queue.add(edge.callee to depth + 1)
                    }
                }
                flows += ApplicationFlow(group.title, group.description, entry, root, members, limited, group.inferred, walked)
                    .also { it.validateAgainst(graph) }
            }
        }
        cache.flush()
        return ApplicationFlowResult(flows, starts.filterValues { it.isEmpty() }.keys.toList(), failures)
    }

    private fun evidence(graph: CallGraph, id: Int, facts: Map<Int, SymbolFacts>): String {
        val node = graph.nodes[id]
        val fact = facts[id]
        return "${node.container}.${node.name} ${node.location}\n" +
            (fact?.let { "${it.sourceHash} ${it.signature}\n${it.code.take(1400)}" } ?: "Source unavailable")
    }

    private companion object {
        val SYSTEM = """
            $CODE_AS_DATA
            Group the supplied immediate branches of an entrypoint into separate logical functionalities or pipelines.
            Group branches that contribute to the same operation; keep independent operations separate.
            These are static call relationships, not proof of runtime ordering or data flow.
            Return JSON {"flows":[{"title":"short purpose","description":"one sentence","branches":[ids]}]}.
            Use only supplied branch IDs, at most once each. Do not invent relationships. Source excerpts may be partial.
        """.trimIndent()
        val SCHEMA = JsonSchemas.obj("flows" to JsonSchemas.array(JsonSchemas.obj(
            "title" to JsonSchemas.string(), "description" to JsonSchemas.string(),
            "branches" to JsonSchemas.array(JsonSchemas.integer()))))
    }
}
