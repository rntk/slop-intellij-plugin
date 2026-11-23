package dev.insight.diff.walk.ui

import dev.insight.diff.ReviewFile
import dev.insight.diff.walk.HunkStatus
import dev.insight.diff.walk.LinkDirection
import dev.insight.diff.walk.WalkCoverage
import dev.insight.diff.walk.WalkHunk
import dev.insight.diff.walk.WalkLink
import dev.insight.diff.walk.WalkLinkGroup
import dev.insight.diff.walk.WalkPhase
import dev.insight.diff.walk.WalkProgress
import dev.insight.diff.walk.WalkStep
import dev.insight.diff.walk.WalkStepKind
import dev.insight.diff.walk.Walkthrough
import dev.insight.diff.wallBlock
import dev.insight.review.ui.ReviewTileStat
import dev.insight.review.ui.countStat
import dev.insight.review.ui.tileCount
import dev.insight.review.wall.WallBlock
import dev.insight.review.wall.WallLine
import java.util.Locale

/*
 * What the Walkthrough page says and lists, without Swing: chip and status labels, the coverage bar, the step list, the
 * rows of a hunk card and wrapped note text.
 */

/** A link chip: "caused by 1", "leads to 5", "same edit as 12". */
internal fun walkChipLabel(group: WalkLinkGroup): String = "${group.label} ${group.hunkIds.size}"

/** The chip that opens the next linked cards of a group, or of a step, already opened in part. */
internal fun walkMoreLabel(remaining: Int): String = "show ${tileCount(remaining)} more"

internal fun walkStatusLabel(status: HunkStatus): String = when (status) {
    HunkStatus.PENDING -> "pending"
    HunkStatus.UNLINKED -> "not linked"
    HunkStatus.COVERED -> "covered"
    HunkStatus.STANDALONE -> "standalone"
    HunkStatus.LINKED -> "linked"
}

internal fun walkStatusTooltip(status: HunkStatus): String = when (status) {
    HunkStatus.PENDING -> "Not handled by the assistant yet"
    HunkStatus.UNLINKED -> "The assistant did not analyze or link this change; review it directly"
    HunkStatus.COVERED -> "Shown in a step through another change's analysis, such as one of many sites of a rename"
    HunkStatus.STANDALONE -> "A self-contained change with a short note"
    HunkStatus.LINKED -> "Analyzed and linked in a step"
}

/** One segment of the coverage bar: [count] hunks of [status], [fraction] of all. */
internal data class WalkCoverageSegment(val status: HunkStatus, val count: Int, val fraction: Double) {
    val label: String get() = "${tileCount(count)} ${walkStatusLabel(status)}"
}

/** The order of the coverage bar, from best handled to not handled. */
internal val WALK_COVERAGE_ORDER = listOf(HunkStatus.LINKED, HunkStatus.COVERED, HunkStatus.STANDALONE, HunkStatus.UNLINKED, HunkStatus.PENDING)

/** The coverage bar's segments in [WALK_COVERAGE_ORDER], empty ones left out; their fractions add up to one. */
internal fun walkCoverageSegments(coverage: WalkCoverage): List<WalkCoverageSegment> {
    if (coverage.total <= 0) return emptyList()
    return WALK_COVERAGE_ORDER.mapNotNull { status ->
        coverage[status].takeIf { it > 0 }?.let { WalkCoverageSegment(status, it, it.toDouble() / coverage.total) }
    }
}

/**
 * The pixel span of each of [segments] in a bar [width] wide: each at least [minWidth] so a few hunks stay clickable, the
 * last ending at [width].
 */
internal fun walkSegmentSpans(segments: List<WalkCoverageSegment>, width: Int, minWidth: Int = 6): List<IntRange> {
    if (segments.isEmpty() || width <= 0) return emptyList()
    val floor = minOf(minWidth, width / segments.size)
    val spare = width - floor * segments.size
    var at = 0
    var share = 0.0
    return segments.mapIndexed { index, segment ->
        share += segment.fraction
        val end = if (index == segments.lastIndex) width else maxOf(at + floor, (floor * (index + 1) + spare * share).toInt())
        (at until end).also { at = end }
    }
}

