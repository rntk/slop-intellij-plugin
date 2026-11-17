package dev.insight.codereview.map

import dev.insight.review.reviewFingerprint

data class CodeMapOptions(
    /** A unit used by at least this many implementation units is shared: a foundation that never glues flows together. */
    val sharedFanIn: Int = 6,
)

/**
 * Turns references into typed edges, from the user to what it uses. A unit's implementation users decide how it relates:
 * its only user owns it ([CodeRelation.SOLE_USE]), a few users are judged ([CodeRelation.USES]), and many make it shared
 * ([CodeRelation.SHARED_USE]). Inheritance is strong unless the base is shared. Each test unit tests one unit, the one its
 * name names or else the one it uses most; documentation mentions are judged. Pure.
 */
internal fun relateCode(units: List<CodeUnit>, references: List<CodeReference>, options: CodeMapOptions = CodeMapOptions()): CodeGraph {
    val byId = units.associateBy { it.id }
    val pairs = references.filter { it.from != it.to && it.from in byId && it.to in byId }.groupBy { it.from to it.to }
    fun kind(id: String) = byId.getValue(id).evidenceKind
    val users = HashMap<String, MutableSet<String>>()
    for ((pair, _) in pairs) {
        val (from, to) = pair
        // Users of the same kind count: implementation for code, tests for shared test fixtures.
        if (kind(from) == kind(to) && kind(from) != "documentation") users.getOrPut(to) { linkedSetOf() } += from
    }
    val fanIn = units.associate { it.id to (users[it.id]?.size ?: 0) }
    val shared = units.filter { it.evidenceKind != "documentation" && fanIn.getValue(it.id) >= options.sharedFanIn }.mapTo(linkedSetOf()) { it.id }
    val tested = units.filter { it.evidenceKind == "test" }.associate { test -> test.id to testedUnit(test, pairs, byId) }
    val edges = mutableListOf<CodeEdge>()
    for ((pair, found) in pairs) {
        val (from, to) = pair
        val kinds = found.mapTo(hashSetOf()) { it.kind }
        val relation = when {
            kind(from) == "documentation" -> if (kind(to) == "documentation") null else CodeRelation.DOCUMENTS
            kind(to) == "documentation" -> null
            kind(from) == "test" && kind(to) != "test" -> if (tested[from] == to) CodeRelation.TESTS else CodeRelation.ALSO_TESTS
            kind(from) != "test" && kind(to) == "test" -> null
            to in shared -> CodeRelation.SHARED_USE
            CodeReferenceKind.INHERITS in kinds -> CodeRelation.EXTENDS
            CodeReferenceKind.OVERRIDE in kinds -> CodeRelation.OVERRIDES
            fanIn.getValue(to) == 1 -> CodeRelation.SOLE_USE
            else -> CodeRelation.USES
        } ?: continue
        edges += CodeEdge(from, to, relation)
    }
    val index = units.withIndex().associate { (i, unit) -> unit.id to i }
    return CodeGraph(units, edges.sortedWith(compareBy({ index.getValue(it.from) }, { index.getValue(it.to) })), fanIn, shared, references)
}

/** The implementation unit [test] tests: the one its name names (FooTest tests Foo), else the one it refers to most. */
private fun testedUnit(test: CodeUnit, pairs: Map<Pair<String, String>, List<CodeReference>>, byId: Map<String, CodeUnit>): String? {
    val targets = pairs.filterKeys { it.first == test.id }.mapNotNull { (pair, found) ->
        byId[pair.second]?.takeIf { it.evidenceKind == "implementation" }?.let { it to found.sumOf { reference -> reference.count } }
    }
    if (targets.isEmpty()) return null
    val names = listOf(test.simpleName, test.path.substringAfterLast('/').substringBefore('.')).map(::testedName).filter { it.isNotEmpty() }.toSet()
    return (targets.firstOrNull { (unit, _) -> unit.simpleName.lowercase() in names } ?: targets.maxBy { it.second }).first.id
}

/** "FooTest", "FooTests", "test_foo", "foo_test" and "FooSpec" name "foo". */
private fun testedName(name: String): String =
    name.lowercase().removePrefix("test_").removeSuffix("tests").removeSuffix("test").removeSuffix("spec").trim('_', '-', '.')

/**
 * Groups units over strong edges into flows. A shared unit takes its own helpers, subclasses' tests and tests, but its
 * users never join it: they list it as shared instead. Documentation joins the flow it mentions most. Each flow is
 * ordered from its roots.
 */
