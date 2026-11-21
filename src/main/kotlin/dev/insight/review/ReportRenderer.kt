package dev.insight.review

/** Safety cap for rendered report length so multi-megabyte log files stay responsive in Swing HTML panes. */
internal const val MAX_REPORT_CHARS = 2_000_000

/** Safety cap for total Markdown table cells rendered so wide sparse tables cannot exhaust heap. */
internal const val MAX_TABLE_CELLS = 20_000

/** Colors injected by the Swing host so HTML reports match Light and Darcula themes cleanly. */
internal data class ReportThemeColors(
    val text: String,
    val muted: String,
    val background: String,
    val codeBg: String,
    val codeBlockBg: String,
    val border: String,
    val linkFg: String,
    val linkBg: String,
    val ambiguousLinkBg: String,
    val tableHeaderBg: String,
) {
    companion object {
        val DEFAULT = ReportThemeColors(
            text = "#1E1F22",
            muted = "#6C707E",
            background = "#FFFFFF",
            codeBg = "#F2F4F7",
            codeBlockBg = "#F6F8FA",
            border = "#DFE1E5",
            linkFg = "#2459D6",
            linkBg = "#EEF4FF",
            ambiguousLinkBg = "#FFF4E5",
            tableHeaderBg = "#F5F7FA",
        )
    }
}

/** One project file cited in the report, with total link mentions and whether it belongs to the active review scope. */
internal data class ReferencedFile(
    val path: String,
    val mentions: Int,
    val inReviewScope: Boolean,
)

/** HTML rendering output alongside every resolved [ReportLink] and an aggregated file index. */
internal data class RenderedReport(
    val html: String,
    val links: List<ReportLink>,
    val referencedFiles: List<ReferencedFile>,
    val ambiguousCount: Int,
    val truncated: Boolean,
)

internal fun isMarkdownFileName(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in MARKDOWN_EXTENSIONS
}

/** Heuristic for pasted text or extensionless files to decide whether Markdown rendering improves readability. */
internal fun looksLikeMarkdown(text: String): Boolean {
    val sample = if (text.length > 16_000) text.substring(0, 16_000) else text
    return MARKDOWN_HINT.containsMatchIn(sample)
}

internal fun parseReportLinkHref(href: String?): Int? =
    href?.removePrefix(LINK_HREF_PREFIX)?.takeIf { href.startsWith(LINK_HREF_PREFIX) }?.toIntOrNull()

internal fun formatLinkLocation(path: String, lines: IntRange?, column: Int? = null): String {
    if (lines == null) return path
    return when {
        lines.first == lines.last && column != null -> "$path:${lines.first}:$column"
        lines.first == lines.last -> "$path:L${lines.first}"
        else -> "$path:L${lines.first}-L${lines.last}"
    }
}

internal fun linkTooltip(link: ReportLink, inReviewScope: Boolean, isDiffReview: Boolean): String = when (link.kind) {
    ReportTargetKind.EXTERNAL_URL -> link.externalUrl.orEmpty()
    ReportTargetKind.DIRECTORY -> {
        val path = link.primaryPath.orEmpty().ifEmpty { "." }
        if (link.isAmbiguous) {
            "Matches ${link.candidates.size} directories: ${link.candidates.take(4).joinToString(", ")}" +
                "${if (link.candidates.size > 4) ", …" else ""} · Click or right-click for Context…"
        } else {
            "Directory: $path · Click or right-click for Context…"
        }
    }
    ReportTargetKind.FILE -> {
        if (link.isAmbiguous) {
            "Matches ${link.candidates.size} files: ${link.candidates.take(4).joinToString(", ")}" +
                "${if (link.candidates.size > 4) ", …" else ""} · Click to choose · Shift+Click or right-click for Context…"
        } else {
            val location = formatLinkLocation(link.primaryPath.orEmpty(), link.lines, link.column)
            val scopeTag = if (inReviewScope) (if (isDiffReview) " · in diff" else " · in scope") else ""
            "$location$scopeTag · Click to open · Shift+Click or right-click for Context…"
        }
    }
}

