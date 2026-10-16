package dev.insight.callgraph

import java.awt.Color

/** Text of one step's card in a [FlowChart]. */
data class FlowCard(
    val title: String,
    /** Small accent line above the title, such as the step number and file. */
    val meta: String? = null,
    val description: String? = null,
    /** Small print under the description: layer, domain, concerns. */
    val details: String? = null,
    val tooltip: String? = null,
    /** Colour of the step's dot, for example its domain; the accent colour when null. */
    val color: Color? = null,
    /** Known execution entry, independent of whether this step was placed as a chart column. */
    val entryPoint: Boolean = false,
)

sealed interface FlowItem

/**
 * A function on the chart, numbered in reading order. It calls [calls] functions, listed in [items] while its calls
 * are open, and has [callers] direct callers, listed in [upstream] while they are open (null while closed).
 */
data class FlowStep(
    val node: Int,
    val number: Int,
    val card: FlowCard,
    val items: List<FlowItem> = emptyList(),
    val calls: Int = 0,
    val callers: Int = 0,
    val upstream: List<FlowItem>? = null,
) : FlowItem

/** Another occurrence of a function already shown as step [number]. */
data class FlowRef(val node: Int, val number: Int, val title: String) : FlowItem

/** A fork: one of [branches] runs, then the flow continues below, unless that branch [ends]. */
data class FlowDecision(val kind: DecisionKind, val question: String, val branches: List<FlowBranch>) : FlowItem

/** [items] may be empty: the branch skips straight to the end of the decision. */
data class FlowBranch(val label: String, val kind: BranchKind, val items: List<FlowItem> = emptyList())

/** The function is left here by a `return`, `throw` or `raise` ([keyword]); [text] is the statement. */
data class FlowExit(val keyword: String, val text: String) : FlowItem

/** Whether running [items] certainly leaves the function: they end with an exit, or with a decision all of whose branches end. */
fun ends(items: List<FlowItem>): Boolean = when (val last = items.lastOrNull()) {
    is FlowExit -> true
    is FlowDecision -> last.branches.all { ends(it.items) }
    else -> false
}

/** Calls repeated by a loop. */
data class FlowLoop(val label: String, val items: List<FlowItem>) : FlowItem

/** [CONDITION]: `if` or `when`/`switch`; [DISPATCH]: a call of a base method runs one of its implementations; [ERROR]: a `catch`. */
enum class DecisionKind { CONDITION, DISPATCH, ERROR }

/** [OTHERWISE]: `else`, `default`, or the implicit path when no condition holds; [ERROR]: the error handler. */
enum class BranchKind { CASE, OTHERWISE, ERROR }

/** A function placed on the chart as a column of its own, under [title] (its card title when null), such as a named flow. */
data class FlowStart(val node: Int, val title: String? = null, val description: String? = null)

/**
 * What the chart shows: the functions placed on it, in column order, and the functions whose calls ([openCalls]) or
 * direct callers ([openCallers]) are shown, wherever they appear.
 */
data class FlowBoard(
    val starts: List<FlowStart> = emptyList(),
    val openCalls: Set<Int> = emptySet(),
    val openCallers: Set<Int> = emptySet(),
    val compact: Boolean = false,
    val openRegions: Set<String> = emptySet(),
    /** Chat-authored interpretations, keyed by the original bounded block; snapshot-specific and undoable. */
    val regionOverrides: Map<String, List<FlowRegionPlan>> = emptyMap(),
) {
    /** The board with ids moved by [id] (for example into a rebuilt graph); functions it maps to null are dropped. */
    fun map(id: (Int) -> Int?): FlowBoard = FlowBoard(
        starts.mapNotNull { start -> id(start.node)?.let { start.copy(node = it) } }.distinctBy { it.node },
        openCalls.mapNotNullTo(hashSetOf(), id),
        openCallers.mapNotNullTo(hashSetOf(), id),
        compact = compact, // Region occurrence ids are specific to a source snapshot.
    )
}

