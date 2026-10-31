package dev.insight.canvas

import dev.insight.agent.ChatMessage
import dev.insight.agent.ChatRole
import dev.insight.graph.CallGraphNode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Why a symbol card has no live source: its target lies outside the project, or the IDE could not resolve it. */
enum class CanvasTargetNote(val label: String, val notice: String) {
    EXTERNAL("external", "External symbol: its source is outside the project, so only what the graph knows is shown."),
    UNRESOLVED("unresolved", "Unresolved symbol: the IDE could not find its declaration, so only its name is shown."),
}

/** The note of a graph [node] a card cannot show source for, or null when its declaration is in the project. */
fun canvasTargetNote(node: CallGraphNode): CanvasTargetNote? = when {
    node.unresolved -> CanvasTargetNote.UNRESOLVED
    !node.inProject || node.filePath == null -> CanvasTargetNote.EXTERNAL
    else -> null
}

/** Shown when a restored symbol's current source differs from the saved one. */
const val CANVAS_SOURCE_CHANGED = "Source changed since this card was saved; showing the current code."

/** Shown when a restored symbol cannot be found again. */
const val CANVAS_SYMBOL_MISSING = "Symbol not found in the current code; showing the saved source without links."

/** Shown when a restored selection's text no longer matches its parent. */
const val CANVAS_SELECTION_CHANGED = "The code around this selection changed since it was saved; its links are off."

/** `Function`, `Class`, … for a symbol card, `Selection` otherwise. */
fun canvasKindLabel(card: CanvasCard): String = when {
    card.kind == CardKind.SELECTION || card.symbol == null -> "Selection"
    else -> card.symbol.kind.lowercase().replaceFirstChar { it.titlecase(Locale.ROOT) }.ifEmpty { "Symbol" }
}

/** The card's second header line: `function in Store · src/Store.kt:10-20`, or `external` without a place. */
fun canvasCardMeta(card: CanvasCard): String {
    val symbol = card.symbol
    val what = if (card.kind == CardKind.SELECTION || symbol == null) "selection"
        else symbol.kind.lowercase().ifEmpty { "symbol" } + symbol.container.takeIf { it.isNotBlank() }?.let { " in $it" }.orEmpty()
    val where = if (card.filePath.isEmpty()) "no source" else card.location
    return "$what · $where"
}

/** The header of a card's source section: `Source · 12 lines`, `Selected text · 3 lines`, with `(cut)` when truncated. */
fun canvasSourceTitle(card: CanvasCard): String {
    val lines = if (card.text.isEmpty()) 0 else card.text.count { it == '\n' } + 1
    val label = if (card.kind == CardKind.SELECTION) "Selected text" else "Source"
    return "$label · $lines ${if (lines == 1) "line" else "lines"}" + if (card.textTruncated) " (cut)" else ""
}

/**
 * One entry of a choice among a link's targets: `save(Int) · function in Store · src/Store.kt:12`, with `external` or
 * `unresolved` when the card will have no source. [path] gives the node's project-relative path.
 */
fun canvasTargetLabel(node: CallGraphNode, path: (String?) -> String?): String {
    val kind = node.kind.name.lowercase() + node.container.takeIf { it.isNotBlank() }?.let { " in $it" }.orEmpty()
    val note = canvasTargetNote(node)
    val place = when {
        note != null -> note.label
        else -> path(node.filePath)?.let { file -> nodeLine(node)?.let { "$file:${it + 1}" } ?: file } ?: ""
    }
    return listOf(node.name, kind, place).filter { it.isNotBlank() }.joinToString(" · ")
}

/** A saved session as listed in the Sessions menu: `Main.kt · 3 cards · Oct 31 14:02`. */
fun canvasSessionLabel(entry: CanvasSessionEntry, locale: Locale = Locale.getDefault()): String {
    val cards = if (entry.cards == 1) "1 card" else "${entry.cards} cards"
    val time = SimpleDateFormat("MMM d HH:mm", locale).format(Date(entry.updated))
    return "${entry.title} · $cards · $time"
}

/** How a line of a card's chat is shown. */
enum class CanvasChatLineKind { USER, ASSISTANT, TOOL, NOTICE }

/** One line of a card's compact chat transcript. */
data class CanvasChatLine(val kind: CanvasChatLineKind, val label: String, val text: String)

/** The longest tool line in a card's transcript; tool results are never shown there. */
const val CANVAS_TOOL_LINE = 100

/**
 * A card's compact transcript of [messages]: questions and answers in full, notices as hints, each tool call as one
 * short line. Blank messages are left out.
 */
fun canvasTranscript(messages: List<ChatMessage>): List<CanvasChatLine> = messages.filter { it.text.isNotBlank() }.map { message ->
    when (message.role) {
        ChatRole.USER -> CanvasChatLine(CanvasChatLineKind.USER, "You", message.text.trim())
        ChatRole.ASSISTANT -> CanvasChatLine(CanvasChatLineKind.ASSISTANT, "Insight", message.text.trim())
        ChatRole.NOTICE -> CanvasChatLine(CanvasChatLineKind.NOTICE, "", message.text.trim())
        ChatRole.TOOL -> {
            val line = message.text.lineSequence().first().trim()
            CanvasChatLine(CanvasChatLineKind.TOOL, "Tool", if (line.length > CANVAS_TOOL_LINE) line.take(CANVAS_TOOL_LINE - 1) + "…" else line)
        }
    }
}

/** The text of the canvas's help tooltip, as HTML. */
val canvasHelpHtml = """
    <html><div style='width:420px'>
    The file is shown on the left; underlined names lead into the code graph. Click one to open its card beside it, or
    select any text and choose <b>Open card</b> (also in the right-click menu). Cards open further cards from their own
    source, so a chain can follow the code as far as you like; an arrow links each card to where it was opened.<br><br>
    Each card has its own chat, whose context is the chain from the file down to the card. Every part of a card folds
    with its ▾ toggle; <b>Close</b> also closes the cards opened from it; <b>Source</b> opens the real file.<br><br>
    Drag empty space, or middle-drag, to move; Ctrl/⌘ + wheel zooms. The session is saved as you work: <b>Sessions</b>
    opens the saved sessions side panel, with fuzzy search by file name. <b>Session ▾</b> starts a new one, switches
    to another session of this file, or deletes this one. Reopening resolves symbols
    again; a card whose symbol is gone keeps its saved text and chat.
    </div></html>
""".trimIndent().replace("\n", " ")
