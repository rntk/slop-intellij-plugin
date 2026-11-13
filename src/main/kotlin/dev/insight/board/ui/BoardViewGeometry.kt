package dev.insight.board.ui

import dev.insight.board.Annotation
import dev.insight.board.BoardAnchor
import dev.insight.board.BoardRect
import dev.insight.board.BoardSide
import dev.insight.board.Block
import dev.insight.board.ChainBlock
import dev.insight.board.CodeBlock
import dev.insight.board.CodeKind
import dev.insight.board.GroupBlock
import dev.insight.board.HeadingBlock
import dev.insight.board.StepsBlock
import dev.insight.board.SymbolBlock
import dev.insight.board.TableBlock
import dev.insight.board.TextBlock
import dev.insight.board.Tone
import kotlin.math.abs
import kotlin.math.min

/*
 * The Swing-free parts of the board view: which anchor a block opens, how code and patch lines are numbered and annotated,
 * how prose wraps, how chips flow and where arrows meet block edges. Pixel units are whatever the caller measures in.
 */

/** The code "Open" goes to for [block]: its own anchor, or the first anchor among its steps or cells; null for prose. */
internal fun primaryAnchor(block: Block): BoardAnchor? = when (block) {
    is CodeBlock -> block.anchor
    is SymbolBlock -> block.anchor
    is ChainBlock -> block.steps.firstNotNullOfOrNull { it.anchor }
    is StepsBlock -> block.steps.firstNotNullOfOrNull { it.anchor }
    is TableBlock -> block.rows.asSequence().flatten().firstNotNullOfOrNull { it.anchor }
    is HeadingBlock, is TextBlock, is GroupBlock -> null
}

internal fun toneLabel(tone: Tone): String = when (tone) {
    Tone.NOTE -> "Note"
    Tone.INSIGHT -> "Insight"
    Tone.WARNING -> "Warning"
    Tone.QUESTION -> "Question"
}

internal enum class CodeRowKind { CONTEXT, ADDED, REMOVED, HUNK }

/** One shown line of a code block: its document [number] on the anchor's side (null for lines of the other side or hunk headers). */
internal data class CodeRow(val number: Int?, val text: String, val kind: CodeRowKind, val annotations: List<Annotation> = emptyList())

private val HUNK = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)""")

/**
 * The rows of [block]: source lines numbered from the anchor's first line; patch lines numbered on the anchor's side, with
 * `@@` headers restarting the numbering. Annotations attach to the last row of their line, or to the last row when no row
 * shows it.
 */
internal fun codeRows(block: CodeBlock): List<CodeRow> {
    val lines = block.text.replace("\r\n", "\n").removeSuffix("\n").split('\n').map { it.replace("\t", "    ") }
    val before = block.anchor.side == BoardSide.BEFORE
    var next = block.anchor.first
    val rows = lines.map { line ->
        if (block.kind == CodeKind.SOURCE) return@map CodeRow(next++, line, CodeRowKind.CONTEXT)
        val hunk = HUNK.find(line)
        when {
            hunk != null -> {
                next = hunk.groupValues[if (before) 1 else 2].toInt()
                CodeRow(null, line, CodeRowKind.HUNK)
            }
            line.startsWith("+++ ") || line.startsWith("--- ") || line.startsWith("diff ") || line.startsWith("\\") ->
                CodeRow(null, line, CodeRowKind.HUNK)
            line.startsWith("+") -> CodeRow(if (before) null else next++, line, CodeRowKind.ADDED)
            line.startsWith("-") -> CodeRow(if (before) next++ else null, line, CodeRowKind.REMOVED)
            else -> CodeRow(next++, line, CodeRowKind.CONTEXT)
        }
    }.toMutableList()
    val unplaced = mutableListOf<Annotation>()
    for ((line, notes) in block.annotations.groupBy { it.line }) {
        val at = rows.indexOfLast { it.number == line }
        if (at >= 0) rows[at] = rows[at].copy(annotations = rows[at].annotations + notes) else unplaced += notes
    }
    if (unplaced.isNotEmpty()) rows[rows.lastIndex] = rows.last().let { it.copy(annotations = it.annotations + unplaced) }
    return rows
}

/**
 * [text] broken into lines no wider than [width] by [measure]: paragraphs at newlines (blank lines kept), words at spaces,
 * and words wider than a line at characters.
 */
internal fun wrapLines(text: String, width: Int, measure: (String) -> Int): List<String> {
    val room = width.coerceAtLeast(1)
    val out = mutableListOf<String>()
    for (paragraph in text.replace("\r\n", "\n").split('\n')) {
        if (paragraph.isBlank()) {
            out += ""
            continue
        }
        var line = ""
        for (word in paragraph.trim().split(Regex("\\s+"))) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (measure(candidate) <= room) {
                line = candidate
                continue
            }
            if (line.isNotEmpty()) out += line
            var rest = word
            while (rest.length > 1 && measure(rest) > room) {
                var cut = rest.length - 1
                while (cut > 1 && measure(rest.substring(0, cut)) > room) cut--
                out += rest.substring(0, cut)
                rest = rest.substring(cut)
            }
            line = rest
        }
        if (line.isNotEmpty()) out += line
    }
    return out
}

/** Items of [widths] placed left to right in rows of [height], wrapping before an item that would pass [maxWidth]. */
internal fun flowLayout(widths: List<Int>, height: Int, maxWidth: Int, hgap: Int, vgap: Int): List<BoardRect> {
    var x = 0
    var y = 0
    return widths.map { natural ->
        val w = natural.coerceIn(1, maxWidth.coerceAtLeast(1))
        if (x > 0 && x + w > maxWidth) {
            x = 0
            y += height + vgap
        }
        BoardRect(x, y, w, height).also { x += w + hgap }
    }
}

internal data class BoardPoint(val x: Double, val y: Double)

private fun BoardRect.center() = BoardPoint(x + width / 2.0, y + height / 2.0)

private fun BoardRect.encloses(other: BoardRect) = x <= other.x && y <= other.y && right >= other.right && bottom >= other.bottom

/** Where the line from [rect]'s centre toward [toward] leaves the rectangle. */
internal fun rectExit(rect: BoardRect, toward: BoardPoint): BoardPoint {
    val c = rect.center()
    val dx = toward.x - c.x
    val dy = toward.y - c.y
    if (dx == 0.0 && dy == 0.0) return c
    val tx = if (dx == 0.0) Double.MAX_VALUE else rect.width / 2.0 / abs(dx)
    val ty = if (dy == 0.0) Double.MAX_VALUE else rect.height / 2.0 / abs(dy)
    val t = min(1.0, min(tx, ty))
    return BoardPoint(c.x + dx * t, c.y + dy * t)
}

/** The arrow from [from] to [to] between their edges, or null when one rectangle holds the other (a block and its group). */
internal fun edgeSegment(from: BoardRect, to: BoardRect): Pair<BoardPoint, BoardPoint>? {
    if (from.encloses(to) || to.encloses(from)) return null
    return rectExit(from, to.center()) to rectExit(to, from.center())
}