/** The calls the chart explores. */
data class FlowSource(
    /**
     * Calls by caller. Calls without a known call site are listed in this order; an INHERITS edge means the base
     * method (the caller) runs that implementation.
     */
    val calls: Map<Int, List<CallGraphEdge>>,
    /** Direct callers by callee. */
    val callers: Map<Int, List<Int>>,
    /**
     * Call sites and exits by caller; calls of callers without sites keep the order of [calls] and have no branches.
     */
    val callSites: Map<Int, List<SourceSite>> = emptyMap(),
    /** Candidate key → validated subgroups; an empty list means no coherent group was found. */
    val regionPlans: Map<String, List<FlowRegionPlan>> = emptyMap(),
    /**
     * Callees that compact boards leave out of their callers, counted on the caller's card instead: helpers such as
     * logging and plumbing that reach no visible side effect. Control structure and exits are never folded.
     */
    val folded: Set<Int> = emptySet(),
    /** Card of a function on compact boards, such as its summary; [card] when null. */
    val compactCard: ((Int) -> FlowCard)? = null,
    /** Lightweight facts for collapsed groups; do not build child cards just to show their effects. */
    val effectsOf: (Int) -> Set<String> = { emptySet() },
    val isEntryPoint: (Int) -> Boolean = { false },
    val card: (Int) -> FlowCard,
)

/** One column of the chart: a placed function with what is open around it. */
data class FlowLane(val title: String, val description: String?, val root: FlowStep)

data class FlowChart(val lanes: List<FlowLane>) {
    /** Function and region cards in reading order, for keyboard navigation through the compact view. */
    val visibleCards: List<FlowItem> by lazy { buildList {
        fun collect(item: FlowItem) {
            if (item is FlowStep) item.upstream.orEmpty().forEach(::collect)
            if (item is FlowStep || item is FlowRegion) add(item)
            children(item).forEach(::collect)
        }
        lanes.forEach { collect(it.root) }
    } }
    /** Visible regions, including those inside expanded regions. */
    val regions: List<FlowRegion> by lazy { buildList {
        fun collect(item: FlowItem) {
            if (item is FlowRegion) add(item)
            if (item is FlowStep) item.upstream.orEmpty().forEach(::collect)
            children(item).forEach(::collect)
        }
        lanes.forEach { collect(it.root) }
    } }
    /** Every step, upstream ones included, in [FlowStep.number] order. */
    val steps: List<FlowStep> by lazy { buildList { lanes.forEach { collect(it.root, this) } }.sortedBy { it.number } }

    private val byNode by lazy { steps.associateBy { it.node } }

    /** The step of [node]; a function is a step at most once. */
    fun step(node: Int): FlowStep? = byNode[node]

    private fun collect(item: FlowItem, into: MutableList<FlowStep>) {
        if (item is FlowStep) {
            item.upstream.orEmpty().forEach { collect(it, into) }
            into += item
        }
        children(item).forEach { collect(it, into) }
    }
}

/** Items directly inside [item]: a step's callees, every branch of a decision, a loop's body. */
fun children(item: FlowItem): List<FlowItem> = when (item) {
    is FlowStep -> item.items
    is FlowDecision -> item.branches.flatMap { it.items }
    is FlowLoop -> item.items
    is FlowRegion -> item.items
    is FlowRef, is FlowExit -> emptyList()
}

/** Number of steps inside [item], at any depth. */
fun stepsWithin(item: FlowItem): Int = children(item).sumOf { (if (it is FlowStep) 1 else 0) + stepsWithin(it) }

/**
 * Arranges the [board] as columns read top to bottom, one per placed function. A step with open callers has them
 * above it, side by side; a step with open calls is followed by what it calls, in source order from
 * [FlowSource.callSites]: calls under a condition, a loop or an error handler become [FlowDecision]s and [FlowLoop]s,
 * a branch ending with a `return`, `throw` or `raise` ends with a [FlowExit] (so a guard such as `if (x) return` is a
 * decision even without calls), and an INHERITS edge (a base method dispatching to implementations) becomes a fork over the implementations. Every
 * function is a [FlowStep] once (placed functions at the top of their columns, others where first reached) and a
 * [FlowRef] elsewhere. Steps are numbered in reading order: callers before the function, calls after it. PSI-free.
 */
fun buildFlowChart(board: FlowBoard, source: FlowSource): FlowChart {
    val builder = FlowChartBuilder(board, source)
    val lanes = board.starts.map { start ->
        val root = builder.step(start.node)
        FlowLane(start.title ?: root.card.title, start.description, root)
    }
    return FlowChart(builder.numbered(lanes))
}

private class FlowChartBuilder(private val board: FlowBoard, private val source: FlowSource) {
    /** Functions that already have their step; placed functions are claimed first so they head their columns. */
    private val claimed = board.starts.mapTo(hashSetOf()) { it.node }
    private val cards = HashMap<Int, FlowCard>()
    private val codeCards = HashMap<Int, FlowCard>()
    private val folded = if (board.compact) source.folded else emptySet()

