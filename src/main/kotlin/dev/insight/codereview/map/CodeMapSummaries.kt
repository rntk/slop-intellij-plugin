package dev.insight.codereview.map

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.insight.codereview.CodeFile
import dev.insight.codereview.CodeSnapshot
import dev.insight.codereview.languageOf
import dev.insight.llm.CODE_AS_DATA
import dev.insight.llm.clipLine
import dev.insight.prompts.PromptTemplates
import dev.insight.review.BRANCH_CITATION_GUIDANCE
import dev.insight.review.LEAF_CITATION_GUIDANCE
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.SUMMARY_JSON_SHAPE
import dev.insight.review.SUMMARY_SEGMENT_GUIDANCE
import dev.insight.review.SUMMARY_WRITING_GUIDANCE
import dev.insight.review.SummaryEvidence
import dev.insight.review.SummaryKind
import dev.insight.review.SummaryNode
import dev.insight.review.balancedGroups
import dev.insight.review.numberedRows
import dev.insight.review.reviewFingerprint
import dev.insight.review.summaryFocusLine

internal const val CODE_MAP_ROOT_IDENTITY = "code-map-root"
internal const val FLOW_IDENTITY_PREFIX = "flow:"
internal const val UNIT_IDENTITY_PREFIX = "unit:"
internal const val STANDALONE_FLOW_IDENTITY = "flow:standalone"
private const val GROUP_IDENTITY_PREFIX = "flow-group:"
private const val STANDALONE_LABEL = "Standalone units"

/**
 * The summary tree of a code map: the root, a branch per flow in map order with its units in chain order, and one branch
 * holding the standalone units. Units are the leaves, each in the one flow it is a member of. A branch's key leaves out
 * its title, so a retitled flow keeps its summary while its units are unchanged.
 */
internal fun buildCodeMapTree(map: CodeMap, files: List<CodeFile>): ReviewSummaryTree {
    val byPath = files.associateBy { it.path }
    val nodes = mutableListOf<SummaryNode>()

    fun leaf(unit: CodeUnit, parent: Int): Int? {
        val file = byPath[unit.path] ?: return null
        val id = nodes.size
        val whole = unit.lines == null
        nodes += SummaryNode(id, if (whole) SummaryKind.FILE else SummaryKind.CHUNK, unit.path, unitLabel(unit), parent, emptyList(),
            listOf(unit.path), reviewFingerprint("unit", unit.id, unit.fingerprint), UNIT_IDENTITY_PREFIX + unit.id, file, lines = unit.lines)
        return id
    }

    fun add(kind: SummaryKind, path: String, label: String, identity: String, parent: Int?, keepEmpty: Boolean = false, entries: (Int) -> List<Int?>): Int? {
        val id = nodes.size
        nodes += SummaryNode(id, kind, path, label, parent, emptyList(), emptyList(), "", identity, isTopicBranch = true)
        val members = entries(id).filterNotNull()
        if (members.isEmpty() && !keepEmpty) {
            nodes.removeAt(id)
            return null
        }
        val children = balancedGroups(members, ReviewSummaryTree.MAX_CHILDREN) { group ->
            val groupId = nodes.size
            nodes += SummaryNode(groupId, SummaryKind.GROUP, path, "${nodes[group.first()].label} … ${nodes[group.last()].label}", id, group,
                group.flatMap { nodes[it].files }.distinct(), reviewFingerprint("group", *group.map { nodes[it].key }.toTypedArray()),
                "$GROUP_IDENTITY_PREFIX$identity:${nodes[group.first()].identity}:${nodes[group.last()].identity}", isTopicBranch = true)
            group.forEach { nodes[it] = nodes[it].copy(parent = groupId) }
            groupId
        }
        nodes[id] = nodes[id].copy(children = children, files = children.flatMap { nodes[it].files }.distinct(),
            key = reviewFingerprint(identity, *children.map { nodes[it].key }.toTypedArray()))
        return id
    }

    add(SummaryKind.ROOT, "", "Code map", CODE_MAP_ROOT_IDENTITY, null, keepEmpty = true) { root ->
        map.chains.map { flow ->
            val title = map.title(flow)
            add(SummaryKind.DIRECTORY, title, title, FLOW_IDENTITY_PREFIX + flow.id, root) { branch -> map.units(flow).map { leaf(it, branch) } }
        } + listOf(map.standalone.takeIf { it.isNotEmpty() }?.let { standalone ->
            add(SummaryKind.DIRECTORY, STANDALONE_LABEL, STANDALONE_LABEL, STANDALONE_FLOW_IDENTITY, root) { branch ->
                standalone.flatMap { map.units(it) }.map { leaf(it, branch) }
            }
        })
    }
    // Groups were added after their members; restore the summary tree's pre-order contract.
    val order = mutableListOf<Int>()
    fun visit(id: Int) { order += id; nodes[id].children.forEach(::visit) }
    visit(0)
    val ids = order.withIndex().associate { it.value to it.index }
    return ReviewSummaryTree(order.map { old ->
        nodes[old].copy(id = ids.getValue(old), parent = nodes[old].parent?.let(ids::getValue), children = nodes[old].children.map(ids::getValue))
    })
}

