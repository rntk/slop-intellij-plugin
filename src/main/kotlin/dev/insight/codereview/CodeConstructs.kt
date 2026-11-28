package dev.insight.codereview

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import dev.insight.diff.FileMetrics
import dev.insight.diff.Metric
import dev.insight.diff.ReviewFile
import dev.insight.diff.SemanticMetrics

/** What the Words page counts: the words of the source, or its source constructs. */
internal enum class CodeWordView(val label: String, val hint: String) {
    WORDS("Words", "Chart the most frequent words of the source in scope"),
    CONSTRUCTS("Constructs", "Chart the source constructs in scope (branches, loops, literals…); larger ones occur more often"),
}

/**
 * The constructs of whole files as [CodeWords]: each construct occurs as often as the files hold it. Files that were not
 * analyzed (no code analysis for their language, too large…) are left out, and constructs that do not occur are omitted.
 * [CodeWords.distinctWords] is the number of constructs found, [CodeWords.totalWords] their occurrences.
 */
internal fun codeConstructs(files: List<FileMetrics>): CodeWords {
    val words = Metric.entries.filter { it.construct }.mapNotNull { metric ->
        val byFile = linkedMapOf<String, Int>()
        files.filter { it.skipped == null }.forEach { file -> file.after?.get(metric)?.takeIf { it > 0 }?.let { byFile[file.path] = it } }
        CodeWord(metric.label, byFile.values.sum(), byFile).takeIf { it.count > 0 }
    }.sortedWith(compareByDescending<CodeWord> { it.count }.thenBy { it.word })
    return CodeWords(words, words.sumOf { it.count }, words.size)
}

/**
 * Parses [files] and counts their constructs. Each file is read in its own smart read action, so a write only
 * restarts the file being parsed. [checkCanceled] is called between files.
 */
internal suspend fun countCodeConstructs(project: Project, files: List<CodeFile>, checkCanceled: () -> Unit = {}): CodeWords {
    val metrics = SemanticMetrics(project)
    return codeConstructs(files.map { file ->
        checkCanceled()
        // An empty before side makes the file an added one, so only its own side is parsed.
        val whole = ReviewFile(file.path, file.absolutePath, null, "added", "", file.fingerprint, before = "", after = file.text)
        smartReadAction(project) { metrics.file(whole) }
    })
}
