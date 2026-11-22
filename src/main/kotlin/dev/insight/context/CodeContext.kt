package dev.insight.context

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import dev.insight.agent.SourceSide
import dev.insight.diff.ReviewFile
import dev.insight.diff.ReviewSnapshot
import dev.insight.review.SummaryKind

/**
 * What a context covers, narrow to wide: [CHANGE] is a whole comparison, [FILE_DIFF] one file's diff, [HUNK] one diff hunk, and
 * [SYMBOL] an enclosing declaration, which may appear several times (a method, then its class).
 */
enum class ContextScopeKind { CHANGE, FILE_DIFF, HUNK, SELECTION, LINE, SYMBOL, FILE, DIRECTORY }

/** The view the context picker was opened from. */
enum class ContextOrigin { EDITOR, IDE_DIFF, DIFFS_WALL, CODE_WALL, OTHER }

/** A declaration a context covers; [kind] is a lower-case symbol kind such as `function` or `class`. */
data class ContextSymbol(val name: String, val qualifiedName: String?, val kind: String)

/**
 * A piece of code the user picked: [text] is its source, or unified patch text when [isPatch]. [path] is project-relative and null
 * for a multi-file [ContextScopeKind.CHANGE]; [lines] are 1-based and inclusive on [side] for line-based scopes; [files]
 * lists every path covered.
 */
data class CodeContext(
    val kind: ContextScopeKind,
    /** A short UI label such as `Function render`, `Hunk @@ -10,4 +10,6 @@` or `Line 42`. */
    val label: String,
    val path: String?,
    val absolutePath: String?,
    val lines: IntRange?,
    val side: SourceSide,
    val text: String,
    val isPatch: Boolean = false,
    val symbol: ContextSymbol? = null,
    val files: List<String> = listOfNotNull(path),
)

/** `path:10-20`, `path:42` or `path`; a multi-file context names its file count. */
fun CodeContext.location(): String {
    if (kind == ContextScopeKind.DIRECTORY) return "${path.orEmpty().ifEmpty { "." }}/"
    val where = path ?: files.singleOrNull() ?: "${files.size} files"
    val range = lines ?: return where
    return if (range.first == range.last) "$where:${range.first}" else "$where:${range.first}-${range.last}"
}

/** The context as Markdown for chat or the clipboard: a header naming its label, location and diff side, then a fenced block. */
fun CodeContext.toMarkdown(): String {
    val sideNote = if (side == SourceSide.CURRENT) "" else " (${side.label})"
    val language = if (isPatch) "diff" else path?.substringAfterLast('/')?.substringAfterLast('.', "")?.lowercase().orEmpty()
    // A fence longer than any backtick run inside the text keeps the block intact.
    val fence = "`".repeat(maxOf(3, (Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0) + 1))
    return buildString {
        append("**").append(label).append("** `").append(location()).append('`').append(sideNote).append('\n')
        append(fence).append(language).append('\n')
        append(text)
        if (!text.endsWith('\n')) append('\n')
        append(fence).append('\n')
    }
}

/** A typed key of [ContextData]; [name] is for debugging only, keys compare by identity. */
class ContextKey<T : Any>(val name: String) {
    override fun toString() = name
}

enum class ReviewDestination { SUMMARIES, OUTLINE, HIERARCHY, DOCUMENT, EXPLORER, SPLIT, TREE, DIFF, TOPICS, GROUPING, WORDS, DIFF_OVERVIEW, METRICS, MOVES, REPORT_VIEWER }

/** Starts a missing file or directory summary in the review that owns a picker. */
internal class ContextSummaryActions(
    val available: (String, SummaryKind) -> Boolean,
    val generate: (String, SummaryKind) -> Boolean,
    val matchesDiff: (ReviewFile) -> Boolean = { true },
)

/** A project file a picked text links to: [path] is project-relative, [lines] the 1-based range the link names, if any. */
data class LinkedFile(val path: String, val absolutePath: String?, val lines: IntRange? = null)

internal class ReportSelectionActions(
    val openChat: (String) -> Unit,
    val delete: () -> Unit,
)

/** View-specific extras a host passes to actions without changing the core model. */
class ContextData {
    private val values = HashMap<ContextKey<*>, Any>()

    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(key: ContextKey<T>): T? = values[key] as T?

    fun <T : Any> put(key: ContextKey<T>, value: T): ContextData = apply { values[key] = value }

    operator fun contains(key: ContextKey<*>) = key in values
}

/** Keys hosts commonly fill; plugin views may declare their own. */
object ContextKeys {
    internal val SUMMARY_GENERATION = ContextKey<ContextSummaryActions>("summaryGeneration")
    internal val REPORT_SELECTION = ContextKey<ReportSelectionActions>("reportSelection")
    val REVIEW_FILE = ContextKey<ReviewFile>("reviewFile")
    /** The files a multi-file scope of linked targets covers, matched by [CodeContext.files]; actions open them all at once. */
    val LINKED_FILES = ContextKey<List<LinkedFile>>("linkedFiles")
    val OPEN_ON_DIFFS_WALL = ContextKey<(CodeContext) -> Unit>("openOnDiffsWall")
    val OPEN_ON_CODE_WALL = ContextKey<(CodeContext) -> Unit>("openOnCodeWall")
    val REVIEW_DESTINATIONS = ContextKey<Map<ReviewDestination, () -> Unit>>("reviewDestinations")
    val REVIEW_SNAPSHOT = ContextKey<ReviewSnapshot>("reviewSnapshot")
    val VIRTUAL_FILE = ContextKey<VirtualFile>("virtualFile")
    val PSI_FILE = ContextKey<PsiFile>("psiFile")
    val EDITOR = ContextKey<Editor>("editor")
    /** The caret or click offset in [EDITOR]'s document. */
    val OFFSET = ContextKey<Int>("offset")
    /**
     * Opens the context's file in a host-managed canvas session, using host state such as the Diffs wall snapshot.
     * Review diff actions offer the canvas only when a host provides this.
     */
    val OPEN_ON_CANVAS = ContextKey<(CodeContext) -> Unit>("openOnCanvas")
}
