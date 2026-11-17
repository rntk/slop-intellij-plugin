package dev.insight.codereview.map

import dev.insight.review.ReviewSummaries
import dev.insight.review.ui.SummaryWordWeights

/** What the code map card shows: the map of the current snapshot, if built, its summaries, and whether /code-map may run now. */
internal data class CodeMapCard(
    val map: CodeMap?,
    val summaries: ReviewSummaries,
    val running: Boolean,
    val canRun: Boolean,
)

/** The card's one command button: build a map, write its missing summaries, or build and write it again. */
internal enum class CodeMapAction(val label: String, val tooltip: String) {
    GENERATE("Generate", "Group the classes and files in scope into flows, then summarize each flow (/code-map)"),
    FILL("Fill summaries", "Write the summaries the map is still missing (/code-map)"),
    REGENERATE("Regenerate", "Build the map again and write every summary afresh (/code-map)"),
}

/** The list entry of the standalone units, after every flow. */
internal const val STANDALONE_FLOWS = "standalone"

/** A flow in the card's list, or all standalone units as [STANDALONE_FLOWS]. */
internal data class FlowEntry(val id: String, val title: String, val stats: String, val foundation: Boolean = false, val entry: Boolean = false)

/** A flow a unit's step links to; selecting it shows that flow. */
internal data class FlowLink(val entry: String, val title: String)

/** A member of a unit as a drilled-in step lists it, with the other units that use it and that it uses. */
internal data class MemberRow(val member: CodeMember, val usedBy: List<String>, val uses: List<String>) {
    val location: String get() = ":${member.lines.first}"
}

/**
 * One unit of a flow as the card lists it. [relations] link it to earlier steps, [links] to other flows, [members] are its
 * declarations for drilling in. A collapsed step shows only its [gist], the first sentence of its [summary]; [more] is
 * the rest. [via] is the strongest edge from an earlier step; steps that share it follow the same unit in the same way.
 */
internal data class UnitRow(
    val unit: CodeUnit,
    val depth: Int,
    val role: String,
    val name: String,
    val detail: String,
    val location: String,
    val size: String,
    val relations: List<String>,
    val summary: String?,
    val links: List<FlowLink>,
    val members: List<MemberRow>,
    val gist: String? = null,
    val more: String? = null,
    val via: CodeEdge? = null,
    val weights: Map<String, Double> = emptyMap(),
) {
    val key: String get() = UNIT_IDENTITY_PREFIX + unit.id
}

internal sealed interface UnitItem {
    val key: String
    val rows: List<UnitRow>
}

internal data class SingleUnit(val row: UnitRow) : UnitItem {
    override val key: String get() = row.key
    override val rows: List<UnitRow> get() = listOf(row)
}

/** At least [FLOW_FAN_OUT_MIN] units that follow one unit by one relation, such as the helpers only one class uses. */
internal data class UnitFan(override val key: String, val title: String, val stats: String, override val rows: List<UnitRow>) : UnitItem

internal data class UnitSection(val key: String, val title: String, val stats: String, val items: List<UnitItem>, val open: Boolean) {
    val rows: List<UnitRow> get() = items.flatMap { it.rows }
}

/** A shared unit of another flow that this flow uses: [users] are this flow's units that use it. */
internal data class SharedRow(val unit: CodeUnit, val owner: FlowLink?, val users: List<String>)

internal data class FlowDetail(
    val entry: String,
    val title: String,
    val summary: String?,
    val rows: List<UnitRow>,
    val sections: List<UnitSection> = emptyList(),
    /** Shared foundations of other flows that this flow uses. */
    val shared: List<SharedRow> = emptyList(),
    /** For a foundation: the flows that share it. */
    val sharers: List<FlowLink> = emptyList(),
    val weights: Map<String, Double> = emptyMap(),
) {
    /** The key of every part that expands: sections, fan-outs and steps. */
    val keys: List<String> get() = sections.flatMap { section ->
        listOf(section.key) + section.items.flatMap { item -> listOfNotNull(item.key.takeIf { item is UnitFan }) + item.rows.map { it.key } }
    }
}

