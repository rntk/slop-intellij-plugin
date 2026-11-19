package dev.insight.review

/**
 * PSI categories. The [isBase] kinds are disjoint declarations (comments count PSI blocks, not lines); the others are
 * finer lenses that overlap them (an interface is also a class, a test also a function) or stand alone (imports, lambdas).
 */
enum class RadarSymbolKind(val label: String, val unit: String, val plural: String, val description: String) {
    FUNCTION("Functions", "function", "functions", "Functions and methods, including constructors and property accessors"),
    CLASS("Classes", "class", "classes", "Classes, interfaces, enums, objects, structs and other named types"),
    PROPERTY("Properties", "property", "properties", "Properties and fields"),
    VARIABLE("Variables", "variable", "variables", "Local and top-level variables"),
    CONSTANT("Constants", "constant", "constants", "Explicit constants, such as const, static final and enum constants"),
    PARAMETER("Parameters", "parameter", "parameters", "Function and type parameters"),
    OTHER("Other declarations", "declaration", "declarations", "Other declarations, such as type aliases, impl blocks and modules"),
    COMMENT("Comments", "comment", "comments", "Comment blocks, including documentation comments"),
    INTERFACE("Interfaces", "interface", "interfaces", "Interfaces, traits and protocols"),
    ENUM("Enums", "enum", "enums", "Enum types"),
    STRUCT("Data types", "data type", "data types", "Structs, records, data and value classes, dataclasses"),
    OBJECT("Objects", "object", "objects", "Kotlin object and companion object declarations"),
    TYPE_ALIAS("Type aliases", "type alias", "type aliases", "Type alias declarations"),
    IMPL("Impl blocks", "impl block", "impl blocks", "Rust impl blocks"),
    CONSTRUCTOR("Constructors", "constructor", "constructors",
        "Constructors and initializers; Go NewX functions and Rust associated new functions by convention"),
    TEST("Tests", "test", "tests", "Test functions and test calls, recognized by annotations, attributes, names or it/test calls"),
    ASYNC("Async", "async site", "async sites", "Async and suspend functions, and goroutine launches"),
    LAMBDA("Lambdas", "lambda", "lambdas", "Lambdas, closures, arrow functions and function literals"),
    IMPORT("Imports", "import", "imports", "Import statements and import specs");

    val isBase: Boolean get() = ordinal <= COMMENT.ordinal

    companion object {
        /** The order of the Charts lenses: types, then callables, then values, then the rest. */
        val LENSES = listOf(CLASS, INTERFACE, ENUM, STRUCT, OBJECT, TYPE_ALIAS, IMPL, FUNCTION, CONSTRUCTOR, TEST, ASYNC, LAMBDA,
            PROPERTY, CONSTANT, VARIABLE, PARAMETER, OTHER, IMPORT, COMMENT)
    }
}

data class RadarSymbols(val counts: Map<RadarSymbolKind, Int> = emptyMap()) {
    operator fun get(kind: RadarSymbolKind): Int = counts[kind] ?: 0
    val declarations: Int get() = counts.filterKeys { it.isBase && it != RadarSymbolKind.COMMENT }.values.sum()
    operator fun plus(other: RadarSymbols) = RadarSymbols((counts.keys + other.counts.keys).associateWith { this[it] + other[it] })
}

/** Counts captured with the evidence; rendering never reads the IDE. */
data class RadarLines(val added: Int = 0, val removed: Int = 0, val source: Int = 0, val symbols: RadarSymbols = RadarSymbols()) {
    val changed: Int get() = added + removed
    operator fun plus(other: RadarLines) = RadarLines(added + other.added, removed + other.removed, source + other.source, symbols + other.symbols)

    companion object {
        val ZERO = RadarLines()
    }
}

/** One measure drawn as a polygon over every axis, such as added lines. */
enum class RadarSeries(val label: String, val value: (RadarLines) -> Int) {
    ADDED("Added lines", RadarLines::added),
    REMOVED("Removed lines", RadarLines::removed),
    SOURCE("Lines", RadarLines::source),
    FUNCTIONS("Functions", { it.symbols[RadarSymbolKind.FUNCTION] }),
    CLASSES("Classes", { it.symbols[RadarSymbolKind.CLASS] }),
    PROPERTIES("Properties", { it.symbols[RadarSymbolKind.PROPERTY] }),
    VARIABLES("Variables", { it.symbols[RadarSymbolKind.VARIABLE] }),
    CONSTANTS("Constants", { it.symbols[RadarSymbolKind.CONSTANT] }),
    PARAMETERS("Parameters", { it.symbols[RadarSymbolKind.PARAMETER] }),
    OTHER("Other declarations", { it.symbols[RadarSymbolKind.OTHER] }),
    COMMENTS("Comments", { it.symbols[RadarSymbolKind.COMMENT] });

    val isSymbol: Boolean get() = this != ADDED && this != REMOVED && this != SOURCE
}

