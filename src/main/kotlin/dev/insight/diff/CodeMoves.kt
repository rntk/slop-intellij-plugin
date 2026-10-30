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
 * so that boilerplate does not pair unrelated files. A run of matching lines counts once it has [minLines] significant
 * lines and [minChars] characters; nearby runs between the same two files merge into one block. Moves within one file
 * are not reported.
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
        val runs = mutableListOf<Run>()
        var i = 0
        while (i < removed.size) {
            var best: Run? = null
            for ((path, start, _) in added[removed[i].text].orEmpty()) {
                if (path == file.path || used.getValue(path)[start]) continue
                val target = addedLines.getValue(path)
                val free = used.getValue(path)
                var length = 0
                while (i + length < removed.size && start + length < target.size && !free[start + length] &&
                    removed[i + length].text == target[start + length].text) length++
                val candidate = Run(path, i, start, length)
                if (best == null || length > best.length || (length == best.length && continues(runs.lastOrNull(), candidate))) best = candidate
            }
            val run = best
            if (run != null && (continues(runs.lastOrNull(), run) ||
                    run.length >= minLines && (0 until run.length).sumOf { removed[i + it].text.length } >= minChars)) {
                runs += run
                for (k in 0 until run.length) used.getValue(run.to)[run.toStart + k] = true
                i += run.length
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
// Hunk starts are one-based, or the line before an empty side; counting from them numbers the lines as in the file.
private val HUNK = Regex("""@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@.*""")
private val WHITESPACE = Regex("\\s+")
private val IMPORT = Regex("""^(import|package|using|#include|from \S+ import)\b""")
