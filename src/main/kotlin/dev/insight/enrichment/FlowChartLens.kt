package dev.insight.enrichment

import dev.insight.callgraph.CallGraph
import dev.insight.callgraph.CallGraphEdge
import dev.insight.callgraph.CallSite
import dev.insight.callgraph.FlowCard
import dev.insight.callgraph.FlowSource
import dev.insight.callgraph.FlowStart
import dev.insight.callgraph.SymbolKind

/**
 * The calls the flow chart explores, keyed by ids of `graph` (the current build), and the text of its steps from
 * enrichment judgments: docs, layer, domain and concerns. Without a [snapshot] every in-project call is followed; with
 * one, calls are followed like enrichment flows: utilities, error paths and incidental calls are left out. Callers are
 * every in-project call, so any function can be reached from below. PSI-free and unit-testable.
 */
class FlowChartLens(snapshot: EnrichmentSnapshot?, private val graph: CallGraph, colorBy: ColorBy = ColorBy.DOMAIN) {
    private val lens = snapshot?.let { Remapped.of(it, graph) }
    private val domains = snapshot?.enrichment?.domains.orEmpty()
    private val colours = EnrichmentLens.Colouring(colorBy, domains)

    /** Calls the chart shows inside a step, by caller; base methods dispatch to their implementations. */
    val calls: Map<Int, List<CallGraphEdge>>

    /** Direct in-project callers by callee, including base methods of implementations; locals are lifted to their functions. */
    val callers: Map<Int, List<Int>>

    init {
        val lifted = liftCalls(graph)
        val all = flowCalls(lifted, lens?.facts.orEmpty())
        val follows = flowFilter(lifted, lens?.nodes.orEmpty(), lens?.edges.orEmpty(), FlowOptions())
        calls = all.mapValues { (_, edges) -> edges.filter(follows) }.filterValues { it.isNotEmpty() }
        callers = all.values.asSequence().flatten().filter { it.caller != it.callee && graph.nodes[it.caller].inProject }
            .groupBy { it.callee }.mapValues { (_, edges) -> edges.map { it.caller }.distinct().sorted() }
    }

    /** The calls with [callSites] read from source, and cards using [facts] (of the current build) where present, else the snapshot's. */
    fun source(callSites: Map<Int, List<CallSite>> = emptyMap(), facts: Map<Int, SymbolFacts> = emptyMap()): FlowSource =
        FlowSource(calls, callers, callSites) { card(it, facts[it]) }

    /** The column for enrichment flow [flowId]: its entry under the flow's name, or null when it no longer exists. */
    fun start(flowId: String): FlowStart? =
        lens?.flows?.firstOrNull { it.id == flowId }?.let { FlowStart(it.entry, it.name, it.description) }

    fun card(id: Int, current: SymbolFacts? = null): FlowCard {
        val node = graph.nodes[id]
        val symbol = current ?: lens?.facts?.get(id)
        val annotation = lens?.nodes?.get(id)
        val meta = listOfNotNull(
            "▶ entry point".takeIf { (annotation?.entryPoint ?: 0.0) >= EnrichmentLens.ENTRY_POINT },
            "creates".takeIf { node.kind == SymbolKind.CLASS },
            node.location.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        val details = annotation?.let { a ->
            listOfNotNull(
                a.layer?.choice,
                a.domain?.choice?.takeIf { it != Domain.NONE }?.let { choice -> domains.firstOrNull { it.id == choice }?.name ?: choice },
                a.concerns.filterValues { it >= EnrichmentLens.BADGE_CONCERN }.entries.sortedByDescending { it.value }
                    .take(EnrichmentLens.MAX_BADGES).takeIf { it.isNotEmpty() }?.joinToString(", ") { it.key },
                "utility".takeIf { (a.utility ?: 0.0) >= EnrichmentLens.UTILITY },
            ).joinToString(" · ")
        }
        val tooltip = listOfNotNull(
            symbol?.signature?.takeIf { it.isNotBlank() },
            symbol?.doc,
            annotation?.importance?.let { "importance ${"%.1f".format(java.util.Locale.ROOT, it.score)}/${it.max}" },
        ).joinToString("\n").ifEmpty { null }
        return FlowCard(
            title = title(graph, id, symbol),
            meta = meta.ifEmpty { null },
            description = symbol?.doc?.let { summary(it) },
            details = details?.ifEmpty { null },
            tooltip = tooltip,
            color = annotation?.let(colours::value)?.let { colours.color(it.first) },
        )
    }

    companion object {
        /** `Owner.name` without parameters: the class for methods, the module or file for top-level functions. */
        fun title(graph: CallGraph, id: Int, facts: SymbolFacts? = null): String {
            val node = graph.nodes[id]
            val name = node.name.substringBefore('(')
            if (node.kind == SymbolKind.CLASS) return name.substringBefore('<')
            // Python containers read `Class in package.module`; others are qualified names or paths.
            val owner = (facts?.container ?: node.container).substringBefore(" in ").substringAfterLast('.').substringAfterLast('/').trim()
            return if (owner.isEmpty() || owner == name) name else "$owner.$name"
        }

        /** The first paragraph of a doc comment, as one line. */
        internal fun summary(doc: String): String? =
            doc.trim().split(Regex("\\n\\s*\\n")).first().replace(Regex("\\s+"), " ").trim().ifEmpty { null }
    }
}
