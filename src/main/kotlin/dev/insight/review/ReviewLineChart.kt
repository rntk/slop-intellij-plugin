package dev.insight.review

/** Right-aligns [text] in a monospace row of [columns] cells, filling the leading cells with dots. */
internal fun dotLeader(text: String, columns: Int): String = text.padStart(columns, '.')

internal enum class LineChartMetric(
    val label: String,
    val unit: String,
    val description: String,
    val diffSeries: RadarSeries? = null,
) {
    LINES("Lines of code", "line", "Source text lines, including blank lines and comments"),
    SIZE("Size", "byte", "UTF-8 bytes in the captured source text"),
    ITEMS("Items", "item", "Files in the selected scope"),
    ADDED("Added lines", "added line", "Added lines in the selected diff directory", RadarSeries.ADDED),
    REMOVED("Removed lines", "removed line", "Removed lines in the selected diff directory", RadarSeries.REMOVED);

    fun value(file: ReviewSource): Long = when (this) {
        LINES -> radarLines(file).source.toLong()
        SIZE -> file.text?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
        ITEMS -> 1L
        ADDED -> radarLines(file).added.toLong()
        REMOVED -> radarLines(file).removed.toLong()
    }
}

/** Physical folders and files only: summary groups and file chunks never become chart entries. */
internal class ReviewLineChart(tree: ReviewSummaryTree, val metric: LineChartMetric = LineChartMetric.LINES) {
    data class Entry(val path: String, val label: String, val directory: Boolean, val value: Long, val files: Int)

    private val fileNodes = tree.nodes.filter { it.kind == SummaryKind.FILE }.distinctBy { it.path }

    private val sources: List<ReviewSource> = fileNodes.mapNotNull { it.file }

    private val entries = tree.nodes.filter { it.kind == SummaryKind.DIRECTORY || it.kind == SummaryKind.FILE }
        .associate { node ->
            node.path to Entry(node.path, node.label, node.kind == SummaryKind.DIRECTORY,
                0, if (node.kind == SummaryKind.FILE) 1 else 0)
        }.toMutableMap()
    val rootPath: String = tree.rootDirectoryPrefix
    val root: Entry

    init {
        // Count each captured file once, regardless of summary groups or chunks beneath it.
        for (node in fileNodes) {
            val value = node.file?.let(metric::value) ?: 0L
            entries[node.path]?.let { entries[node.path] = it.copy(value = value) }
            var parent = node.path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                entries[parent]?.let { entries[parent] = it.copy(value = it.value + value, files = it.files + 1) }
                parent = parent.substringBeforeLast('/', "")
            }
        }
        root = entries[rootPath] ?: Entry("", tree.root.label, true,
            entries.values.filterNot { it.directory }.sumOf { it.value }, fileNodes.size)
    }

    fun hasEntry(path: String): Boolean = path == root.path || path in entries

    fun files(path: String): List<ReviewSource> {
        if (path == root.path) {
            return if (root.path.isEmpty()) sources else sources.filter { it.path.startsWith("${root.path}/") }
        }
        val entry = entries[path] ?: return emptyList()
        return if (!entry.directory) sources.filter { it.path == entry.path }
        else sources.filter { entry.path.isEmpty() || it.path.startsWith("${entry.path}/") }
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

    fun children(directory: Entry): List<Entry> = entries.values.filter {
        it.path != directory.path && it.path.substringBeforeLast('/', "") == directory.path
    }.sortedWith(compareBy<Entry> { it.value }.thenBy { it.label }.thenBy { it.path })

    /** One visible line: an entry and how deep it sits below the shown directory. */
    data class Row(val entry: Entry, val depth: Int, val expanded: Boolean)

    /** The shown directory's entries, with the entries of each [expanded] subdirectory listed beneath it. */
    fun rows(directory: Entry, expanded: Set<String>): List<Row> {
        val rows = mutableListOf<Row>()
        fun add(parent: Entry, depth: Int) {
            for (child in children(parent)) {
                val open = child.directory && child.path in expanded
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
}