internal fun unitLabel(unit: CodeUnit): String = when (unit.kind) {
    UnitKind.CLASS -> unit.name
    UnitKind.TOP_LEVEL -> "${unit.path.substringAfterLast('/')} · top-level code"
    UnitKind.FILE -> unit.path.substringAfterLast('/')
}

internal fun CodeMap.unitOf(node: SummaryNode): CodeUnit? =
    node.identity.takeIf { it.startsWith(UNIT_IDENTITY_PREFIX) }?.removePrefix(UNIT_IDENTITY_PREFIX)?.let(graph::unit)

internal fun CodeMap.flowOf(node: SummaryNode): CodeFlow? =
    node.identity.takeIf { it.startsWith(FLOW_IDENTITY_PREFIX) && it != STANDALONE_FLOW_IDENTITY }?.removePrefix(FLOW_IDENTITY_PREFIX)?.let(::flow)

/**
 * The source lines of a unit as ranges: a class's lines, each top-level declaration of a file that also has classes, or
 * the whole file.
 */
internal fun unitRanges(unit: CodeUnit, file: CodeFile): List<IntRange> = when {
    unit.kind == UnitKind.TOP_LEVEL && unit.lines != null && unit.members.isNotEmpty() -> unit.members.map { it.lines }
    unit.lines != null -> listOf(unit.lines)
    file.lineCount == 0 -> emptyList()
    else -> listOf(1..file.lineCount)
}

private const val MAX_RELATIONS = 8
private const val MAX_NAMED = 3

/**
 * What code knows of [unit]'s links as sentences, at most [limit]: what it uses and what uses it, strongest first,
 * those inside its flow before those in other flows.
 */
internal fun CodeMap.unitRelations(unit: CodeUnit, limit: Int = MAX_RELATIONS): List<String> {
    val own = flowOf(unit.id)?.units?.toSet().orEmpty()
    val touching = graph.edges.filter { (it.from == unit.id) != (it.to == unit.id) }
    fun sentences(edges: List<CodeEdge>, suffix: String): List<String> {
        val uses = edges.filter { it.from == unit.id }.sortedBy { it.relation.strength.ordinal }.mapNotNull { edge ->
            graph.unit(edge.to)?.let { "${edge.relation.label} ${it.reference()}$suffix" }
        }
        val users = edges.filter { it.to == unit.id }.groupBy { it.relation }.entries.sortedBy { it.key.strength.ordinal }.mapNotNull { (relation, group) ->
            val named = group.mapNotNull { graph.unit(it.from) }.distinctBy { it.id }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val list = named.take(MAX_NAMED).joinToString(", ") { it.reference() } + if (named.size > MAX_NAMED) ", …" else ""
            if (named.size == 1) "${relation.passive} $list$suffix" else "${relation.passive} ${named.size} units: $list$suffix"
        }
        return uses + users
    }
    val (inside, across) = touching.partition { (if (it.from == unit.id) it.to else it.from) in own }
    return (sentences(inside, "") + sentences(across, " (in another flow)")).distinct().take(limit)
}

/** What a summary request shows of a code-map leaf, a unit's own source, and of a flow, its chain. */
internal class CodeMapSummaryEvidence(private val map: CodeMap, private val snapshot: CodeSnapshot) : SummaryEvidence {
    private fun fileOf(unit: CodeUnit): CodeFile? = snapshot.byPath[unit.path]

    private fun code(unit: CodeUnit): List<Pair<IntRange, String>> {
        val file = fileOf(unit) ?: return emptyList()
        return unitRanges(unit, file).map { it to file.lines(it) }
    }

    override fun size(node: SummaryNode): Int = map.unitOf(node)?.let { unit -> code(unit).sumOf { it.second.length + 1 } } ?: 0

    override fun rows(node: SummaryNode): IntRange {
        val unit = map.unitOf(node) ?: return IntRange.EMPTY
        val ranges = fileOf(unit)?.let { unitRanges(unit, it) }.orEmpty()
        return if (ranges.isEmpty()) IntRange.EMPTY else ranges.minOf { it.first }..ranges.maxOf { it.last }
    }

    override fun evidenceKind(node: SummaryNode): String =
        map.unitOf(node)?.evidenceKind ?: node.files.map(::codeEvidenceKind).distinct().singleOrNull() ?: "mixed"