/** Renders a Markdown report to HTML with clickable project links resolved through [index]. */
internal fun renderMarkdownReport(
    text: String,
    index: ReportProjectIndex,
    colors: ReportThemeColors = ReportThemeColors.DEFAULT,
    maxChars: Int = MAX_REPORT_CHARS,
    maxTableCells: Int = MAX_TABLE_CELLS,
): RenderedReport {
    val (bounded, textTruncated) = truncateReportText(normalizeLineEndings(text), maxChars)
    val cellBudget = maxTableCells.coerceAtLeast(0)
    var renderedTableCells = 0
    var anyTableTruncated = false
    val collector = ReportLinkCollector(index)
    val lines = bounded.split('\n')
    val body = StringBuilder()

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        if (trimmed.isEmpty()) {
            collector.context.onBlankLine()
            i++
            continue
        }

        val fence = fencePrefix(trimmed)
        if (fence != null) {
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                codeLines += lines[i]
                i++
            }
            if (i < lines.size) i++
            body.append("<pre class=\"code-block\">")
            codeLines.forEachIndexed { idx, codeLine ->
                if (idx > 0) body.append('\n')
                body.append(renderTextWithLinks(codeLine, collector, inBackticks = true, allowContextualLines = false))
            }
            body.append("</pre>\n")
            continue
        }

        val heading = HEADING_LINE.matchEntire(trimmed)
        if (heading != null) {
            collector.context.onHeading()
            val level = heading.groupValues[1].length
            val content = heading.groupValues[2].trim().replace(Regex("\\s+#+$"), "")
            body.append("<h$level>")
                .append(renderInline(content, collector, inHeading = true, inListItem = false))
                .append("</h$level>\n")
            i++
            continue
        }

        if (RULE_LINE.matches(trimmed)) {
            collector.context.onRule()
            body.append("<hr>\n")
            i++
            continue
        }

        if (i + 1 < lines.size && isTableLine(trimmed) && TABLE_SEPARATOR_LINE.matches(lines[i + 1].trim())) {
            if (renderedTableCells >= cellBudget) {
                i += 2
                while (i < lines.size && isTableLine(lines[i].trim()) && lines[i].trim().isNotEmpty()) i++
                anyTableTruncated = true
                body.append("<p class=\"muted\">Table truncated at ${formatNumber(cellBudget)} cells.</p>\n")
                continue
            }
            val headerCells = splitTableRow(trimmed)
            val headerLimit = minOf(headerCells.size, cellBudget - renderedTableCells)
            var tableTruncated = headerLimit < headerCells.size
            val alignments = parseTableAlignments(lines[i + 1].trim(), headerLimit)
            body.append("<table cellspacing=\"0\" cellpadding=\"4\"><thead><tr>")
            collector.context.withTableRow {
                for (col in 0 until headerLimit) {
                    val alignAttr = alignments.getOrNull(col)?.let { " align=\"$it\"" }.orEmpty()
                    body.append("<th$alignAttr>")
                        .append(renderInline(headerCells[col], collector, inHeading = false, inListItem = false))
                        .append("</th>")
                }
            }
            renderedTableCells += headerLimit
            body.append("</tr></thead><tbody>")
            i += 2
            while (i < lines.size && isTableLine(lines[i].trim()) && lines[i].trim().isNotEmpty()) {
                if (renderedTableCells >= cellBudget) {
                    tableTruncated = true
                    while (i < lines.size && isTableLine(lines[i].trim()) && lines[i].trim().isNotEmpty()) i++
                    break
                }
                val rowCells = splitTableRow(lines[i].trim())
                val rowLimit = minOf(rowCells.size, cellBudget - renderedTableCells)
                body.append("<tr>")
                collector.context.withTableRow {
                    for (col in 0 until rowLimit) {
                        val alignAttr = alignments.getOrNull(col)?.let { " align=\"$it\"" }.orEmpty()
                        body.append("<td$alignAttr>")
                            .append(renderInline(rowCells[col], collector, inHeading = false, inListItem = false))
                            .append("</td>")
                    }
                }
                renderedTableCells += rowLimit
                body.append("</tr>")
                i++
                if (rowLimit < rowCells.size) {
                    tableTruncated = true
                    while (i < lines.size && isTableLine(lines[i].trim()) && lines[i].trim().isNotEmpty()) i++
                    break
                }
            }
            body.append("</tbody></table>\n")
            if (tableTruncated) {
                anyTableTruncated = true
                body.append("<p class=\"muted\">Table truncated at ${formatNumber(cellBudget)} cells.</p>\n")
            }
            continue
        }

        if (BLOCKQUOTE_LINE.containsMatchIn(line)) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && BLOCKQUOTE_LINE.containsMatchIn(lines[i])) {
                quoteLines += lines[i].replaceFirst(BLOCKQUOTE_PREFIX, "")
                i++
            }
            body.append("<blockquote>")
            quoteLines.forEachIndexed { idx, ql ->
                if (idx > 0) body.append("<br>")
                collector.context.onPlainLine()
                body.append(renderInline(ql.trim(), collector, inHeading = false, inListItem = false))
            }
            body.append("</blockquote>\n")
            continue
        }

        if (parseListItem(line) != null) {
            i = renderListBlock(lines, i, collector, body)
            continue
        }

        val paragraphLines = mutableListOf<String>()
        while (i < lines.size) {
            val next = lines[i]
            val nextTrim = next.trim()
            if (nextTrim.isEmpty() ||
                fencePrefix(nextTrim) != null ||
                HEADING_LINE.matches(nextTrim) ||
                RULE_LINE.matches(nextTrim) ||
                BLOCKQUOTE_LINE.containsMatchIn(next) ||
                parseListItem(next) != null ||
                (i + 1 < lines.size && isTableLine(nextTrim) && TABLE_SEPARATOR_LINE.matches(lines[i + 1].trim()))
            ) break
            paragraphLines += nextTrim
            i++
        }
        body.append("<p>")
        paragraphLines.forEachIndexed { idx, pLine ->
            if (idx > 0) body.append(' ')
            collector.context.onPlainLine()
            body.append(renderInline(pLine, collector, inHeading = false, inListItem = false))
        }
        body.append("</p>\n")
    }

    if (textTruncated) {
        body.append("<p class=\"muted\">Report truncated at ${formatNumber(maxChars)} characters.</p>\n")
    }
    val truncated = textTruncated || anyTableTruncated
    return buildRenderedReport(body.toString(), collector.links, index, colors, truncated, isPlainText = false)
}

