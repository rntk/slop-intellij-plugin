package dev.insight.diff

/** One contiguous stretch of code removed from [from] and added to [to]; line ranges are one-based and inclusive. */
internal data class MovedBlock(
    val from: String,
    val to: String,
    val fromLines: IntRange,
    val toLines: IntRange,
    /** Significant lines matched in this block; blank, brace-only and import lines inside the ranges are not counted. */
    val lines: Int,
)

/** Code of one source file that reappears in [path]. */
internal data class MoveTarget(val path: String, val blocks: List<MovedBlock>) {
    val lines: Int get() = blocks.sumOf { it.lines }
}

/**
 * Where the code of one changed file went. [lines] counts the significant lines of its before side: [removed] of them
 * were removed, of which the [targets] received some; the rest [stayed].
 */
internal data class FileMoves(val path: String, val lines: Int, val removed: Int, val targets: List<MoveTarget>) {
    val moved: Int get() = targets.sumOf { it.lines }
    val stayed: Int get() = lines - removed
    val deleted: Int get() = removed - moved
    fun share(count: Int): Double = if (lines == 0) 0.0 else count.toDouble() / lines
}

internal data class CodeMoves(val sources: List<FileMoves>) {
    val destinations: List<String> get() = sources.flatMap { source -> source.targets.map { it.path } }.distinct().sorted()
    val blocks: List<MovedBlock> get() = sources.flatMap { source -> source.targets.flatMap { it.blocks } }
}

/**
 * Finds code removed from one file and added to another, from the snapshot patches alone. Lines are compared trimmed
 * and with whitespace collapsed, so reindented code still matches; blank, punctuation-only and import lines are skipped
 * so that boilerplate does not pair unrelated files. A run starts only at an exact match that is rare among the added
 * lines and not a comment, and counts once it has [minLines] significant lines and [minChars] characters. Once started,
 * a run also takes [similar] lines, such as a renamed identifier, and continues after small gaps into the same file;
 * nearby runs between the same two files merge into one block. Moves within one file are not reported, and when
 * several files lose the same code, the first one in [files] claims it.
 */
internal fun codeMoves(files: List<ReviewFile>, minLines: Int = 3, minChars: Int = 60): CodeMoves {
    val sides = files.map { it to patchSides(it.patch) }
    val added = sides.flatMap { (file, side) -> side.added.mapIndexed { index, line -> Triple(file.path, index, line) } }
        .groupBy { it.third.text }
    val addedLines = sides.associate { (file, side) -> file.path to side.added }
    val used = sides.associate { (file, side) -> file.path to BooleanArray(side.added.size) }
    val sources = sides.mapNotNull { (file, side) ->
        val removed = side.removed
        if (removed.isEmpty()) return@mapNotNull null

        /** The run through removed line [from] and added line [to] of [path], grown back to [floor] and forwards over unclaimed similar lines. */
        fun grow(path: String, from: Int, to: Int, floor: Int): Run {
            val target = addedLines.getValue(path)
            val free = used.getValue(path)
            fun pairs(a: Int, b: Int) = a in removed.indices && b in target.indices && !free[b] && similar(removed[a].text, target[b].text)
            var back = 0
            while (from - back - 1 >= floor && pairs(from - back - 1, to - back - 1)) back++
            var length = 0
            while (pairs(from + length, to + length)) length++
            return Run(path, from - back, to - back, back + length)
        }

        /** Whether [run] may start a move: long enough, with an exact line that is rare in the review and not a comment. */
        fun seeds(run: Run): Boolean {
            val target = addedLines.getValue(run.to)
            val lines = (0 until run.length).map { removed[run.fromStart + it].text }
            return lines.size >= minLines && lines.sumOf { it.length } >= minChars && lines.withIndex().any { (k, text) ->
                text == target[run.toStart + k].text && !comment(text) && added.getValue(text).size <= RARE
            }
        }

        val runs = mutableListOf<Run>()
        var i = 0
        while (i < removed.size) {
            val last = runs.lastOrNull()
            val floor = last?.let { it.fromStart + it.length } ?: 0
            val text = removed[i].text
            val starts = added[text].orEmpty().takeIf { it.size <= HOTSPOT && !comment(text) }.orEmpty()
            val seed = starts.filter { (path, start, _) -> path != file.path && !used.getValue(path)[start] }
                .map { (path, start, _) -> grow(path, i, start, floor) }.filter(::seeds).maxByOrNull { it.length }
            // A continuation looks only just past the end of the last run, so it also finds similar and hotspot lines.
            val next = last?.takeIf { i - floor <= MAX_GAP }?.let { run ->
                val end = run.toStart + run.length
                (end..minOf(end + MAX_GAP, addedLines.getValue(run.to).size - 1)).map { grow(run.to, i, it, i) }
                    .filter { it.length > 0 }.maxByOrNull { it.length }
            }
            val run = if (seed != null && (next == null || seed.length > next.length)) seed else next
            if (run != null) {
                runs += run
                for (k in 0 until run.length) used.getValue(run.to)[run.toStart + k] = true
                i = run.fromStart + run.length
            } else i++
        }
        val blocks = merge(runs).map { group ->
            val target = addedLines.getValue(group.first().to)
            MovedBlock(file.path, group.first().to,
                removed[group.first().fromStart].line..removed[group.last().fromStart + group.last().length - 1].line,
                target[group.first().toStart].line..target[group.last().toStart + group.last().length - 1].line,
                group.sumOf { it.length })
        }
        val total = file.before?.let { before -> before.lineSequence().count { significant(it) != null } } ?: removed.size
        FileMoves(file.path, maxOf(total, removed.size), removed.size,
            blocks.groupBy { it.to }.map { (path, list) -> MoveTarget(path, list) }.sortedByDescending { it.lines })
    }.filter { it.targets.isNotEmpty() }
        .sortedWith(compareByDescending<FileMoves> { it.moved }.thenBy { it.path })
    return CodeMoves(sources)
}

