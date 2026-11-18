package dev.insight.context

/** One row of the picker's action list: a group header, an action under it, or the note shown when nothing applies. */
internal sealed interface PickerRow {
    data class Header(val group: String) : PickerRow
    data class Item(val action: ContextAction) : PickerRow
    data object Empty : PickerRow
}

/** The action list rows of [arranged] sections: each group's header, then its actions; a single [PickerRow.Empty] for none. */
internal fun actionRows(arranged: List<Pair<String, List<ContextAction>>>): List<PickerRow> =
    if (arranged.isEmpty()) listOf(PickerRow.Empty)
    else arranged.flatMap { (group, actions) -> listOf<PickerRow>(PickerRow.Header(group)) + actions.map(PickerRow::Item) }

/** The number of lines [text] holds; a final line break adds none. */
internal fun textLineCount(text: String): Int = if (text.isEmpty()) 0 else text.count { it == '\n' } + if (text.endsWith('\n')) 0 else 1

/**
 * What the scope list shows after a scope's label: its lines (`10-20`, `42`), or for a whole file or change its size. [lineCount]
 * and [fileCount] turn a number into the unit's words.
 */
internal fun scopeDetail(context: CodeContext, lineCount: (Int) -> String, fileCount: (Int) -> String): String {
    val range = context.lines
    return when {
        range != null && range.first == range.last -> "${range.first}"
        range != null -> "${range.first}-${range.last}"
        context.kind == ContextScopeKind.CHANGE -> fileCount(context.files.size)
        context.kind == ContextScopeKind.DIRECTORY -> if (context.files.isEmpty()) "" else fileCount(context.files.size)
        else -> lineCount(textLineCount(context.text))
    }
}

/** A file name for [context]'s preview, which picks its syntax highlighting: a diff for patches, else the file's own name. */
internal fun previewFileName(context: CodeContext): String = when {
    context.isPatch -> "context.diff"
    else -> context.path?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: "context.txt"
}

/** The line shown at the top of the preview's gutter for [context], or null when its rows are not numbered lines of a file. */
internal fun previewFirstLine(context: CodeContext): Int? = if (context.isPatch) null else context.lines?.first
