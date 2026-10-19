package dev.insight.codereview

import dev.insight.diff.ReviewSource
import dev.insight.diff.SourceChunk
import dev.insight.diff.reviewFingerprint
import java.util.Locale

/**
 * A source file in scope with its LF text. A file over [ChunkOptions.singleChars] has [chunks]: contiguous line ranges that
 * are summarized separately. Equal while the path and text are unchanged; [stamp] and [length] let a reload reuse it unread.
 */
class CodeFile(
    override val path: String,
    override val absolutePath: String,
    val text: String,
    override val fingerprint: String = reviewFingerprint("code", path, text),
    val chunks: List<SourceChunk> = emptyList(),
    internal val stamp: Long = -1,
    internal val length: Long = text.length.toLong(),
) : ReviewSource {
    val lineCount: Int = if (text.isEmpty()) 0 else text.count { it == '\n' } + if (text.endsWith('\n')) 0 else 1

    /** Offsets of the first character of each line. */
    internal val lineStarts: IntArray by lazy {
        val starts = IntArray(lineCount)
        var line = 0
        if (lineCount > 0) starts[line++] = 0
        for (i in text.indices) if (text[i] == '\n' && line < lineCount) starts[line++] = i + 1
        starts
    }

    /** The text of the 1-based lines in [range], clamped to the file, without the final line separator. */
    fun lines(range: IntRange): String {
        if (lineCount == 0) return ""
        val first = range.first.coerceIn(1, lineCount)
        val last = range.last.coerceIn(1, lineCount)
        if (last < first) return ""
        val end = if (last < lineCount) lineStarts[last] - 1 else text.length.let { if (text.endsWith('\n')) it - 1 else it }
        return text.substring(lineStarts[first - 1], end)
    }

    override val facts: List<String> get() = listOfNotNull(
        "${count(lineCount)} ${if (lineCount == 1) "line" else "lines"}",
        chunks.takeIf { it.isNotEmpty() }?.let { "${count(it.size)} parts" },
    )

    override fun equals(other: Any?): Boolean = other is CodeFile && other.path == path && other.fingerprint == fingerprint
    override fun hashCode(): Int = 31 * path.hashCode() + fingerprint.hashCode()
    // Swing tree renderers call toString before applying their custom label.
    override fun toString(): String = path.substringAfterLast('/')
}

enum class SkipReason(val label: String) {
    BINARY("binary"),
    TOO_LARGE("too large"),
    MINIFIED("minified"),
    GENERATED("generated"),
    UNREADABLE("unreadable"),
    LIMIT("over the scope limit"),
}

data class SkippedFile(val path: String, val reason: SkipReason)

/** A start directory: [path] is project-relative, "" for the project root. */
data class CodeScope(val path: String, val absolutePath: String) {
    val displayName: String get() = path.ifEmpty { "Project root" }
}

data class CodeLimits(
    val maxFiles: Int = 20_000,
    val maxFileChars: Int = 2_000_000,
    val maxTotalChars: Long = 64_000_000,
    val chunk: ChunkOptions = ChunkOptions(),
)

/**
 * The files of one scope in project-relative path order. [skipped] lists files that were left out; at most
 * [CodeReviewLoader.MAX_LISTED_OVER_LIMIT] of the [overLimit] files beyond the limits are listed. [ignoredCount] counts
 * VCS-ignored files and folders that were pruned (their contents are not counted).
 */
data class CodeSnapshot(
    val scope: CodeScope,
    val files: List<CodeFile>,
    val skipped: List<SkippedFile> = emptyList(),
    val ignoredCount: Int = 0,
    val truncated: Boolean = false,
    val overLimit: Int = 0,
    val limits: CodeLimits = CodeLimits(),
) {
    val byPath: Map<String, CodeFile> by lazy { files.associateBy { it.path } }
    val totalChars: Long by lazy { files.sumOf { it.text.length.toLong() } }
    val totalLines: Long by lazy { files.sumOf { it.lineCount.toLong() } }
    /** Files split into parts. */
    val chunkedFiles: Int by lazy { files.count { it.chunks.isNotEmpty() } }
    val partCount: Int by lazy { files.sumOf { it.chunks.size } }

    companion object {
        fun empty(scope: CodeScope = CodeScope("", "")) = CodeSnapshot(scope, emptyList())
    }
}

private fun count(value: Int): String = String.format(Locale.US, "%,d", value)
