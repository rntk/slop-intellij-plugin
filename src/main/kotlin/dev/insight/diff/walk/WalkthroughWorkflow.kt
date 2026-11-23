package dev.insight.diff.walk

import com.intellij.openapi.diagnostic.logger
import dev.insight.agent.CallAllowance
import dev.insight.agent.ChatTool
import dev.insight.agent.LimitedTool
import dev.insight.agent.ToolCallEvent
import dev.insight.agent.ToolChatRunner
import dev.insight.agent.ToolRegistry
import dev.insight.decisions.DecisionFn
import dev.insight.diff.ReviewFile
import dev.insight.diff.ReviewSnapshot
import dev.insight.llm.CODE_AS_DATA
import dev.insight.llm.Completion
import dev.insight.llm.CompletionFn
import dev.insight.llm.CompletionRequest
import dev.insight.llm.LlmMessage
import dev.insight.prompts.PromptTemplates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

private val LOG = logger<WalkthroughWorkflow>()

/**
 * Limits of one walkthrough run. A turn usually handles several hunks (a rename's sites are covered by one step), so the
 * defaults fit diffs of a few hundred hunks; anything left when a limit is reached is shown as not linked.
 */
data class WalkOptions(
    /** Seed turns, each an independent conversation about one seed. */
    val maxSeedTurns: Int = 80,
    /** Tool rounds in one seed turn before the model must answer. */
    val maxToolRoundsPerTurn: Int = 24,
    /** Completion requests in the whole run, subagents included; a hard stop. Decisions requests are counted apart. */
    val maxRequests: Int = 800,
    /** Nudges when the model answers while a seed hunk is still pending. */
    val maxFollowUps: Int = 2,
    /** Most hunks of one file a seed turn starts from; only hunks of up to [smallHunkLines] changed lines are batched. */
    val seedBatchSize: Int = 3,
    val smallHunkLines: Int = 12,
    /** The request window of a seed turn, in characters. */
    val contextChars: Int = 96_000,
    /** One tool result, in characters. */
    val toolResultChars: Int = 12_000,
    /** Seed rows given in the turn's message; the model reads on with read_hunks. */
    val seedPatchChars: Int = 8_000,
    /** The walkthrough status given in the turn's message. */
    val summaryChars: Int = 4_000,
    /** ask_subagent calls per seed turn; 0 removes the tool. */
    val subagentCallsPerTurn: Int = 3,
    val subagentToolRounds: Int = 12,
    val subagentContextChars: Int = 48_000,
    val subagentAnswerChars: Int = 3_000,
    /** Classify hunks with decisions to order seeds; heuristics are used when off or without decisions. */
    val triage: Boolean = true,
    val triageBatchSize: Int = 8,
    val maxTriageRequests: Int = 60,
    /** Check links with decisions after the run and store their confidence. */
    val verifyLinks: Boolean = true,
    val verifyBatchSize: Int = 6,
    val maxVerifyRequests: Int = 40,
    /** Failed turns in a row (such as an unreachable server) after which the run stops. */
    val maxConsecutiveFailures: Int = 3,
) {
    init {
        require(maxSeedTurns >= 0 && maxToolRoundsPerTurn >= 1 && maxRequests >= 0 && maxFollowUps >= 0)
        require(seedBatchSize >= 1 && smallHunkLines >= 0 && contextChars > 0 && toolResultChars >= 1_000)
        require(seedPatchChars > 0 && summaryChars > 0)
        require(subagentCallsPerTurn >= 0 && subagentToolRounds >= 1 && subagentContextChars > 0 && subagentAnswerChars > 0)
        require(triageBatchSize >= 1 && maxTriageRequests >= 0 && verifyBatchSize >= 1 && maxVerifyRequests >= 0)
        require(maxConsecutiveFailures >= 1)
    }
}

enum class WalkPhase { TRIAGE, WALKING, VERIFYING, DONE }

/**
 * Where a run is: [handled] of [total] hunks (linked, covered or standalone), [steps] recorded, the [seed] hunk ids of
 * the current [turn] of at most [maxTurns], completion [requests] made and [failedTurns]. [message] is a status line.
 */
data class WalkProgress(
    val phase: WalkPhase,
    val handled: Int,
    val total: Int,
    val steps: Int,
    val seed: List<String> = emptyList(),
    val turn: Int = 0,
    val maxTurns: Int = 0,
    val requests: Int = 0,
    val failedTurns: Int = 0,
    val message: String = "",
)

/** The run's completion budget was used up; the turn that hit it ends. */
class WalkBudgetExhausted(limit: Int) : IOException("The walkthrough request budget ($limit) is used up")

