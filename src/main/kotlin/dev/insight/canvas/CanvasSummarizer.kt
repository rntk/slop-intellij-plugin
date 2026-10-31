package dev.insight.canvas

import com.intellij.openapi.progress.ProcessCanceledException
import dev.insight.agent.describeChatFailure
import dev.insight.llm.CODE_AS_DATA
import dev.insight.llm.CompletionFn
import dev.insight.llm.CompletionRequest
import dev.insight.llm.FinishReason
import dev.insight.llm.LlmMessage
import kotlinx.coroutines.CancellationException

/**
 * What a card's summary is written from: a [kind] of card naming [name] (a symbol of [symbolKind] in [container], or
 * null for a selection) at 0-based document line [firstLine] of [path], with its [text]. [language] is the file's
 * language when known; [chain] is a short hint of how the reader got here, such as `Main.kt → run → selection`.
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
            language, (listOf(rootPath.substringAfterLast('/')) + chain.map { if (it.wholeFile || it.kind == CardKind.SYMBOL) it.title else "selection" })
                .joinToString(" → "))
    }
}

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
    val system = "Summarize code for a developer reading it on a canvas. $what Write 2-4 plain sentences without a title, " +
        "headings or lists; inline code names are fine. Base the summary only on the code shown; mention uncertainty rather than " +
        "inventing behavior. $CODE_AS_DATA"
    val user = buildString {
        append("Kind: ${if (input.kind == CardKind.SELECTION) "selection" else input.symbolKind?.lowercase() ?: "symbol"}\n")
        input.name?.let { append("Name: $it\n") }
        input.container?.takeIf { it.isNotBlank() }?.let { append("Declared in: $it\n") }
        append("Location: ${input.path}:${input.firstLine + 1}-${input.lastLine + 1}\n")
        input.language?.let { append("Language: $it\n") }
        if (input.chain.isNotBlank()) append("Reached from: ${input.chain}\n")
        append("\nCode:\n$numbered\n")
        if (truncated) append("[The code continues beyond line ${input.firstLine + text.count { it == '\n' } + 1}; summarize what is shown.]\n")
    }
    return listOf(LlmMessage.system(system), LlmMessage.user(user))
}

/** Writes card summaries with an injected completion port; no IDE or UI dependencies. */
class CanvasSummarizer(private val complete: CompletionFn, private val maxTokens: Int? = 600) {
    /**
     * The summary of [input], trimmed. [onProgress] reports what is being summarized. Cancellation propagates; any other
     * failure, an empty answer or a cut-off one becomes a [CanvasSummaryException].
     */
    suspend fun summarize(input: CanvasSummaryInput, onProgress: (String) -> Unit = {}): String {
        if (input.text.isBlank()) throw CanvasSummaryException("There is no code to summarize.")
        onProgress("Summarizing ${input.name ?: "selection"}…")
        val completion = try {
            complete(CompletionRequest(canvasSummaryMessages(input), temperature = 0.2, maxTokens = maxTokens))
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
