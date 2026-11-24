package dev.insight.review

/** Right-aligns [text] in a monospace row of [columns] cells, filling the leading cells with dots. */
internal fun dotLeader(text: String, columns: Int): String = text.padStart(columns, '.')

/**
 * What the hierarchy measures: source lines, bytes, files, diff rows, or a PSI lens such as functions. Lenses count the
 * [RadarSymbolKind] captured with each file, and inside a file, within each declaration.
 */
internal class LineChartMetric private constructor(
    val id: String,
    val label: String,
    val unit: String,
    val plural: String,
    val description: String,
    val diffSeries: RadarSeries? = null,
    val symbol: RadarSymbolKind? = null,
) {
    fun value(file: ReviewSource): Long = when {
        symbol != null -> (file.symbolCounts?.get(symbol) ?: 0).toLong()
        this == LINES -> radarLines(file).source.toLong()
        this == SIZE -> file.text?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
        this == ITEMS -> 1L
        this == ADDED -> radarLines(file).added.toLong()
        this == REMOVED -> radarLines(file).removed.toLong()
        this == FOUND -> radarLines(file).let { if (file is PatchSource) it.added + it.removed else it.source }.toLong()
        else -> 0L
    }

    fun value(declaration: OutlineDeclaration): Long = when {
        symbol != null -> declaration.symbols[symbol].toLong()
        this == LINES || this == FOUND -> (declaration.lines.last - declaration.lines.first + 1).toLong()
        this == SIZE -> declaration.bytes.toLong()
        else -> 0L
    }

    /** Whether parsed files unfold into their declarations; files and diff rows have no per-declaration value. */
    val outlines: Boolean get() = diffSeries == null && this != ITEMS

    /** Lenses hide entries holding none of what they count. */
    val hidesEmpty: Boolean get() = symbol != null

    fun unitText(value: Long) = if (value == 1L) unit else plural

    override fun equals(other: Any?) = other is LineChartMetric && other.id == id
    override fun hashCode() = id.hashCode()
    override fun toString() = id

    companion object {
        val LINES = LineChartMetric("lines", "Lines of code", "line", "lines", "Source text lines, including blank lines and comments")
        val SIZE = LineChartMetric("size", "Size", "byte", "bytes", "UTF-8 bytes in the captured source text")
        val ITEMS = LineChartMetric("items", "Items", "item", "items", "Files in the selected scope")
        val ADDED = LineChartMetric("added", "Added lines", "added line", "added lines", "Added lines in the selected diff directory",
            RadarSeries.ADDED)
        val REMOVED = LineChartMetric("removed", "Removed lines", "removed line", "removed lines",
            "Removed lines in the selected diff directory", RadarSeries.REMOVED)
        /** The lines of the files a Charts search found: source lines, or changed lines of a diff. */
        val FOUND = LineChartMetric("found", "Search", "line", "lines", "Lines of the files and folders the search found")

        private val lenses = RadarSymbolKind.entries.associateWith {
            LineChartMetric("symbol:${it.name}", it.label, it.unit, it.plural, it.description, symbol = it)
        }

        fun lens(kind: RadarSymbolKind): LineChartMetric = lenses.getValue(kind)

        /** The lenses with something to show in [files], in [RadarSymbolKind.LENSES] order. */
        fun lenses(files: List<ReviewSource>): List<LineChartMetric> {
            val total = files.mapNotNull { it.symbolCounts }.fold(RadarSymbols(), RadarSymbols::plus)
            return RadarSymbolKind.LENSES.filter { total[it] > 0 }.map(::lens)
        }
    }
}

/**
 * Physical folders and files only: summary groups and file chunks never become chart entries. With [outlines], parsed
 * files unfold into their declarations, and declarations into their members.
 */