/** Renders a plain-text or log report with line numbers and smart clickable project links. */
internal fun renderPlainTextReport(
    text: String,
    index: ReportProjectIndex,
    colors: ReportThemeColors = ReportThemeColors.DEFAULT,
    maxChars: Int = MAX_REPORT_CHARS,
): RenderedReport {
    val (bounded, truncated) = truncateReportText(normalizeLineEndings(text), maxChars)
    val collector = ReportLinkCollector(index)
    val lines = bounded.split('\n')
    val width = lines.size.toString().length.coerceAtLeast(2)
    val body = StringBuilder("<pre class=\"raw-text\">")
    lines.forEachIndexed { idx, line ->
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> collector.context.onBlankLine()
            HEADING_LINE.matches(trimmed) -> collector.context.onHeading()
            RULE_LINE.matches(trimmed) -> collector.context.onRule()
            else -> {
                val list = parseListItem(line)
                if (list != null) collector.context.onListItem(list.indent)
                else collector.context.onPlainLine()
            }
        }
        if (idx > 0) body.append('\n')
        val lineNo = (idx + 1).toString().padStart(width, ' ')
        body.append("<span class=\"ln\">").append(lineNo).append("  </span>")
        body.append(renderTextWithLinks(line, collector, inBackticks = false, allowContextualLines = true, inHeading = HEADING_LINE.matches(trimmed), inListItem = parseListItem(line) != null))
    }
    body.append("</pre>\n")
    if (truncated) {
        body.append("<p class=\"muted\">Report truncated at ${formatNumber(maxChars)} characters.</p>\n")
    }
    return buildRenderedReport(body.toString(), collector.links, index, colors, truncated, isPlainText = true)
}

