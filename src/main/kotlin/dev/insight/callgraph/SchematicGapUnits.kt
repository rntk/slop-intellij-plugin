package dev.insight.callgraph

/**
 * Lines [first]..[last] of a schematic summarized as one gap: whole statements or declarations, or, when [construct]
 * is set, only the header of that longer construct. [folded] are bodies of declarations inside, read as their blocks;
 * [symbols] are headlines of blocks inside, which the reader opens separately.
 */
data class GapUnit(val first: Int, val last: Int, val construct: IntRange? = null,
    val folded: List<IntRange> = emptyList(), val symbols: List<Int> = emptyList()) {
    operator fun contains(line: Int) = line in first..last
}

/** A complete statement or declaration on lines [first]..[last]; when it has a body, its header ends on [headerLast]. */
data class GapConstruct(val first: Int, val last: Int, val headerLast: Int? = null)

/**
 * Bounds on one gap summary, measured without the bodies of folded declarations: at most [lines] lines, [chars]
 * characters and [declarations] folded declarations. A construct grows into the one around it while that stays
 * within [glance] lines, so a lone `return` is read with its `if`.
 */
data class GapBudget(val lines: Int = 50, val chars: Int = 4_000, val declarations: Int = 3, val glance: Int = 12)

/**
 * The gaps of [rows] to summarize, in source order and never overlapping. Each [meaningful] line of a code row lies in
 * one: the [constructs] around it (smallest first, inside the schematic's scope) within [budget], else the header of
 * the smallest when the line is part of it, else the line itself. Overlapping units merge, as do plain ones meeting
 * inside one code row while they fit; units always cover blocks they touch whole. [text] gives a line's source.
 */
fun gapUnits(rows: List<SchematicRow>, text: (Int) -> String, meaningful: (Int) -> Boolean,
    constructs: (Int) -> List<GapConstruct>, budget: GapBudget = GapBudget()): List<GapUnit> {
    val blocks = rows.filterIsInstance<BlockRow>()
    val declarations = blocks.filter { it.declared != null }
    fun body(declaration: BlockRow) = (declaration.headline + 1)..declaration.last
    fun inside(first: Int, last: Int) = declarations.filter { it.first >= first && it.last <= last }
    fun shown(first: Int, last: Int) = inside(first, last).let { folded -> (first..last).filter { line -> folded.none { line in body(it) } } }
    fun fits(first: Int, last: Int): Boolean {
        if (inside(first, last).size > budget.declarations) return false
        val shown = shown(first, last)
        return shown.size <= budget.lines && shown.sumOf { text(it).trim().length } <= budget.chars
    }
    /** Widened to whole blocks, so a unit never cuts a folded declaration. */
    fun covering(first: Int, last: Int): IntRange {
        var range = first..last
        while (true) {
            val wider = blocks.filter { it.first <= range.last && it.last >= range.first }
                .fold(range) { acc, row -> minOf(acc.first, row.first)..maxOf(acc.last, row.last) }
            if (wider == range) return range
            range = wider
        }
    }
    fun pick(line: Int): Pair<IntRange, IntRange?> {
        val chain = constructs(line).filter { line in it.first..it.last }
        val smallest = chain.firstOrNull() ?: return covering(line, line) to null
        if (fits(smallest.first, smallest.last)) {
            var chosen = smallest
            for (outer in chain.drop(1)) {
                if (shown(outer.first, outer.last).size > budget.glance || !fits(outer.first, outer.last)) break
                chosen = outer
            }
            return covering(chosen.first, chosen.last) to null
        }
        val header = smallest.headerLast?.takeIf { line <= it && it < smallest.last && fits(smallest.first, it) }
            ?: return covering(line, line) to null
        return covering(smallest.first, header) to smallest.first..smallest.last
    }

    val drafts = mutableListOf<Pair<IntRange, IntRange?>>()
    for (row in rows.filterIsInstance<CodeRow>()) {
        var line = row.first
        while (line <= row.last) {
            if (!meaningful(line)) { line++; continue }
            val draft = pick(line)
            drafts += draft
            line = draft.first.last + 1
        }
    }
    val codeAt = rows.filterIsInstance<CodeRow>().flatMap { row -> (row.first..row.last).map { it to row } }.toMap()
    val merged = mutableListOf<Pair<IntRange, IntRange?>>()
    for (draft in drafts.sortedWith(compareBy({ it.first.first }, { -it.first.last }))) {
        val (range, construct) = draft
        val previous = merged.lastOrNull()
        if (previous == null) {
            merged += draft
            continue
        }
        val (before, beforeConstruct) = previous
        if (range.first <= before.last) {
            val union = covering(before.first, maxOf(before.last, range.last))
            merged[merged.lastIndex] = union to when (union) { before -> beforeConstruct; range -> construct; else -> null }
        } else if (range.first == before.last + 1 && beforeConstruct == null && construct == null &&
            codeAt[before.last]?.let { it === codeAt[range.first] } == true && fits(before.first, range.last) &&
            blocks.none { it.first >= before.first && it.last <= range.last }) {
            // Plain statements read on together while the code between blocks continues and the whole still fits;
            // a gap holding blocks stays apart, so gaps never chain into a whole body.
            merged[merged.lastIndex] = (before.first..range.last) to null
        } else merged += draft
    }
    return merged.map { (range, construct) ->
        val inside = blocks.filter { it.first >= range.first && it.last <= range.last }
        GapUnit(range.first, range.last, construct,
            inside.filter { it.declared != null }.map { body(it) }.filter { !it.isEmpty() },
            inside.map { it.headline })
    }
}

/** [rows] with code rows cut where [units] begin and end, so each code row lies in at most one unit. */
fun splitAtUnits(rows: List<SchematicRow>, units: List<GapUnit>): List<SchematicRow> = rows.flatMap { row ->
    if (row !is CodeRow) return@flatMap listOf(row)
    val cuts = units.flatMap { listOf(it.first, it.last + 1) }.filter { it in row.first + 1..row.last }.distinct().sorted()
    (listOf(row.first) + cuts).zip(cuts + (row.last + 1)) { first, end -> CodeRow(first, end - 1) }
}
