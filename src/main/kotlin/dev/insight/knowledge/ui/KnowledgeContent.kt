package dev.insight.knowledge.ui

import dev.insight.knowledge.Anchor
import dev.insight.knowledge.Citation
import dev.insight.knowledge.Claim
import dev.insight.knowledge.ClaimStatus
import dev.insight.knowledge.DistillEvent
import dev.insight.knowledge.DocTarget
import dev.insight.knowledge.DriftFinding
import dev.insight.knowledge.KnowledgeOp
import dev.insight.knowledge.KnowledgeState
import dev.insight.knowledge.LedgerEntry
import dev.insight.knowledge.Verdict
import dev.insight.knowledge.accepted
import dev.insight.knowledge.docLocation
import dev.insight.review.ui.ReviewTileStat
import dev.insight.review.ui.countStat

/*
 * What the Knowledge page shows and asks, and the plain-text formatting of it. Nothing here touches Swing or the IDE, so it
 * is tested without a project; see KnowledgePage for the view.
 */

/** Claims by lifecycle: [live] and [suspect] are active, [retired] and [superseded] left the live set but are kept. */
internal data class KnowledgeCounts(val live: Int = 0, val suspect: Int = 0, val retired: Int = 0, val superseded: Int = 0) {
    val active: Int get() = live + suspect
    val total: Int get() = live + suspect + retired + superseded
}

internal fun knowledgeCounts(state: KnowledgeState): KnowledgeCounts {
    var live = 0
    var suspect = 0
    var retired = 0
    var superseded = 0
    for (claim in state.claims.values) when (claim.status) {
        ClaimStatus.Live -> live++
        is ClaimStatus.Suspect -> suspect++
        is ClaimStatus.Retired -> retired++
        is ClaimStatus.Superseded -> superseded++
    }
    return KnowledgeCounts(live, suspect, retired, superseded)
}

/** The tile's stats: how many claims are live and how many are suspect; nothing while the base is empty. */
internal fun knowledgeStats(counts: KnowledgeCounts): List<ReviewTileStat> = buildList {
    if (counts.total == 0) return@buildList
    add(countStat(counts.live, "live", "live"))
    if (counts.suspect > 0) add(countStat(counts.suspect, "suspect", "suspect"))
}

/** "12 live · 3 suspect · 1 retired · 0 superseded". */
internal fun countsLine(counts: KnowledgeCounts): String =
    "${counts.live} live · ${counts.suspect} suspect · ${counts.retired} retired · ${counts.superseded} superseded"

internal fun statusLabel(status: ClaimStatus): String = when (status) {
    ClaimStatus.Live -> "Live"
    is ClaimStatus.Suspect -> "Suspect"
    is ClaimStatus.Retired -> "Retired"
    is ClaimStatus.Superseded -> "Superseded"
}

/** Why a claim has its status, or the claims that replaced it; blank for a live claim. */
internal fun statusDetail(status: ClaimStatus): String = when (status) {
    ClaimStatus.Live -> ""
    is ClaimStatus.Suspect -> status.reason
    is ClaimStatus.Retired -> status.reason
    is ClaimStatus.Superseded -> "replaced by " + status.by.joinToString(", ")
}

/** Which claims the Claims list shows. */
internal enum class ClaimFilter(val label: String, private val matches: (ClaimStatus) -> Boolean) {
    ACTIVE("Live and suspect", { it is ClaimStatus.Live || it is ClaimStatus.Suspect }),
    SUSPECT("Suspect", { it is ClaimStatus.Suspect }),
    RETIRED("Retired", { it is ClaimStatus.Retired }),
    SUPERSEDED("Superseded", { it is ClaimStatus.Superseded }),
    ALL("All", { true });

    fun accepts(claim: Claim): Boolean = matches(claim.status)
    override fun toString() = label
}

/** [claims] of [filter], grouped by area in path order (the root, shown first, is `""`), each area's claims by id. */
internal fun claimsByArea(claims: List<Claim>, filter: ClaimFilter): List<Pair<String, List<Claim>>> =
    claims.filter(filter::accepts).groupBy { it.area }.toSortedMap().map { (area, list) -> area to list.sortedBy { it.id } }

internal fun areaLabel(area: String): String = area.ifEmpty { "(project)" }

