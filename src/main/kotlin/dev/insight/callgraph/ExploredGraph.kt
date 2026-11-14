package dev.insight.callgraph

import dev.insight.agent.ToolInputException
import dev.insight.graph.CallGraph
import dev.insight.graph.CallOccurrence
import dev.insight.graph.RelationKind
import dev.insight.graph.SymbolKind
import dev.insight.graph.fuzzyNameSearch

/**
 * Read-only questions a chat asks about the graph explored so far. [snapshot] gives the current graph, with calls from
 * locals lifted to their function (see `liftCalls`), and its call occurrences; indexes are rebuilt only when it
 * changes. [summaries] are model-written descriptions of symbols, when known; [relative] shortens file paths;
 * [expand] resolves a symbol's own scope on demand.
 */
internal class ExploredGraph(
    private val snapshot: () -> Pair<CallGraph, List<CallOccurrence>>,
    private val summaries: (Int) -> String? = { null },
    private val relative: (String) -> String = { it },
    private val expand: suspend (Int) -> Unit = {},
) {
    @Volatile private var cached: Indexes? = null
    private val indexes: Indexes get() {
        val (graph, occurrences) = snapshot()
        return cached?.takeIf { it.graph === graph && it.occurrences === occurrences }
            ?: Indexes(graph, occurrences).also { cached = it }
    }
    private class Indexes(val graph: CallGraph, val occurrences: List<CallOccurrence>) {
        private val edges by lazy { graph.edges.filter { it.caller != it.callee } }
        val callees by lazy { edges.filter { it.kind == RelationKind.CALL }
            .groupBy({ it.caller }, { it.callee }).mapValues { it.value.distinct() } }
        val callers by lazy { edges.filter { it.kind == RelationKind.CALL }
            .groupBy({ it.callee }, { it.caller }).mapValues { it.value.distinct() } }
        val members by lazy { edges.filter { it.kind == RelationKind.CONTAINS }.groupBy({ it.caller }, { it.callee }) }
        val owner by lazy { edges.filter { it.kind == RelationKind.CONTAINS }.associate { it.callee to it.caller } }
        val calls by lazy { occurrences.groupBy { it.scope } }
    }
    private val nodes get() = indexes.graph.nodes

    /** Resolves [id]'s scope, discovering its direct calls, when it is a project declaration. */
    suspend fun load(id: Int) {
        if (nodes.getOrNull(id)?.let { it.inProject && !it.unresolved } == true) expand(id)
    }

    fun callees(id: Int): List<Int> = indexes.callees[id].orEmpty()

    /** One line naming [id] for the model: id, kind, name, container, location and how far it can be followed. */
    fun line(id: Int): String {
        val node = nodes[id]
        val flags = listOfNotNull("external".takeIf { !node.inProject && !node.unresolved }, "unresolved".takeIf { node.unresolved })
        return "#$id ${node.kind.name.lowercase()} ${node.name}" +
            node.container.takeIf { it.isNotBlank() }?.let { " in $it" }.orEmpty() +
            (node.filePath?.let { " · ${relative(it)}" } ?: node.location.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()) +
            if (flags.isEmpty()) "" else " (${flags.joinToString()})"
    }

    fun find(query: String, kind: SymbolKind?, limit: Int): String {
        val candidates = nodes.indices.filter { id ->
            val node = nodes[id]
            !node.unresolved && node.kind != SymbolKind.DIRECTORY && (kind == null || node.kind == kind) &&
                node.kind !in setOf(SymbolKind.PARAMETER, SymbolKind.VARIABLE)
        }
        // Project code first: external declarations are boundaries with no body to follow.
        val found = fuzzyNameSearch(query, candidates) { nodes[it].name }.sortedByDescending { nodes[it].inProject }
        if (found.isEmpty()) return "No symbols match '$query'. Try a shorter name, or search_text for the text."
        return found.take(limit).joinToString("\n", transform = ::line) +
            if (found.size > limit) "\n[${found.size - limit} more; narrow the query.]" else ""
    }

    fun inspect(id: Int): String {
        val index = indexes
        val nodes = index.graph.nodes
        val node = nodes.getOrNull(id) ?: throw ToolInputException("No symbol #$id")
        fun list(title: String, ids: List<Int>) = if (ids.isEmpty()) "" else "\n$title (${ids.size}):\n" +
            ids.take(LIST_LIMIT).joinToString("\n") { "  ${line(it)}" } + if (ids.size > LIST_LIMIT) "\n  [${ids.size - LIST_LIMIT} more]" else ""
        val summary = summaries(id)?.trim()?.takeIf { it.isNotEmpty() }?.let { "\nSummary (model-written): $it" }.orEmpty()
        val sites = index.calls[id].orEmpty().take(LIST_LIMIT).joinToString("\n") { site ->
            val targets = site.displayTargets.joinToString(", ") { "#$it ${nodes[it].name}" }.ifEmpty { "none" }
            "  ${site.expression.lineSequence().first().take(120)} → $targets (${site.status.name.lowercase()})"
        }
        return line(id) + node.signature?.let { "\nSignature: $it" }.orEmpty() + summary +
            index.owner[id]?.let { "\nDeclared in: ${line(it)}" }.orEmpty() +
            list("Declares", index.members[id].orEmpty().filter { nodes[it].kind !in setOf(SymbolKind.PARAMETER, SymbolKind.VARIABLE) }) +
            list("Calls", index.callees[id].orEmpty()) + list("Called by", index.callers[id].orEmpty()) +
            (if (sites.isEmpty()) "" else "\nCall sites in its body:\n$sites") +
            when {
                node.unresolved -> "\nThe IDE found no declaration for this call; search_text for the name to look further."
                !node.inProject -> "\nExternal declaration: its body is not part of the graph."
                else -> ""
            }
    }

    /** Shortest static call chains to [target], from [from] or else from [roots]. */
    fun paths(from: Int?, roots: List<Int>, target: Int, maxDepth: Int): String {
        val index = indexes
        val nodes = index.graph.nodes
        if (target !in nodes.indices) throw ToolInputException("No symbol #$target")
        if (from != null && from !in nodes.indices) throw ToolInputException("No symbol #$from")
        val starts = from?.let(::listOf) ?: roots.filter { it in nodes.indices }
        val found = shortestCallPaths(starts, target, maxDepth, MAX_PATHS) { id ->
            if (nodes[id].inProject && !nodes[id].unresolved) index.callees[id].orEmpty() else emptyList()
        }
        if (found.isEmpty()) return "No static call chain of at most $maxDepth calls reaches ${line(target)}. " +
            "It may be called through an interface, callback, reflection or framework wiring; check its callers with inspect_symbol."
        return found.mapIndexed { index, path ->
            "Chain ${index + 1}: " + path.joinToString(" → ") { "#$it ${nodes[it].name}" }
        }.joinToString("\n")
    }

    private companion object {
        const val LIST_LIMIT = 40
        const val MAX_PATHS = 3
    }
}