/** The segment of [segments] at [x] in a bar [width] wide, or null outside it. */
internal fun walkSegmentAt(segments: List<WalkCoverageSegment>, width: Int, x: Int): WalkCoverageSegment? =
    walkSegmentSpans(segments, width).indexOfFirst { x in it }.takeIf { it >= 0 }?.let(segments::get)

/** "12 / 40 changes handled" and what the rest are. */
internal fun walkCoverageText(coverage: WalkCoverage): String {
    val parts = walkCoverageSegments(coverage).joinToString(" · ") { it.label }
    return "${tileCount(coverage.handled)} / ${tileCount(coverage.total)} changes handled" + if (parts.isEmpty()) "" else " · $parts"
}

/** The steps a walkthrough tells, the unlinked list not counted. */
internal fun walkStoryCount(walkthrough: Walkthrough): Int = walkthrough.steps.count { it.kind != WalkStepKind.UNLINKED }

/** The Walkthrough tile's stats; nothing before a run. A pass over the hunks in memory, as tiles require. */
internal fun walkTileStats(walkthrough: Walkthrough?): List<ReviewTileStat> {
    if (walkthrough == null || walkthrough.steps.isEmpty()) return emptyList()
    val coverage = walkthrough.coverage()
    return listOf(countStat(walkStoryCount(walkthrough), "step"),
        ReviewTileStat("${tileCount(coverage.handled)} / ${tileCount(coverage.total)}", "changes linked"))
}

/** A status line for a running walkthrough. */
internal fun walkProgressText(progress: WalkProgress): String {
    val phase = when (progress.phase) {
        WalkPhase.TRIAGE -> "Classifying"
        WalkPhase.WALKING -> "Walking"
        WalkPhase.VERIFYING -> "Checking links"
        WalkPhase.DONE -> "Done"
    }
    val parts = buildList {
        add("${tileCount(progress.handled)} / ${tileCount(progress.total)} handled")
        add(if (progress.steps == 1) "1 step" else "${tileCount(progress.steps)} steps")
        if (progress.phase == WalkPhase.WALKING && progress.maxTurns > 0) add("turn ${progress.turn} / ${progress.maxTurns}")
        if (progress.requests > 0) add("${tileCount(progress.requests)} requests")
        if (progress.failedTurns > 0) add("${progress.failedTurns} failed")
    }
    return "$phase · " + parts.joinToString(" · ") + progress.message.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
}

/** What the step list shows. */
internal enum class WalkListFilter(val label: String, val status: HunkStatus? = null) {
    ALL("All steps"),
    NOT_ON_CANVAS("Not on the canvas yet"),
    LINKED("Linked", HunkStatus.LINKED),
    COVERED("Covered", HunkStatus.COVERED),
    STANDALONE("Standalone", HunkStatus.STANDALONE),
    UNLINKED("Not linked", HunkStatus.UNLINKED),
    PENDING("Pending", HunkStatus.PENDING);

    override fun toString() = label

    companion object {
        fun of(status: HunkStatus): WalkListFilter = entries.first { it.status == status }
    }
}

/** The id of the list's group of hunks in no step yet, such as every hunk before a run. */
internal const val WALK_PENDING_GROUP = "pending"

/** A row of the step list: a step (or the pending group) and, under an expanded one, its hunks. */
internal sealed interface WalkListRow {
    /** [shown] of the group's [total] hunks match the filter. */
    data class Group(val id: String, val title: String, val kind: WalkStepKind?, val total: Int, val shown: Int,
                     val expanded: Boolean, val step: WalkStep?) : WalkListRow {
        /** The unlinked step and the pending group stand out: their changes have no story yet. */
        val flagged: Boolean get() = kind == WalkStepKind.UNLINKED || kind == null
        val badge: String get() = when (kind) {
            WalkStepKind.CHAIN -> "chain"
            WalkStepKind.STANDALONE -> "standalone"
            WalkStepKind.UNLINKED -> "not linked"
            null -> "pending"
        }
        val countText: String get() = (if (shown == total) "" else "${tileCount(shown)} of ") +
            "${tileCount(total)} change${if (total == 1) "" else "s"}"
    }

