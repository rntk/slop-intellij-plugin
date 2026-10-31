package dev.insight.canvas

import com.intellij.openapi.progress.ProcessCanceledException
import dev.insight.agent.describeChatFailure
import dev.insight.agent.ChatTool
import dev.insight.agent.ToolChatRunner
import dev.insight.llm.Completion
import dev.insight.llm.CODE_AS_DATA
import dev.insight.llm.CompletionFn
import dev.insight.llm.CompletionRequest
import dev.insight.llm.FinishReason
import dev.insight.llm.LlmMessage
import kotlinx.coroutines.CancellationException

/**
 * What a card's summary is written from: a [kind] of card naming [name] (a symbol of [symbolKind] in [container], or
 * null for a selection) at 0-based document line [firstLine] of [path], with its [text]. [language] is the file's
 * language when known; [chain] is how the developer reached the code (see [canvasReadingPath]), or empty.
 */
data class CanvasSummaryInput(
    val kind: CardKind,
    val name: String?,
    val path: String,
    val firstLine: Int,
    val text: String,
    val symbolKind: String? = null,
    val container: String? = null,
    val language: String? = null,
    val chain: String = "",
) {
    val lastLine: Int get() = firstLine + text.count { it == '\n' }

    companion object {
        /** The input for [card], with [chain] its root-first chain (see [CanvasSessionModel.chain]). */
        fun of(card: CanvasCard, rootPath: String, chain: List<CanvasCard>, language: String? = null) = CanvasSummaryInput(
            if (card.wholeFile) CardKind.SYMBOL else card.kind, card.title, card.filePath, card.firstLine, card.text,
            if (card.wholeFile) "FILE" else card.symbol?.kind, if (card.wholeFile) null else card.symbol?.container,
            language, canvasReadingPath(rootPath, chain))
    }
}

/**
 * How the developer reached the last card of [chain] (root-first) from the file they opened, [rootPath]: one numbered
 * step per card, each saying where in the previous step's code it was opened and, when that code is at hand, quoting
 * the line. Empty when the card was opened at its own declaration in the opened file, since the path then says nothing.
 */
fun canvasReadingPath(rootPath: String, chain: List<CanvasCard>): String {
    val current = chain.lastOrNull() ?: return ""
    if (chain.size == 1 && openedInside(current, rootPath)) return ""
    val steps = listOf("$rootPath, the file the developer opened") + chain.mapIndexed { index, card ->
        val parent = chain.getOrNull(index - 1)
        val parentPath = parent?.filePath ?: rootPath
        val what = if (index == chain.lastIndex) "this ${readingKind(card)}" else readingName(card)
        val how = when {
            card.kind == CardKind.SELECTION && !card.wholeFile -> "selected in the code of step ${index + 1}"
            openedInside(card, parentPath) -> "opened at its declaration"
            else -> "opened from a reference at $parentPath:${card.anchor.line + 1}" +
                parent?.let { referenceLine(it, card.anchor.line) }?.let { ": `$it`" }.orEmpty()
        }
        "$what, $how"
    }
    return steps.withIndex().joinToString("\n") { (index, step) -> "${index + 1}. $step" }
}

/** Whether [card] was opened from within its own source in [parentPath], such as by clicking its declaration. */
private fun openedInside(card: CanvasCard, parentPath: String) =
    card.filePath == parentPath && card.anchor.line in card.firstLine..card.lastLine

/** `function`, `class`, `selection` or `file`. */
private fun readingKind(card: CanvasCard) = when {
    card.wholeFile -> "file"
    card.kind == CardKind.SELECTION || card.symbol == null -> "selection"
    else -> card.symbol.kind.lowercase().ifEmpty { "symbol" }
}

/** ``function `run` in App (src/App.kt:10-12)``, `selection (src/App.kt:11)` or `file src/App.kt`. */
private fun readingName(card: CanvasCard): String {
    val symbol = card.symbol
    return when {
        card.wholeFile -> "file ${card.filePath}"
        card.kind == CardKind.SELECTION || symbol == null -> "selection (${card.location})"
        else -> "${readingKind(card)} `${symbol.name}`" + symbol.container.takeIf { it.isNotBlank() }?.let { " in $it" }.orEmpty() +
            " (${card.location})"
    }
}

