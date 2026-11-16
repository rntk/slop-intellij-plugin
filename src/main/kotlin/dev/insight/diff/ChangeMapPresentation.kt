package dev.insight.diff

import dev.insight.diff.atoms.AtomKind
import dev.insight.diff.atoms.AtomRole
import dev.insight.diff.atoms.AtomStatus
import dev.insight.diff.atoms.CHANGE_MAP_ROOT_IDENTITY
import dev.insight.diff.atoms.CLUSTER_IDENTITY_PREFIX
import dev.insight.diff.atoms.ATOM_IDENTITY_PREFIX
import dev.insight.diff.atoms.STANDALONE_CLUSTER_IDENTITY
import dev.insight.diff.atoms.ChangeAtom
import dev.insight.diff.atoms.ChangeCluster
import dev.insight.diff.atoms.ChangeMap
import dev.insight.diff.atoms.patchLineNumbers
import dev.insight.review.ReviewSummaries
import dev.insight.review.ui.EvidenceLine
import dev.insight.review.ui.LineMatch
import dev.insight.review.ui.LineSide
import dev.insight.review.ui.LineTrace

/** What the change map card shows: the map of the current snapshot, if built, its summaries, and whether /change-map may run now. */
internal data class ChangeMapCard(
    val map: ChangeMap?,
    val files: List<ReviewFile>,
    val summaries: ReviewSummaries,
    val running: Boolean,
    val canRun: Boolean,
)

/** The card's one command button: build a map, write its missing summaries, or build and write it again. */
internal enum class ChangeMapAction(val label: String, val tooltip: String) {
    GENERATE("Generate", "Group the changed declarations into logical changes, then summarize each chain (/change-map)"),
    FILL("Fill summaries", "Write the summaries the map is still missing (/change-map)"),
    REGENERATE("Regenerate", "Build the map again and write every summary afresh (/change-map)"),
}

/** The list entry of the standalone changes, after every chain. */
internal const val STANDALONE_ENTRY = "standalone"

/** A logical change in the card's list: a chain by its cluster id, or all standalone changes as [STANDALONE_ENTRY]. */
internal data class ChangeEntry(val id: String, val title: String, val stats: String, val foundation: Boolean = false)

/** A change [atom]'s step links to; selecting it shows that change. */
internal data class ChangeLink(val entry: String, val title: String)

/** One step of a chain as the card lists it. [relations] link it to earlier steps, [links] to other changes. */
internal data class StepRow(
    val atom: ChangeAtom,
    val depth: Int,
    val role: String,
    val name: String,
    val detail: String,
    val location: String,
    val counts: String,
    val relations: List<String>,
    val summary: String?,
    val links: List<ChangeLink>,
)

internal data class ChangeDetail(val entry: String, val title: String, val summary: String?, val rows: List<StepRow>)

/**
 * Everything the change map card decides without Swing: header, list entries, the rows of a chain, and which summary
 * belongs to which chain or atom, matched by the tree identities `cluster:<id>`, `atom:<id>` and the root.
 */
internal class ChangeMapPresentation(private val card: ChangeMapCard) {
    private val map = card.map
    private val texts: Map<String, String> = card.summaries.tree.nodes.mapNotNull { node -> card.summaries.text(node.id)?.let { node.identity to it } }.toMap()
    private val atoms: List<ChangeAtom> get() = map?.graph?.atoms.orEmpty()

    val action: ChangeMapAction get() = when {
        map == null -> ChangeMapAction.GENERATE
        !card.summaries.complete -> ChangeMapAction.FILL
        else -> ChangeMapAction.REGENERATE
    }
    val empty: Boolean get() = map != null && atoms.isEmpty()

    val header: String get() {
        val map = map ?: return "Change map"
        return "Change map · ${count(map.chains.size, "logical change")} · ${map.standalone.size} standalone · " +
            "${count(atoms.size, "declaration")} in ${count(atoms.map { it.path }.distinct().size, "file")}"
    }

    val rootSummary: String? get() = map?.let { texts[CHANGE_MAP_ROOT_IDENTITY] }

    val progress: String? get() = map?.takeIf { card.summaries.total > 0 && !card.summaries.complete }
        ?.let { "${card.summaries.count} / ${card.summaries.total} summaries written" }

    val entries: List<ChangeEntry> get() {
        val map = map ?: return emptyList()
        return map.chains.map { ChangeEntry(it.id, map.title(it), stats(map.atoms(it)), it.foundation) } +
            listOfNotNull(map.standalone.takeIf { it.isNotEmpty() }?.let { standalone ->
                ChangeEntry(STANDALONE_ENTRY, "Standalone changes (${standalone.size})", stats(standalone.flatMap(map::atoms)))
            })
    }

    /** [kept] while it is still an entry, else the first one. */
    fun selection(kept: String?): String? = entries.let { all -> all.firstOrNull { it.id == kept }?.id ?: all.firstOrNull()?.id }