    private fun cardOf(node: Int) = cards.getOrPut(node) {
        source.compactCard?.takeIf { board.compact }?.invoke(node) ?: codeCardOf(node)
    }

    /** The card naming the code itself, for labels derived from code names. */
    private fun codeCardOf(node: Int) = codeCards.getOrPut(node) { source.card(node) }

    private fun item(node: Int): FlowItem = if (claimed.add(node)) step(node) else FlowRef(node, 0, cardOf(node).title)

    /** The step of [node], which must already be claimed. Numbers are assigned by [numbered]. */
    fun step(node: Int): FlowStep {
        val callers = source.callers[node].orEmpty().filter { it != node }.distinct()
        val upstream = if (node in board.openCallers) callers.map(::item) else null
        val calls = source.calls[node].orEmpty().filter { it.callee != node }
        val open = node in board.openCalls
        val items = if (open) items(node, calls) else emptyList()
        val hidden = calls.filter { it.kind != RelationKind.INHERITS && it.callee in folded }.mapTo(hashSetOf()) { it.callee }.size
        val card = cardOf(node).let { card ->
            if (!open || hidden == 0) card
            else card.copy(details = listOfNotNull(card.details, "+$hidden helper call${if (hidden == 1) "" else "s"} folded").joinToString(" · "))
        }
        return FlowStep(node, 0, card, items, calls.mapTo(hashSetOf()) { it.callee }.size, callers.size, upstream)
    }

    private fun items(node: Int, edges: List<CallGraphEdge>): List<FlowItem> {
        val rank = edges.withIndex().reversed().associate { it.value.callee to it.index }
        val calls = edges.filter { it.kind != RelationKind.INHERITS && it.callee !in folded }.mapTo(linkedSetOf()) { it.callee }
        val sites = source.callSites[node].orEmpty().filter { it !is CallSite || it.callee in calls }.sortedWith(sourceOrder)
            .distinctBy { if (it is CallSite) it.copy(text = null) else it }
        val result = group(node, sites, 0).toMutableList()
        val sited = sites.mapNotNullTo(hashSetOf()) { (it as? CallSite)?.callee }
        (calls - sited).forEach { result += item(it) }
        val implementations = edges.filter { it.kind == RelationKind.INHERITS }.map { it.callee }.distinct().sortedBy { rank[it] }
        if (implementations.size == 1) result += item(implementations.single())
        else if (implementations.size > 1) result += FlowDecision(DecisionKind.DISPATCH, "Which implementation?", implementations.map { id ->
            val title = codeCardOf(id).title
            FlowBranch(if ('.' in title) title.substringBeforeLast('.') else title, BranchKind.CASE, listOf(item(id)))
        })
        return result
    }

    /** [lanes] with steps numbered in reading order and references pointing at their steps. */
    fun numbered(lanes: List<FlowLane>): List<FlowLane> {
        val numbers = HashMap<Int, Int>()
        fun count(item: FlowItem) {
            if (item is FlowStep) {
                item.upstream.orEmpty().forEach(::count)
                numbers[item.node] = numbers.size + 1
            }
            children(item).forEach(::count)
        }
        lanes.forEach { count(it.root) }
        fun renumber(item: FlowItem): FlowItem = when (item) {
            is FlowStep -> item.copy(number = numbers.getValue(item.node), items = item.items.map(::renumber),
                upstream = item.upstream?.map(::renumber))
            is FlowRef -> item.copy(number = numbers[item.node] ?: 0)
            is FlowExit -> item
            is FlowDecision -> item.copy(branches = item.branches.map { it.copy(items = it.items.map(::renumber)) })
            is FlowLoop -> item.copy(items = item.items.map(::renumber))
            is FlowRegion -> item.copy(items = item.items.map(::renumber))
        }
        return lanes.map { it.copy(root = renumber(it.root) as FlowStep) }
    }

