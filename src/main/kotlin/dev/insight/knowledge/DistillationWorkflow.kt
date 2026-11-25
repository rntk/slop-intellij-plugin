package dev.insight.knowledge

import com.intellij.openapi.diagnostic.logger
import dev.insight.agent.CallAllowance
import dev.insight.agent.ChatTool
import dev.insight.agent.LimitedTool
import dev.insight.agent.ToolChatRunner
import dev.insight.agent.ToolRegistry
import dev.insight.decisions.DecisionFn
import dev.insight.llm.CompletionFn
import dev.insight.llm.LlmMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

private val LOG = logger<DistillationWorkflow>()

/**
 * Distills knowledge about a scope of the project into [base], incrementally and under the ratchet.
 *
 * A run partitions the scope, then refreshes the active claims in it from their anchors (IDE-only: moved anchors are
 * reanchored, changed or gone ones make claims suspect, generated claims whose anchors are all gone retire). Areas are
 * processed bottom-up, deepest first; within a depth level up to [DistillLimits.parallelism] workers run at once. A
 * worker (one per partition) is a bounded tool-using turn that reads its files' evidence and records proposals with
 * [DistillTools]; partitions whose claims are all unchanged and whose files are covered are skipped, so re-runs only
 * redo what drifted. Workers never write: a single organizer ([DistillOrganizer]) submits every op as `organizer`, so
 * the [RatchetGuard] judges each one, and lifts ideas shared by child areas to their parent after each level.
 *
 * [tools] are read-only project tools offered to every worker (limited per worker); [registry] holds more the model can
 * load with search_tools. Both are shared by parallel workers, so they must be safe to call concurrently. Without
 * [decide], dedupe only matches identical normalized texts, contradictions are not checked and meaning-preserving ops are rejected
 * by the guard; a warning says so.
 */