private data class ListItemParse(val indent: Int, val ordered: Boolean, val startNumber: Int?, val checkbox: Boolean?, val content: String)

private fun parseListItem(line: String): ListItemParse? {
    val unordered = UNORDERED_LIST_ITEM.matchEntire(line)
    if (unordered != null) {
        val indent = unordered.groupValues[1].length
        val rest = unordered.groupValues[2]
        val task = TASK_PREFIX.matchAt(rest, 0)
        return if (task != null) {
            val checked = task.groupValues[1].equals("x", ignoreCase = true)
            ListItemParse(indent, ordered = false, startNumber = null, checkbox = checked, content = rest.substring(task.range.last + 1))
        } else {
            ListItemParse(indent, ordered = false, startNumber = null, checkbox = null, content = rest)
        }
    }
    val ordered = ORDERED_LIST_ITEM.matchEntire(line)
    if (ordered != null) {
        val indent = ordered.groupValues[1].length
        val num = ordered.groupValues[2].toIntOrNull()
        val rest = ordered.groupValues[3]
        return ListItemParse(indent, ordered = true, startNumber = num, checkbox = null, content = rest)
    }
    return null
}

private fun renderListBlock(lines: List<String>, startIndex: Int, collector: ReportLinkCollector, out: StringBuilder): Int {
    val first = parseListItem(lines[startIndex]) ?: return startIndex + 1
    val ordered = first.ordered
    val tag = if (ordered) "ol" else "ul"
    if (ordered && first.startNumber != null && first.startNumber != 1) {
        out.append("<ol start=\"${first.startNumber}\">")
    } else {
        out.append("<$tag>")
    }
    var i = startIndex
    while (i < lines.size) {
        val item = parseListItem(lines[i]) ?: break
        if (item.indent < first.indent) break
        if (item.indent == first.indent && item.ordered != ordered) break
        if (item.indent > first.indent) {
            // Unexpectedly indented item without parent; still render inside a nested list.
            i = renderListBlock(lines, i, collector, out)
            continue
        }
        collector.context.onListItem(item.indent)
        out.append("<li>")
        when (item.checkbox) {
            false -> out.append("&#9744; ")
            true -> out.append("&#9745; ")
            null -> Unit
        }
        out.append(renderInline(item.content, collector, inHeading = false, inListItem = true))
        i++
        // Consume nested list items or indented continuation lines.
        while (i < lines.size) {
            val next = lines[i]
            val nextTrim = next.trim()
            if (nextTrim.isEmpty()) break
            val nextItem = parseListItem(next)
            if (nextItem != null) {
                if (nextItem.indent > item.indent) {
                    i = renderListBlock(lines, i, collector, out)
                    continue
                } else break
            }
            if (next.startsWith("  ") || next.startsWith("\t")) {
                out.append(' ').append(renderInline(nextTrim, collector, inHeading = false, inListItem = true))
                i++
            } else break
        }
        out.append("</li>\n")
    }
    out.append("</$tag>\n")
    return i
}