internal const val FLOW_FAN_OUT_MIN = 3
internal const val FLOW_SECTION_OPEN_MAX = 4
private const val GIST_MAX = 220
private const val MEMBER_LINKS = 4

/**
 * Everything the code map card decides without Swing: header, list entries, the rows of a flow, and which summary belongs
 * to which flow or unit, matched by the tree identities `flow:<id>`, `unit:<id>` and the root.
 */
internal class CodeMapPresentation(private val card: CodeMapCard, private val tagsCloud: Boolean = false) {
    private val map = card.map
    private val nodeIds: Map<String, Int> = card.summaries.tree.nodes.associate { it.identity to it.id }
    private val wordWeights = SummaryWordWeights(card.summaries)
    private val texts: Map<String, String> = card.summaries.tree.nodes.mapNotNull { node -> card.summaries.text(node.id)?.let { node.identity to it } }.toMap()
    private val units: List<CodeUnit> get() = map?.graph?.units.orEmpty()

    val action: CodeMapAction get() = when {
        map == null -> CodeMapAction.GENERATE
        !card.summaries.complete -> CodeMapAction.FILL
        else -> CodeMapAction.REGENERATE
    }
    val empty: Boolean get() = map != null && units.isEmpty()

    val header: String get() {
        val map = map ?: return "Code map"
        val flows = map.chains.count { !it.foundation }
        val shared = map.chains.count { it.foundation }
        return "Code map · ${count(flows, "flow")} · $shared shared · ${map.standalone.size} standalone · " +
            "${count(units.size, "unit")} in ${count(units.map { it.path }.distinct().size, "file")}" +
            if (map.analyzed < map.files) " · ${map.analyzed} of ${map.files} files analyzed" else ""
    }

    val rootSummary: String? get() = map?.let { texts[CODE_MAP_ROOT_IDENTITY] }
    val rootWeights: Map<String, Double> get() = weights(CODE_MAP_ROOT_IDENTITY)

    private fun weights(identity: String): Map<String, Double> =
        if (tagsCloud) nodeIds[identity]?.let { wordWeights[it] }.orEmpty() else emptyMap()

    val progress: String? get() = map?.takeIf { card.summaries.total > 0 && !card.summaries.complete }
        ?.let { "${card.summaries.count} / ${card.summaries.total} summaries written" }

    val entries: List<FlowEntry> get() {
        val map = map ?: return emptyList()
        return map.chains.map { FlowEntry(it.id, map.title(it), stats(map.units(it)), it.foundation, it.entry) } +
            listOfNotNull(map.standalone.takeIf { it.isNotEmpty() }?.let { standalone ->
                FlowEntry(STANDALONE_FLOWS, "Standalone units (${standalone.size})", stats(standalone.flatMap(map::units)))
            })
    }

    /** [kept] while it is still an entry, else the first one. */
    fun selection(kept: String?): String? = entries.let { all -> all.firstOrNull { it.id == kept }?.id ?: all.firstOrNull()?.id }

