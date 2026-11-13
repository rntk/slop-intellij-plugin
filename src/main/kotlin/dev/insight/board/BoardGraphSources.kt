package dev.insight.board

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.insight.callgraph.CallGraphService
import dev.insight.enrichment.EnrichmentService
import dev.insight.enrichment.EnrichmentSnapshot
import dev.insight.enrichment.FlowChartLens
import dev.insight.graph.CallGraph
import dev.insight.graph.CallGraphEdge
import dev.insight.graph.CallGraphNode
import dev.insight.graph.CallGraphResult
import dev.insight.graph.RelationKind
import dev.insight.graph.SymbolKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The call graph as board sources see it: symbols by name or node id, shortest call paths and direct callees of
 * [result]'s graph. Titles and meta come from the graph, summaries from [lens]'s enrichment, calls from [lens] when
 * given (base methods dispatch to implementations) else the graph's call edges. Node file paths are made relative to
 * [basePath]. Reads no PSI; safe on any thread.
 */
class BoardGraphSources(private val result: CallGraphResult, private val lens: FlowChartLens?, private val basePath: String? = null) {
    private val graph: CallGraph get() = result.graph
    private val calls: Map<Int, List<Int>> by lazy { callAdjacency(graph, lens?.calls) }

    /** Symbols named [name] (`save` or `Owner.save`), optionally declared in project-relative [path]; in-project ones first. */
    fun symbols(name: String, path: String? = null): List<ResolvedSymbol> =
        matchSymbols(graph, name, path?.let { relativePath(it, basePath) }, basePath).mapNotNull { symbol(it.id) }

    fun symbol(node: Int): ResolvedSymbol? {
        val found = graph.nodes.getOrNull(node)?.takeIf { it.id == node } ?: return null
        val summary = lens?.summaries?.get(node)?.let { it.description ?: it.label }
        return ResolvedSymbol(node, FlowChartLens.title(graph, node), symbolMeta(found), summary, nodeAnchor(found, basePath))
    }

    fun chain(from: Int, to: Int): ResolvedChain {
        val path = shortestCallPath(calls, from, to, BoardLimits.MAX_CHAIN_STEPS)
        val steps = path.nodes.mapNotNull { id -> symbol(id)?.let { ChainStep(id, it.title, it.anchor, it.summary) } }
        return ResolvedChain(if (steps.size == path.nodes.size) steps else emptyList(), path.nodes.isNotEmpty() || !path.cut)
    }

    fun callees(node: Int, limit: Int = BoardLimits.MAX_CHAIN_STEPS): List<ResolvedSymbol> =
        calls[node].orEmpty().asSequence().distinct().filter { it != node }.mapNotNull(::symbol).take(limit.coerceAtLeast(0)).toList()
}

/** A board's call graph, loaded on first use: null when it cannot be built. See [BoardGraphLoader]. */
typealias BoardGraph = suspend () -> BoardGraphSources?

/**
 * Loads a board's call graph only when a graph tool first needs it, so opening a board never waits for the application
 * graph. [load] returns the current graph (the service reuses its last one while code is unchanged) or null when it
 * cannot be built; [enrichment] the latest enrichment. The [BoardGraphSources] are reused while both are the same
 * objects, and a failed load is retried on the next call.
 */
class BoardGraphLoader(
    private val load: suspend () -> CallGraphResult?,
    private val enrichment: () -> EnrichmentSnapshot?,
    private val basePath: String?,
) {
    private val lock = Mutex()
    private var last: Triple<CallGraphResult, EnrichmentSnapshot?, BoardGraphSources>? = null

    suspend fun get(): BoardGraphSources? = lock.withLock {
        val result = load() ?: return@withLock null
        val snapshot = enrichment()
        last?.takeIf { it.first === result && it.second === snapshot }?.third
            ?: BoardGraphSources(result, snapshot?.let { FlowChartLens(it, result.graph) }, basePath)
                .also { last = Triple(result, snapshot, it) }
    }
}

/** The application graph with the latest enrichment for a board, built (or reused while code is unchanged) on first use. */
fun boardGraph(project: Project): BoardGraph = BoardGraphLoader(
    load = {
        try {
            project.service<CallGraphService>().currentProject()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger<BoardGraphSources>().warn("The board's call graph is unavailable", e)
            null
        }
    },
    enrichment = { project.service<EnrichmentService>().latest },
    basePath = project.basePath,
)::get