private fun renderInline(
    text: String,
    collector: ReportLinkCollector,
    inHeading: Boolean,
    inListItem: Boolean,
): String {
    if (text.isEmpty()) return ""
    val out = StringBuilder()
    var i = 0
    var plainStart = 0

    fun flushPlain(end: Int) {
        if (end > plainStart) {
            out.append(renderTextWithLinks(text.substring(plainStart, end), collector, inBackticks = false, allowContextualLines = true, inHeading = inHeading, inListItem = inListItem))
        }
        plainStart = end
    }

    while (i < text.length) {
        if (text[i] == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE_MD_CHARS) {
            flushPlain(i)
            out.append(escapeHtml(text[i + 1].toString()))
            i += 2
            plainStart = i
            continue
        }

        // Inline code span: `...` or ``...``
        if (text[i] == '`') {
            var ticks = 0
            while (i + ticks < text.length && text[i + ticks] == '`') ticks++
            val close = findClosingBackticks(text, i + ticks, ticks)
            if (close != null) {
                flushPlain(i)
                val rawInner = text.substring(i + ticks, close)
                val inner = if (rawInner.length >= 2 && rawInner.startsWith(' ') && rawInner.endsWith(' ') && rawInner.any { it != ' ' }) {
                    rawInner.substring(1, rawInner.length - 1)
                } else rawInner
                out.append("<code>")
                    .append(renderTextWithLinks(inner, collector, inBackticks = true, allowContextualLines = true, inHeading = inHeading, inListItem = inListItem))
                    .append("</code>")
                i = close + ticks
                plainStart = i
                continue
            }
        }

        // Explicit Markdown link: [label](target)
        if (text[i] == '[') {
            val mdLink = parseMarkdownLinkAt(text, i)
            if (mdLink != null) {
                flushPlain(i)
                val resolved = collector.resolveMarkdownTarget(mdLink.target, mdLink.label, inHeading, inListItem)
                if (resolved != null) {
                    out.append(wrapAnchor(resolved, renderInlineNoLinks(mdLink.label)))
                } else {
                    out.append(renderInline(mdLink.label, collector, inHeading, inListItem))
                }
                i = mdLink.end
                plainStart = i
                continue
            }
        }

        // Bold: **...**
        if (text.startsWith("**", i)) {
            val close = findClosingDelimiter(text, i + 2, "**")
            if (close != null && close > i + 2 && !text[i + 2].isWhitespace() && !text[close - 1].isWhitespace()) {
                flushPlain(i)
                out.append("<b>")
                    .append(renderInline(text.substring(i + 2, close), collector, inHeading, inListItem))
                    .append("</b>")
                i = close + 2
                plainStart = i
                continue
            }
        }

        // Strikethrough: ~~...~~
        if (text.startsWith("~~", i)) {
            val close = findClosingDelimiter(text, i + 2, "~~")
            if (close != null && close > i + 2 && !text[i + 2].isWhitespace() && !text[close - 1].isWhitespace()) {
                flushPlain(i)
                out.append("<s>")
                    .append(renderInline(text.substring(i + 2, close), collector, inHeading, inListItem))
                    .append("</s>")
                i = close + 2
                plainStart = i
                continue
            }
        }

        // Italic: *...*
        if (text[i] == '*' && !text.startsWith("**", i)) {
            val close = findClosingSingleStar(text, i + 1)
            if (close != null && close > i + 1 && !text[i + 1].isWhitespace() && !text[close - 1].isWhitespace()) {
                flushPlain(i)
                out.append("<i>")
                    .append(renderInline(text.substring(i + 1, close), collector, inHeading, inListItem))
                    .append("</i>")
                i = close + 1
                plainStart = i
                continue
            }
        }

        // Italic: _..._ only at word boundaries so snake_case stays intact.
        if (text[i] == '_' && (i == 0 || !isWordChar(text[i - 1]))) {
            val close = findClosingBoundaryUnderscore(text, i + 1)
            if (close != null && close > i + 1 && !text[i + 1].isWhitespace() && !text[close - 1].isWhitespace()) {
                flushPlain(i)
                out.append("<i>")
                    .append(renderInline(text.substring(i + 1, close), collector, inHeading, inListItem))
                    .append("</i>")
                i = close + 1
                plainStart = i
                continue
            }
        }

        i++
    }
    flushPlain(text.length)
    return out.toString()
}

private fun renderInlineNoLinks(text: String): String = escapeHtml(text)