    fun detail(entry: String): FlowDetail? {
        val map = map ?: return null
        if (entry == STANDALONE_FLOWS) {
            val rows = map.standalone.flatMap { flow -> map.units(flow).map { row(flow, it, FlowRole.STANDALONE, 0, emptyList(), null) } }
            val sections = rows.groupBy { standaloneRole(it.unit) }.entries.sortedBy { STANDALONE_ORDER.indexOf(it.key) }.map { (role, members) -> section(role, members) }
            return FlowDetail(entry, "Standalone units", texts[STANDALONE_FLOW_IDENTITY], rows, openSole(sections), weights = weights(STANDALONE_FLOW_IDENTITY))
        }
        val flow = map.flow(entry) ?: return null
        val position = flow.steps.withIndex().associate { it.value.unit to it.index }
        val inside = map.edgesWithin(flow)
        val rows = flow.steps.mapIndexedNotNull { index, step ->
            val unit = map.graph.unit(step.unit) ?: return@mapIndexedNotNull null
            val earlier = inside.filter { it.to == unit.id && (position[it.from] ?: Int.MAX_VALUE) < index }.sortedBy { it.relation.strength.ordinal }
            val own = inside.filter { it.from == unit.id && (position[it.to] ?: Int.MAX_VALUE) < index }.sortedBy { it.relation.strength.ordinal }
            val relations = (earlier.mapNotNull { edge -> map.graph.unit(edge.from)?.let { "${edge.relation.passive} ${displayName(it)}" } } +
                own.mapNotNull { edge -> map.graph.unit(edge.to)?.let { "${edge.relation.label} ${displayName(it)}" } }).distinct()
            row(flow, unit, step.role, step.depth, relations, earlier.firstOrNull())
        }
        val roles = flow.steps.associate { it.unit to it.role }
        val sections = rows.groupBy { roles.getValue(it.unit.id) }.entries.sortedBy { it.key.ordinal }.map { (role, members) -> section(role, members) }
        val ids = flow.units.toSet()
        val shared = flow.shared.mapNotNull { id ->
            val unit = map.graph.unit(id) ?: return@mapNotNull null
            val owner = map.flowOf(id)?.let { FlowLink(it.id, map.title(it)) }
            val users = map.graph.edges.filter { it.to == id && it.from in ids }.mapNotNull { map.graph.unit(it.from)?.let(::displayName) }.distinct()
            SharedRow(unit, owner, users)
        }
        val sharers = if (flow.foundation) map.sharers(flow).map { if (it.standalone) FlowLink(STANDALONE_FLOWS, "Standalone units") else FlowLink(it.id, map.title(it)) }.distinct() else emptyList()
        return FlowDetail(entry, map.title(flow), texts[FLOW_IDENTITY_PREFIX + flow.id], rows, openSole(sections), shared, sharers,
            weights(FLOW_IDENTITY_PREFIX + flow.id))
    }

    private fun section(role: FlowRole, rows: List<UnitRow>): UnitSection {
        val fans = rows.filter { it.via != null }.groupBy { it.via!!.from to it.via.relation }.values.filter { it.size >= FLOW_FAN_OUT_MIN }
        val fanOf = fans.flatMap { members -> members.map { it.unit.id to members } }.toMap()
        val items = mutableListOf<UnitItem>()
        val placed = hashSetOf<String>()
        for (row in rows) {
            val members = fanOf[row.unit.id]
            if (members == null) { items += SingleUnit(row); continue }
            if (!placed.add(members.first().unit.id)) continue
            val via = members.first().via!!
            val cause = map?.graph?.unit(via.from)?.let(::displayName) ?: "an earlier step"
            items += UnitFan("fan:${via.from}:${via.relation.name}", "${members.size} ${via.relation.passive} $cause", stats(members.map { it.unit }), members)
        }
        return UnitSection("section:${role.name}", flowSectionLabel(role), stats(rows.map { it.unit }), items,
            open = role == FlowRole.ROOT || rows.size <= FLOW_SECTION_OPEN_MAX)
    }

    private fun openSole(sections: List<UnitSection>) = sections.singleOrNull()?.let { listOf(it.copy(open = true)) } ?: sections

    private fun row(flow: CodeFlow, unit: CodeUnit, role: FlowRole, depth: Int, relations: List<String>, via: CodeEdge?): UnitRow {
        val map = map!!
        val links = map.graph.edges.filter { (it.from == unit.id) != (it.to == unit.id) }
            .mapNotNull { edge -> map.flowOf(if (edge.from == unit.id) edge.to else edge.from) }
            .filter { it.id != flow.id }
            .map { other -> if (other.standalone) FlowLink(STANDALONE_FLOWS, "Standalone units") else FlowLink(other.id, map.title(other)) }
            .distinct()
            .filterNot { flow.standalone && it.entry == STANDALONE_FLOWS }
        val summary = texts[UNIT_IDENTITY_PREFIX + unit.id]
        val gist = summary?.let(::flowGist)
        val more = summary?.takeIf { it != gist }
            ?.let { if (gist != null && it.startsWith(gist)) it.substring(gist.length).trim() else it }?.takeIf { it.isNotEmpty() }
        return UnitRow(unit, depth, flowRoleLabel(role), displayName(unit), unitDetail(unit, map), unitLocation(unit), "${unit.lineCount} lines",
            relations, summary, links, members(unit), gist, more, via, weights(UNIT_IDENTITY_PREFIX + unit.id))
    }