/** A significant line of one side of a patch: its one-based line number and normalized text. */
internal data class PatchLine(val line: Int, val text: String)

internal data class PatchSides(val removed: List<PatchLine>, val added: List<PatchLine>)

/** The significant removed and added lines of a unified patch, numbered on their own sides. */
internal fun patchSides(patch: String): PatchSides {
    val removed = mutableListOf<PatchLine>()
    val added = mutableListOf<PatchLine>()
    var old = 0
    var new = 0
    for (line in patch.lineSequence()) {
        val hunk = HUNK.matchEntire(line)
        if (hunk != null) {
            old = hunk.groupValues[1].toInt()
            new = hunk.groupValues[2].toInt()
            continue
        }
        when (line.firstOrNull()) {
            ' ' -> { old++; new++ }
            '-' -> { significant(line.substring(1))?.let { removed += PatchLine(old, it) }; old++ }
            '+' -> { significant(line.substring(1))?.let { added += PatchLine(new, it) }; new++ }
        }
    }
    return PatchSides(removed, added)
}

/** The line trimmed with whitespace collapsed, or null if it carries no code of its own. */
internal fun significant(line: String): String? {
    val text = line.trim().replace(WHITESPACE, " ")
    if (text.count { it.isLetterOrDigit() } < 3) return null
    if (IMPORT.containsMatchIn(text)) return null
    return text
}

/**
 * Whether two significant lines are the same or near enough to belong to one move: they share a non-trivial token, and
 * their identifier, number and punctuation tokens differ in one identifier only or share a long common subsequence.
 */
internal fun similar(a: String, b: String): Boolean {
    if (a == b) return true
    val x = tokens(a)
    val y = tokens(b)
    if (x.none { it.length >= 3 && it[0].isLetterOrDigit() && it in y }) return false
    if (x.size == y.size && x.indices.singleOrNull { x[it] != y[it] }?.let { x[it][0].isLetter() && y[it][0].isLetter() } == true) return true
    val common = Array(x.size + 1) { IntArray(y.size + 1) }
    for (m in x.indices.reversed()) for (n in y.indices.reversed()) {
        common[m][n] = if (x[m] == y[n]) common[m + 1][n + 1] + 1 else maxOf(common[m + 1][n], common[m][n + 1])
    }
    return 2.0 * common[0][0] / (x.size + y.size) >= SIMILARITY
}

private fun tokens(text: String): List<String> = TOKEN.findAll(text).map { it.value }.toList()

/** Whether the line is only a comment, which may sit inside a move but does not start one, as licence headers repeat. */
private fun comment(text: String): Boolean = COMMENTS.any { text.startsWith(it) }

private data class Run(val to: String, val fromStart: Int, val toStart: Int, val length: Int)

/** Whether [run] follows [last] into the same file after small gaps on both sides, such as a line edited on the way. */
private fun continues(last: Run?, run: Run): Boolean = last != null && last.to == run.to &&
    run.fromStart - (last.fromStart + last.length) in 0..MAX_GAP && run.toStart - (last.toStart + last.length) in 0..MAX_GAP

/** Groups consecutive runs that continue each other into one block. */
private fun merge(runs: List<Run>): List<List<Run>> {
    val groups = mutableListOf<MutableList<Run>>()
    for (run in runs) {
        if (continues(groups.lastOrNull()?.last(), run)) groups.last() += run else groups += mutableListOf(run)
    }
    return groups
}

private const val MAX_GAP = 3
/** A new run needs an exact line added at most this often in the review, so common code does not pair files. */
private const val RARE = 2
/** Lines added more often than this do not start runs, which keeps repetitive code from making the search quadratic. */
private const val HOTSPOT = 50
private const val SIMILARITY = 0.6
private val COMMENTS = listOf("//", "/*", "*", "#", "--", "'''", "\"\"\"")
private val TOKEN = Regex("""[\p{L}_][\p{L}\p{N}_]*|\p{N}+|\S""")
// Hunk starts are one-based, or the line before an empty side; counting from them numbers the lines as in the file.
private val HUNK = Regex("""@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@.*""")
private val WHITESPACE = Regex("\\s+")
private val IMPORT = Regex("""^(import|package|using|#include|from \S+ import)\b""")
