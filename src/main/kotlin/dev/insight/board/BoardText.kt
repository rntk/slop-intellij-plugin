package dev.insight.board

/**
 * The board as the model reads it: a compact plain-text outline in layout order, with ids, kinds, titles, anchors and
 * the start of each text. Code blocks show their anchor and line count, not their source; the model reads sources with
 * its source tools. Used for the chat's context snapshot and the board_overview tool.
 */
fun describeBoard(board: Board, maxTextChars: Int = 160): String = buildString {
    append(board.title)
    board.origin?.let { append("\nOrigin: ").append(it.kind).append(" · ").append(it.title) }
    if (board.blocks.isEmpty()) append("\n(empty board)")
    for (block in board.topLevel) {
        append('\n').append(describeBlock(board, block, maxTextChars))
        if (block is GroupBlock) for (child in board.children(block)) {
            append("\n  ").append(describeBlock(board, child, maxTextChars))
        }
    }
    if (board.edges.isNotEmpty()) {
        append("\nEdges:")
        for (edge in board.edges) {
            append("\n  ").append(edge.from).append(" -> ").append(edge.to)
            edge.label?.let { append(" (").append(it).append(')') }
        }
    }
}

/** One line about [block]: `[id] kind · title · anchor`, with the start of its text. */
fun describeBlock(board: Board, block: Block, maxTextChars: Int = 160): String {
    val kind = when (block) {
        is HeadingBlock -> "heading"
        is TextBlock -> "text"
        is CodeBlock -> "code"
        is SymbolBlock -> "symbol"
        is ChainBlock -> "chain"
        is StepsBlock -> "steps"
        is TableBlock -> "table"
        is GroupBlock -> "group"
    }
    val parts = mutableListOf<String?>(kind, block.width.name.lowercase())
    fun excerpt(text: String?) = text?.let { t ->
        val flat = t.trim().replace(Regex("\\s+"), " ")
        if (flat.isEmpty()) null else "\"" + (if (flat.length > maxTextChars) flat.take(maxTextChars).trimEnd() + "…" else flat) + "\""
    }
    when (block) {
        is HeadingBlock -> {
            parts += block.text.trim().replace(Regex("\\s+"), " ")
            parts += "h${block.level}"
        }
        is TextBlock -> {
            parts += block.title
            parts += block.tone?.name?.lowercase()
            parts += excerpt(block.text)
        }
        is CodeBlock -> {
            parts += block.title
            parts += block.anchor.toString()
            parts += "${block.anchor.lineCount} lines"
            if (block.kind == CodeKind.DIFF) parts += "diff"
            if (block.annotations.isNotEmpty()) parts += "${block.annotations.size} annotations"
        }
        is SymbolBlock -> {
            parts += block.title
            parts += block.meta
            parts += block.anchor?.toString()
            parts += excerpt(block.summary)
        }
        is ChainBlock -> {
            parts += block.title
            parts += block.steps.joinToString(" -> ") { it.title }.ifEmpty { "no steps" }
            if (!block.complete) parts += "cut off"
        }
        is StepsBlock -> {
            parts += block.title
            parts += "${block.steps.size} steps"
            parts += excerpt(block.steps.firstOrNull()?.text)
        }
        is TableBlock -> {
            parts += block.title
            parts += block.columns.joinToString(", ")
            parts += "${block.rows.size} rows"
        }
        is GroupBlock -> {
            parts += block.title
            parts += block.tone?.name?.lowercase()
            parts += "children: " + block.children.filter { board.block(it) != null }.joinToString(", ").ifEmpty { "none" }
        }
    }
    parts += if (block.interpretation) "(interpretation)" else "(evidence)"
    return "[${block.id}] " + parts.filter { !it.isNullOrBlank() }.joinToString(" · ")
}

/** The board as Markdown for export: headings, prose, fenced code with anchors, tables and numbered steps. */
fun boardMarkdown(board: Board): String {
    val sections = mutableListOf("# ${board.title.trim()}")
    for (block in board.topLevel) markdownBlock(board, block, sections)
    return sections.joinToString("\n\n") + "\n"
}