/**
 * Builds a [Walkthrough] of a diff: hunks are triaged (decisions, else heuristics) into a seed order, then each seed
 * turn asks the model, with the walkthrough tools, to find what caused the seed and what it leads to and record that.
 * Turns are independent conversations that get a compact status, not the history, so context stays small. The run
 * stops when no hunk is pending or a limit of [options] is reached; every hunk left is shown as not linked by the
 * assistant, and links are verified with decisions when enabled. [projectTools] (such as `projectContextTools(project)`)
 * are loadable through search_tools, never required. Without [decide] everything works with heuristics.
 */
class WalkthroughWorkflow(
    private val complete: CompletionFn,
    private val decide: DecisionFn?,
    private val projectTools: List<ChatTool> = emptyList(),
    private val options: WalkOptions = WalkOptions(),
) {
    /** Runs over [snapshot]'s files; see the other overload. */
    suspend fun run(
        snapshot: ReviewSnapshot,
        focus: String? = null,
        onUpdate: suspend (Walkthrough) -> Unit = {},
        onProgress: suspend (WalkProgress) -> Unit = {},
        onToolCall: suspend (ToolCallEvent) -> Unit = {},
    ): Walkthrough = run(snapshot.files, snapshot.comparison.summary, focus, onUpdate, onProgress, onToolCall)

    /**
     * Walks [files], compared as [comparison] describes (shown to the model), with an optional reviewer [focus].
     * [onUpdate] receives a snapshot after every change; the last one, also returned, is finalized and complete.
     * On cancellation a finalized snapshot with `complete = false` is sent to [onUpdate] before the exception propagates.
     * [onToolCall] reports every tool call, subagent ones named `ask_subagent/<tool>`.
     */
    suspend fun run(
        files: List<ReviewFile>,
        comparison: String,
        focus: String? = null,
        onUpdate: suspend (Walkthrough) -> Unit = {},
        onProgress: suspend (WalkProgress) -> Unit = {},
        onToolCall: suspend (ToolCallEvent) -> Unit = {},
    ): Walkthrough {
        val hunks = walkHunks(files)
        onUpdate(Walkthrough.pending(hunks))
        onProgress(WalkProgress(WalkPhase.TRIAGE, 0, hunks.size, 0, message = "Classifying ${hunks.size} hunks…"))
        val triaged = WalkTriage(decide.takeIf { options.triage }, options.triageBatchSize, maxRequests = options.maxTriageRequests).classify(hunks)
        val ledger = WalkLedger(hunks, triaged.triage, onUpdate)
        triaged.warnings.forEach(ledger::warn)
        val order = seedOrder(hunks, triaged.triage)
        val budget = RequestBudget(complete, options.maxRequests)
        val tools = WalkTools(files, ledger, order.map { it.id }, options.toolResultChars)
        val registry = ToolRegistry().register("reading the before or after source of changed files", tools.readSnapshotSource)
        if (projectTools.isNotEmpty()) registry.register(AskSubagentTool.PROJECT_TOPIC, projectTools)
        val ask = AskSubagentTool(budget::complete, tools, projectTools = projectTools, maxRounds = options.subagentToolRounds,
            contextChars = options.subagentContextChars, answerChars = options.subagentAnswerChars,
            toolResultChars = options.toolResultChars, knownHunk = { ledger.hunk(it) != null }, onToolCall = onToolCall)
        val baseTools = tools.readOnly().filter { it !== tools.readSnapshotSource } + tools.recording()
        ledger.emit()

        var turn = 0
        var failed = 0
        var failedInRow = 0
        var stoppedByFailures = false
        val attempted = HashSet<String>()
        suspend fun progress(phase: WalkPhase, seed: List<String>, message: String) = onProgress(WalkProgress(phase,
            ledger.snapshot().coverage().handled, hunks.size, ledger.stepCount, seed, turn, options.maxSeedTurns, budget.used, failed, message))
        try {
            while (true) {
                val seed = nextSeed(order, ledger, attempted) ?: break
                if (turn >= options.maxSeedTurns) {
                    ledger.warn("Stopped after ${options.maxSeedTurns} seed turns; ${ledger.pendingCount} hunks were not analyzed.")
                    break
                }
                if (budget.used >= options.maxRequests) {
                    ledger.warn("Stopped at the budget of ${options.maxRequests} requests; ${ledger.pendingCount} hunks were not analyzed.")
                    break
                }
                turn++
                attempted += seed
                progress(WalkPhase.WALKING, seed, "Turn $turn: ${seed.first()}${if (seed.size > 1) " and ${seed.size - 1} more" else ""}")
                val turnTools = baseTools + listOfNotNull(options.subagentCallsPerTurn.takeIf { it > 0 }?.let {
                    LimitedTool(ask, CallAllowance(it, "No ask_subagent calls left in this turn; use the read tools or record what you know."))
                })
                val runner = ToolChatRunner(budget::complete, turnTools, maxToolRounds = options.maxToolRoundsPerTurn,
                    maxToolResultChars = options.toolResultChars, maxContextChars = options.contextChars, maxHistoryChars = 0,
                    evictionTarget = 0.85, followUp = { followUp(seed, ledger) }, maxFollowUps = options.maxFollowUps, registry = registry)
                try {
                    runner.run(listOf(LlmMessage.system(SYSTEM), LlmMessage.user(turnMessage(seed, tools, comparison, focus))), onToolCall)
                    failedInRow = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: WalkBudgetExhausted) {
                    ledger.warn("Stopped at the budget of ${options.maxRequests} requests; ${ledger.pendingCount} hunks were not analyzed.")
                    break
                } catch (e: Exception) {
                    failed++
                    failedInRow++
                    LOG.warn("Walkthrough turn $turn failed", e)
                    if (failedInRow >= options.maxConsecutiveFailures) {
                        stoppedByFailures = true
                        ledger.warn("Stopped after $failedInRow failed turns in a row (${e.message ?: e.javaClass.simpleName}).")
                        break
                    }
                }
            }
            if (failed > 0 && !stoppedByFailures)
                ledger.warn("$failed turn${if (failed == 1) "" else "s"} failed; their seeds may be shown as not linked.")
            ledger.finalize()
            ledger.emit()
            val links = ledger.snapshot().links
            if (options.verifyLinks && decide != null && links.isNotEmpty()) {
                progress(WalkPhase.VERIFYING, emptyList(), "Checking ${links.size} links…")
                val verified = WalkLinkVerifier(decide, options.verifyBatchSize, maxRequests = options.maxVerifyRequests)
                    .verify(links, hunks.associateBy { it.id })
                verified.warnings.forEach(ledger::warn)
                ledger.setConfidence(verified.confidence)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                ledger.finalize()
                ledger.warn("The walkthrough was cancelled; changes it did not reach are shown as not linked.")
                ledger.emit()
            }
            throw e
        }
        val result = ledger.markComplete()
        progress(WalkPhase.DONE, emptyList(), coverageLine(result.coverage()))
        return result
    }

    /** The best unattempted pending hunk, with up to [WalkOptions.seedBatchSize] - 1 small pending hunks of its file. */
    private fun nextSeed(order: List<WalkHunk>, ledger: WalkLedger, attempted: Set<String>): List<String>? {
        fun open(hunk: WalkHunk) = hunk.id !in attempted && ledger.status(hunk.id) == HunkStatus.PENDING
        fun small(hunk: WalkHunk) = hunk.added + hunk.removed <= options.smallHunkLines
        val first = order.firstOrNull(::open) ?: return null
        if (options.seedBatchSize == 1 || !small(first)) return listOf(first.id)
        val siblings = ledger.hunks.filter { it.path == first.path && it.id != first.id && open(it) && small(it) }
            .take(options.seedBatchSize - 1)
        return listOf(first.id) + siblings.map { it.id }
    }

    private fun followUp(seed: List<String>, ledger: WalkLedger): String? {
        val left = seed.filter { ledger.status(it) == HunkStatus.PENDING }
        if (left.isEmpty()) return null
        return "Still pending: ${left.joinToString()}. Before answering, handle each: record_step (it may be the root or " +
            "a hunk of the chain), extend_step to add it to an existing step, or mark_standalone if it is self-contained."
    }

    private fun turnMessage(seed: List<String>, tools: WalkTools, comparison: String, focus: String?): String = buildString {
        if (comparison.isNotBlank()) append("Comparison: ").append(comparison).append('\n')
        focus?.takeIf { it.isNotBlank() }?.let { append("Reviewer focus (user preference): ").append(it.trim()).append('\n') }
        append("\nWalkthrough status:\n")
        val status = tools.status(0, options.summaryChars / 2)
        append(if (status.length <= options.summaryChars) status else status.take(options.summaryChars).substringBeforeLast('\n') +
            "\n[Status cut; call walk_status for more.]")
        append("\n\nSeed hunk${if (seed.size == 1) "" else "s"} for this turn: ").append(seed.joinToString()).append('\n')
        append(tools.renderHunks(seed, 0, options.seedPatchChars)).append('\n')
        append("\nFind what caused ").append(if (seed.size == 1) "this change" else "these changes")
        append(" and what it leads to across the diff, record it, and answer with one short sentence.")
    }

    private companion object {
        val SYSTEM: String by lazy { PromptTemplates.render("diff-walkthrough-system.md", mapOf("CODE_AS_DATA" to CODE_AS_DATA)) }
    }
}

/** Counts completion requests and throws [WalkBudgetExhausted] once [limit] were made. Confined like the ledger. */
private class RequestBudget(private val inner: CompletionFn, private val limit: Int) {
    var used: Int = 0
        private set

    suspend fun complete(request: CompletionRequest): Completion {
        if (used >= limit) throw WalkBudgetExhausted(limit)
        used++
        return inner(request)
    }
}
