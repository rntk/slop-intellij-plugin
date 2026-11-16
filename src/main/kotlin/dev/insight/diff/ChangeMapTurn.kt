package dev.insight.diff

import com.intellij.openapi.application.EDT
import dev.insight.agent.ChatTool
import dev.insight.agent.ToolCallEvent
import dev.insight.agent.ToolInputException
import dev.insight.core.withCountProgress
import dev.insight.decisions.DecisionFn
import dev.insight.diff.atoms.ChangeMap
import dev.insight.diff.atoms.ChangeMapRun
import dev.insight.diff.atoms.ChangeMapWorkflow
import dev.insight.diff.atoms.changeMapSummarizer
import dev.insight.llm.CompletionFn
import dev.insight.review.summariesFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import dev.insight.settings.ChatSettings

/**
 * The /change-map turn of a diff review tab, as an adapter around [ChangeMapWorkflow] and the change-map summarizer.
 * It builds the map when the snapshot has none, or when its summaries are complete (a re-run regenerates), stores it so the
 * card shows the structure before any summary exists, then writes the missing summaries. The state is read and written only
 * on the EDT, and only while the snapshot is still the one the turn captured: a moved revision fails the turn.
 */
internal class ChangeMapTurn(
    private val state: DiffReviewState,
    /** Builds the map of the files from code; a read action in production, a fake in tests. */
    private val build: suspend (List<ReviewFile>) -> ChangeMap,
    private val complete: CompletionFn,
    private val decide: DecisionFn?,
    private val readTool: ChatTool,
    private val maxContextChars: Int,
    /** Shows the card's new state (EDT). */
    private val changed: () -> Unit,
    private val isClosed: () -> Boolean,
) {
    suspend fun run(snapshot: ReviewSnapshot, question: String, onToolCall: suspend (ToolCallEvent) -> Unit): String {
        val files = snapshot.files
        val focus = summariesFocus(question)
        if (files.isEmpty()) return changeMapReply(0, null, EMPTY_MAP_SUMMARIES, 0)
        val (revision, existing, done) = withContext(Dispatchers.EDT) {
            if (isClosed()) throw ToolInputException("This review tab is closed")
            // Every update replaces the snapshot, so the same instance means the revision the turn captured.
            if (state.snapshot !== snapshot) throw ToolInputException(DIFF_CHANGED)
            state.check(state.revision)
            state.showChangeMap()
            changed()
            Triple(state.revision, state.changeMap, state.changeMapDone)
        }
        var built: ChangeMapRun? = null
        val map = if (existing != null && !done) existing else {
            val run = withCountProgress("Building the change map…", "judgments and titles") { onProgress ->
                ChangeMapWorkflow(complete, decide).run(build(files), files, focus, onProgress)
            }
            onEdt(revision) {
                if (existing != null) state.clearChangeMapSummaries()
                state.setChangeMap(revision, files, run.map)
                changed()
            }
            built = run
            run.map
        }
        val start = onEdt(revision) { state.changeMapSummaries }
        val run = withCountProgress("Summarizing the change map…", "summaries") { onProgress ->
            changeMapSummarizer(complete, readTool, map, files, maxContextChars = maxContextChars).run(start, focus, { entries ->
                onEdt(revision) { state.summarizeChangeMap(revision, entries).also { changed() } }
            }, onToolCall, onProgress)
        }
        return changeMapReply(files.size, map, run.summaries, run.requests, built, run.error)
    }

    /** Runs [action] on the EDT while the tab is open and still on [revision]; a refreshed diff fails with the state's message. */
    private suspend fun <T> onEdt(revision: Int, action: () -> T): T = withContext(Dispatchers.EDT) {
        if (isClosed()) throw ToolInputException("This review tab is closed")
        state.check(revision)
        action()
    }
}

/** The configured Decisions API, or null when it cannot be set up, so the map keeps the clusters code built. Reads the password safe on IO. */
internal suspend fun configuredDecisions(): DecisionFn? = withContext(Dispatchers.IO) {
    try {
        ChatSettings.getInstance().createDecisionsClient().let { client -> client::evaluate }
    } catch (e: Exception) {
        if (e is ControlFlowException || e is CancellationException) throw e
        logger<ChangeMapTurn>().info("Change map judgments are off: ${e.message}")
        null
    }
}