/**
 * One spoke of the radar: a topic, group, folder or file at the picked level, with every file beneath it. [path] is the
 * file a leaf opens; branches have none.
 */
data class RadarAxis(
    val identity: String,
    val label: String,
    val tooltip: String,
    val files: List<ReviewSource>,
    val lines: RadarLines,
    val path: String? = null,
    /** The entries this axis stands for when the remainder beyond the axis cap is merged into one. */
    val merged: Int = 1,
)

/** The axes at one level, the series to draw, and how many levels the switcher offers. */
data class RadarChart(val axes: List<RadarAxis>, val series: List<RadarSeries>, val levels: Int, val level: Int) {
    val total: RadarLines get() = axes.fold(RadarLines.ZERO) { sum, axis -> sum + axis.lines }
}

/** Patches count hunk rows; sources carry text lines and their captured PSI counts. */
fun radarLines(file: ReviewSource): RadarLines {
    return when (file) {
        is PatchSource -> {
            var added = 0
            var removed = 0
            var inHunk = false
            for (line in file.patch.lineSequence()) when {
                line.startsWith("diff --git ") -> inHunk = false
                line.startsWith("@@") -> inHunk = true
                // A patch without hunk headers is only its rows; file headers never start a hunk row.
                !inHunk && (line.startsWith("+++") || line.startsWith("---")) -> Unit
                line.startsWith('+') -> added++
                line.startsWith('-') -> removed++
            }
            RadarLines(added = added, removed = removed)
        }
        else -> RadarLines(source = file.text?.let(::countTextLines) ?: 0, symbols = file.symbolCounts ?: RadarSymbols())
    }
}

private fun countTextLines(text: String): Int =
    if (text.isEmpty()) 0 else text.count { it == '\n' } + if (text.endsWith('\n')) 0 else 1

/** Parsed source categories; diffs retain added/removed line polygons. */
fun radarSeries(files: List<ReviewSource>, symbols: Boolean = true): List<RadarSeries> = when {
    files.any { it is PatchSource } -> listOf(RadarSeries.ADDED, RadarSeries.REMOVED)
    symbols && files.any { it.symbolCounts != null } -> {
        val total = RadarLines(symbols = files.mapNotNull { it.symbolCounts }.fold(RadarSymbols(), RadarSymbols::plus))
        RadarSeries.entries.filter { it.isSymbol && it.value(total) > 0 }.ifEmpty { listOf(RadarSeries.FUNCTIONS) }
    }
    else -> listOf(RadarSeries.SOURCE)
}

/** At most this many spokes; the smallest entries beyond it merge into one "Other" spoke. */
const val MAX_RADAR_AXES = 36

/**
 * The folder radar of a summary tree. Level 1 is the root's entries; each level goes one folder deeper, and files above
 * the level stay as their own spokes, so the last level shows every file. Groups the summarizer made are not folders, so
 * the radar looks through them; merged folders show as one entry, as in the hierarchy. A null [level] picks the first
 * level with at least three spokes, else the last.
 */
fun summaryRadar(tree: ReviewSummaryTree, level: Int?, symbols: Boolean = true): RadarChart {
    val files = tree.nodes.mapNotNull { if (it.kind == SummaryKind.FILE) it.file else null }.distinctBy { it.path }
    if (files.isEmpty()) return RadarChart(emptyList(), radarSeries(files, symbols), 0, 0)
    fun entries(node: SummaryNode): List<SummaryNode> = tree.shownChildren(node).flatMap {
        if (it.kind == SummaryKind.GROUP) entries(it) else listOf(it)
    }
    fun isLeaf(node: SummaryNode) = node.kind == SummaryKind.FILE || node.kind == SummaryKind.CHUNK
    fun depth(node: SummaryNode): Int = if (isLeaf(node)) 1 else 1 + (entries(node).maxOfOrNull(::depth) ?: 0)
    val levels = entries(tree.root).maxOfOrNull(::depth) ?: 0
    /** The spokes at level [picked], each with the level it sits at. */
    fun at(picked: Int): List<Pair<SummaryNode, Int>> {
        val nodes = mutableListOf<Pair<SummaryNode, Int>>()
        fun collect(node: SummaryNode, at: Int) {
            if (isLeaf(node) || at == picked) nodes += node to at else entries(node).forEach { collect(it, at + 1) }
        }
        entries(tree.root).forEach { collect(it, 1) }
        return nodes
    }
    val picked = level?.coerceIn(1, maxOf(levels, 1)) ?: ((1..levels).firstOrNull { at(it).size >= 3 } ?: maxOf(levels, 1))
    val byPath = files.associateBy { it.path }
    val nodes = at(picked)
    val axes = nodes.map { (node, at) ->
        val sources = node.files.mapNotNull(byPath::get)
        val leaf = isLeaf(node)
        // A file above the picked level keeps its folder, so files of the same name stay apart.
        val label = if (leaf) node.path.split('/').takeLast(if (at < picked) 2 else 1).joinToString("/")
            else tree.shownLabel(node) + "/"
        RadarAxis("node:${node.identity}", label, node.location, sources, sum(sources), node.path.takeIf { leaf })
    }
    return RadarChart(capped(axes, series = radarSeries(files, symbols)), radarSeries(files, symbols), levels, picked)
}

