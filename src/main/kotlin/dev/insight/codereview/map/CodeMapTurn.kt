package dev.insight.codereview.map

import com.intellij.openapi.application.EDT
import dev.insight.agent.ToolCallEvent
import dev.insight.agent.ToolInputException
import dev.insight.codereview.CODE_SUMMARY_OPTIONS
import dev.insight.codereview.CodeReviewState
import dev.insight.codereview.CodeSnapshot
import dev.insight.codereview.estimateSummaryRequests
import dev.insight.core.withCountProgress
import dev.insight.decisions.DecisionFn
import dev.insight.llm.CompletionFn
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummarizer
import dev.insight.review.summariesFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The /code-map turn of a code review tab, as an adapter around [CodeMapWorkflow] and the code-map summarizer. It builds
 * the map when the revision has none, or when its summaries are complete (a re-run regenerates), stores it so the card
 * shows the flows before any summary exists, then writes the missing summaries. The state is read and written only on
 * the EDT, and only while the revision is still the one the turn captured.
 */
internal class CodeMapTurn(
    private val state: CodeReviewState,
    /** Builds the map of the snapshot from code; a read action in production, a fake in tests. */
    private val build: suspend (CodeSnapshot) -> CodeMap,
    private val complete: CompletionFn,
    private val decide: DecisionFn?,
    private val maxContextChars: Int,
    /** Asks before a summary run of this many requests; false stops after the map is built. */
    private val confirm: suspend (Int) -> Boolean,
    /** Shows the card's new state (EDT). */
    private val changed: () -> Unit,
    private val isClosed: () -> Boolean,
) {
    suspend fun run(snapshot: CodeSnapshot, question: String, onToolCall: suspend (ToolCallEvent) -> Unit): String {
        val focus = summariesFocus(question)
        if (snapshot.files.isEmpty()) return codeMapReply(0, null, EMPTY_SUMMARIES, 0)
        val (revision, existing, done) = withContext(Dispatchers.EDT) {
            if (isClosed()) throw ToolInputException("This review tab is closed")
            if (state.snapshot !== snapshot) throw ToolInputException(dev.insight.codereview.CODE_CHANGED)
            state.check(state.revision)
            state.showCodeMap()
            changed()
            Triple(state.revision, state.codeMap, state.codeMapDone)
        }
        var built: CodeMapRun? = null
        val map = if (existing != null && !done) existing else {
            val run = withCountProgress("Building the code map…", "judgments and titles") { onProgress ->
                CodeMapWorkflow(complete, decide).run(build(snapshot), snapshot.files, focus, onProgress)
            }
            onEdt(revision) {
                if (existing != null) state.clearCodeMapSummaries()
                state.setCodeMap(revision, run.map)
                changed()
            }
            built = run
            run.map
        }
        val evidence = CodeMapSummaryEvidence(map, snapshot)
        val start = onEdt(revision) { state.codeMapSummaries }
        val requests = estimateSummaryRequests(start, CODE_SUMMARY_OPTIONS, evidence::size, restartComplete = false)
        if (requests > 0 && !confirm(requests))
            return codeMapReply(snapshot.files.size, map, start, 0, built, "Summaries were not written: they need about $requests model requests.")
        val run = withCountProgress("Summarizing the code map…", "summaries") { onProgress ->
            ReviewSummarizer(complete, null, CODE_SUMMARY_OPTIONS, evidence, maxContextChars).run(start, focus, { entries ->
                onEdt(revision) { state.summarizeCodeMap(revision, entries).also { changed() } }
            }, onToolCall, onProgress)
        }
        return codeMapReply(snapshot.files.size, map, run.summaries, run.requests, built, run.error)
    }

    private suspend fun <T> onEdt(revision: Int, action: () -> T): T = withContext(Dispatchers.EDT) {
        if (isClosed()) throw ToolInputException("This review tab is closed")
        state.check(revision)
        action()
    }

    private companion object {
        val EMPTY_SUMMARIES = ReviewSummaries(buildCodeMapTree(CodeMap(CodeGraph(emptyList(), emptyList()), emptyList()), emptyList()))
    }
}

/**
 * The chat answer of /code-map: the whole map's summary once every summary is written; otherwise what the map holds,
 * what the judgments did, why a step stopped, and that running /code-map again continues.
 */
internal fun codeMapReply(fileCount: Int, map: CodeMap?, summaries: ReviewSummaries, requests: Int, build: CodeMapRun? = null, error: String? = null): String {
    if (fileCount == 0) return "There are no files in scope to map."
    if (map == null) return "No code map was built.${error?.let { " $it" }.orEmpty()} Run /code-map again to retry."
    val units = map.graph.units.size
    if (units == 0) return "No classes or files to map."
    val flows = map.chains.count { !it.foundation }
    val shared = map.chains.count { it.foundation }
    val shape = "${plural(flows, "flow")} · $shared shared · ${map.standalone.size} standalone · ${plural(units, "unit")}" +
        if (map.analyzed < map.files) " · ${map.analyzed} of ${map.files} files analyzed" else ""
    val allRequests = requests + (build?.requests ?: 0)
    val root = summaries.text(summaries.tree.root.id)
    val problems = listOfNotNull(build?.error, error)
    if (summaries.complete && root != null)
        return "$root\n\n[$shape · ${summaries.total} summaries · ${plural(allRequests, "request")}]" + problems.joinToString("") { "\n\n$it" }
    return buildString {
        append("Mapped ${plural(units, "unit")} into ${plural(flows, "flow")}, ${plural(shared, "shared foundation")} and ")
        append("${plural(map.standalone.size, "standalone unit")}.")
        if (map.analyzed < map.files) append(" Only the first ${map.analyzed} of ${map.files} files were analyzed.")
        build?.takeIf { it.merged + it.attached > 0 }?.let { append(" Judgments merged ${it.merged} and attached ${it.attached}.") }
        append(" Summarized ${summaries.count} of ${summaries.total} entries with ${plural(allRequests, "request")}.")
        problems.forEach { append("\n\n").append(it) }
        append("\n\n[Run /code-map again to write the missing summaries.]")
    }
}

private fun plural(value: Int, one: String) = if (value == 1) "1 $one" else "$value ${one}s"
