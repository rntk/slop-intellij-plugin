package dev.insight.callgraph

/** A bounded, consecutive sequence of call occurrences in one function and one control branch. */
data class FlowRegionEvidence(val owner: Int, val sites: List<CallSite>) {
    init { require(sites.size in 2..MAX_REGION_CALLS && sites.all { it.frames == sites.first().frames }) }
    /** Occurrences, not just callee symbols: repeated calls remain distinct. Valid for this graph build only. */
    val key: String get() = "$owner:" + sites.joinToString(";") { "${it.offset}/${it.callee}" }
    companion object { const val MAX_REGION_CALLS = 6 }
}

/** Text is inferred; membership and ordering must be validated against [evidence]. */
data class FlowRegionPlan(
    val evidence: FlowRegionEvidence,
    val title: String,
    val description: String? = null,
    val inferred: Boolean = false,
)

/** A collapsed region does not build or claim its child functions until expanded. */
data class FlowRegion(val plan: FlowRegionPlan, val expanded: Boolean, val items: List<FlowItem> = emptyList(),
    val effects: Set<String> = emptySet(), val containsEntryPoint: Boolean = false) : FlowItem

/** Identifies an occurrence, including repeated calls to the same function. */
val CallSite.occurrenceId: String get() = "$offset/$callee"

/** Uses the same visible calls and folding rules as the compact chart builder. */
fun FlowSource.regionCandidates(owner: Int): List<FlowRegionEvidence> {
    val callees = calls[owner].orEmpty().filter { it.kind != RelationKind.INHERITS && it.callee != owner }
        .mapTo(hashSetOf()) { it.callee }
    return flowRegionCandidates(owner, callSites[owner].orEmpty().filter { it !is CallSite || it.callee in callees }, folded)
}

/**
 * Regions never straddle a decision, loop, exit or change of control frames. This only groups call occurrences;
 * statements that are not represented by the call-site analyzer remain outside the model's execution claims.
 */
fun flowRegionCandidates(owner: Int, sites: List<SourceSite>, folded: Set<Int> = emptySet()): List<FlowRegionEvidence> {
    val result = mutableListOf<FlowRegionEvidence>()
    val run = mutableListOf<CallSite>()
    fun flush() {
        run.chunked(FlowRegionEvidence.MAX_REGION_CALLS).filter { it.size >= 2 }
            .forEach { result += FlowRegionEvidence(owner, it) }
        run.clear()
    }
    // Folded helpers are not on the chart, so the calls around them are consecutive there.
    for (site in sites.sortedWith(sourceOrder).distinctBy { if (it is CallSite) it.copy(text = null) else it }) {
        if (site !is CallSite) { flush(); continue }
        if (site.callee in folded) continue
        if (run.isNotEmpty() && run.first().frames != site.frames) flush()
        run += site
    }
    flush()
    return result
}

/** Reject invented occurrences, reordered or disconnected groups, and overlapping membership. */
fun validateRegionPlans(candidate: FlowRegionEvidence, proposed: List<FlowRegionPlan>): List<FlowRegionPlan> {
    val used = hashSetOf<CallSite>()
    return proposed.sortedBy { candidate.sites.indexOf(it.evidence.sites.first()) }.filter { plan ->
        val members = plan.evidence.sites
        val start = candidate.sites.indexOf(members.first())
        val valid = plan.evidence.owner == candidate.owner && plan.title.isNotBlank() && start >= 0 &&
            candidate.sites.drop(start).take(members.size) == members && members.none { it in used }
        if (valid) used += members
        valid
    }
}

/** Shared card measurement and painting; this proxy never enters function selection or numbering. */
internal fun FlowRegion.presentation() = FlowStep(plan.evidence.owner, 0, FlowCard(
    title = (if (expanded) "▾ " else "▸ ") + plan.title,
    meta = "${plan.evidence.sites.size} call occurrences · " + if (plan.inferred) "LLM interpretation" else "structural group",
    description = plan.description,
    details = listOfNotNull(effects.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(", ", postfix = " via grouped calls"),
        "Contains entry point".takeIf { containsEntryPoint },
        if (expanded) "Click to collapse" else "Click to inspect source calls").joinToString(" · "),
))

/**
 * Functions whose calls to open so that [root]'s chart shows at most about [budget] items: breadth-first from [root],
 * a function is opened only when [expandable] and when all its [calls] still fit. Functions that do not fit, or that
 * read as one step, stay collapsed for the user to drill into. Calls already counted elsewhere still take a row as references.
 */
fun budgetedOpenCalls(root: Int, calls: (Int) -> List<Int>, expandable: (Int) -> Boolean, budget: Int): Set<Int> {
    val open = linkedSetOf<Int>()
    val seen = hashSetOf(root)
    var shown = 1
    val queue = ArrayDeque(listOf(root))
    while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        if (node != root && !expandable(node)) continue
        val callees = calls(node).filter { it != node }.distinct()
        if (callees.isEmpty() || (node != root && shown + callees.size > budget)) continue
        open += node
        shown += callees.size
        callees.filter(seen::add).forEach(queue::addLast)
    }
    return open
}