    data class Hunk(val hunk: WalkHunk, val status: HunkStatus, val group: String, val onCanvas: Boolean, val root: Boolean) : WalkListRow
}

/**
 * The step list of [walkthrough]: each step, then a group of the hunks in no step, with the hunks of [expanded] groups
 * under them. A filter keeps the groups with a matching hunk and lists only those; [onCanvas] holds the hunks the canvas
 * shows. A lone group, such as every hunk before a run, is expanded.
 */
internal fun walkListRows(walkthrough: Walkthrough, filter: WalkListFilter, expanded: Set<String>, onCanvas: Set<String>): List<WalkListRow> {
    fun matches(id: String) = when (filter) {
        WalkListFilter.ALL -> true
        WalkListFilter.NOT_ON_CANVAS -> id !in onCanvas
        else -> walkthrough.statusOf(id) == filter.status
    }
    data class Source(val id: String, val title: String, val kind: WalkStepKind?, val step: WalkStep?, val hunks: List<String>)
    val inSteps = walkthrough.steps.flatMapTo(HashSet()) { it.hunks }
    val loose = walkthrough.hunks.map { it.id }.filter { it !in inSteps }
    val sources = walkthrough.steps.map { Source(it.id, it.title, it.kind, it, it.hunks.distinct()) } +
        listOfNotNull(loose.takeIf { it.isNotEmpty() }?.let { Source(WALK_PENDING_GROUP, "Not in a step yet", null, null, it) })
    val visible = sources.mapNotNull { source -> source to source.hunks.filter(::matches).takeIf { it.isNotEmpty() || filter == WalkListFilter.ALL } }
        .mapNotNull { (source, hunks) -> hunks?.let { source to it } }
    val lone = visible.size == 1
    return visible.flatMap { (source, hunks) ->
        val open = lone || source.id in expanded
        listOf(WalkListRow.Group(source.id, source.title, source.kind, source.hunks.size, hunks.size, open, source.step)) +
            if (!open) emptyList() else hunks.mapNotNull { id ->
                walkthrough.hunk(id)?.let { WalkListRow.Hunk(it, walkthrough.statusOf(id), source.id, id in onCanvas, source.step?.rootHunk == id) }
            }
    }
}

/** A hunk's file name and number: "Foo.kt #2". */
internal fun walkHunkName(hunk: WalkHunk): String = "${hunk.path.substringAfterLast('/')} #${hunk.index}"

/** Where a hunk is in its file: its new-side lines (else old-side ones) and enclosing section, or what the whole file is. */
internal fun walkHunkPlace(hunk: WalkHunk): String {
    if (hunk.wholeFile) return "whole file · ${hunk.fileStatus}" + (hunk.previousPath?.let { " from ${it.substringAfterLast('/')}" }.orEmpty())
    val lines = hunk.newLines.takeUnless { it.isEmpty() } ?: hunk.oldLines
    val range = when {
        lines.isEmpty() -> "line ${hunk.newStart}"
        lines.first == lines.last -> "line ${lines.first}"
        else -> "lines ${lines.first}–${lines.last}"
    }
    return range + hunk.section.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
}

/** A card's title: the path, or `old → new` for a rename. */
internal fun walkHunkTitle(hunk: WalkHunk): String =
    hunk.previousPath?.takeIf { it != hunk.path }?.let { "$it → ${hunk.path}" } ?: hunk.path

/** The rows a hunk card paints, at most [maxRows], with [hidden] more, as a wall block for highlighting. */
internal data class WalkHunkRows(val block: WallBlock, val hidden: Int) {
    val lines: List<WallLine> get() = block.lines
}