internal fun clusterCode(graph: CodeGraph, files: Int = 0, analyzed: Int = files): CodeMap {
    val units = graph.units
    val index = units.withIndex().associate { (i, unit) -> unit.id to i }
    val parent = IntArray(units.size) { it }
    fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; var c = x; while (parent[c] != r) { val n = parent[c]; parent[c] = r; c = n }; return r }
    fun union(a: Int, b: Int) { val x = find(a); val y = find(b); if (x != y) parent[maxOf(x, y)] = minOf(x, y) }
    for (edge in graph.edges) {
        if (edge.relation.strength != LinkStrength.STRONG) continue
        // Implementations of a shared base are separate flows that share it.
        if (edge.to in graph.shared && edge.relation != CodeRelation.TESTS) continue
        union(index.getValue(edge.from), index.getValue(edge.to))
    }
    for (doc in units.filter { it.evidenceKind == "documentation" }) {
        val documented = graph.edges.filter { it.from == doc.id && it.relation == CodeRelation.DOCUMENTS }.mapTo(hashSetOf()) { it.to }
        val mentions = graph.references.filter { it.from == doc.id && it.to in documented }.groupBy({ find(index.getValue(it.to)) }, { it.count })
        val best = mentions.mapValues { it.value.sum() }.entries.maxWithOrNull(compareBy({ it.value }, { -it.key }))?.key ?: continue
        union(index.getValue(doc.id), best)
    }
    val flows = units.indices.groupBy { find(it) }.values.map { members -> flowOf(graph, index, members.map { units[it] }) }
    return CodeMap(graph, orderedFlows(graph, flows), files, analyzed)
}

/** A flow of [members] with its chain: depth from the roots, then tests, then documentation; and the shared units it uses. */
private fun flowOf(graph: CodeGraph, index: Map<String, Int>, members: List<CodeUnit>, title: String? = null): CodeFlow {
    val sorted = members.sortedBy { index.getValue(it.id) }
    val ids = sorted.map { it.id }.toSet()
    val code = sorted.filter { it.evidenceKind == "implementation" }
    val codeIds = code.map { it.id }.toSet()
    val edges = graph.edges.filter { it.relation.strength != LinkStrength.WEAK && it.from in codeIds && it.to in codeIds }
    val next = edges.groupBy({ it.from }, { it.to })
    val used = edges.map { it.to }.toSet()
    val depth = HashMap<String, Int>()
    fun walk(roots: List<String>) {
        val queue = ArrayDeque<String>()
        roots.filter { it !in depth }.forEach { depth[it] = 0; queue += it }
        while (queue.isNotEmpty()) {
            val unit = queue.removeFirst()
            for (to in next[unit].orEmpty()) if (to !in depth) { depth[to] = depth.getValue(unit) + 1; queue += to }
        }
    }
    walk(code.filter { it.id !in used }.map { it.id })
    // A cycle leaves no root: the unit that uses the most of the rest starts it.
    while (code.any { it.id !in depth }) {
        val rest = code.filter { it.id !in depth }
        walk(listOf(rest.maxWith(compareBy<CodeUnit>({ next[it.id].orEmpty().size }, { -index.getValue(it.id) })).id))
    }
    val foundation = ids.any { it in graph.shared }
    val single = sorted.size == 1 && !foundation
    val steps = sorted.sortedWith(compareBy({ EVIDENCE_ORDER.indexOf(it.evidenceKind) }, { depth[it.id] ?: 0 }, { it.path }, { it.lines?.first ?: 0 }))
        .map { unit ->
            val role = when {
                single -> FlowRole.STANDALONE
                unit.evidenceKind == "test" -> FlowRole.TEST
                unit.evidenceKind == "documentation" -> FlowRole.DOCUMENTATION
                depth[unit.id] == 0 -> FlowRole.ROOT
                else -> FlowRole.FOLLOWS
            }
            FlowStep(unit.id, role, depth[unit.id] ?: 0)
        }
    val roots =steps.filter { it.role == FlowRole.ROOT || it.role == FlowRole.STANDALONE }.map { it.unit }
    val entry = !foundation && roots.any { graph.unit(it)?.evidenceKind == "implementation" && (graph.fanIn[it] ?: 0) == 0 }
    val shared = graph.edges.filter { it.from in ids && it.to !in ids && it.to in graph.shared && it.relation != CodeRelation.ALSO_TESTS }
        .map { it.to }.distinct()
    return CodeFlow(reviewFingerprint(*ids.sorted().toTypedArray()), steps, title, foundation, entry, shared)
}