    /** Items for [sites] (in source order) whose control frames above [depth] are the same. */
    private fun group(owner: Int, sites: List<SourceSite>, depth: Int): List<FlowItem> {
        val result = mutableListOf<FlowItem>()
        var i = 0
        while (i < sites.size) {
            val frame = sites[i].frames.getOrNull(depth)
            if (frame == null && sites[i] is CallSite && board.compact) {
                var end = i + 1
                while (end < sites.size && sites[end] is CallSite && sites[end].frames.getOrNull(depth) == null) end++
                val run = sites.subList(i, end).filterIsInstance<CallSite>()
                for (chunk in run.chunked(FlowRegionEvidence.MAX_REGION_CALLS)) {
                    if (chunk.size == 1) { result += item(chunk.single().callee); continue }
                    val candidate = FlowRegionEvidence(owner, chunk)
                    val proposals = board.regionOverrides[candidate.key] ?: source.regionPlans[candidate.key]
                    val plans = if (proposals == null) listOf(FlowRegionPlan(candidate,
                        "Calls in ${codeCardOf(owner).title.substringAfterLast('.')}", "Expand to inspect ${chunk.size} call occurrences."))
                        else validateRegionPlans(candidate, proposals)
                    var at = 0
                    while (at < chunk.size) {
                        val plan = plans.firstOrNull { it.evidence.sites.first() == chunk[at] }
                        if (plan == null) { result += item(chunk[at++].callee); continue }
                        val expanded = plan.evidence.key in board.openRegions
                        result += FlowRegion(plan, expanded, if (expanded) plan.evidence.sites.map { item(it.callee) } else emptyList(),
                            effects = plan.evidence.sites.flatMapTo(sortedSetOf()) { source.effectsOf(it.callee) },
                            containsEntryPoint = plan.evidence.sites.any { source.isEntryPoint(it.callee) })
                        at += plan.evidence.sites.size
                    }
                }
                i = end
                continue
            }
            if (frame == null) {
                result += when (val site = sites[i++]) {
                    is CallSite -> item(site.callee)
                    is ExitSite -> FlowExit(site.keyword, site.text)
                }
                continue
            }
            var j = i + 1
            while (j < sites.size && sites[j].frames.getOrNull(depth)?.construct == frame.construct) j++
            val chunk = sites.subList(i, j)
            result += if (frame.kind == ControlKind.LOOP) FlowLoop(frame.label, group(owner, chunk, depth + 1)) else decision(owner, chunk, depth)
            i = j
        }
        return result
    }

    private fun decision(owner: Int, sites: List<SourceSite>, depth: Int): FlowDecision {
        val first = sites.first().frames[depth]
        // Branches in source order; their items are built in that order so steps are numbered as they are read.
        val branches = sites.groupBy { it.frames[depth].branch }.toSortedMap().values.map { it.first().frames[depth] to group(owner, it, depth + 1) }
        if (first.kind == ControlKind.ERROR) {
            return FlowDecision(DecisionKind.ERROR, "Fails?", listOf(FlowBranch("no", BranchKind.OTHERWISE)) +
                branches.map { (frame, items) -> FlowBranch(ControlText.clip("on error " + frame.label), BranchKind.ERROR, items) })
        }
        val conditions = branches.filter { !it.first.otherwise }
        val otherwise = branches.firstOrNull { it.first.otherwise }?.second
        if (first.kind == ControlKind.BRANCH && conditions.size <= 1 && first.subject != null && conditions.all { it.first.label == first.subject }) {
            return FlowDecision(DecisionKind.CONDITION, first.subject + "?", listOf(
                FlowBranch("yes", BranchKind.CASE, conditions.firstOrNull()?.second.orEmpty()),
                FlowBranch("no", BranchKind.OTHERWISE, otherwise.orEmpty()),
            ))
        }
        // `x == "a"`, `x == "b"`… reads better as the question `x?` with answers `"a"`, `"b"`.
        val compared = conditions.map { comparison.matchEntire(it.first.label) }
        val subject = compared.firstOrNull()?.groupValues?.get(1)?.takeIf { left ->
            conditions.size > 1 && compared.all { it != null && it.groupValues[1] == left }
        }
        val question = when {
            subject != null -> "$subject?"
            first.kind == ControlKind.CASE -> first.subject?.let { "$it?" } ?: "Which case?"
            else -> "Which condition?"
        }
        val cases = conditions.mapIndexed { index, (frame, items) ->
            FlowBranch(if (subject != null) compared[index]!!.groupValues[2] else frame.label, BranchKind.CASE, items)
        }
        val fallback = when {
            otherwise != null -> FlowBranch(if (first.kind == ControlKind.CASE) "default" else "else", BranchKind.OTHERWISE, otherwise)
            first.kind == ControlKind.CASE -> FlowBranch("other cases", BranchKind.OTHERWISE)
            else -> FlowBranch("otherwise", BranchKind.OTHERWISE)
        }
        return FlowDecision(DecisionKind.CONDITION, question, cases + fallback)
    }

    private companion object {
        val comparison = Regex("""(.+?)\s*===?\s*(.+)""")
    }
}
