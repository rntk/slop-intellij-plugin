package dev.insight.codereview

/** How often a word occurs in the source in scope, and how often in each file it occurs in. */
internal data class CodeWord(val word: String, val count: Int, val files: Map<String, Int>)

/** The most frequent [words] of the source files; [totalWords] counts every word, not only the listed ones. */
internal data class CodeWords(val words: List<CodeWord>, val totalWords: Int, val distinctWords: Int)

/** The same words as the Word changes chart of Diff Review: identifiers and words of two or more characters. */
private val WORD = Regex("[\\p{L}_$][\\p{L}\\p{N}_$]*")

/**
 * Counts the words of every file, keeping the most frequent [limit]. A second pass counts the files of those words only,
 * so a large scope does not hold a count for every word in every file. [checkCanceled] is called between files.
 */
internal fun codeWords(files: List<CodeFile>, limit: Int = 300, checkCanceled: () -> Unit = {}): CodeWords {
    val counts = hashMapOf<String, IntArray>()
    var total = 0
    files.forEach { file ->
        checkCanceled()
        WORD.findAll(file.text).forEach { match ->
            if (match.value.length < 2) return@forEach
            counts.getOrPut(match.value) { IntArray(1) }[0]++
            total++
        }
    }
    val top = counts.entries.sortedWith(compareByDescending<Map.Entry<String, IntArray>> { it.value[0] }.thenBy { it.key })
        .take(limit).map { it.key }
    val wanted = top.toHashSet()
    val byFile = top.associateWithTo(hashMapOf()) { linkedMapOf<String, Int>() }
    files.forEach { file ->
        checkCanceled()
        WORD.findAll(file.text).forEach { match ->
            if (match.value in wanted) byFile.getValue(match.value).merge(file.path, 1, Int::plus)
        }
    }
    val words = top.map { word -> CodeWord(word, counts.getValue(word)[0], byFile.getValue(word)) }
    return CodeWords(words, total, counts.size)
}
