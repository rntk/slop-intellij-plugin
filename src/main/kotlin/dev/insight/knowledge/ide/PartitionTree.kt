package dev.insight.knowledge.ide

import dev.insight.knowledge.KnowledgePartition

/** Directory names whose content is generated or built, never worth distilling. */
private val EXCLUDED_DIRS = setOf(
    "build", "out", "node_modules", ".git", ".gradle", ".idea", ".insight-review-cache", "target", "dist", "generated",
    "__pycache__", ".venv", "venv", ".svn", ".hg",
)

/** Whether a project-relative [path] lies inside a generated, build or tooling directory. [isDirectory] says whether [path] itself is one. */
internal fun isExcludedPath(path: String, isDirectory: Boolean = false): Boolean {
    val segments = normalizeProjectPath(path).split('/').filter { it.isNotEmpty() }
    return (if (isDirectory) segments else segments.dropLast(1)).any { it in EXCLUDED_DIRS }
}

/**
 * One partition per directory that holds files directly. A directory with more than [maxFilesPerPartition] files is split into
 * the fewest chunks that fit, of sizes differing by at most one, in path order; the chunks share the directory's area. [parent]
 * is the parent directory's area (`""` for top-level directories) and null for the root area `""`. Partitions come in path
 * order, so a directory precedes its subdirectories.
 */
internal fun partitionFiles(files: Collection<String>, maxFilesPerPartition: Int): List<KnowledgePartition> {
    require(maxFilesPerPartition >= 1) { "maxFilesPerPartition must be at least 1" }
    val byDirectory = files.map(::normalizeProjectPath).filter { it.isNotEmpty() }.distinct().sorted()
        .groupBy { it.substringBeforeLast('/', "") }
    return byDirectory.toSortedMap().flatMap { (directory, direct) ->
        val parent = if (directory.isEmpty()) null else directory.substringBeforeLast('/', "")
        val chunks = (direct.size + maxFilesPerPartition - 1) / maxFilesPerPartition
        val base = direct.size / chunks
        val extra = direct.size % chunks
        var from = 0
        List(chunks) { i ->
            val size = base + if (i < extra) 1 else 0
            KnowledgePartition(directory, direct.subList(from, from + size), parent).also { from += size }
        }
    }
}
