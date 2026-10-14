package dev.insight.entrypoints

import java.util.TreeMap

/**
 * Renders project-relative paths as an indented tree that fits a character budget. Large folders are summarized
 * (file count and extensions) rather than listed, deep folders are folded, and chains of single-folder directories
 * are joined (`src/main/kotlin/`). Root files are always listed: manifests and build files there usually name the
 * entry points. When a folder is cut, files named like entry points or manifests are kept first.
 */
object FileTree {
    private class Dir {
        val dirs = TreeMap<String, Dir>()
        val files = mutableListOf<String>()
        val total: Int get() = files.size + dirs.values.sumOf { it.total }
        fun extensions(): Map<String, Int> {
            val counts = sortedMapOf<String, Int>()
            files.forEach { counts.merge(extension(it), 1, Int::plus) }
            dirs.values.forEach { child -> child.extensions().forEach { (k, v) -> counts.merge(k, v, Int::plus) } }
            return counts
        }
    }

    /** (files listed per folder, deepest folder level expanded), from most to least detailed. */
    private val LEVELS = listOf(
        Int.MAX_VALUE to Int.MAX_VALUE, 25 to Int.MAX_VALUE, 12 to Int.MAX_VALUE, 6 to 8, 3 to 6,
        0 to 6, 0 to 4, 0 to 3, 0 to 2, 0 to 1,
    )

    fun render(paths: List<String>, maxChars: Int): String {
        val root = Dir()
        for (path in paths) {
            if (isNoise(path)) continue
            val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            var dir = root
            for (part in parts.dropLast(1)) dir = dir.dirs.getOrPut(part, ::Dir)
            dir.files += parts.last()
        }
        if (root.total == 0) return "(no files)"
        var text = ""
        for ((perDir, depth) in LEVELS) {
            text = buildString { render(root, 0, perDir, depth, this) }.trimEnd()
            if (text.length <= maxChars) return text
        }
        return text.take(maxChars).substringBeforeLast('\n') + "\n… (tree truncated)"
    }

    private fun render(dir: Dir, depth: Int, perDir: Int, maxDepth: Int, out: StringBuilder) {
        val indent = "  ".repeat(depth)
        for ((name, start) in dir.dirs) {
            // Join single-folder chains so deep package paths take one line.
            var label = "$name/"
            var child = start
            while (child.files.isEmpty() && child.dirs.size == 1) {
                val (next, nested) = child.dirs.entries.first()
                label += "$next/"
                child = nested
            }
            if (depth + 1 > maxDepth) out.append(indent).append(label).append(" (").append(summary(child)).append(")\n")
            else {
                out.append(indent).append(label).append('\n')
                render(child, depth + 1, perDir, maxDepth, out)
            }
        }
        val limit = if (depth == 0) Int.MAX_VALUE else perDir
        val shown = if (dir.files.size <= limit) dir.files.sorted()
            else dir.files.sortedWith(compareBy<String> { if (isLandmark(it)) 0 else 1 }.thenBy { it }).take(limit).sorted()
        shown.forEach { out.append(indent).append(it).append('\n') }
        val hidden = dir.files.size - shown.size
        if (hidden > 0) {
            val rest = Dir().apply { files += dir.files - shown.toSet() }
            out.append(indent).append("… ").append(hidden).append(if (shown.isEmpty()) " files (" else " more files (")
                .append(extensionSummary(rest.extensions())).append(")\n")
        }
    }

    private fun summary(dir: Dir): String {
        val total = dir.total
        return "$total file${if (total == 1) "" else "s"}: ${extensionSummary(dir.extensions())}"
    }

    private fun extensionSummary(counts: Map<String, Int>): String =
        counts.entries.sortedByDescending { it.value }.take(5).joinToString(", ") { "${it.key} ×${it.value}" } +
            if (counts.size > 5) ", …" else ""

    private fun extension(name: String) = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) name else ".$it" }

    private val LANDMARK = Regex(
        "(?i)^(main|app|application|index|server|cli|program|bootstrap|startup|launcher|manage|setup|wsgi|asgi|__main__|" +
            "plugin|manifest|androidmanifest|package|pyproject|cargo|go|build|settings|makefile|dockerfile|procfile|compose|docker-compose)" +
            "([._-].*)?$"
    )

    /** File names that often declare or are entry points. */
    internal fun isLandmark(name: String) = LANDMARK.matches(name)

    private val TOOL_DIRS = setOf(".git", ".idea", ".gradle", ".traverse-cache", "__pycache__", ".pytest_cache",
        ".mypy_cache", ".ruff_cache", "node_modules", ".venv")
    private val OUTPUT_DIRS = setOf("venv", "build", "dist", "target", "vendor", "coverage")
    private val APP_DIRS = setOf("frontend", "backend", "client", "server", "web", "app")
    private val WORKSPACE_DIRS = setOf("packages", "apps", "services", "modules")

    /** Ambiguous names such as build are output only at the root or conventional app/workspace roots. */
    internal fun isNoise(path: String): Boolean {
        val dirs = path.replace('\\', '/').removePrefix("./").split('/').dropLast(1)
        if (dirs.any { it in TOOL_DIRS }) return true
        return dirs.withIndex().any { (i, name) ->
            name in OUTPUT_DIRS && (i == 0 || i == 1 && dirs[0] in APP_DIRS || i == 2 && dirs[0] in WORKSPACE_DIRS)
        }
    }
}