/** The trimmed 0-based document [line] of [card]'s text, at most 160 characters; null when blank or not in the text. */
private fun referenceLine(card: CanvasCard, line: Int): String? =
    card.text.split('\n').getOrNull(line - card.firstLine)?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { if (it.length > 160) it.take(159) + "…" else it }

/** A summary could not be written; [message] is meant for the card. */
class CanvasSummaryException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Characters of a card's text sent for its summary; the rest is left out with a notice. */
const val CANVAS_SUMMARY_TEXT_LIMIT = 16_000

/** The system and user messages asking for [input]'s summary. */
fun canvasSummaryMessages(input: CanvasSummaryInput): List<LlmMessage> {
    val (text, truncated) = boundCardText(input.text, CANVAS_SUMMARY_TEXT_LIMIT)
    val numbered = text.split('\n').withIndex().joinToString("\n") { (index, line) -> "${input.firstLine + index + 1}: $line" }
    val what = when (input.kind) {
        CardKind.SELECTION -> "Explain what the selected code does in the context of its file: its purpose, what it reads and changes, " +
            "and how it fits the surrounding code."
        CardKind.SYMBOL -> "Explain what this ${input.symbolKind?.lowercase() ?: "symbol"} does: its purpose, inputs, effects and result."
    }
    val system = "Summarize this code for a developer. $what Be very brief, usually 1-3 sentences, without a title, " +
        "headings or lists; inline code names are fine. When the message shows how the developer reached the code, " +
        "describe the code in general, then add what it does for that caller if that clarifies its role. " +
        "Use read_file and search_text when more context is needed. Base the summary on the source; mention uncertainty " +
        "rather than inventing behavior. $CODE_AS_DATA"
    val user = buildString {
        append("Kind: ${if (input.kind == CardKind.SELECTION) "selection" else input.symbolKind?.lowercase() ?: "symbol"}\n")
        input.name?.let { append("Name: $it\n") }
        input.container?.takeIf { it.isNotBlank() }?.let { append("Declared in: $it\n") }
        append("Location: ${input.path}:${input.firstLine + 1}-${input.lastLine + 1}\n")
        input.language?.let { append("Language: $it\n") }
        if (input.chain.isNotBlank()) append("\nHow the developer reached this code (one path to it; it may have other callers):\n${input.chain}\n")
        append("\nCode:\n$numbered\n")
        if (truncated) append("[The code continues beyond line ${input.firstLine + text.count { it == '\n' } + 1}; use read_file to read more.]\n")
    }
    return listOf(LlmMessage.system(system), LlmMessage.user(user))
}

/** Writes card summaries with an injected completion port; no IDE or UI dependencies. */
class CanvasSummarizer(private val complete: CompletionFn, private val maxTokens: Int? = 600, private val tools: List<ChatTool> = emptyList()) {
    /**
     * The summary of [input], trimmed. [onProgress] reports what is being summarized. Cancellation propagates; any other
     * failure, an empty answer or a cut-off one becomes a [CanvasSummaryException].
     */
    suspend fun summarize(input: CanvasSummaryInput, onProgress: (String) -> Unit = {}): String {
        if (input.text.isBlank()) throw CanvasSummaryException("There is no code to summarize.")
        onProgress("Summarizing ${input.name ?: "selection"}…")
        val completion = try {
            if (tools.isEmpty()) complete(CompletionRequest(canvasSummaryMessages(input), temperature = 0.2, maxTokens = maxTokens))
            else {
                val result = ToolChatRunner({ complete(it.copy(temperature = 0.2, maxTokens = maxTokens)) }, tools, maxToolRounds = 8)
                    .run(canvasSummaryMessages(input)) { onProgress("Reading context: ${it.name}…") }
                Completion(result.reply, finishReason = result.finishReason)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            throw CanvasSummaryException("The summary failed: ${describeChatFailure(e)}", e)
        }
        val text = completion.content.trim()
        if (completion.finishReason == FinishReason.TRUNCATED) throw CanvasSummaryException("The summary was cut off; try again.")
        if (completion.finishReason == FinishReason.CONTENT_FILTER) throw CanvasSummaryException("The model declined to summarize this code.")
        if (text.isEmpty()) throw CanvasSummaryException("The model returned an empty summary.")
        return text
    }
}
