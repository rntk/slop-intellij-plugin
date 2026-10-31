package dev.insight.canvas

import dev.insight.callgraph.BlockRow
import dev.insight.callgraph.Schematic
import dev.insight.callgraph.SchematicMention
import dev.insight.callgraph.SchematicRow

/**
 * A clickable part of a text viewer showing a schematic's text: [range] holds offsets into that text (both ends
 * inclusive), and clicking it leads to graph symbols [targets]. A [reference] names types and properties without
 * calling them.
 */
data class CanvasLink(val range: IntRange, val targets: List<Int>, val reference: Boolean = false)

/** The text a viewer shows for [schematic], which [canvasLinks] offsets point into. */
fun canvasText(schematic: Schematic): String = schematic.lines.joinToString("\n")

/** Links of [schematic] as offsets into [canvasText]. */
fun canvasLinks(schematic: Schematic): List<CanvasLink> =
    canvasLinks(schematic.firstLine, schematic.lines, schematic.rows, schematic.mentions)

/**
 * Links of [rows] and [mentions] over [lines], which start at document line [firstLine], as offsets into
 * `lines.joinToString("\n")`. Each block row's links lie on its headline: placed links at their columns, and links
 * without columns (the whole row, or calls whose place is unknown) on the headline's trimmed text. Mentions at the same
 * columns share a link; those overlapping a placed row link are left out, since the row already leads there, while
 * those inside a whole-row link are narrower and win at their place. Links without targets or outside the lines are
 * left out. Sorted by start offset.
 */
fun canvasLinks(firstLine: Int, lines: List<String>, rows: List<SchematicRow>,
    mentions: List<SchematicMention> = emptyList()): List<CanvasLink> {
    val starts = lineStarts(lines)
    val links = mutableListOf<CanvasLink>()
    val placed = mutableListOf<IntRange>()
    for (row in rows) {
        if (row !is BlockRow) continue
        val index = row.headline - firstLine
        val line = lines.getOrNull(index) ?: continue
        for (link in row.links) {
            if (link.targets.isEmpty()) continue
            val columns = link.columns?.let { it.first.coerceAtLeast(0)..it.last.coerceAtMost(line.length - 1) }
                ?: trimmed(line)
                ?: continue
            if (columns.isEmpty()) continue
            val range = (starts[index] + columns.first)..(starts[index] + columns.last)
            links += CanvasLink(range, link.targets, link.reference)
            if (link.columns != null) placed += range
        }
    }
    mentions.mapNotNull { mention ->
        val index = mention.line - firstLine
        val line = lines.getOrNull(index) ?: return@mapNotNull null
        val columns = mention.columns.first.coerceAtLeast(0)..mention.columns.last.coerceAtMost(line.length - 1)
        if (columns.isEmpty()) null else ((starts[index] + columns.first)..(starts[index] + columns.last)) to mention
    }.filter { (range, _) -> placed.none { it.first <= range.last && range.first <= it.last } }
        .groupBy({ it.first }, { it.second })
        .forEach { (range, group) ->
            links += CanvasLink(range, group.map { it.target }.distinct(), group.all { it.reference })
        }
    return links.sortedBy { it.range.first }
}

/** The narrowest link at [offset], so a placed name wins over the whole line around it; null when none is there. */
fun linkAt(links: List<CanvasLink>, offset: Int): CanvasLink? =
    links.filter { offset in it.range }.minByOrNull { it.range.last - it.range.first }

/**
 * Links within the slice [start] until [end] of their text, with offsets re-based to the slice: for a selection card
 * whose text is that slice of its parent's text. Links crossing the slice's ends are clipped to it.
 */
fun linksInSlice(links: List<CanvasLink>, start: Int, end: Int): List<CanvasLink> = links.mapNotNull { link ->
    val first = maxOf(link.range.first, start)
    val last = minOf(link.range.last, end - 1)
    if (first > last) null else link.copy(range = (first - start)..(last - start))
}

/**
 * The anchor of a card opened at [start] until [end] of [text], whose first line is 0-based document line [firstLine].
 */
fun cardAnchor(text: String, start: Int, end: Int, firstLine: Int): CardAnchor {
    val from = start.coerceIn(0, text.length)
    var line = firstLine
    for (i in 0 until from) if (text[i] == '\n') line++
    return CardAnchor(from, end.coerceIn(from, text.length), line)
}

/** Offset of each line's first character in the lines joined by newlines. */
private fun lineStarts(lines: List<String>): IntArray {
    val starts = IntArray(lines.size)
    for (i in 1 until lines.size) starts[i] = starts[i - 1] + lines[i - 1].length + 1
    return starts
}

/** Columns of [line] without its leading and trailing whitespace, or null when it is blank. */
private fun trimmed(line: String): IntRange? {
    val first = line.indexOfFirst { !it.isWhitespace() }
    if (first < 0) return null
    return first..line.indexOfLast { !it.isWhitespace() }
}
