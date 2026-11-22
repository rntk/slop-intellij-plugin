package dev.insight.links

import dev.insight.graph.SymbolKind

/**
 * One declaration a symbol mention may point to: [path] project-relative (or absolute outside the project), [line] 1-based,
 * [container] the declaring class, file or package as the IDE presents it, when known.
 */
data class SymbolCandidate(
    val name: String,
    val kind: SymbolKind?,
    val container: String?,
    val path: String,
    val absolutePath: String,
    val line: Int,
    val column: Int = 1,
) {
    val fileName: String get() = path.substringAfterLast('/')
    val directory: String get() = path.substringBeforeLast('/', "")

    /** One declaration: overloads and one-line classes share a line, so the column tells them apart. */
    data class Identity(val absolutePath: String, val line: Int, val column: Int)

    val identity: Identity get() = Identity(absolutePath, line, column)
}

/**
 * What the text around a mention tells about the declaration it means: [files] the project files the text referred to most
 * recently first, and [scopeFiles] the files under review.
 */
data class SymbolHints(
    val files: List<String> = emptyList(),
    val scopeFiles: Set<String> = emptySet(),
)

/**
 * Orders [candidates] for [mention], most likely first, dropping duplicates of one declaration. When the mention is qualified
 * and some candidates are declared in that qualifier, the others are dropped, so `Foo.run` lists only `Foo`'s `run`.
 * Keeps the [limit] most likely, after ranking, so a common name still lists the declarations the text points to.
 */
fun rankSymbolCandidates(
    candidates: List<SymbolCandidate>,
    mention: SymbolMention,
    hints: SymbolHints = SymbolHints(),
    limit: Int = MAX_LISTED_CANDIDATES,
): List<SymbolCandidate> {
    val unique = candidates.distinctBy { it.identity }
    val exact = unique.filter { it.name == mention.name }.ifEmpty { unique }
    val owner = mention.qualifier.lastOrNull()
    val qualified = owner?.let { o -> exact.filter { declaredIn(it, o) } }?.takeIf { it.isNotEmpty() } ?: exact
    val recent = hints.files.withIndex().associate { (i, path) -> path to i }
    val recentDirs = hints.files.map { it.substringBeforeLast('/', "") }.toSet()
    fun score(c: SymbolCandidate): Int {
        var s = 0
        recent[c.path]?.let { s += 40 - minOf(it, 20) }
        if (c.directory in recentDirs) s += 8
        if (c.path in hints.scopeFiles) s += 6
        if (mention.call && c.kind == SymbolKind.FUNCTION) s += 4
        if (!mention.call && mention.name.first().isUpperCase() && c.kind == SymbolKind.CLASS) s += 4
        if (isTestPath(c.path)) s -= 3
        return s
    }
    return qualified.sortedWith(compareByDescending<SymbolCandidate> { score(it) }.thenBy { it.path }.thenBy { it.line }.thenBy { it.column })
        .take(limit)
}

/** At most this many declarations are listed for one mention. */
const val MAX_LISTED_CANDIDATES = 200

/** `Declarations of run (3)`: the title of a candidate list. */
fun symbolCandidatesTitle(name: String, count: Int): String = "Declarations of $name ($count)"

/** `Function in ReportLinkCollector`, or just the kind when the container is unknown. */
fun symbolCandidateDetail(candidate: SymbolCandidate): String {
    val kind = symbolKindLabel(candidate.kind)
    return candidate.container?.takeIf { it.isNotBlank() }?.let { "$kind in $it" } ?: kind
}

/** `ReportLinkParser.kt:42 · src/main/kotlin/dev/insight/review`. */
fun symbolCandidateLocation(candidate: SymbolCandidate): String =
    "${candidate.fileName}:${candidate.line}" + candidate.directory.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()

fun symbolKindLabel(kind: SymbolKind?): String = when (kind) {
    SymbolKind.CLASS -> "Class"
    SymbolKind.FUNCTION -> "Function"
    SymbolKind.PROPERTY -> "Property"
    SymbolKind.PARAMETER -> "Parameter"
    SymbolKind.VARIABLE -> "Variable"
    SymbolKind.FILE -> "File"
    SymbolKind.DIRECTORY -> "Directory"
    SymbolKind.SYMBOL, null -> "Symbol"
}

private fun declaredIn(candidate: SymbolCandidate, owner: String): Boolean {
    val container = candidate.container ?: return false
    return container.split('.', ' ', '(', ')', '/', ':', '#').any { it == owner } ||
        candidate.fileName.substringBeforeLast('.') == owner
}

private fun isTestPath(path: String): Boolean {
    val lower = path.lowercase()
    return "/test/" in "/$lower" || "/tests/" in "/$lower" || lower.substringAfterLast('/').let { it.contains("test.") || it.startsWith("test_") }
}