/** Rows a hunk card shows before it notes the rest; double-clicking opens the native diff with all of them. */
internal const val WALK_CARD_ROWS = 28

/**
 * [hunk]'s rows as the Diffs wall parses them, numbered on both sides, under the path of [file] so its sides can colour them;
 * the `@@` header is left to the card's title. A hunk with no textual diff gets one note row.
 */
internal fun walkHunkRows(hunk: WalkHunk, file: ReviewFile?, maxRows: Int = WALK_CARD_ROWS): WalkHunkRows {
    val source = (file ?: ReviewFile(hunk.path, hunk.path, hunk.previousPath, hunk.fileStatus, "", "")).copy(patch = hunk.patch)
    val parsed = wallBlock(source, Int.MAX_VALUE, 0)
    val rows = parsed.lines.dropWhile { it.kind == WallLine.Kind.HUNK }
        .ifEmpty { listOf(WallLine(WallLine.Kind.NOTE, "[No textual diff: ${hunk.fileStatus}${hunk.previousPath?.let { " from $it" }.orEmpty()}]")) }
    val shown = rows.take(maxRows)
    return WalkHunkRows(parsed.copy(lines = shown, hiddenLines = rows.size - shown.size, more = emptyList(), loadMore = null), rows.size - shown.size)
}

/** The reasons of [links] seen from [hunkId], one line each, with any weak verification noted. */
internal fun walkLinkReasons(hunkId: String, links: List<WalkLink>, name: (String) -> String): List<String> = links.map { link ->
    val direction = when {
        link.relation.symmetric -> LinkDirection.SYMMETRIC
        link.from == hunkId -> LinkDirection.OUTGOING
        else -> LinkDirection.INCOMING
    }
    val confidence = link.confidence?.let { String.format(Locale.US, " (%s%.0f%% sure)", if (link.weak) "weak, " else "", it * 100) }.orEmpty()
    "${link.relation.label(direction)} ${name(link.other(hunkId))}: ${link.reason.trim()}$confidence"
}

/** [markdown] as plain text for a painted note: emphasis, code ticks and link targets dropped, list items bulleted. */
internal fun walkPlainText(markdown: String): String = markdown.replace("\r\n", "\n").lines().joinToString("\n") { line ->
    line.replace(Regex("^\\s{0,3}#{1,6}\\s+"), "")
        .replace(Regex("^(\\s*)[-*+]\\s+"), "$1• ")
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
        .replace("`", "")
}.trim()

/**
 * [text] wrapped to lines [width] wide as [measure] measures them, breaking at spaces and, for a word wider than a line,
 * inside it; paragraphs stay apart. At most [maxLines], the last ending in "…" when text was cut.
 */
internal fun walkWrap(text: String, width: Int, measure: (String) -> Int, maxLines: Int = Int.MAX_VALUE): List<String> {
    val out = mutableListOf<String>()
    var cut = false
    paragraphs@ for (paragraph in text.split('\n')) {
        if (paragraph.isBlank()) { out += ""; continue }
        var line = ""
        for (word in paragraph.trim().split(Regex("\\s+"))) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (measure(candidate) <= width || line.isEmpty() && word.length <= 1) { line = candidate; continue }
            if (line.isNotEmpty()) out += line
            var rest = word
            while (measure(rest) > width && rest.length > 1) {
                var take = rest.length - 1
                while (take > 1 && measure(rest.take(take)) > width) take--
                out += rest.take(take)
                rest = rest.drop(take)
            }
            line = rest
        }
        if (line.isNotEmpty()) out += line
        if (out.size > maxLines) { cut = true; break@paragraphs }
    }
    while (out.lastOrNull()?.isEmpty() == true) out.removeAt(out.lastIndex)
    if (out.size > maxLines) cut = true
    if (!cut) return out
    val kept = out.take(maxLines).toMutableList()
    if (kept.isNotEmpty()) kept[kept.lastIndex] = kept.last().trimEnd().let { if (it.endsWith("…")) it else "$it…" }
    return kept
}