    override fun describe(node: SummaryNode, item: JsonObject, limit: Int) {
        val unit = map.unitOf(node) ?: return
        val file = fileOf(unit) ?: return
        item.addProperty("path", unit.path)
        languageOf(unit.path)?.let { item.addProperty("language", it) }
        item.addProperty("unit", unitLabel(unit))
        item.addProperty("unit_kind", when (unit.kind) { UnitKind.CLASS -> "class"; UnitKind.TOP_LEVEL -> "top-level code"; UnitKind.FILE -> "file" })
        unit.header.takeIf { it.isNotEmpty() }?.let { item.addProperty("header", it.clipLine(300)) }
        item.addProperty("evidence_kind", unit.evidenceKind)
        item.addProperty("total_lines", file.lineCount)
        val parts = code(unit)
        val text = StringBuilder()
        for ((index, part) in parts.withIndex()) {
            if (index > 0) text.append("\n…\n")
            text.append(numberedRows(part.second, part.first.first))
        }
        val shown = text.toString()
        item.addProperty("code", if (shown.length <= limit) shown else shown.take(limit).substringBeforeLast('\n'))
        if (shown.length > limit) item.addProperty("code_cut", true)
        map.unitRelations(unit).takeIf { it.isNotEmpty() }?.let { relations -> item.add("relations", JsonArray().apply { relations.forEach(::add) }) }
    }

    override fun leafSystem(tree: ReviewSummaryTree, focus: String): String =
        "$SYSTEM_PREFIX\n" + PromptTemplates.load("code-map-leaf.md") + "\n" + LEAF_CITATION_GUIDANCE + summaryFocusLine(focus)

    override fun branchSystem(focus: String): String =
        "$SYSTEM_PREFIX\n" + PromptTemplates.load("code-map-branch.md") + "\n" + BRANCH_CITATION_GUIDANCE + summaryFocusLine(focus)

    override fun describeBranch(node: SummaryNode, item: JsonObject) {
        val present = snapshot.byPath.keys
        when {
            node.identity == CODE_MAP_ROOT_IDENTITY -> {
                item.addProperty("flows", map.chains.count { !it.foundation })
                item.addProperty("shared_foundations", map.chains.count { it.foundation })
                item.addProperty("standalone_units", map.standalone.size)
            }
            node.identity == STANDALONE_FLOW_IDENTITY ->
                item.addProperty("note", "Its children are standalone units that no strong relation links; do not look for a common purpose.")
            else -> map.flowOf(node)?.let { flow ->
                item.addProperty("flow_title", map.title(flow))
                if (flow.foundation) item.addProperty("shared_foundation", "Code that many other flows use.")
                if (flow.entry) item.addProperty("entry", "Its root is used by nothing else in scope: a likely entry point.")
                item.add("chain", chain(flow, present))
                if (flow.shared.isNotEmpty()) item.add("uses_shared", JsonArray().apply {
                    flow.shared.mapNotNull(map.graph::unit).take(MAX_RELATIONS).forEach { add(it.reference()) }
                })
            }
        }
    }

    /** The steps of [flow] in reading order; each names the relation that links an earlier step to it, when code found one. */
    private fun chain(flow: CodeFlow, present: Set<String>): JsonArray {
        val steps = flow.steps.filter { step -> map.graph.unit(step.unit)?.path in present }
        val position = steps.withIndex().associate { it.value.unit to it.index + 1 }
        val inside = map.edgesWithin(flow)
        return JsonArray().apply {
            steps.forEachIndexed { index, step ->
                val unit = map.graph.unit(step.unit) ?: return@forEachIndexed
                val link = inside.filter { it.to == step.unit && (position[it.from] ?: Int.MAX_VALUE) <= index }.minByOrNull { it.relation.strength.ordinal }
                    ?: inside.filter { it.from == step.unit && (position[it.to] ?: Int.MAX_VALUE) <= index }.minByOrNull { it.relation.strength.ordinal }
                add(JsonObject().apply {
                    addProperty("step", index + 1)
                    addProperty("role", step.role.name.lowercase())
                    addProperty("unit", unitLabel(unit))
                    addProperty("path", unit.path)
                    link?.let { edge -> map.graph.edgeSentence(edge)?.let { addProperty("relation", it) } }
                })
            }
        }
    }

    private companion object {
        val SYSTEM_PREFIX = PromptTemplates.render(
            "code-map-prefix.md", mapOf("CODE_AS_DATA" to CODE_AS_DATA, "SUMMARY_JSON_SHAPE" to SUMMARY_JSON_SHAPE),
        ) + "\n" + SUMMARY_WRITING_GUIDANCE + "\n" + SUMMARY_SEGMENT_GUIDANCE
    }
}
