package dev.insight.canvas

/**
 * What a card on the canvas shows of a file: [path] as the card names it (project-relative, or absolute outside the
 * project), its 0-based document [lines], and whether it shows the [whole] file. A selection may start and end within a
 * line: [startColumn] is the 0-based column its first line starts at, [endColumn] the column its last line ends before
 * (null for the end of the line).
 */
internal data class CanvasShownSpan(
    val cardId: String,
    val path: String,
    val lines: IntRange,
    val whole: Boolean,
    val startColumn: Int = 0,
    val endColumn: Int? = null,
) {
    /** Whether the span shows 0-based [line], and [column] on it when known. */
    fun shows(line: Int, column: Int? = null): Boolean {
        if (line !in lines) return false
        if (column == null) return true
        if (line == lines.first && column < startColumn) return false
        val end = endColumn
        return !(line == lines.last && end != null && column >= end)
    }
}

/**
 * The 0-based columns a selection of [anchor]'s offsets in its parent's text starts at and ends before, where the parent's
 * first line starts at [parentStartColumn] (0 unless the parent is itself a selection starting within a line).
 */
internal fun canvasSelectionColumns(parentText: String, parentStartColumn: Int, anchor: CardAnchor): Pair<Int, Int> {
    fun columnAt(offset: Int): Int {
        val at = offset.coerceIn(0, parentText.length)
        val lineStart = parentText.lastIndexOf('\n', at - 1) + 1
        return at - lineStart + if (lineStart == 0) parentStartColumn else 0
    }
    return columnAt(anchor.startOffset) to columnAt(anchor.endOffset)
}

/** Where a link in a card's summary or chat leads on the canvas. */
internal sealed interface CanvasTextMove {
    /** The target is already on card [cardId]: scroll it to 0-based [line], or just show the card when null. */
    data class Scroll(val cardId: String, val line: Int?) : CanvasTextMove

    /** The target is on no card yet: open it from the card the text belongs to. */
    data object Open : CanvasTextMove
}

/**
 * Decides where following a link to [path] at 0-based [line] (null for the file as a whole) from the text of card
 * [contextId] goes, so the reader stays on the canvas: the card the text belongs to when it shows the target, otherwise
 * another card that shows it, nearest the context first (its children, then its chain up to the root, then the rest);
 * otherwise a new card. A link to a file without a line matches only cards showing the whole file. With a 0-based [column]
 * (a declaration's), a selection must contain that column, not just its line.
 */
internal fun canvasTextMove(
    contextId: String,
    path: String,
    line: Int?,
    shown: List<CanvasShownSpan>,
    nearness: (String) -> Int = { if (it == contextId) 0 else 1 },
    column: Int? = null,
): CanvasTextMove {
    val match = shown.filter { it.path == path && (if (line == null) it.whole else it.shows(line, column)) }
        .minWithOrNull(compareBy<CanvasShownSpan> { nearness(it.cardId) }.thenBy { if (it.whole) 1 else 0 })
        ?: return CanvasTextMove.Open
    return CanvasTextMove.Scroll(match.cardId, line)
}

/**
 * How near card [cardId] is to card [contextId] on [session], for choosing among cards that show the same code:
 * the context itself, then its children, then its chain up to the root, then any other card.
 */
internal fun canvasCardNearness(session: CanvasSession, contextId: String, cardId: String): Int {
    if (cardId == contextId) return 0
    if (session.card(cardId)?.parentId == contextId) return 1
    val chain = session.chain(contextId).map { it.id }
    val up = chain.indexOf(cardId)
    if (up >= 0) return 2 + (chain.size - 1 - up)
    return Int.MAX_VALUE
}

/**
 * Where a card opened from a text link anchors in [parentText]: the first whole-word occurrence of [name] when the
 * parent's source names it, otherwise null, leaving the choice to the caller.
 */
internal fun canvasTextAnchor(parentText: String, name: String?): IntRange? {
    if (name.isNullOrBlank()) return null
    return Regex("(?<![\\w$])${Regex.escape(name)}(?![\\w$])").find(parentText)?.range
}