private fun renderTextWithLinks(
    text: String,
    collector: ReportLinkCollector,
    inBackticks: Boolean,
    allowContextualLines: Boolean,
    inHeading: Boolean = false,
    inListItem: Boolean = false,
): String {
    val matches = collector.scanSegment(text, inBackticks, allowContextualLines, inHeading, inListItem)
    if (matches.isEmpty()) return escapeHtml(text)
    val out = StringBuilder(text.length + matches.size * 32)
    var pos = 0
    for (m in matches) {
        if (m.start > pos) out.append(escapeHtml(text.substring(pos, m.start)))
        out.append(wrapAnchor(m.link, escapeHtml(text.substring(m.start, m.end))))
        pos = m.end
    }
    if (pos < text.length) out.append(escapeHtml(text.substring(pos)))
    return out.toString()
}

private fun wrapAnchor(link: ReportLink, escapedInnerHtml: String): String {
    val cls = when {
        link.kind == ReportTargetKind.EXTERNAL_URL -> "rl-ext"
        link.kind == ReportTargetKind.DIRECTORY -> "rl-dir"
        link.isAmbiguous -> "rl-ambig"
        else -> "rl"
    }
    return "<a class=\"$cls\" href=\"$LINK_HREF_PREFIX${link.id}\">$escapedInnerHtml</a>"
}

private fun findClosingBackticks(text: String, start: Int, count: Int): Int? {
    var i = start
    while (i < text.length) {
        if (text[i] == '`') {
            var run = 0
            while (i + run < text.length && text[i + run] == '`') run++
            if (run == count) return i
            i += run
        } else {
            i++
        }
    }
    return null
}

private data class ParsedMdLink(val label: String, val target: String, val end: Int)

private fun parseMarkdownLinkAt(text: String, start: Int): ParsedMdLink? {
    // Do not match inside `[ ]` task checkboxes or `[CS-016]` labels without `(...)`.
    var i = start + 1
    var depth = 1
    while (i < text.length && depth > 0) {
        when (text[i]) {
            '\\' -> i += 2
            '[' -> { depth++; i++ }
            ']' -> { depth--; if (depth == 0) break else i++ }
            '`' -> {
                var ticks = 0
                while (i + ticks < text.length && text[i + ticks] == '`') ticks++
                val close = findClosingBackticks(text, i + ticks, ticks)
                i = if (close != null) close + ticks else i + ticks
            }
            else -> i++
        }
    }
    if (depth != 0 || i + 1 >= text.length || text[i + 1] != '(') return null
    val label = text.substring(start + 1, i)
    var j = i + 2
    var parenDepth = 1
    while (j < text.length && parenDepth > 0) {
        when (text[j]) {
            '\\' -> j += 2
            '(' -> { parenDepth++; j++ }
            ')' -> { parenDepth--; if (parenDepth == 0) break else j++ }
            else -> j++
        }
    }
    if (parenDepth != 0) return null
    val rawTarget = text.substring(i + 2, j).trim()
    // Optional Markdown title after whitespace: `(url "title")`
    val target = if (rawTarget.startsWith("<")) {
        rawTarget.substringAfter('<').substringBefore('>')
    } else {
        rawTarget.substringBefore(' ').substringBefore('\t')
    }
    return ParsedMdLink(label, target, j + 1)
}

private fun findClosingDelimiter(text: String, start: Int, delim: String): Int? {
    var i = start
    while (i + delim.length <= text.length) {
        if (text[i] == '`') {
            var ticks = 0
            while (i + ticks < text.length && text[i + ticks] == '`') ticks++
            val close = findClosingBackticks(text, i + ticks, ticks)
            i = if (close != null) close + ticks else i + ticks
            continue
        }
        if (text.startsWith(delim, i)) return i
        i++
    }
    return null
}

private fun findClosingSingleStar(text: String, start: Int): Int? {
    var i = start
    while (i < text.length) {
        if (text[i] == '`') {
            var ticks = 0
            while (i + ticks < text.length && text[i + ticks] == '`') ticks++
            val close = findClosingBackticks(text, i + ticks, ticks)
            i = if (close != null) close + ticks else i + ticks
            continue
        }
        if (text[i] == '*' && !text.startsWith("**", i)) return i
        if (text.startsWith("**", i)) { i += 2; continue }
        i++
    }
    return null
}