/**
 * Up to [maxPaths] shortest chains along [next] from any of [starts] to [target], each at most [maxDepth] steps.
 * Breadth first, so every chain has the same, minimal length.
 */
fun shortestCallPaths(starts: Collection<Int>, target: Int, maxDepth: Int, maxPaths: Int, next: (Int) -> List<Int>): List<List<Int>> {
    if (target in starts) return listOf(listOf(target))
    val depth = HashMap<Int, Int>()
    val previous = HashMap<Int, MutableList<Int>>()
    var frontier = starts.distinct().onEach { depth[it] = 0 }
    var level = 0
    while (frontier.isNotEmpty() && target !in depth && level < maxDepth) {
        level++
        val reached = LinkedHashSet<Int>()
        for (id in frontier) for (other in next(id)) {
            if (depth[other]?.let { it < level } == true) continue
            depth[other] = level
            previous.getOrPut(other) { mutableListOf() }.let { if (id !in it) it += id }
            reached += other
        }
        frontier = reached.toList()
    }
    if (target !in depth) return emptyList()
    val paths = mutableListOf<List<Int>>()
    fun back(id: Int, suffix: List<Int>) {
        if (paths.size >= maxPaths) return
        val path = listOf(id) + suffix
        if (depth[id] == 0) { paths += path; return }
        previous[id].orEmpty().forEach { back(it, path) }
    }
    back(target, emptyList())
    return paths
}