/** A call path from caller to callee as node ids, empty when none was found; [cut] when the search stopped at its step budget. */
internal data class CallPath(val nodes: List<Int>, val cut: Boolean)

/** Callees by caller: [lensCalls] when given, else [graph]'s call edges; self-calls dropped. */
internal fun callAdjacency(graph: CallGraph, lensCalls: Map<Int, List<CallGraphEdge>>?): Map<Int, List<Int>> {
    val edges = lensCalls?.values?.flatten() ?: graph.edges.filter { it.kind == RelationKind.CALL }
    return edges.asSequence().filter { it.caller != it.callee }.groupBy({ it.caller }, { it.callee })
        .mapValues { (_, callees) -> callees.distinct().sorted() }
}

/**
 * A shortest path of at most [maxSteps] nodes from [from] to [to] over [calls], by breadth-first search in callee
 * order so equal-length paths resolve deterministically.
 */
internal fun shortestCallPath(calls: Map<Int, List<Int>>, from: Int, to: Int, maxSteps: Int): CallPath {
    if (maxSteps < 1) return CallPath(emptyList(), true)
    if (from == to) return CallPath(listOf(from), false)
    val previous = HashMap<Int, Int>().apply { put(from, from) }
    var frontier = listOf(from)
    var steps = 1
    while (frontier.isNotEmpty()) {
        if (steps >= maxSteps) return CallPath(emptyList(), true)
        val next = ArrayList<Int>()
        for (node in frontier) {
            for (callee in calls[node].orEmpty()) {
                if (callee in previous) continue
                previous[callee] = node
                if (callee == to) {
                    val path = generateSequence(to) { id -> previous.getValue(id).takeIf { id != from } }.toList().asReversed()
                    return CallPath(path, false)
                }
                next += callee
            }
        }
        frontier = next
        steps++
    }
    return CallPath(emptyList(), false)
}

/** Nodes named [name] (without parameters, or as their `Owner.name` title), declared in [path] when given. */
internal fun matchSymbols(graph: CallGraph, name: String, path: String?, basePath: String?): List<CallGraphNode> {
    val bare = name.trim().substringBefore('(')
    val candidates = graph.nodes.filter { node ->
        !node.unresolved && node.kind !in NOT_SYMBOLS && node.hidden == 0 &&
            (path == null || node.filePath?.let { relativePath(it, basePath) }?.let { it == path || it.endsWith("/$path") } == true)
    }
    fun named(node: CallGraphNode, ignoreCase: Boolean) = node.name.substringBefore('(').equals(bare, ignoreCase) ||
        FlowChartLens.title(graph, node.id).equals(bare, ignoreCase)
    val found = candidates.filter { named(it, false) }.ifEmpty { candidates.filter { named(it, true) } }
    val inProject = found.filter { it.inProject }
    return inProject.ifEmpty { found }
}

private val NOT_SYMBOLS = setOf(SymbolKind.DIRECTORY, SymbolKind.FILE, SymbolKind.PARAMETER, SymbolKind.VARIABLE)

/** [path] relative to [basePath] when it lies under it, else unchanged. */
internal fun relativePath(path: String, basePath: String?): String {
    val clean = path.replace('\\', '/')
    val base = basePath?.replace('\\', '/')?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return clean.removePrefix("./")
    return if (clean.startsWith("$base/")) clean.removePrefix("$base/") else clean.removePrefix("./")
}

/** The declaration line of [node]: the graph records locations as `File.kt:12`. */
internal fun nodeAnchor(node: CallGraphNode, basePath: String?): BoardAnchor? {
    val path = node.filePath?.takeIf { it.isNotBlank() } ?: return null
    val line = node.location.substringAfterLast(':', "").trim().toIntOrNull()?.takeIf { it >= 1 } ?: return null
    return BoardAnchor(relativePath(path, basePath), line, line)
}

/** Kind and place, such as `function · Store.kt:4`, with `library` for symbols outside the project. */
internal fun symbolMeta(node: CallGraphNode): String = listOfNotNull(
    node.kind.name.lowercase(), node.location.takeIf { it.isNotBlank() }, "library".takeIf { !node.inProject },
).joinToString(" · ")