private fun findClosingBoundaryUnderscore(text: String, start: Int): Int? {
    var i = start
    while (i < text.length) {
        if (text[i] == '`') {
            var ticks = 0
            while (i + ticks < text.length && text[i + ticks] == '`') ticks++
            val close = findClosingBackticks(text, i + ticks, ticks)
            i = if (close != null) close + ticks else i + ticks
            continue
        }
        if (text[i] == '_' && (i + 1 >= text.length || !isWordChar(text[i + 1]))) return i
        i++
    }
    return null
}

private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

private fun fencePrefix(trimmed: String): String? = when {
    trimmed.startsWith("```") -> "```"
    trimmed.startsWith("~~~") -> "~~~"
    else -> null
}

private fun isTableLine(trimmed: String): Boolean = '|' in trimmed && !trimmed.startsWith('>')

private fun splitTableRow(trimmed: String): List<String> {
    val core = trimmed.removePrefix("|").removeSuffix("|")
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    while (i < core.length) {
        if (core[i] == '\\' && i + 1 < core.length && core[i + 1] == '|') {
            current.append('|')
            i += 2
        } else if (core[i] == '`') {
            var ticks = 0
            while (i + ticks < core.length && core[i + ticks] == '`') ticks++
            val close = findClosingBackticks(core, i + ticks, ticks)
            if (close != null) {
                current.append(core, i, close + ticks)
                i = close + ticks
            } else {
                current.append(core, i, i + ticks)
                i += ticks
            }
        } else if (core[i] == '|') {
            cells += current.toString().trim()
            current.setLength(0)
            i++
        } else {
            current.append(core[i])
            i++
        }
    }
    cells += current.toString().trim()
    return cells
}

private fun parseTableAlignments(separatorLine: String, columns: Int): List<String?> {
    val cells = splitTableRow(separatorLine)
    return (0 until columns).map { idx ->
        val cell = cells.getOrNull(idx)?.trim().orEmpty()
        when {
            cell.startsWith(':') && cell.endsWith(':') -> "center"
            cell.endsWith(':') -> "right"
            cell.startsWith(':') -> "left"
            else -> null
        }
    }
}

private fun normalizeLineEndings(text: String): String =
    text.replace("\r\n", "\n").replace('\r', '\n')

private fun truncateReportText(text: String, maxChars: Int): Pair<String, Boolean> {
    if (text.length <= maxChars) return text to false
    val cut = text.lastIndexOf('\n', maxChars).takeIf { it > 0 } ?: maxChars
    return text.substring(0, cut) to true
}

private fun buildRenderedReport(
    bodyHtml: String,
    links: List<ReportLink>,
    index: ReportProjectIndex,
    colors: ReportThemeColors,
    truncated: Boolean,
    isPlainText: Boolean,
): RenderedReport {
    val counts = LinkedHashMap<String, Int>()
    for (link in links) {
        if (link.kind == ReportTargetKind.FILE && !link.isAmbiguous) {
            val path = link.primaryPath ?: continue
            counts[path] = (counts[path] ?: 0) + 1
        }
    }
    val referenced = counts.entries
        .map { (path, count) -> ReferencedFile(path, count, path in index.reviewScopeFiles) }
        .sortedWith(compareByDescending<ReferencedFile> { it.inReviewScope }.thenByDescending { it.mentions }.thenBy { it.path })
    val ambiguous = links.count { it.isAmbiguous }
    val html = buildString {
        append("<html><head><style>")
        append(reportCss(colors, isPlainText))
        append("</style></head><body>")
        append(bodyHtml)
        append("</body></html>")
    }
    return RenderedReport(html, links, referenced, ambiguous, truncated)
}

