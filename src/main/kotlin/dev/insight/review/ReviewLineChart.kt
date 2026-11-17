package dev.insight.review

/** Right-aligns [text] in a monospace row of [columns] cells, filling the leading cells with dots. */
internal fun dotLeader(text: String, columns: Int): String = text.padStart(columns, '.')

/** Physical folders and files only: summary groups and file chunks never become chart entries. */
internal class ReviewLineChart(tree: ReviewSummaryTree, series: RadarSeries = RadarSeries.SOURCE) {
    data class Entry(val path: String, val label: String, val directory: Boolean, val lines: Long, val files: Int)

    private val sources: List<ReviewSource> = tree.nodes
        .filter { it.kind == SummaryKind.FILE }
        .distinctBy { it.path }
        .mapNotNull { it.file }

    private val entries = tree.nodes.filter { it.kind == SummaryKind.DIRECTORY || it.kind == SummaryKind.FILE }
        .associate { node ->
            node.path to Entry(node.path, node.label, node.kind == SummaryKind.DIRECTORY,
                0, if (node.kind == SummaryKind.FILE) 1 else 0)
        }.toMutableMap()
    val rootPath: String = tree.rootDirectoryPrefix
    val root: Entry

    init {
        // Count each captured file once, regardless of summary groups or chunks beneath it.
        for (node in tree.nodes.filter { it.kind == SummaryKind.FILE }.distinctBy { it.path }) {
            val lines = node.file?.let(::radarLines)?.let(series.value)?.toLong() ?: 0L
            entries[node.path]?.let { entries[node.path] = it.copy(lines = lines) }
            var parent = node.path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                entries[parent]?.let { entries[parent] = it.copy(lines = it.lines + lines, files = it.files + 1) }
                parent = parent.substringBeforeLast('/', "")
            }
        }
        root = entries[rootPath] ?: Entry("", tree.root.label, true,
            entries.values.filterNot { it.directory }.sumOf { it.lines }, entries.values.count { !it.directory })
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
    }.sortedWith(compareBy<Entry> { it.lines }.thenBy { it.label }.thenBy { it.path })

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