/**
 * The topic radar: [level] 0 is the broadest topics, each level one narrower, and [TopicOutline.depth] shows every file.
 * Files whose topic is shallower than the level stay as their own spokes; unclassified files share one spoke.
 */
fun topicRadar(outline: TopicOutline, level: Int, unclassified: String = "Awaiting topic", symbols: Boolean = true): RadarChart {
    val series = radarSeries(outline.files, symbols)
    if (outline.files.isEmpty()) return RadarChart(emptyList(), series, 0, 0)
    val picked = level.coerceIn(0, outline.depth)
    val axes = mutableListOf<RadarAxis>()
    fun fileAxis(index: Int) {
        val file = outline.files[index]
        axes += RadarAxis("file:${file.path}", file.path.substringAfterLast('/'), file.path, listOf(file), radarLines(file), file.path)
    }
    fun collect(entries: List<TopicEntry>) {
        for (entry in entries) when (entry) {
            is TopicEntry.File -> fileAxis(entry.index)
            is TopicEntry.Topic -> if (entry.node.level >= picked) {
                val sources = (entry.node.first..entry.node.last).map(outline.files::get)
                axes += RadarAxis("topic:${entry.node.level}:${entry.node.identity}", entry.node.name, entry.node.fullPath, sources, sum(sources))
            } else collect(outline.children(entry.node))
        }
    }
    collect(outline.children(null))
    outline.pending?.let { pending ->
        val sources = pending.map(outline.files::get)
        if (picked == outline.depth) pending.forEach(::fileAxis)
        else axes += RadarAxis("topic:pending", unclassified, "${sources.size} files without a topic yet", sources, sum(sources))
    }
    return RadarChart(capped(axes, series = series), series, outline.depth + 1, picked)
}

private fun sum(files: List<ReviewSource>) = files.fold(RadarLines.ZERO) { total, file -> total + radarLines(file) }

/** Keeps the largest [MAX_RADAR_AXES] - 1 spokes in their reading order and merges the rest into "Other". */
internal fun capped(axes: List<RadarAxis>, max: Int = MAX_RADAR_AXES, series: List<RadarSeries> = listOf(RadarSeries.SOURCE, RadarSeries.ADDED, RadarSeries.REMOVED)): List<RadarAxis> {
    if (axes.size <= max) return axes
    val size: (RadarAxis) -> Int = { axis -> series.sumOf { it.value(axis.lines) } }
    val kept = axes.sortedByDescending(size).take(max - 1).toSet()
    val rest = axes.filterNot(kept::contains)
    val files = rest.flatMap { it.files }
    val other = RadarAxis("other", "Other (${rest.size})", rest.joinToString("\n") { it.label }.take(2000), files,
        rest.fold(RadarLines.ZERO) { total, axis -> total + axis.lines }, merged = rest.size)
    return axes.filter(kept::contains) + other
}

/**
 * The share of the radius [value] reaches when the outer ring is [max]. A logarithmic scale keeps small entries visible
 * beside one huge entry; zero is always the center.
 */
fun radarScale(value: Int, max: Int, logarithmic: Boolean): Double = when {
    value <= 0 || max <= 0 -> 0.0
    logarithmic -> (kotlin.math.ln(1.0 + value) / kotlin.math.ln(1.0 + max)).coerceIn(0.0, 1.0)
    else -> (value.toDouble() / max).coerceIn(0.0, 1.0)
}

/** The value of ring [ring] of [rings] (the outermost being [max]), inverting [radarScale]. */
fun radarRingValue(ring: Int, rings: Int, max: Int, logarithmic: Boolean): Int {
    val share = ring.toDouble() / rings
    return if (logarithmic) (kotlin.math.exp(share * kotlin.math.ln(1.0 + max)) - 1).roundToIntSafely()
        else (share * max).roundToIntSafely()
}

private fun Double.roundToIntSafely(): Int = kotlin.math.round(this).toInt().coerceAtLeast(0)

/** Compact labels for line or symbol counts; comments stay separate from declarations unless one series is selected. */
fun radarValueText(lines: RadarLines, series: List<RadarSeries>, selected: RadarSeries? = null): String {
    val single = selected?.takeIf { it in series }
    return when {
        single != null -> String.format(java.util.Locale.US, "%,d %s", single.value(lines), single.label.lowercase(java.util.Locale.US))
        RadarSeries.SOURCE in series -> String.format(java.util.Locale.US, "%,d lines", lines.source)
        RadarSeries.ADDED in series -> String.format(java.util.Locale.US, "+%,d −%,d", lines.added, lines.removed)
        else -> String.format(java.util.Locale.US, "%,d symbols · %,d comments", lines.symbols.declarations, lines.symbols[RadarSymbolKind.COMMENT])
    }
}