private fun reportCss(c: ReportThemeColors, isPlainText: Boolean): String = """
    body { color: ${c.text}; background-color: ${c.background}; margin: 12px 16px; ${if (isPlainText) "" else "line-height: 1.45;"} }
    h1 { font-size: 1.45em; margin: 14px 0 8px 0; border-bottom: 1px solid ${c.border}; padding-bottom: 4px; }
    h2 { font-size: 1.25em; margin: 14px 0 6px 0; border-bottom: 1px solid ${c.border}; padding-bottom: 3px; }
    h3 { font-size: 1.1em; margin: 12px 0 4px 0; }
    h4, h5, h6 { font-size: 1.0em; margin: 10px 0 4px 0; }
    p { margin: 6px 0; }
    hr { border: 0; border-top: 1px solid ${c.border}; margin: 12px 0; }
    ul, ol { margin: 4px 0 6px 20px; padding-left: 8px; }
    li { margin: 3px 0; }
    blockquote { margin: 6px 0; padding: 4px 12px; border-left: 3px solid ${c.border}; color: ${c.muted}; }
    code { background-color: ${c.codeBg}; padding: 1px 4px; font-family: monospace; font-size: 0.94em; }
    pre.code-block, pre.raw-text { background-color: ${c.codeBlockBg}; border: 1px solid ${c.border}; padding: 8px 10px; font-family: monospace; font-size: 0.94em; margin: 8px 0; }
    pre.raw-text { background-color: ${c.background}; border: none; padding: 2px 4px; }
    span.ln { color: ${c.muted}; }
    table { border-collapse: collapse; margin: 8px 0; border: 1px solid ${c.border}; }
    th { background-color: ${c.tableHeaderBg}; border: 1px solid ${c.border}; font-weight: bold; }
    td { border: 1px solid ${c.border}; }
    a.rl { color: ${c.linkFg}; background-color: ${c.linkBg}; text-decoration: underline; }
    a.rl-ambig { color: ${c.linkFg}; background-color: ${c.ambiguousLinkBg}; text-decoration: underline; }
    a.rl-dir { color: ${c.linkFg}; text-decoration: underline; }
    a.rl-ext { color: ${c.linkFg}; text-decoration: underline; }
    .muted { color: ${c.muted}; font-style: italic; }
""".trimIndent()

internal fun escapeHtml(text: String): String {
    val out = StringBuilder(text.length)
    for (c in text) {
        when (c) {
            '&' -> out.append("&amp;")
            '<' -> out.append("&lt;")
            '>' -> out.append("&gt;")
            '"' -> out.append("&quot;")
            '\'' -> out.append("&#39;")
            else -> out.append(c)
        }
    }
    return out.toString()
}

private fun formatNumber(n: Int): String = String.format(java.util.Locale.US, "%,d", n)

private const val LINK_HREF_PREFIX = "report-link:"
private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd")
private val MARKDOWN_HINT = Regex("(?m)^#{1,6}\\s|^```|^\\|.+\\|\\s*$|^\\s*[-*]\\s+\\[[ xX]\\]|\\[[^\\]]+]\\([^)]+\\)")
private val HEADING_LINE = Regex("^(#{1,6})\\s+(.+)$")
private val RULE_LINE = Regex("^([-*_])(?:\\s*\\1){2,}$")
private val TABLE_SEPARATOR_LINE = Regex("^\\|?\\s*:?-{2,}:?\\s*(?:\\|\\s*:?-{2,}:?\\s*)*\\|?$")
private val BLOCKQUOTE_LINE = Regex("^\\s*>")
private val BLOCKQUOTE_PREFIX = Regex("^\\s*>\\s?")
private val UNORDERED_LIST_ITEM = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val ORDERED_LIST_ITEM = Regex("^(\\s*)(\\d{1,4})\\.\\s+(.*)$")
private val TASK_PREFIX = Regex("^\\[([ xX])]\\s+")
private val ESCAPABLE_MD_CHARS = setOf('\\', '`', '*', '_', '{', '}', '[', ']', '(', ')', '#', '+', '-', '.', '!', '|', '~')
