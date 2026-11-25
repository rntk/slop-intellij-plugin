package dev.insight.knowledge

/**
 * Limits and thresholds of one distillation run. Similarities are normalized token Jaccard (see [claimSimilarity]);
 * probabilities come from decisions.
 */
data class DistillLimits(
    /** Workers running at once within one depth level. */
    val parallelism: Int = 4,
    /** Partitions handled in one run; the rest are left for a later run with a warning. */
    val maxPartitions: Int = 200,
    /** Tool rounds of a worker turn before it must answer. */
    val maxToolRounds: Int = 12,
    /** Nudges when a worker answers while suspect claims it was given are still unhandled. */
    val maxFollowUps: Int = 1,
    /** Calls of the injected read-only project tools per worker. */
    val maxToolCalls: Int = 24,
    /** Evidence excerpts given to a worker, and read for each verification. */
    val evidenceChars: Int = 12_000,
    /** propose_claim and revise_claim calls per worker. */
    val maxProposals: Int = 24,
    /** Claims in child areas (from at least two children) that must express one idea before it is lifted to the parent. */
    val liftThreshold: Int = 2,
    /** Most known claims listed in a worker's message. */
    val maxClaimsInPrompt: Int = 60,
    val maxClaimChars: Int = 400,
    val contextChars: Int = 64_000,
    val toolResultChars: Int = 8_000,
    /** Pairs at least this similar are asked "same claim?" (dedupe and lifting) when decisions are available. */
    val shortlistSimilarity: Double = 0.35,
    /** Pairs at least this similar are asked "contradict?". */
    val conflictSimilarity: Double = 0.25,
    /** Without decisions, only pairs at least this similar count as duplicates; conservative on purpose. */
    val lexicalDuplicateSimilarity: Double = 0.85,
    /** Decision probabilities: same claim, contradiction, support for a confirmation, entailment of a lifted claim. */
    val sameThreshold: Double = 0.8,
    val contradictThreshold: Double = 0.8,
    val supportThreshold: Double = 0.7,
    /** A refutation retires a generated claim at [refuteConfidence]; between [suspectConfidence] and it, marks it suspect. */
    val refuteConfidence: Double = 0.8,
    val suspectConfidence: Double = 0.5,
    /** Questions in one decisions request, and pairs judged per area (most similar first). */
    val decisionBatchSize: Int = 12,
    val maxDecisionPairs: Int = 60,
) {
    init {
        require(parallelism >= 1 && maxPartitions >= 0 && maxToolRounds >= 1 && maxFollowUps >= 0 && maxToolCalls >= 0)
        require(evidenceChars > 0 && maxProposals >= 1 && liftThreshold >= 2 && maxClaimsInPrompt >= 0 && maxClaimChars >= 40)
        require(contextChars > 0 && toolResultChars >= 1_000 && decisionBatchSize >= 1 && maxDecisionPairs >= 0)
        listOf(shortlistSimilarity, conflictSimilarity, lexicalDuplicateSimilarity, sameThreshold, contradictThreshold,
            supportThreshold, refuteConfidence, suspectConfidence).forEach { require(it in 0.0..1.0) }
        require(suspectConfidence <= refuteConfidence)
    }
}

/** What a worker recorded. Workers never submit; the organizer turns proposals into guarded ops. */
sealed interface DistillProposal {
    /** `worker:<area>`. */
    val actor: String

    data class Propose(
        override val actor: String,
        val text: String,
        val level: ClaimLevel,
        val anchors: List<Anchor>,
        val citations: List<Citation> = emptyList(),
    ) : DistillProposal

    data class Confirm(override val actor: String, val id: String) : DistillProposal
    data class Revise(override val actor: String, val id: String, val text: String, val reason: String) : DistillProposal
    data class Refute(override val actor: String, val id: String, val reason: String, val evidence: String) : DistillProposal
}

/** Updates of a run, sent in order; [DistillationWorkflow.run] serializes them although workers run in parallel. */
sealed interface DistillEvent {
    data class PartitionStarted(val area: String, val files: Int, val index: Int, val total: Int) : DistillEvent
    /** [skipped] when every claim of the partition was unchanged; [error] when its worker failed. */
    data class PartitionFinished(
        val area: String,
        val proposals: Int,
        val skipped: Boolean = false,
        val error: String? = null,
    ) : DistillEvent
    data class Proposed(val area: String, val proposal: DistillProposal) : DistillEvent
    data class Submitted(val entry: LedgerEntry) : DistillEvent
    /** The guard rejected the op; the entry is in the ledger but was not applied. */
    data class Rejected(val entry: LedgerEntry) : DistillEvent
    data class Warning(val message: String) : DistillEvent
}

/** A claim active before the run that is not active after it, with no accepted ledger entry naming it. */
data class DistillAuditFinding(val claimId: String, val status: String)

/**
 * Outcome of a run: [entries] it submitted (accepted and rejected, in order), counts, [warnings] and the ratchet [audit],
 * which is empty unless something outside the guard dropped claims.
 */
data class DistillResult(
    val partitions: Int,
    val distilled: Int,
    val skipped: Int,
    val proposals: Int,
    val duplicates: Int,
    val lifted: Int,
    val submitted: Int,
    val rejected: Int,
    val requests: Int,
    val entries: List<LedgerEntry>,
    val warnings: List<String>,
    val audit: List<DistillAuditFinding>,
) {
    val rejectedEntries: List<LedgerEntry> get() = entries.filter { !it.verdict.accepted }
}

/**
 * Claims active in [before] but not in [after] that no accepted entry of [entries] names in its removed ids. An empty
 * result means every claim the run took out of the live set went through the guard.
 */
fun ratchetAudit(before: KnowledgeState, after: KnowledgeState, entries: List<LedgerEntry>): List<DistillAuditFinding> {
    val explained = entries.filter { it.verdict.accepted }.flatMapTo(HashSet()) { it.op.removedIds }
    return before.active().mapNotNull { old ->
        val now = after[old.id]
        when {
            now == null -> DistillAuditFinding(old.id, "missing")
            !now.isActive && old.id !in explained -> DistillAuditFinding(old.id, statusName(now.status))
            else -> null
        }
    }
}
