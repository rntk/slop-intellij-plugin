package dev.insight.diff

import dev.insight.review.SummaryNode
import dev.insight.review.SummarySource

/** The side of a diff a line opens on; a source file has only [AFTER]. */
enum class LineSide { BEFORE, AFTER }

/**
 * A line of a file's evidence: a source line, or a line of a diff with the side and 1-based [number] it opens at. [marker] is
 * `+`, `-` or a space for a [diff] line; [hunk] separates lines that are not adjacent in the file.
 */
data class EvidenceLine(
    val side: LineSide,
    val number: Int,
    val text: String,
    val marker: Char = ' ',
    val hunk: Int = 0,
    val diff: Boolean = false,
) {
    /** Lines worth pointing at: changed lines of a diff, non-blank lines of a source. */
    val candidate: Boolean get() = if (diff) marker != ' ' else text.isNotBlank()
}

/** A block of consecutive lines a summary cites. */
data class LineMatch(val lines: List<EvidenceLine>)

/**
 * What a segment of a file's summary cites in the file: [blocks] holds the lines of each citation, [lines] all of them.
 */
data class LineTrace(
    val identity: String,
    val path: String,
    val query: String,
    val blocks: List<LineMatch>,
    val lines: List<EvidenceLine>,
) {
    val related: List<LineMatch> get() = blocks
    val best: LineMatch? get() = related.firstOrNull()
    val before: List<Int> get() = lines.filter { it.side == LineSide.BEFORE }.map { it.number }
    val after: List<Int> get() = lines.filter { it.side == LineSide.AFTER }.map { it.number }

    /** "Foo.kt:12–14" or "Foo.kt:12–14, removed 9–10", for the status line. */
    val label: String get() {
        val name = path.substringAfterLast('/')
        val parts = listOfNotNull(
            after.takeIf { it.isNotEmpty() }?.let { span(it) },
            before.takeIf { it.isNotEmpty() }?.let { "removed ${span(it)}" },
        )
        return if (parts.isEmpty()) name else "$name:${parts.joinToString(", ")}"
    }

    private fun span(lines: List<Int>) = if (lines.min() == lines.max()) "${lines.min()}" else "${lines.min()}–${lines.max()}"
}

/**
 * The lines a leaf's summary was written from: a part's lines or the whole source file, or every line of a diff's hunks with
 * the side they open at. A diff without before and after texts opens as its patch, so its lines are numbered in the patch.
 */
internal fun evidenceLines(node: SummaryNode): List<EvidenceLine> {
    val file = node.file ?: return emptyList()
    file.text?.let { text ->
        val all = text.replace("\r\n", "\n").replace('\r', '\n').removeSuffix("\n").split('\n')
        val range = node.lines ?: 1..all.size
        return range.filter { it - 1 in all.indices }.map { EvidenceLine(LineSide.AFTER, it, all[it - 1]) }
    }
    val diff = file as? ReviewFile ?: return emptyList()
    return patchLines(diff.patch, inPatch = diff.before == null || diff.after == null)
}

private val HUNK = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

/** The lines of every hunk of [patch]; headers and "\ No newline" notes are not lines of the file. */
internal fun patchLines(patch: String, inPatch: Boolean = false): List<EvidenceLine> {
    val lines = mutableListOf<EvidenceLine>()
    var old = 0
    var new = 0
    var hunk = -1
    for ((index, line) in patch.replace("\r\n", "\n").split('\n').withIndex()) {
        val header = HUNK.find(line)
        if (header != null) {
            old = header.groupValues[1].toInt()
            new = header.groupValues[2].toInt()
            hunk++
            continue
        }
        if (hunk < 0 || line.startsWith("\\")) continue
        val marker = line.firstOrNull()?.takeIf { it == '+' || it == '-' } ?: ' '
        val text = if (line.isEmpty()) "" else line.substring(1)
        val (side, number) = when (marker) {
            '+' -> LineSide.AFTER to new++
            '-' -> LineSide.BEFORE to old++
            else -> LineSide.AFTER to new++.also { old++ }
        }
        lines += if (inPatch) EvidenceLine(LineSide.AFTER, index + 1, text, marker, hunk, diff = true)
            else EvidenceLine(side, number, text, marker, hunk, diff = true)
    }
    // A trailing newline leaves an empty context line that is not in the file.
    return if (lines.lastOrNull()?.let { it.marker == ' ' && it.text.isEmpty() } == true) lines.dropLast(1) else lines
}


/**
 * The lines of [node]'s evidence that [rows] cite: file lines of a source, or for a diff the lines of its patch, numbered
 * from 1 as its summary saw them, each opening on its side of the diff. Hunk headers are not lines of the file.
 */
internal fun citedLines(node: SummaryNode, rows: List<SummarySource.Rows>): List<EvidenceLine> {
    fun cited(number: Int) = rows.any { number in it.first..it.last }
    val lines = evidenceLines(node)
    val diff = node.file as? ReviewFile
    if (node.file?.text != null || diff == null) return lines.filter { cited(it.number) }
    val numbered = patchLines(diff.patch, inPatch = true)
    return lines.filterIndexed { index, _ -> numbered.getOrNull(index)?.let { cited(it.number) } == true }
}