class DistillationWorkflow(
    private val base: KnowledgeBase,
    private val partitioner: KnowledgePartitioner,
    private val evidence: EvidenceProvider,
    private val resolver: AnchorResolver,
    private val complete: CompletionFn,
    private val decide: DecisionFn?,
    private val tools: List<ChatTool> = emptyList(),
    private val registry: ToolRegistry? = null,
    private val limits: DistillLimits = DistillLimits(),
) {
    init {
        val clash = (tools + registry?.tools.orEmpty()).map { it.definition.name }.filter { it in DistillToolNames.ALL }
        require(clash.isEmpty()) { "Project tools must not use the distillation tool names: ${clash.joinToString()}" }
    }

    /**
     * Distills [scope] (project-relative paths). [progress] gets phase, done and total; [onUpdate] gets every event,
     * one at a time, possibly from worker threads. On cancellation everything already submitted stays in [base]; a
     * warning is sent and the cancellation propagates.
     */
    suspend fun run(scope: List<String>, progress: KnowledgeProgress, onUpdate: (DistillEvent) -> Unit = {}): DistillResult {
        val lock = Any()
        val warnings = mutableListOf<String>()
        fun emit(event: DistillEvent) = synchronized(lock) {
            if (event is DistillEvent.Warning) warnings += event.message
            try { onUpdate(event) } catch (e: CancellationException) { throw e } catch (e: Exception) { LOG.warn("Distillation listener failed", e) }
        }
        fun warn(message: String) = emit(DistillEvent.Warning(message))
        fun report(phase: String, done: Int, total: Int) = synchronized(lock) { progress.report(phase, done, total) }

        val before = base.snapshot()
        val startSeq = base.entries().lastOrNull()?.seq ?: 0L
        val requests = AtomicInteger()
        val counted: CompletionFn = { request -> requests.incrementAndGet(); complete(request) }
        val judge = decide?.let { DistillJudge(it, limits.decisionBatchSize, ::warn) }
        if (decide == null) warn(NO_DECISIONS)

        report(PHASE_PARTITION, 0, 1)
        val all = partitioner.partitions(scope)
        val scopeKey = scope.map { it.trim().trim('/') }.distinct().sorted()
        val offset = if (all.size <= limits.maxPartitions) 0 else base.distillOffset(scopeKey).takeIf { it < all.size } ?: 0
        val partitions = all.drop(offset).take(limits.maxPartitions)
        if (partitions.size < all.size) {
            warn("Distilling ${partitions.size} of ${all.size} partitions; run again to continue through the scope.")
        }
        report(PHASE_PARTITION, 1, 1)

        val areas = partitions.mapTo(HashSet()) { it.area } + partitions.mapNotNull { it.parent }
        val stale = before.active().filter { it.area in areas || inScope(it.area, scope) }
        report(PHASE_REFRESH, 0, stale.size)
        val plan = planDistillRefresh(stale, resolver, limits.parallelism)
        plan.warnings.forEach(::warn)
        val organizer = DistillOrganizer(base, resolver, evidence, judge, counted, limits, plan.current, ::emit, ::warn)

        var distilled = 0
        var skipped = 0
        var proposals = 0
        try {
            plan.ops.forEach { organizer.submit(it) }
            report(PHASE_REFRESH, stale.size, stale.size)

            val total = partitions.size
            val finished = AtomicInteger()
            val semaphore = Semaphore(limits.parallelism)
            val levels = partitions.withIndex().groupBy { depth(it.value.area) }.toSortedMap(reverseOrder())
            val minChildDepth = partitions.filter { it.parent != null }.groupBy { it.parent!! }
                .mapValues { (_, children) -> children.minOf { depth(it.area) } }
            report(PHASE_DISTILL, 0, total)
            coroutineScope {
                val inbox = Channel<OrganizerTask>(Channel.UNLIMITED)
                val organizing = launch {
                    for (task in inbox) when (task) {
                        is OrganizerTask.Area -> organizer.organize(task.area, task.proposals)
                        is OrganizerTask.Lift -> {
                            task.parents.forEach { parent ->
                                report(PHASE_LIFT, 0, 1)
                                organizer.lift(parent, partitions.filter { it.parent == parent }.mapTo(HashSet()) { it.area })
                            }
                            task.done.complete(Unit)
                        }
                    }
                }
                for ((level, members) in levels) {
                    val snapshot = base.snapshot()
                    val outcomes = coroutineScope {
                        members.groupBy { it.value.area }.map { (area, parts) ->
                            async {
                                val claims = snapshot.inArea(area)
                                val assigned = assignSuspects(parts.map { it.value }, claims)
                                val results = parts.map { (index, partition) ->
                                    async {
                                        semaphore.withPermit {
                                            work(partition, index, total, claims, assigned[partition].orEmpty(), counted, ::emit, ::warn)
                                        }.also { report(PHASE_DISTILL, finished.incrementAndGet(), total) }
                                    }
                                }.awaitAll()
                                inbox.send(OrganizerTask.Area(area, results.flatMap { it.proposals }))
                                results
                            }
                        }.awaitAll().flatten()
                    }
                    distilled += outcomes.count { !it.skipped }
                    skipped += outcomes.count { it.skipped }
                    proposals += outcomes.sumOf { it.proposals.size }
                    val parents = minChildDepth.filterValues { it == level }.keys.sortedByDescending(::depth)
                    val done = CompletableDeferred<Unit>()
                    inbox.send(OrganizerTask.Lift(parents, done))
                    done.await()
                }
                inbox.close()
                organizing.join()
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                warn("Distillation was stopped; the ${organizer.entries.count { it.verdict.accepted }} accepted changes it made are kept.")
            }
            throw e
        }

        report(PHASE_AUDIT, 0, 1)
        val audit = ratchetAudit(before, base.snapshot(), base.entries().filter { it.seq > startSeq })
        if (audit.isNotEmpty()) warn("Ratchet audit: ${audit.size} claims left the live set without an accepted op naming them.")
        report(PHASE_DONE, 1, 1)
        val entries = organizer.entries
        if (all.isEmpty() || partitions.isNotEmpty()) {
            base.setDistillOffset(scopeKey, if (offset + partitions.size >= all.size) 0 else offset + partitions.size)
        }
        return DistillResult(
            partitions = partitions.size,
            distilled = distilled,
            skipped = skipped,
            proposals = proposals,
            duplicates = organizer.duplicates,
            lifted = organizer.lifted,
            submitted = entries.count { it.verdict.accepted },
            rejected = entries.count { !it.verdict.accepted },
            requests = requests.get(),
            entries = entries,
            warnings = synchronized(lock) { warnings.toList() },
            audit = audit,
        )
    }

    private class WorkOutcome(val skipped: Boolean, val proposals: List<DistillProposal>)

    private sealed interface OrganizerTask {
        class Area(val area: String, val proposals: List<DistillProposal>) : OrganizerTask
        class Lift(val parents: List<String>, val done: CompletableDeferred<Unit>) : OrganizerTask
    }

    /** One worker turn over [partition]; [claims] are the area's active claims, [verify] the suspect ones it re-verifies. */
    private suspend fun work(
        partition: KnowledgePartition,
        index: Int,
        total: Int,
        claims: List<Claim>,
        verify: List<Claim>,
        complete: CompletionFn,
        emit: (DistillEvent) -> Unit,
        warn: (String) -> Unit,
    ): WorkOutcome {
        currentCoroutineContext().ensureActive()
        val area = partition.area
        if (verify.isEmpty() && (partition.files.isEmpty() || claims.isNotEmpty() && uncoveredFiles(partition, claims).isEmpty())) {
            emit(DistillEvent.PartitionFinished(area, 0, skipped = true))
            return WorkOutcome(true, emptyList())
        }
        emit(DistillEvent.PartitionStarted(area, partition.files.size, index, total))
        val verifyIds = verify.mapTo(HashSet()) { it.id }
        val recording = DistillTools(area, claims, verifyIds, limits.maxProposals, limits.maxClaimChars) { emit(DistillEvent.Proposed(area, it)) }
        val anchors = (partition.files.map { Anchor(AnchorKind.FILE, it) } + verify.flatMap { it.anchors }).distinctBy { it.kind to it.ref }
        val excerpts = try {
            evidence.evidence(anchors, limits.evidenceChars)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("Could not read evidence for '$area': ${e.message ?: e.javaClass.simpleName}")
            emptyList()
        }
        val allowance = CallAllowance(limits.maxToolCalls, "No project tool calls left for this area; record what you know now.")
        val runner = ToolChatRunner(complete, recording.recording() + tools.map { LimitedTool(it, allowance) },
            maxToolRounds = limits.maxToolRounds, maxToolResultChars = limits.toolResultChars, maxContextChars = limits.contextChars,
            maxHistoryChars = 0, evictionTarget = 0.85, followUp = { recording.followUp() }, maxFollowUps = limits.maxFollowUps,
            registry = registry?.mapTools { LimitedTool(it, allowance) })
        val message = DistillPrompts.workerMessage(partition, claims.filter { it.id !in verifyIds }, verify, excerpts,
            limits.evidenceChars, limits.maxClaimsInPrompt)
        var error: String? = null
        try {
            runner.run(listOf(LlmMessage.system(DistillPrompts.WORKER_SYSTEM), LlmMessage.user(message)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Distillation worker for '$area' failed", e)
            error = e.message ?: e.javaClass.simpleName
            warn("The worker for '${area.ifEmpty { "(root)" }}' failed ($error); its ${recording.proposals.size} proposals are still organized.")
        }
        emit(DistillEvent.PartitionFinished(area, recording.proposals.size, error = error))
        return WorkOutcome(false, recording.proposals)
    }

    companion object {
        const val PHASE_PARTITION = "Partitioning"
        const val PHASE_REFRESH = "Refreshing"
        const val PHASE_DISTILL = "Distilling"
        const val PHASE_LIFT = "Lifting"
        const val PHASE_AUDIT = "Auditing"
        const val PHASE_DONE = "Done"
        const val NO_DECISIONS = "Decisions are not configured. Duplicate detection requires identical normalized text. " +
            "Contradiction checks, rewrites and confirmations require Decisions."
    }
}

/** Depth of an area path: the root is 0, `src` 1, `src/a` 2. */
internal fun areaDepth(area: String): Int = area.trim('/').let { if (it.isEmpty()) 0 else it.count { c -> c == '/' } + 1 }

private fun depth(area: String) = areaDepth(area)

private fun inScope(area: String, scope: List<String>): Boolean = scope.any { entry ->
    val path = entry.trim().trim('/')
    path.isEmpty() || area == path || area.startsWith("$path/")
}

/** Files of [partition] no claim of [claims] anchors or cites (directly or through a directory anchor). */
internal fun uncoveredFiles(partition: KnowledgePartition, claims: List<Claim>): List<String> {
    val refs = claims.flatMapTo(HashSet()) { claim -> claim.anchors.map { it.ref } + claim.citations.map { it.path } }
    val dirs = claims.flatMap { claim -> claim.anchors.filter { it.kind == AnchorKind.DIRECTORY }.map { it.ref.trimEnd('/') + "/" } }
    return partition.files.filter { file -> file !in refs && dirs.none { file.startsWith(it) } }
}

/**
 * Splits the suspect claims among [partitions] of one area: each goes to the first partition owning one of its anchors
 * or citations, else to the first partition.
 */
internal fun assignSuspects(partitions: List<KnowledgePartition>, claims: List<Claim>): Map<KnowledgePartition, List<Claim>> {
    if (partitions.isEmpty()) return emptyMap()
    return claims.filter { it.status is ClaimStatus.Suspect }.groupBy { claim ->
        val refs = claim.anchors.map { it.ref } + claim.citations.map { it.path }
        partitions.firstOrNull { partition -> partition.files.any { it in refs } } ?: partitions.first()
    }
}