    /** Each member with the units whose code refers to it and the units it refers to, from the member-level references. */
    private fun members(unit: CodeUnit): List<MemberRow> {
        val references = map?.graph?.references.orEmpty()
        val into = references.filter { it.to == unit.id && it.toMember != null }.groupBy { it.toMember!! }
        val out = references.filter { it.from == unit.id && it.fromMember != null }.groupBy { it.fromMember!! }
        fun names(ids: List<String>) = ids.distinct().mapNotNull { map?.graph?.unit(it)?.let(::displayName) }.let { all ->
            all.take(MEMBER_LINKS) + listOfNotNull((all.size - MEMBER_LINKS).takeIf { it > 0 }?.let { "$it more" })
        }
        return unit.members.map { member ->
            MemberRow(member, names(into[member.id].orEmpty().map { it.from }), names(out[member.id].orEmpty().map { it.to }))
        }
    }

    private fun stats(units: List<CodeUnit>) =
        "${units.sumOf { it.lineCount }} lines · ${count(units.size, "unit")} · ${count(units.map { it.path }.distinct().size, "file")}"
}

internal fun flowRoleLabel(role: FlowRole): String = when (role) {
    FlowRole.ROOT -> "Root"
    FlowRole.FOLLOWS -> "Uses"
    FlowRole.TEST -> "Test"
    FlowRole.DOCUMENTATION -> "Docs"
    FlowRole.STANDALONE -> "Unit"
}

internal fun flowSectionLabel(role: FlowRole): String = when (role) {
    FlowRole.ROOT -> "Roots"
    FlowRole.FOLLOWS -> "Uses"
    FlowRole.TEST -> "Tests"
    FlowRole.DOCUMENTATION -> "Docs"
    FlowRole.STANDALONE -> "Units"
}

private val STANDALONE_ORDER = listOf(FlowRole.STANDALONE, FlowRole.TEST, FlowRole.DOCUMENTATION)

private fun standaloneRole(unit: CodeUnit): FlowRole = when (unit.evidenceKind) {
    "test" -> FlowRole.TEST
    "documentation" -> FlowRole.DOCUMENTATION
    else -> FlowRole.STANDALONE
}

/** How a step names a unit: a class by its name, top-level code and whole files by their file name. */
internal fun displayName(unit: CodeUnit): String = when (unit.kind) {
    UnitKind.CLASS -> unit.name
    UnitKind.TOP_LEVEL -> unit.path.substringAfterLast('/')
    UnitKind.FILE -> unit.path.substringAfterLast('/')
}

/** "class · 12 members · used by 3". */
internal fun unitDetail(unit: CodeUnit, map: CodeMap): String = listOfNotNull(
    when (unit.kind) { UnitKind.CLASS -> "class"; UnitKind.TOP_LEVEL -> "top-level code"; UnitKind.FILE -> "whole file" },
    unit.members.size.takeIf { it > 0 }?.let { if (it == 1) "1 member" else "$it members" },
    map.graph.fanIn[unit.id]?.takeIf { it > 0 }?.let { "used by $it" },
    "shared".takeIf { unit.id in map.graph.shared },
).joinToString(" · ")

/** The path with the unit's lines, when it has its own. */
internal fun unitLocation(unit: CodeUnit): String =
    unit.path + (unit.lines?.let { if (it.first == it.last) ":${it.first}" else ":${it.first}-${it.last}" } ?: "")

private val SENTENCE_END = Regex("""(?<=[.!?])\s+(?=[A-Z`"'(\[])""")

/** The first sentence of a summary, cut at a word when it runs past [GIST_MAX] characters. */
internal fun flowGist(summary: String): String {
    val text = summary.trim()
    val first = SENTENCE_END.find(text)?.let { text.substring(0, it.range.first) } ?: text
    if (first.length <= GIST_MAX) return first
    return first.take(GIST_MAX).substringBeforeLast(' ').trimEnd(',', ';', ':') + "…"
}

private fun count(value: Int, one: String) = if (value == 1) "1 $one" else "$value ${one}s"