internal class ReviewLineChart(
    tree: ReviewSummaryTree,
    val metric: LineChartMetric = LineChartMetric.LINES,
    private val outlines: SourceOutlines? = null,
    /** The only file paths to show, such as a search's; folders without any of them are hidden. Null shows every file. */
    private val only: Set<String>? = null,
) {
    enum class Kind { DIRECTORY, FILE, DECLARATION, PLACEHOLDER }

    /**
     * One entry. Declarations have paths below their file's (`src/a.kt#0/2` is the third member of the first declaration),
     * [filePath] is the file a declaration or placeholder belongs to, and a placeholder stands for an outline still parsing.
     */
    data class Entry(
        val path: String,
        val label: String,
        val kind: Kind,
        val value: Long,
        val files: Int,
        val filePath: String = path,
        val declaration: OutlineDeclaration? = null,
        val expandable: Boolean = kind == Kind.DIRECTORY,
    ) {
        val directory: Boolean get() = kind == Kind.DIRECTORY
    }

    private fun shown(node: SummaryNode) = only == null || node.path in only
    private val fileNodes = tree.nodes.filter { it.kind == SummaryKind.FILE && shown(it) }.distinctBy { it.path }

    private val sources: List<ReviewSource> = fileNodes.mapNotNull { it.file }
    private val byPath = sources.associateBy { it.path }

    private val entries = tree.nodes.filter { it.kind == SummaryKind.DIRECTORY || (it.kind == SummaryKind.FILE && shown(it)) }
        .associate { node ->
            val file = node.kind == SummaryKind.FILE
            node.path to Entry(node.path, node.label, if (file) Kind.FILE else Kind.DIRECTORY, 0, if (file) 1 else 0,
                expandable = !file || node.file?.let(::outlinable) == true)
        }.toMutableMap()
    val rootPath: String = tree.rootDirectoryPrefix
    val root: Entry

    init {
        // Count each captured file once, regardless of summary groups or chunks beneath it.
        for (node in fileNodes) {
            val value = node.file?.let(metric::value) ?: 0L
            entries[node.path]?.let { entries[node.path] = it.copy(value = value, expandable = it.expandable && (value > 0 || !metric.hidesEmpty)) }
            var parent = node.path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                entries[parent]?.let { entries[parent] = it.copy(value = it.value + value, files = it.files + 1) }
                parent = parent.substringBeforeLast('/', "")
            }
        }
        // Folders holding none of the shown files disappear, so drilling down passes only through matches.
        if (only != null) entries.values.removeIf { it.directory && it.files == 0 && it.path != rootPath }
        root = entries[rootPath] ?: Entry("", tree.root.label, Kind.DIRECTORY,
            entries.values.filterNot { it.directory }.sumOf { it.value }, fileNodes.size)
    }

    private fun outlinable(file: ReviewSource) = outlines != null && metric.outlines && file.symbolCounts != null

    fun source(path: String): ReviewSource? = byPath[path]

    fun hasEntry(path: String): Boolean = path == root.path || path in entries || declaration(path) != null

    /** The file or directory at [path] when it is visible in the current metric. */
    fun entry(path: String): Entry? = if (path == root.path) root
        else entries[path]?.takeIf { it.value > 0 || !metric.hidesEmpty }

    fun files(path: String): List<ReviewSource> {
        if (path == root.path) {
            return if (root.path.isEmpty()) sources else sources.filter { it.path.startsWith("${root.path}/") }
        }
        val entry = entries[path] ?: return declaration(path)?.let { listOfNotNull(byPath[it.filePath]) }.orEmpty()
        return if (!entry.directory) sources.filter { it.path == entry.path }
        else sources.filter { entry.path.isEmpty() || it.path.startsWith("${entry.path}/") }
    }

    /** The declaration entry at [path], when its file's outline is parsed. */
    fun declaration(path: String): Entry? {
        val file = path.substringBefore('#', "").takeIf { it.isNotEmpty() } ?: return null
        val source = byPath[file]?.takeIf(::outlinable) ?: return null
        var found: OutlineDeclaration? = null
        var children = outlines?.cached(source)?.declarations ?: return null
        for (index in path.substringAfter('#').split('/')) {
            found = children.getOrNull(index.toIntOrNull() ?: return null) ?: return null
            children = found.children
        }
        return found?.let { declarationEntry(path, file, it) }
    }

    /** A removed directory falls back to its nearest surviving ancestor within the current scope. */
    fun directory(path: String?): Entry {
        var current = path ?: return root
        while (current.isNotEmpty()) {
            entries[current]?.takeIf { it.directory && (rootPath.isEmpty() || current == rootPath || current.startsWith("$rootPath/")) }
                ?.let { return it }
            current = current.substringBeforeLast('/', "")
        }
        return root
    }

    fun children(parent: Entry): List<Entry> = when (parent.kind) {
        Kind.DIRECTORY -> entries.values.filter {
            it.path != parent.path && it.path.substringBeforeLast('/', "") == parent.path
        }
        Kind.FILE -> byPath[parent.path]?.takeIf { parent.expandable }?.let { source ->
            val outline = outlines?.cached(source)
                ?: return listOf(Entry("${parent.path}#…", "Parsing…", Kind.PLACEHOLDER, 0, 0, parent.path))
            outline.declarations.mapIndexed { index, it -> declarationEntry("${parent.path}#$index", parent.path, it) }
        }.orEmpty()
        Kind.DECLARATION -> parent.declaration!!.children.mapIndexed { index, it ->
            declarationEntry("${parent.path}/$index", parent.filePath, it)
        }
        Kind.PLACEHOLDER -> emptyList()
    }.filter { it.kind == Kind.PLACEHOLDER || it.value > 0 || !metric.hidesEmpty }
        .sortedWith(compareByDescending<Entry> { it.value }.thenBy { it.label }.thenBy { it.path })

    private fun declarationEntry(path: String, file: String, declaration: OutlineDeclaration): Entry =
        Entry(path, declaration.name, Kind.DECLARATION, metric.value(declaration), 0, file, declaration,
            expandable = declaration.children.any { !metric.hidesEmpty || metric.value(it) > 0 })

    /** One visible line: an entry and how deep it sits below the shown directory. */
    data class Row(val entry: Entry, val depth: Int, val expanded: Boolean)

    /** The shown directory's entries, with the entries of each [expanded] directory, file or declaration listed beneath it. */
    fun rows(directory: Entry, expanded: Set<String>): List<Row> {
        val rows = mutableListOf<Row>()
        fun add(parent: Entry, depth: Int) {
            for (child in children(parent)) {
                val open = child.expandable && child.path in expanded
                rows += Row(child, depth, open)
                if (open) add(child, depth + 1)
            }
        }
        add(directory, 0)
        return rows
    }

    fun breadcrumbs(directory: Entry): List<Entry> {
        val ancestors = mutableListOf<Entry>()
        var current = directory
        while (current.path != root.path) {
            ancestors += current
            current = entries[current.path.substringBeforeLast('/', "")] ?: root
        }
        return listOf(root) + ancestors.asReversed()
    }

    companion object {
        /** Whether [path] lies beneath [parent]: in a directory, a file's outline, or a declaration's members. */
        fun isBeneath(path: String, parent: String): Boolean =
            path.startsWith("$parent/") || (!parent.contains('#') && path.startsWith("$parent#"))
    }
}