    fun detail(entry: String): ChangeDetail? {
        val map = map ?: return null
        if (entry == STANDALONE_ENTRY) {
            val rows = map.standalone.flatMap { cluster -> map.atoms(cluster).map { row(cluster, it, AtomRole.STANDALONE, 0, emptyList()) } }
            return ChangeDetail(entry, "Standalone changes", texts[STANDALONE_CLUSTER_IDENTITY], rows)
        }
        val cluster = map.cluster(entry) ?: return null
        val position = cluster.steps.withIndex().associate { it.value.atom to it.index }
        val inside = map.edgesWithin(cluster)
        val rows = cluster.steps.mapIndexedNotNull { index, step ->
            val atom = map.graph.atom(step.atom) ?: return@mapIndexedNotNull null
            val relations = inside.filter { it.to == atom.id && (position[it.from] ?: Int.MAX_VALUE) < index }
                .sortedBy { it.relation.strength.ordinal }
                .mapNotNull { edge -> map.graph.atom(edge.from)?.let { "${edge.relation.label} ${it.name}" } }.distinct()
            row(cluster, atom, step.role, step.depth, relations)
        }
        return ChangeDetail(entry, map.title(cluster), texts[CLUSTER_IDENTITY_PREFIX + cluster.id], rows)
    }

    private fun row(cluster: ChangeCluster, atom: ChangeAtom, role: AtomRole, depth: Int, relations: List<String>): StepRow {
        val map = map!!
        val links = map.edgesAcross(cluster).filter { it.from == atom.id || it.to == atom.id }
            .mapNotNull { edge -> map.clusterOf(if (edge.from == atom.id) edge.to else edge.from) }
            .filter { it.id != cluster.id }
            .map { other -> if (other.standalone) ChangeLink(STANDALONE_ENTRY, "Standalone changes") else ChangeLink(other.id, map.title(other)) }
            .distinct()
            // A standalone row does not link to the entry it is listed in.
            .filterNot { cluster.standalone && it.entry == STANDALONE_ENTRY }
        return StepRow(atom, depth, roleLabel(role), atomName(atom), atomDetail(atom), atomLocation(atom), "+${atom.added} −${atom.removed}",
            relations, texts[ATOM_IDENTITY_PREFIX + atom.id], links)
    }

    private fun stats(atoms: List<ChangeAtom>) =
        "+${atoms.sumOf { it.added }} −${atoms.sumOf { it.removed }} · ${count(atoms.size, "declaration")} · ${count(atoms.map { it.path }.distinct().size, "file")}"
}

internal fun roleLabel(role: AtomRole): String = when (role) {
    AtomRole.ORIGIN -> "Origin"
    AtomRole.PROPAGATION -> "Follows"
    AtomRole.TEST -> "Test"
    AtomRole.DOCUMENTATION -> "Docs"
    AtomRole.STANDALONE -> "Change"
}

internal fun atomName(atom: ChangeAtom): String = when (atom.kind) {
    AtomKind.LOOSE -> "top-level code"
    AtomKind.FILE -> atom.path.substringAfterLast('/')
    else -> atom.name
}

/** "function · modified · signature changed". */
internal fun atomDetail(atom: ChangeAtom): String = listOfNotNull(
    when (atom.kind) { AtomKind.LOOSE -> "code outside declarations"; AtomKind.FILE -> "whole file"; else -> atom.kind.name.lowercase() },
    atom.status.name.lowercase(),
    "signature changed".takeIf { atom.signatureChanged },
).joinToString(" · ")

/** The path with the declaration's lines on the side it exists on: after, or before for a removed one. */
internal fun atomLocation(atom: ChangeAtom): String {
    val lines = if (atom.status == AtomStatus.REMOVED) atom.beforeLines else atom.afterLines ?: atom.beforeLines
    return atom.path + (lines?.let { if (it.first == it.last) ":${it.first}" else ":${it.first}-${it.last}" } ?: "")
}

/**
 * The changed lines of [atom] in [file], as summaries trace their citations, so the native diff opens at them; every row
 * of the atom when none is changed. Null when no row has a file line.
 */
internal fun atomTrace(atom: ChangeAtom, file: ReviewFile): LineTrace? {
    val owned = atom.rows.flatMapTo(hashSetOf()) { it.toList() }
    val rows = patchLineNumbers(file.patch).filter { it.row in owned }
    val shown = rows.filter { it.row in atom.changedRows }.ifEmpty { rows }
    val lines = shown.map { row ->
        if (row.new != null) EvidenceLine(LineSide.AFTER, row.new, row.text.drop(1), row.marker, row.hunk, diff = true)
        else EvidenceLine(LineSide.BEFORE, row.old!!, row.text.drop(1), row.marker, row.hunk, diff = true)
    }.takeIf { it.isNotEmpty() } ?: return null
    return LineTrace(ATOM_IDENTITY_PREFIX + atom.id, atom.path, "", listOf(LineMatch(lines)), lines)
}

private fun count(value: Int, one: String) = if (value == 1) "1 $one" else "$value ${one}s"
