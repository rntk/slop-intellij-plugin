package dev.insight.context

import com.intellij.psi.codeStyle.NameUtil

/**
 * One row of the picker's action list: a group header, an action under it, the note shown when nothing applies, or the note
 * shown when the filter hides every action.
 */
internal sealed interface PickerRow {
    data class Header(val group: String) : PickerRow
    data class Item(val action: ContextAction) : PickerRow
    data object Empty : PickerRow
    data object NoMatch : PickerRow
}

/** The action list rows of [arranged] sections: each group's header, then its actions; a single [PickerRow.Empty] for none. */
internal fun actionRows(arranged: List<Pair<String, List<ContextAction>>>): List<PickerRow> =
    if (arranged.isEmpty()) listOf(PickerRow.Empty)
    else arranged.flatMap { (group, actions) -> listOf<PickerRow>(PickerRow.Header(group)) + actions.map(PickerRow::Item) }

/**
 * The action list [rows] a filter query keeps, and the [best] match to select; [best] is null for a blank query. When the query
 * hides every action, the rows are a single [PickerRow.NoMatch].
 */
internal class ActionMatches(val rows: List<PickerRow>, val best: ContextAction?)

/**
 * The [actions] whose title fuzzily matches [query] (word starts, camel humps, any case), or failing that their group and title
 * together, in menu sections. [ActionMatches.best] prefers a title match, then the closer match, then the earlier row. A blank
 * query keeps every action.
 */
internal fun filterActions(actions: List<ContextAction>, query: String): ActionMatches {
    val pattern = query.trim()
    if (pattern.isEmpty()) return ActionMatches(actionRows(arrangeActions(actions)), null)
    val matcher = NameUtil.buildMatcher("*$pattern", NameUtil.MatchingCaseSensitivity.NONE)
    val scores = actions.mapNotNull { action ->
        val grouped = "${action.group} ${action.title}"
        when {
            matcher.matches(action.title) -> action to (1 to matcher.matchingDegree(action.title))
            matcher.matches(grouped) -> action to (0 to matcher.matchingDegree(grouped))
            else -> null
        }
    }.toMap()
    val sections = arrangeActions(scores.keys.toList())
    val ordered = sections.flatMap { it.second }
    val best = ordered.withIndex().maxWithOrNull(compareBy<IndexedValue<ContextAction>> { scores.getValue(it.value).first }
        .thenBy { scores.getValue(it.value).second }.thenByDescending { it.index })?.value
    val rows = if (sections.isEmpty() && actions.isNotEmpty()) listOf(PickerRow.NoMatch) else actionRows(sections)
    return ActionMatches(rows, best)
}

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