private val EVIDENCE_ORDER = listOf("implementation", "test", "documentation")

/** Entry flows first, then other flows and foundations, the largest first; then standalone units in file order. */
private fun orderedFlows(graph: CodeGraph, flows: List<CodeFlow>): List<CodeFlow> {
    fun lines(flow: CodeFlow) = flow.units.sumOf { graph.unit(it)?.lineCount ?: 0 }
    val (single, chains) = flows.partition { it.standalone }
    return chains.sortedWith(compareBy<CodeFlow>({ if (it.entry) 0 else if (it.foundation) 2 else 1 }, { -lines(it) }, { it.id })) +
        single.sortedWith(compareBy<CodeFlow>({ graph.unit(it.units.first())?.path.orEmpty() }, { graph.unit(it.units.first())?.lines?.first ?: 0 }, { it.id }))
}

/** Flow pairs linked by medium edges, most linked first; a model judges them. Foundations never merge. */
internal fun flowMergeCandidates(map: CodeMap): List<FlowMergeCandidate> =
    map.graph.edges.filter { it.relation.strength == LinkStrength.MEDIUM }.mapNotNull { edge ->
        val a = map.flowOf(edge.from)?.takeIf { !it.foundation } ?: return@mapNotNull null
        val b = map.flowOf(edge.to)?.takeIf { !it.foundation } ?: return@mapNotNull null
        if (a.id == b.id || a.standalone || b.standalone) null else (if (a.id < b.id) a.id to b.id else b.id to a.id) to edge
    }.groupBy({ it.first }, { it.second }).map { (pair, edges) -> FlowMergeCandidate(pair.first, pair.second, edges) }
        .sortedWith(compareBy({ -it.edges.size }, { it.a }, { it.b }))

/** Flows a standalone unit is linked to by a medium or strong edge, most linked first; a model may attach it to one. */
internal fun flowAttachCandidates(map: CodeMap, standalone: CodeFlow): List<CodeFlow> {
    val own = standalone.units.toSet()
    val counts = map.graph.edges.filter { it.relation.strength != LinkStrength.WEAK && (it.from in own) != (it.to in own) }
        .mapNotNull { map.flowOf(if (it.from in own) it.to else it.from) }
        .filter { !it.standalone && !it.foundation }.groupingBy { it.id }.eachCount()
    val position = map.flows.withIndex().associate { (i, flow) -> flow.id to i }
    return counts.keys.sortedWith(compareBy({ -counts.getValue(it) }, { position.getValue(it) })).map { map.flow(it)!! }
}

/** The map with each of [groups] (flow ids) merged into one flow with a recomputed chain; titles of merged flows are dropped. */
internal fun CodeMap.mergedFlows(groups: List<Set<String>>): CodeMap {
    val parent = HashMap<String, String>()
    fun find(x: String): String = parent[x]?.takeIf { it != x }?.let(::find) ?: x
    val touched = mutableSetOf<String>()
    for (group in groups) {
        val known = group.filter { flow(it) != null }
        if (known.size < 2) continue
        touched += known
        known.drop(1).forEach { parent[find(it)] = find(known.first()) }
    }
    val merged = flows.map { it.id }.filter { it in touched }.groupBy { find(it) }.values
    if (merged.isEmpty()) return this
    val index = graph.units.withIndex().associate { (i, unit) -> unit.id to i }
    val combined = merged.map { ids -> flowOf(graph, index, ids.flatMap { units(flow(it)!!) }) }
    return copy(flows = orderedFlows(graph, flows.filter { it.id !in touched } + combined))
}

/** "B is used by A" sentences for an edge, or null when an end is not in the graph. */
internal fun CodeGraph.edgeSentence(edge: CodeEdge): String? {
    val from = unit(edge.from) ?: return null
    val to = unit(edge.to) ?: return null
    return "${from.reference()} ${edge.relation.label} ${to.reference()}"
}

/** A unit in a sentence: "Foo (src/Foo.kt)". */
internal fun CodeUnit.reference(): String = when (kind) {
    UnitKind.CLASS -> "$name ($path)"
    UnitKind.TOP_LEVEL -> "top-level code of $path"
    UnitKind.FILE -> path
}

/** The edges' sentences, strongest relation first, without repeats. */
internal fun CodeMap.sentences(edges: List<CodeEdge>, limit: Int): List<String> =
    edges.sortedBy { it.relation.strength.ordinal }.mapNotNull(graph::edgeSentence).distinct().take(limit)
