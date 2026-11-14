package dev.insight.canvas

import dev.insight.callgraph.GapUnit

/**
 * A model-written summary of the code between a source's symbols: 0-based document lines [first] to [last] of the card's
 * file (or the root file's), and the [hash] of those lines when it was written, so a summary of code that changed since
 * is no longer shown.
 */
data class GapSummary(val first: Int, val last: Int, val hash: Int, val text: String)

/** The hash of 0-based document lines [first]..[last] of this source's text, or null when they are not all shown. */
fun CanvasSource.gapHash(first: Int, last: Int): Int? {
    val lines = text.split('\n')
    val from = first - firstLine
    val to = last - firstLine
    if (from < 0 || to < from || to > lines.lastIndex) return null
    return lines.subList(from, to + 1).joinToString("\n").hashCode()
}

/** The [summaries] that still describe this source's text, in line order. */
fun CanvasSource.shownGapSummaries(summaries: List<GapSummary>): List<GapSummary> =
    summaries.filter { gapHash(it.first, it.last) == it.hash }.sortedBy { it.first }

/** The gaps of this source with no current summary among [summaries], skipping those of blank lines only. */
fun CanvasSource.unsummarizedGaps(summaries: List<GapSummary>): List<GapUnit> {
    val shown = shownGapSummaries(summaries).map { it.first to it.last }.toSet()
    val lines = text.split('\n')
    return gaps.filter { unit ->
        (unit.first to unit.last) !in shown && (unit.first..unit.last).any { lines.getOrNull(it - firstLine)?.isNotBlank() == true }
    }
}

/** [summaries] with [added] in place of any summary overlapping its lines. */
fun withGapSummary(summaries: List<GapSummary>, added: GapSummary): List<GapSummary> =
    (summaries.filter { it.last < added.first || it.first > added.last } + added).sortedBy { it.first }