private fun markdownBlock(board: Board, block: Block, out: MutableList<String>) {
    when (block) {
        is HeadingBlock -> out += "#".repeat((block.level.coerceIn(1, 5)) + 1) + " " + oneLine(block.text)
        is TextBlock -> {
            val tone = block.tone
            if (tone == null) {
                block.title?.takeIf { it.isNotBlank() }?.let { out += "**${oneLine(it)}**" }
                out += block.text.trim()
            } else {
                val lines = (block.title?.takeIf { it.isNotBlank() }?.let { "**${oneLine(it)}**\n\n" }.orEmpty() + block.text.trim()).lines()
                out += lines.mapIndexed { i, line ->
                    when {
                        i == 0 -> "> **${tone.name}:** $line"
                        line.isEmpty() -> ">"
                        else -> "> $line"
                    }
                }.joinToString("\n")
            }
        }
        is CodeBlock -> {
            val label = block.title?.takeIf { it.isNotBlank() }?.let { "**${oneLine(it)}** · " }.orEmpty()
            out += "$label`${block.anchor}`"
            val fence = "`".repeat(maxOf(3, longestBacktickRun(block.text) + 1))
            val lang = if (block.kind == CodeKind.DIFF) "diff" else languageOf(block.anchor.path)
            out += "$fence$lang\n${block.text.trimEnd('\n')}\n$fence"
            if (block.annotations.isNotEmpty()) out += block.annotations.joinToString("\n") { a ->
                "- line ${a.line}" + (if (a.severity == Severity.INFO) "" else " (${a.severity.name.lowercase()})") + ": ${oneLine(a.text)}"
            }
        }
        is SymbolBlock -> {
            out += "- **${oneLine(block.title)}**" + (block.meta?.let { " (${oneLine(it)})" } ?: "") +
                (block.anchor?.let { " — `$it`" } ?: "") + (block.summary?.let { "\n  ${oneLine(it)}" } ?: "")
        }
        is ChainBlock -> {
            block.title?.takeIf { it.isNotBlank() }?.let { out += "**${oneLine(it)}**" }
            val lines = block.steps.map { s ->
                "- **${oneLine(s.title)}**" + (s.anchor?.let { " — `$it`" } ?: "") + (s.summary?.let { ": ${oneLine(it)}" } ?: "")
            }.toMutableList()
            if (!block.complete) lines += "- … (path cut off)"
            if (lines.isNotEmpty()) out += lines.joinToString("\n")
        }
        is StepsBlock -> {
            block.title?.takeIf { it.isNotBlank() }?.let { out += "**${oneLine(it)}**" }
            if (block.steps.isNotEmpty()) out += block.steps.mapIndexed { i, s ->
                "${i + 1}. ${oneLine(s.text)}" + (s.anchor?.let { " (`$it`)" } ?: "")
            }.joinToString("\n")
        }
        is TableBlock -> {
            block.title?.takeIf { it.isNotBlank() }?.let { out += "**${oneLine(it)}**" }
            fun row(cells: List<String>) = cells.joinToString(" | ", "| ", " |")
            out += (listOf(row(block.columns.map(::cellText)), row(block.columns.map { "---" })) +
                block.rows.map { r -> row(r.map { c -> cellText(c.text) + (c.anchor?.let { " (`$it`)" } ?: "") }) }).joinToString("\n")
        }
        is GroupBlock -> {
            out += "## " + (block.title?.takeIf { it.isNotBlank() }?.let(::oneLine) ?: "Group")
            for (child in board.children(block)) if (child !is GroupBlock) markdownBlock(board, child, out)
        }
    }
}

private fun oneLine(text: String) = text.trim().replace(Regex("\\s+"), " ")

private fun cellText(text: String) = oneLine(text).replace("|", "\\|")

private fun longestBacktickRun(text: String): Int = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0

private fun languageOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "kt", "kts" -> "kotlin"
    "java" -> "java"
    "py" -> "python"
    "go" -> "go"
    "js", "jsx", "mjs" -> "javascript"
    "ts", "tsx" -> "typescript"
    "xml" -> "xml"
    "json" -> "json"
    "md" -> "markdown"
    "sh" -> "bash"
    else -> ""
}
