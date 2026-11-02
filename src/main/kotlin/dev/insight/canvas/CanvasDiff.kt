package dev.insight.canvas

/** One row of a diff excerpt: [old] and [new] are 1-based line numbers on each side, null where the row has none. */
internal data class CanvasDiffRow(val kind: Kind, val text: String, val old: Int? = null, val new: Int? = null) {
    enum class Kind { CONTEXT, ADDED, REMOVED, GAP }
}

private val HUNK = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

/**
 * The hunks of unified [patch] that change new-side [lines] (1-based, inclusive; every hunk for null), with a gap row
 * between hunks. A removal counts inside [lines] when both of its neighbouring new-side lines are. Headers, notes and
 * text before the first hunk are not rows.
 */
internal fun canvasDiffRows(patch: String, lines: IntRange? = null): List<CanvasDiffRow> {
    val hunks = mutableListOf<MutableList<CanvasDiffRow>>()
    val relevant = mutableListOf<Boolean>()
    var old = 0
    var new = 0
    for (raw in patch.replace("\r\n", "\n").split('\n')) {
        val header = HUNK.find(raw)
        if (header != null) {
            old = header.groupValues[1].toInt()
            new = header.groupValues[2].toInt()
            hunks += mutableListOf<CanvasDiffRow>()
            relevant += lines == null
            continue
        }
        if (hunks.isEmpty() || raw.isEmpty() || raw.startsWith("\\")) continue
        val text = raw.substring(1)
        val row = when (raw[0]) {
            '+' -> CanvasDiffRow(CanvasDiffRow.Kind.ADDED, text, new = new++)
            '-' -> CanvasDiffRow(CanvasDiffRow.Kind.REMOVED, text, old = old++)
            else -> CanvasDiffRow(CanvasDiffRow.Kind.CONTEXT, text, old++, new++)
        }
        if (lines != null && when (row.kind) {
                CanvasDiffRow.Kind.ADDED -> row.new!! in lines
                CanvasDiffRow.Kind.REMOVED -> new - 1 >= lines.first && new <= lines.last
                else -> false
            }) relevant[relevant.lastIndex] = true
        hunks.last() += row
    }
    val shown = hunks.filterIndexed { index, rows -> relevant[index] && rows.any { it.kind != CanvasDiffRow.Kind.CONTEXT } }
    return shown.flatMapIndexed { index, rows -> if (index == 0) rows else listOf(CanvasDiffRow(CanvasDiffRow.Kind.GAP, "⋯")) + rows }
}

/** The diff rows of what [source] shows: all of its file's changes for a [wholeFile], else those within its lines. */
internal fun canvasSourceDiff(diffs: Map<String, String>, source: CanvasSource?, wholeFile: Boolean): List<CanvasDiffRow> {
    val patch = source?.file?.path?.let(diffs::get) ?: return emptyList()
    val lines = if (wholeFile) null else (source.firstLine + 1)..(source.firstLine + source.text.count { it == '\n' } + 1)
    return canvasDiffRows(patch, lines)
}

/** `Diff · +added −removed`, as the Diff tab's title. */
internal fun canvasDiffTitle(rows: List<CanvasDiffRow>): String =
    "Diff · +${rows.count { it.kind == CanvasDiffRow.Kind.ADDED }} −${rows.count { it.kind == CanvasDiffRow.Kind.REMOVED }}"
