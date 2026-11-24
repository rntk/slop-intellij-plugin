package dev.insight.review.ui

import dev.insight.agent.SourceSide
import dev.insight.context.CodeContext
import dev.insight.context.ContextData
import dev.insight.context.ContextKeys
import dev.insight.context.ContextOrigin
import dev.insight.context.ContextPick
import dev.insight.context.ContextScopeKind
import dev.insight.context.LinkedFile
import dev.insight.context.ReportSelectionActions
import dev.insight.context.ReviewDestination
import dev.insight.context.capContext
import dev.insight.review.ReportLink
import dev.insight.review.ReportSourceMap
import dev.insight.review.ReportSourceText
import dev.insight.review.ReportTargetKind
import dev.insight.review.ReviewSource
import dev.insight.review.formatLinkLocation
import dev.insight.review.parseReportLinkHref
import javax.swing.JEditorPane
import javax.swing.text.AttributeSet
import javax.swing.text.Document
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLDocument

internal data class ReportTextSelection(val start: Int, val end: Int, val text: String) {
    companion object {
        fun capture(pane: JEditorPane): ReportTextSelection? =
            pane.selectedText?.let { ReportTextSelection(pane.selectionStart, pane.selectionEnd, it) }
    }
}

/** The rendered [links] whose anchors overlap `[start, end)` of [document], in reading order and each once. */
internal fun reportLinksIn(document: Document, start: Int, end: Int, links: List<ReportLink>): List<ReportLink> {
    val html = document as? HTMLDocument ?: return emptyList()
    val found = LinkedHashMap<Int, ReportLink>()
    var pos = start.coerceAtLeast(0)
    val last = end.coerceAtMost(html.length)
    while (pos < last) {
        val element = html.getCharacterElement(pos)
        val anchor = element.attributes.getAttribute(HTML.Tag.A) as? AttributeSet
        parseReportLinkHref(anchor?.getAttribute(HTML.Attribute.HREF) as? String)
            ?.let { id -> links.getOrNull(id)?.let { found.putIfAbsent(id, it) } }
        pos = maxOf(element.endOffset, pos + 1)
    }
    return found.values.toList()
}

/** The files a selection links to, and the preview listing every link, including those left out. */
internal class SelectionLinks(val files: List<LinkedFile>, val preview: String)

/**
 * The project files [links] name, each once with the lines of its first link. Ambiguous links and directories are listed in the
 * preview but not opened, since opening them needs a choice or would pull in a whole tree. Null when no link names a file.
 */
internal fun selectionLinks(links: List<ReportLink>, absolutePath: (String) -> String?): SelectionLinks? {
    val files = LinkedHashMap<String, LinkedFile>()
    val skipped = mutableListOf<String>()
    for (link in links) {
        val path = link.primaryPath
        when {
            link.kind == ReportTargetKind.EXTERNAL_URL || path == null -> Unit
            link.kind == ReportTargetKind.DIRECTORY -> skipped += "${path.ifEmpty { "." }}/ (directory)"
            link.isAmbiguous -> skipped += "${link.displayText} (ambiguous: ${link.candidates.joinToString(", ")})"
            else -> {
                val existing = files[path]
                if (existing == null) {
                    files[path] = LinkedFile(path, absolutePath(path), link.lines)
                } else if (existing.lines == null && link.lines != null) {
                    files[path] = existing.copy(lines = link.lines)
                }
            }
        }
    }
    if (files.isEmpty()) return null
    val preview = buildString {
        files.values.forEach { append(formatLinkLocation(it.path, it.lines)).append('\n') }
        if (skipped.isNotEmpty()) {
            append("\nNot included:\n")
            skipped.forEach { append("  ").append(it).append('\n') }
        }
    }
    return SelectionLinks(files.values.toList(), preview)
}

/**
 * The picker for selected report text: the excerpt, then, when it links to project files, one more scope covering all of them.
 * Actions on that scope open every linked file at once: on the review's Diffs or Code wall through [openOnWall], in editors, or as
 * cards in a canvas session.
 */
internal fun reportSelectionContextPick(
    selection: ReportTextSelection,
    actions: ReportSelectionActions,
    destinations: Map<ReviewDestination, () -> Unit>,
    links: List<ReportLink> = emptyList(),
    absolutePath: (String) -> String? = { null },
    reviewFiles: List<ReviewSource> = emptyList(),
    openOnWall: ((List<ReviewSource>, String) -> Unit)? = null,
    label: String = "Selected report text",
): ContextPick {
    val text = CodeContext(ContextScopeKind.SELECTION, label, null, null, null,
        SourceSide.CURRENT, capContext(selection.text))
    val data = ContextData().put(ContextKeys.REPORT_SELECTION, actions)
    if (destinations.isNotEmpty()) data.put(ContextKeys.REVIEW_DESTINATIONS, destinations)
    val linked = selectionLinks(links, absolutePath) ?: return ContextPick(ContextOrigin.OTHER, listOf(text), data)
    data.put(ContextKeys.LINKED_FILES, linked.files)
    val scope = CodeContext(ContextScopeKind.CHANGE, "Links in selection", null, null, null, SourceSide.CURRENT,
        capContext(linked.preview), files = linked.files.map { it.path })
    if (openOnWall != null) putWallOpeners(data, linked.files, reviewFiles, openOnWall)
    return ContextPick(ContextOrigin.OTHER, listOf(text, scope), data)
}