/** [text] on one line, cut to [max] characters with an ellipsis. */
internal fun oneLine(text: String, max: Int = 140): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
}

/** The ids of the claims [op] is about, new and old. */
internal fun claimIdsOf(op: KnowledgeOp): List<String> = when (op) {
    is KnowledgeOp.Add -> listOf(op.claim.id)
    is KnowledgeOp.Rewrite -> listOf(op.fromId, op.to.id)
    is KnowledgeOp.Merge -> op.fromIds + op.to.id
    is KnowledgeOp.Split -> listOf(op.fromId) + op.to.map { it.id }
    is KnowledgeOp.Retire -> listOf(op.id)
    is KnowledgeOp.MarkSuspect -> listOf(op.id)
    is KnowledgeOp.Confirm -> listOf(op.id)
    is KnowledgeOp.Reanchor -> listOf(op.id)
    is KnowledgeOp.Contradict -> listOf(op.aId, op.bId)
    is KnowledgeOp.Restore -> listOf(op.id)
}

/** The ledger entries that mention the claim [id], oldest first. */
internal fun claimHistory(entries: List<LedgerEntry>, id: String): List<LedgerEntry> = entries.filter { id in claimIdsOf(it.op) }

/** What [op] does, such as "Retire c-1 · anchors gone" or "Rewrite c-1 → c-2". */
internal fun describeOp(op: KnowledgeOp): String = when (op) {
    is KnowledgeOp.Add -> "Add ${op.claim.id}: ${oneLine(op.claim.text, 100)}"
    is KnowledgeOp.Rewrite -> "Rewrite ${op.fromId} → ${op.to.id}: ${oneLine(op.to.text, 100)}"
    is KnowledgeOp.Merge -> "Merge ${op.fromIds.joinToString(", ")} → ${op.to.id}: ${oneLine(op.to.text, 100)}"
    is KnowledgeOp.Split -> "Split ${op.fromId} → ${op.to.joinToString(", ") { it.id }}"
    is KnowledgeOp.Retire -> "Retire ${op.id}: ${oneLine(op.reason, 100)}"
    is KnowledgeOp.MarkSuspect -> "Mark ${op.id} suspect: ${oneLine(op.reason, 100)}"
    is KnowledgeOp.Confirm -> "Confirm ${op.id}"
    is KnowledgeOp.Reanchor -> "Reanchor ${op.id}"
    is KnowledgeOp.Contradict -> "Contradict ${op.aId} / ${op.bId}: ${oneLine(op.reason, 100)}"
    is KnowledgeOp.Restore -> "Restore ${op.id}"
}

/** One ledger line: `#12 worker:src · Add c-…`, with the reason after "rejected" for a rejected op. */
internal fun describeEntry(entry: LedgerEntry): String {
    val head = "#${entry.seq} ${entry.actor} · ${describeOp(entry.op)}"
    return when (val verdict = entry.verdict) {
        is Verdict.Accepted -> head
        is Verdict.Rejected -> "$head — rejected: ${oneLine(verdict.reason, 200)}"
    }
}

/** The rejection reason of [entry], or null for an accepted one. */
internal fun rejectionOf(entry: LedgerEntry): String? = (entry.verdict as? Verdict.Rejected)?.reason

/** One activity line for a distillation event; null for events that add nothing (a proposal is shown once it is submitted). */
internal fun describeEvent(event: DistillEvent): String? = when (event) {
    is DistillEvent.PartitionStarted -> "Reading ${areaLabel(event.area)} (${event.files} files, ${event.index + 1} of ${event.total})"
    is DistillEvent.PartitionFinished -> when {
        event.error != null -> "${areaLabel(event.area)} failed: ${oneLine(event.error, 200)}"
        event.skipped -> "${areaLabel(event.area)} unchanged, skipped"
        else -> "${areaLabel(event.area)}: ${event.proposals} proposals"
    }
    is DistillEvent.Proposed -> null
    is DistillEvent.Submitted -> describeEntry(event.entry)
    is DistillEvent.Rejected -> describeEntry(event.entry)
    is DistillEvent.Warning -> "Warning: ${oneLine(event.message, 200)}"
}

/** One activity line for a drift finding, led by its `doc:line` location. */
internal fun describeFinding(finding: DriftFinding): String =
    "${finding.kind.label}: ${docLocation(finding.doc, finding.startLine, finding.endLine)} · ${oneLine(finding.text, 100)}"

