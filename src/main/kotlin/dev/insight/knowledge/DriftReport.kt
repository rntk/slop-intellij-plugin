package dev.insight.knowledge

/** What a doc drift check found about one doc statement. */
enum class DriftKind(val label: String) {
    /** An anchor of the statement no longer exists in the code. */
    BROKEN_ANCHOR("Broken anchor"),
    /** The code no longer supports the statement. */
    STALE("Stale"),
    /** Accurate, but an implementation detail or frequently changing value, which flow docs should not state. */
    TOO_DETAILED("Too detailed"),
    /** The statement points at no code, so it cannot be checked automatically. */
    UNANCHORED("Unanchored"),
    /** Verified against the code and still accurate. */
    OK("OK"),
}

/** One doc statement ([text] at [doc]:[startLine]-[endLine], the claim [claimId]) and what the check found. */
data class DriftFinding(
    val kind: DriftKind,
    val claimId: String,
    val doc: String,
    val startLine: Int,
    val endLine: Int,
    val text: String,
    val anchors: List<Anchor>,
    val reason: String,
    val section: String? = null,
)

/** A suggested edit of lines [startLine]-[endLine] of [doc]: replace [old] by [new] (blank to drop it). Never applied automatically. */
data class DocPatch(
    val doc: String,
    val startLine: Int,
    val endLine: Int,
    val old: String,
    val new: String,
    val reason: String,
)

/**
 * What a run did and saw. [docs] and [claims] count what was synced and checked; [added] and [retired] are doc-sync ops;
 * [reanchored] anchors that moved; [verified] claims asked of decisions; [skipped] claims that needed verification but hit a
 * limit or had no evidence; [unchanged] claims whose code did not change since their last verification.
 */
data class DriftCounts(
    val docs: Int = 0,
    val claims: Int = 0,
    val added: Int = 0,
    val retired: Int = 0,
    val reanchored: Int = 0,
    val verified: Int = 0,
    val skipped: Int = 0,
    val unchanged: Int = 0,
    val byKind: Map<DriftKind, Int> = emptyMap(),
) {
    operator fun get(kind: DriftKind): Int = byKind[kind] ?: 0
}

data class DriftReport(
    val findings: List<DriftFinding>,
    val patches: List<DocPatch>,
    val warnings: List<String>,
    val counts: DriftCounts,
) {
    /** Findings that need a human look: everything but [DriftKind.OK]. */
    val issues: List<DriftFinding> get() = findings.filter { it.kind != DriftKind.OK }
}

/** `doc:12` or `doc:12-14`, the shape the Report viewer links to a file range. */
fun docLocation(doc: String, startLine: Int, endLine: Int): String =
    if (endLine > startLine) "$doc:$startLine-$endLine" else "$doc:$startLine"

private val REPORT_ORDER = listOf(DriftKind.BROKEN_ANCHOR, DriftKind.STALE, DriftKind.TOO_DETAILED, DriftKind.UNANCHORED)

/**
 * A readable report grouped by doc. Every finding and suggested edit starts with a `doc:line` location so the Report viewer
 * links it; findings show the statement, anchors, reason and the suggested replacement overlapping them. OK findings are only
 * counted. Suggestions are proposals: no file is ever written.
 */
fun DriftReport.toMarkdown(): String = buildString {
    appendLine("# Doc drift report")
    appendLine()
    appendLine(summaryLine(counts))
    val docs = (findings.map { it.doc } + patches.map { it.doc }).distinct()
    if (docs.isEmpty()) {
        appendLine()
        appendLine("No drift found.")
    }
    for (doc in docs) {
        appendLine()
        appendLine("## $doc")
        val mine = findings.filter { it.doc == doc }
        for (kind in REPORT_ORDER) {
            val ofKind = mine.filter { it.kind == kind }.sortedBy { it.startLine }
            if (ofKind.isEmpty()) continue
            appendLine()
            appendLine("### ${kind.label} (${ofKind.size})")
            appendLine()
            for (finding in ofKind) appendFinding(finding, patches.filter { it.doc == doc && it.startLine <= finding.endLine && it.endLine >= finding.startLine })
        }
        val docPatches = patches.filter { it.doc == doc }.sortedBy { it.startLine }
        if (docPatches.isNotEmpty()) {
            appendLine()
            appendLine("### Suggested edits (${docPatches.size})")
            appendLine()
            appendLine("Proposals only; the doc was not changed.")
            for (patch in docPatches) {
                appendLine()
                appendLine("- ${docLocation(doc, patch.startLine, patch.endLine)}")
                appendPatchBody(patch, "  ")
            }
        }
    }
    if (warnings.isNotEmpty()) {
        appendLine()
        appendLine("## Warnings")
        appendLine()
        warnings.forEach { appendLine("- ${it.lineClean()}") }
    }
}

private fun summaryLine(c: DriftCounts): String {
    val parts = buildList {
        add("${c.claims} statements in ${c.docs} docs")
        for (kind in REPORT_ORDER + DriftKind.OK) if (c[kind] > 0) add("${c[kind]} ${kind.label.lowercase()}")
        if (c.added > 0) add("${c.added} added from docs")
        if (c.retired > 0) add("${c.retired} retired after doc edits")
        if (c.reanchored > 0) add("${c.reanchored} reanchored")
        if (c.skipped > 0) add("${c.skipped} not verified")
    }
    return "Checked " + parts.joinToString(", ") + "."
}

private fun StringBuilder.appendFinding(finding: DriftFinding, nearby: List<DocPatch>) {
    appendLine("- ${docLocation(finding.doc, finding.startLine, finding.endLine)}")
    appendLine("  - Statement: ${finding.text.lineClean()}")
    appendLine("  - Reason: ${finding.reason.lineClean()}")
    if (finding.anchors.isNotEmpty())
        appendLine("  - Anchors: " + finding.anchors.joinToString(", ") { "${it.kind.name.lowercase()} ${it.ref}" })
    for (patch in nearby) {
        appendLine("  - Suggested replacement:")
        appendPatchBody(patch, "    ")
    }
}

private fun StringBuilder.appendPatchBody(patch: DocPatch, indent: String) {
    appendLine("$indent- Old: ${patch.old.lineClean()}")
    appendLine("$indent- New: ${patch.new.lineClean().ifEmpty { "(remove)" }}")
    if (patch.reason.isNotBlank()) appendLine("$indent- Why: ${patch.reason.lineClean()}")
}

private fun String.lineClean(): String = replace(Regex("\\s+"), " ").trim()
