package dev.insight.enrichment

import dev.insight.callgraph.CallGraph
import dev.insight.callgraph.CallGraphEdge
import dev.insight.callgraph.FlowCard
import dev.insight.callgraph.FlowRegionPlan
import dev.insight.callgraph.FlowSource
import dev.insight.callgraph.FlowStart
import dev.insight.callgraph.GraphDecorations
import dev.insight.callgraph.SourceSite
import dev.insight.callgraph.SymbolKind
import dev.insight.callgraph.budgetedOpenCalls

/**
 * The calls the flow chart explores, keyed by ids of `graph` (the current build), and the text of its steps from
 * enrichment judgments: docs, layer, domain, concerns, function summaries and rule effects. Every in-project call is
 * retained, including utilities and error paths; compact boards fold regions and effect-free helpers into counts
 * without deleting evidence, and turning compact off shows every call. PSI-free and unit-testable.
 */
class FlowChartLens(snapshot: EnrichmentSnapshot?, private val graph: CallGraph, colorBy: ColorBy = ColorBy.DOMAIN,
    private val entryPoints: GraphDecorations = GraphDecorations.NONE) {
    private val lens = snapshot?.let { Remapped.of(it, graph) }
    private val domains = snapshot?.enrichment?.domains.orEmpty()
    private val colours = EnrichmentLens.Colouring(colorBy, domains)

    /** Calls the chart shows inside a step, by caller; base methods dispatch to their implementations. */
    val calls: Map<Int, List<CallGraphEdge>>

    /** Direct in-project callers by callee, including base methods of implementations; locals are lifted to their functions. */
    val callers: Map<Int, List<Int>>

    /** Bottom-up summaries and rule effects of the current build's functions. */
    val summaries: Map<Int, FunctionSummary> = lens?.summaries.orEmpty()
    private val effects: Map<Int, Effects> = lens?.effects.orEmpty()

    private fun isEntryPoint(id: Int) = entryPoints.nodes[id]?.entryPoint == true ||
        (lens?.nodes?.get(id)?.entryPoint ?: 0.0) >= EnrichmentLens.ENTRY_POINT

    /**
     * Helpers the compact chart folds into their callers: summarized as logging or plumbing, and reaching no storage or
     * network effect by rules. A model role alone never hides a call that writes or talks to the outside.
     */
    val folded: Set<Int> = summaries.filter { (id, summary) ->
        !isEntryPoint(id) && summary.role in FunctionRole.FOLDED && effects[id]?.reached.orEmpty().none { it in Effects.VISIBLE }
    }.keys

    init {
        val lifted = liftCalls(graph)
        val all = flowCalls(lifted, lens?.facts.orEmpty())
        calls = all.filterValues { it.isNotEmpty() }
        callers = all.values.asSequence().flatten().filter { it.caller != it.callee && graph.nodes[it.caller].inProject }
            .groupBy { it.callee }.mapValues { (_, edges) -> edges.map { it.caller }.distinct().sorted() }
    }

    /** The calls with [callSites] (and exits) read from source, and cards using [facts] (of the current build) where present, else the snapshot's. */
    fun source(callSites: Map<Int, List<SourceSite>> = emptyMap(), facts: Map<Int, SymbolFacts> = emptyMap(),
        regionPlans: Map<String, List<FlowRegionPlan>> = emptyMap()): FlowSource =
        FlowSource(calls, callers, callSites, regionPlans, folded, { compactCard(it, facts[it]) }) { card(it, facts[it]) }

    /**
     * Functions to open when [start] is placed on a compact chart: the main path down to about [budget] items. Folded
     * helpers take no room, and functions summarized as one step stay closed. Only the start opens without summaries.
     */
    fun openCalls(start: Int, budget: Int = DEFAULT_BUDGET): Set<Int> {
        if (summaries.isEmpty()) return setOf(start)
        return budgetedOpenCalls(start, { id -> calls[id].orEmpty().map { it.callee }.filter { it !in folded } },
            { id -> summaries[id]?.single != true }, budget)
    }

    /**
     * The card of [id] on compact boards: the summary as title and description, the code name in the meta line.
     * Effects stay rule-derived; the meta line says the text is a model interpretation.
     */
    fun compactCard(id: Int, current: SymbolFacts? = null): FlowCard {
        val code = card(id, current)
        val summary = summaries[id] ?: return code
        val symbol = current ?: lens?.facts?.get(id)
        val stale = current != null && !fresh(id, current)
        return code.copy(
            title = summary.label,
            meta = listOfNotNull(code.meta?.takeIf { it.startsWith("▶") }?.substringBefore(" · "), title(graph, id, symbol), if (stale) "summary predates source edits" else "LLM summary")
                .joinToString(" · "),
            description = summary.description ?: code.description,
            details = listOfNotNull(summary.role?.id, code.details).joinToString(" · ").ifEmpty { null },
            tooltip = listOfNotNull(graph.nodes[id].location.takeIf { it.isNotBlank() }, code.tooltip,
                "Title and description are a model interpretation of the source; effects come from code rules.").joinToString("\n"),
        )
    }

    /** Whether the summary of [id] was written from the source that [current] was read from. */
    private fun fresh(id: Int, current: SymbolFacts) = lens?.facts?.get(id)?.sourceHash == current.sourceHash

    /** Summaries still describing the source of [current] (facts of the current build), for reuse in new prompts. */
    fun freshSummaries(current: Map<Int, SymbolFacts>): Map<Int, FunctionSummary> =
        summaries.filter { (id, _) -> current[id]?.let { fresh(id, it) } == true }

    /** Rule effects as card text: direct effects plain, effects of called code marked `via calls`. */
    private fun effectText(id: Int): String? {
        val e = effects[id] ?: return null
        val own = Effects.VISIBLE.filter { it in e.own }
        val via = Effects.VISIBLE.filter { it in e.reached && it !in e.own }
        return listOfNotNull(own.takeIf { it.isNotEmpty() }?.joinToString(", "),
            via.takeIf { it.isNotEmpty() }?.joinToString(", ", postfix = " via calls"),
            "throws".takeIf { Effects.THROWS in e.own }).joinToString(" · ").ifEmpty { null }
    }

    /** The column for enrichment flow [flowId]: its entry under the flow's name, or null when it no longer exists. */
    fun start(flowId: String): FlowStart? =
        lens?.flows?.firstOrNull { it.id == flowId }?.let { FlowStart(it.entry, it.name, it.description) }

    fun card(id: Int, current: SymbolFacts? = null): FlowCard {
        val node = graph.nodes[id]
        val symbol = current ?: lens?.facts?.get(id)
        val annotation = lens?.nodes?.get(id)
        val meta = listOfNotNull(
            "▶ entry point".takeIf { isEntryPoint(id) },
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
        }.let { judged -> listOfNotNull(judged?.ifEmpty { null }, effectText(id)).joinToString(" · ") }
        val tooltip = listOfNotNull(
            entryPoints.nodes[id]?.tooltip,
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
            entryPoint = isEntryPoint(id),
        )
    }

    companion object {
        /** Items a placed function opens to on a compact chart before the user drills down. */
        const val DEFAULT_BUDGET = 24

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
