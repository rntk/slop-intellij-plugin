package dev.insight.codereview

import dev.insight.review.ChatFocus
import dev.insight.review.ReviewSourceRange
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummarizer
import dev.insight.review.ReviewSummaryOptions
import dev.insight.review.ReviewTopics
import dev.insight.review.SummaryNode
import dev.insight.review.chatFocusPrompt

/** The pinned focus for a turn on [snapshot], numbered like read_code; empty without a focus. */
internal fun codeFocusPrompt(snapshot: CodeSnapshot, focus: ChatFocus?): String {
    focus ?: return ""
    return "\n\n" + chatFocusPrompt(focus, "code", READ_CODE_TOOL) { path, lines ->
        snapshot.byPath[path]?.let { file -> numberedLines(file, lines ?: 1..file.lineCount) }
    }
}

/** Describes the captured review scope in a model prompt. */
internal fun codePromptScope(scope: CodeScope, paths: Set<String>?): String {
    val name = if (scope.entry) "the entry file ${scope.path} and the project files it depends on (imports, calls and references, " +
        "transitively)" else scope.path.ifEmpty { "the project root" }
    return paths?.let { "${it.size} selected paths of $name" } ?: name
}

/**
 * About how many model requests a summary run of [summaries] sends: missing leaves batched like [ReviewSummarizer] (by
 * count, by [size] and alone when cut), then the missing branches wave by wave in batches of [ReviewSummaryOptions.foldersPerRequest].
 * A branch with a single child reuses its summary without a request. A complete tree starts over when [restartComplete].
 */
internal fun estimateSummaryRequests(
    summaries: ReviewSummaries,
    options: ReviewSummaryOptions,
    size: (SummaryNode) -> Int,
    restartComplete: Boolean = true,
): Int {
    val start = if (restartComplete && summaries.complete) ReviewSummaries(summaries.tree) else summaries
    val tree = start.tree
    val missing = start.missing().toHashSet()
    if (missing.isEmpty()) return 0
    var requests = 0
    var batch = 0
    var chars = 0
    // Wave of a missing node: 0 when its children are all written, else one after its latest missing child.
    val wave = HashMap<Int, Int>()
    for (node in tree.nodes.asReversed()) {
        if (node.id !in missing) continue
        wave[node.id] = node.children.filter { it in missing }.maxOfOrNull { wave.getValue(it) + 1 } ?: 0
        if (node.children.isNotEmpty()) continue
        val chars1 = size(node)
        if (chars1 > options.patchChars) { requests++; continue }
        if (batch > 0 && (batch >= options.filesPerRequest || chars + chars1 > options.batchChars)) { requests++; batch = 0; chars = 0 }
        batch++; chars += chars1
    }
    if (batch > 0) requests++
    val branches = tree.nodes.filter { it.id in missing && it.children.size > 1 }.groupingBy { wave.getValue(it.id) }.eachCount()
    branches.values.forEach { requests += (it + options.foldersPerRequest - 1) / options.foldersPerRequest }
    return requests
}

/** One request per file that a /topics run classifies: the missing files, or every file when the topics are complete. */
internal fun estimateTopicRequests(topics: ReviewTopics): Int =
    if (topics.complete) topics.files.size else topics.files.count { it.path !in topics.labels }

/**
 * The lines a source block shows for [lines] (a part) or a whole file: from its first line, at most [CODE_SOURCE_LINES]
 * lines and [CODE_SOURCE_CHARS] characters. Null when the file is empty or its first line alone is too long.
 */
internal fun sourceBlockRange(file: CodeFile, lines: IntRange?): ReviewSourceRange? {
    if (file.lineCount == 0) return null
    val start = (lines?.first ?: 1).coerceIn(1, file.lineCount)
    var end = minOf(lines?.last ?: file.lineCount, start + CODE_SOURCE_LINES - 1, file.lineCount).coerceAtLeast(start)
    if (file.lines(start..start).length > CODE_SOURCE_CHARS) return null
    if (file.lines(start..end).length > CODE_SOURCE_CHARS) {
        // The longest prefix that fits: the first line fits, the full range does not.
        var low = start
        var high = end
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (file.lines(start..middle).length <= CODE_SOURCE_CHARS) low = middle else high = middle - 1
        }
        end = low
    }
    return ReviewSourceRange(file.path, start, end, CODE_SOURCE_SIDE)
}

/** The 1-based [range] of [file], each line prefixed with its number so the model can cite path:line. */
internal fun numberedLines(file: CodeFile, range: IntRange): String {
    if (file.lineCount == 0) return ""
    val first = range.first.coerceIn(1, file.lineCount)
    return file.lines(first..range.last.coerceIn(first, file.lineCount)).lineSequence()
        .withIndex().joinToString("\n") { (i, line) -> "${first + i}: $line" }
}