/** Replays display-only removals when the same report text is rendered again. */
internal class ReportTextEdits(document: Document) {
    private val original = document.getText(0, document.length)
    private val removals = mutableListOf<IntRange>()

    fun restore(document: Document): Boolean {
        if (document.length != original.length || document.getText(0, document.length) != original) return false
        removals.forEach { document.remove(it.first, it.last - it.first + 1) }
        return true
    }

    fun delete(document: Document, selection: ReportTextSelection) {
        document.remove(selection.start, selection.end - selection.start)
        removals += selection.start until selection.end
    }

    /** Map a display selection back to the surviving runs of the original document. */
    fun originalRanges(selected: IntRange): List<IntRange> {
        var ranges = listOf(selected)
        for (removed in removals.asReversed()) {
            val length = removed.last - removed.first + 1
            ranges = ranges.flatMap { range ->
                when {
                    range.last < removed.first -> listOf(range)
                    range.first >= removed.first -> listOf(range.first + length..range.last + length)
                    else -> listOf(range.first until removed.first, removed.last + 1..range.last + length)
                }
            }
        }
        return ranges
    }
}

internal fun reportSourceDeletion(
    document: HTMLDocument,
    selection: ReportTextSelection,
    map: ReportSourceMap,
    isPlainText: Boolean,
): List<IntRange> = reportSourceSelection(document, selection.start until selection.end, map, isPlainText)
    ?.let(map::deletionRanges).orEmpty()

internal fun reportSourceCopy(
    originalDocument: HTMLDocument,
    selection: ReportTextSelection,
    map: ReportSourceMap,
    isPlainText: Boolean,
    edits: ReportTextEdits?,
): String? {
    val selected = selection.start until selection.end
    val ranges = edits?.originalRanges(selected) ?: listOf(selected)
    return map.copySource(ranges.mapNotNull { reportSourceSelection(originalDocument, it, map, isPlainText) })
}

/** Swing collapses whitespace in Markdown, so map each selected glyph within its own source span. */
private fun reportSourceSelection(
    document: HTMLDocument,
    selected: IntRange,
    map: ReportSourceMap,
    isPlainText: Boolean,
): IntRange? {
    var first: Int? = null
    var last: Int? = null
    var position = selected.first
    val end = minOf(selected.last + 1, document.length)
    while (position < end) {
        val leaf = document.getCharacterElement(position)
        val span = document.reportSpanId(position, "report-source-")?.let(map.spans::getOrNull)
        val leafEnd = minOf(leaf.endOffset, document.length)
        val range = if (span != null) {
            val displayed = document.getText(leaf.startOffset, leafEnd - leaf.startOffset)
            sourceRangeInSpan(span, displayed, position - leaf.startOffset,
                minOf(end, leafEnd) - leaf.startOffset, isPlainText) ?: return null
        } else if (isPlainText && document.getText(position, minOf(end, leafEnd) - position) == "\n") {
            val lineStart = document.getParagraphElement(position).startOffset
            document.reportSpanId(lineStart, "report-newline-")?.let { it..it }
        } else null
        if (range != null) {
            if (first == null) first = range.first
            last = range.last
        }
        position = leafEnd
    }
    return if (first != null && last != null && first <= last) first..last else null
}

private fun HTMLDocument.reportSpanId(position: Int, prefix: String): Int? {
    val attrs = getCharacterElement(position).attributes.getAttribute(HTML.Tag.SPAN) as? AttributeSet
    val id = attrs?.getAttribute(HTML.Attribute.ID) as? String
    return id?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull()
}

private fun sourceRangeInSpan(
    span: ReportSourceText,
    displayed: String,
    start: Int,
    end: Int,
    isPlainText: Boolean,
): IntRange? {
    if (span.text == displayed) return span.offset + start until span.offset + end
    // Markdown escapes display their second character and delete as a pair.
    if (span.text.length == 2 && span.text.startsWith('\\') && displayed == span.text.substring(1)) return span.range
    if (isPlainText) return null

    var sourceIndex = 0
    var first = 0
    var last = 0
    for ((index, character) in displayed.withIndex()) {
        if (index == 0 && !character.isHtmlSpace()) {
            while (span.text.getOrNull(sourceIndex)?.isHtmlSpace() == true) sourceIndex++
        }
        val sourceStart = sourceIndex
        if (character.isHtmlSpace()) {
            while (span.text.getOrNull(sourceIndex)?.isHtmlSpace() == true) sourceIndex++
            if (sourceIndex == sourceStart) return null
        } else {
            if (span.text.getOrNull(sourceIndex) != character) return null
            sourceIndex++
        }
        if (index == start) first = sourceStart
        if (index == end - 1) last = sourceIndex
    }
    return if (last > first) span.offset + first until span.offset + last else null
}

private fun Char.isHtmlSpace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r'
