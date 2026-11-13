package dev.insight.board

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The words of the board editor and its entry points: chat drafts about a block, the status line and the saved boards'
 * menu labels. Plain functions so they are tested without Swing.
 */

/** Where the editor's board stands against its saved file. */
enum class BoardSaveState { NEW, UNSAVED, SAVING, SAVED, FAILED, NO_STORE }

/** The block's kind and what tells it apart, as `code · src/A.kt:3-9` or `text · Why it retries`. */
fun boardBlockLabel(block: Block, max: Int = 48): String {
    val kind = when (block) {
        is HeadingBlock -> "heading"
        is TextBlock -> block.tone?.name?.lowercase() ?: "text"
        is CodeBlock -> if (block.kind == CodeKind.DIFF) "diff" else "code"
        is SymbolBlock -> "symbol"
        is ChainBlock -> "chain"
        is StepsBlock -> "steps"
        is TableBlock -> "table"
        is GroupBlock -> "group"
    }
    val detail = when (block) {
        is HeadingBlock -> block.text
        is TextBlock -> block.title ?: block.text
        is CodeBlock -> block.title ?: block.anchor.toString()
        is SymbolBlock -> block.title
        is ChainBlock -> block.title ?: block.steps.joinToString(" → ") { it.title }
        is StepsBlock -> block.title
        is TableBlock -> block.title ?: block.columns.joinToString(", ")
        is GroupBlock -> block.title
    }?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
    return if (detail == null) kind else "$kind · ${clip(detail, max)}"
}

/** The chat draft a block's Ask action leaves in the input: `About block c1 (code · src/A.kt:3-9): `. */
fun boardAskDraft(block: Block): String = "About block ${block.id} (${boardBlockLabel(block)}): "

/** The editor's status line: block count and save state. */
fun boardStatusText(blocks: Int, state: BoardSaveState): String {
    val count = if (blocks == 1) "1 block" else "$blocks blocks"
    val saved = when (state) {
        BoardSaveState.NEW -> "not saved yet"
        BoardSaveState.UNSAVED -> "unsaved changes"
        BoardSaveState.SAVING -> "saving…"
        BoardSaveState.SAVED -> "saved"
        BoardSaveState.FAILED -> "could not save; see the IDE log"
        BoardSaveState.NO_STORE -> "not saved: the project has no directory"
    }
    return "$count · $saved"
}

/** A saved board in the entry point's menu: its title, block count and when it was last changed. */
fun boardEntryLabel(entry: BoardEntry, zone: ZoneId = ZoneId.systemDefault()): String {
    val updated = ENTRY_TIME.withZone(zone).format(Instant.ofEpochMilli(entry.updated))
    val blocks = if (entry.blocks == 1) "1 block" else "${entry.blocks} blocks"
    return "${clip(entry.title, 60)} · $blocks · $updated"
}

private val ENTRY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

private fun clip(text: String, max: Int) = if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"