/** The fraction of [done] in [total], or null when there is nothing to count. */
internal fun fractionOf(done: Int, total: Int): Double? = if (total > 0) (done.toDouble() / total).coerceIn(0.0, 1.0) else null

/** "Syncing docs 3 / 10". */
internal fun progressText(phase: String, done: Int, total: Int): String = if (total > 0) "$phase $done / $total" else phase

/**
 * What to render a doc about for [area] of [claims]: titled by the area's last segment (the project name for the root), updating
 * the Markdown doc most of the area's claims were read from, if any. The root covers every area.
 */
internal fun docTargetFor(area: String, claims: List<Claim>, projectName: String): DocTarget {
    val inArea = claims.filter { it.isActive && (area.isEmpty() || it.area == area || it.area.startsWith(area.trimEnd('/') + "/")) }
    val existing = inArea.mapNotNull { it.source?.takeIf { source -> source.endsWith(".md", ignoreCase = true) } }
        .groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .firstOrNull()?.key
    val title = area.trimEnd('/').substringAfterLast('/').replace('-', ' ').replace('_', ' ').ifBlank { projectName }
    return DocTarget(title, setOf(area), existing)
}

/** [text] in a Markdown fence labelled [language], long enough that backticks inside cannot close it. */
internal fun fenced(text: String, language: String = ""): String {
    val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(maxOf(3, longest + 1))
    return "$fence$language\n${text.trimEnd('\n')}\n$fence\n"
}

/** Which of the reports a run left the page shows. */
internal enum class ReportKind(val label: String) { DRIFT("Drift report"), DRAFT("Rendered draft"), DIFF("Diff against the doc") }

/** A Markdown report for the Report area; [copyText] is the raw text "Copy draft" puts on the clipboard, if it offers one. */
internal data class KnowledgeReport(val kind: ReportKind, val title: String, val markdown: String, val copyText: String? = null) {
    override fun toString() = title
}

/**
 * The page's content: the claims and ledger of the knowledge base ([revision] is the last ledger seq, so a view redraws them
 * only when it changes), the run in progress, and the reports and notes of the last runs.
 */
internal data class KnowledgeContent(
    val counts: KnowledgeCounts = KnowledgeCounts(),
    val claims: List<Claim> = emptyList(),
    val entries: List<LedgerEntry> = emptyList(),
    val revision: Long = 0,
    val running: Boolean = false,
    /** What the run in progress is doing, with its progress as a fraction of the phase when it has one. */
    val progress: String? = null,
    val fraction: Double? = null,
    /** How the last run ended, or what could not start. */
    val status: String? = null,
    val warnings: List<String> = emptyList(),
    /** What the last run did, oldest first. */
    val activity: List<String> = emptyList(),
    val reports: List<KnowledgeReport> = emptyList(),
    val selectedReport: ReportKind? = null,
    /** Counts the runs that produced reports, so the page shows the Reports tab again after each. */
    val reportSerial: Int = 0,
    /** The docs "Check docs" reads, as the flows folder or the chosen docs. */
    val docs: String = "flows/*.md",
    /** The knowledge directory, or why there is none. */
    val location: String? = null,
    val error: String? = null,
    /** Whether the review has files to distill. */
    val canDistill: Boolean = true,
) {
    /** Areas that hold active claims, for the doc to render; the root first. */
    val areas: List<String> get() = (claims.filter { it.isActive }.map { it.area }.toSortedSet() + "").toList().sorted()
}

/** What the page asks of the review. */
internal class KnowledgeActions(
    val checkDocs: () -> Unit = {},
    val chooseDocs: () -> Unit = {},
    val distill: () -> Unit = {},
    /** Renders a draft doc for the claims of an area (`""` is the whole project). */
    val renderDoc: (area: String) -> Unit = {},
    val stop: () -> Unit = {},
    val retire: (claimId: String) -> Unit = {},
    val restore: (claimId: String) -> Unit = {},
    val openAnchor: (Anchor) -> Unit = {},
    val openCitation: (Citation) -> Unit = {},
    val close: () -> Unit = {},
)

/** Whether [entries] holds a rejected entry; the page flags the ledger then. */
internal fun hasRejections(entries: List<LedgerEntry>): Boolean = entries.any { !it.verdict.accepted }
