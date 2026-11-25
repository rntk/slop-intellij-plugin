package dev.insight.knowledge

import dev.insight.llm.CompletionFn
import dev.insight.llm.CompletionRequest
import dev.insight.llm.LlmMessage
import dev.insight.llm.clipLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The only writer of a distillation run (actor [ACTOR]): turns worker proposals into ops and submits them, so the
 * [RatchetGuard] judges every change. Runs in a single coroutine; not thread-safe.
 *
 * Per area: confirmations, revisions and refutations of known claims are verified with [judge] against [evidence]
 * (never taken on a worker's word); new proposals are anchored with [resolver], deduplicated against each other and the
 * area's active claims (lexical shortlist, then "same claim?" judgments), added, and checked for contradictions. Lifting
 * adds parent-level claims for ideas several child areas share, leaving the children in place. Without [judge],
 * only identical normalized texts count as duplicates, contradictions are not checked and confirmations are not applied.
 */
internal class DistillOrganizer(
    private val base: KnowledgeBase,
    private val resolver: AnchorResolver,
    private val evidence: EvidenceProvider,
    private val judge: DistillJudge?,
    private val complete: CompletionFn,
    private val limits: DistillLimits,
    /** Refreshed anchors by claim id, from the refresh plan. */
    private val current: Map<String, List<Anchor>>,
    private val emit: (DistillEvent) -> Unit,
    private val warn: (String) -> Unit,
) {
    private val submitted = mutableListOf<LedgerEntry>()
    /** Claims this run added (adds, rewrites and lifts); lifting only considers groups containing one. */
    private val added = LinkedHashSet<String>()
    private var confirmWarned = false

    val entries: List<LedgerEntry> get() = submitted.toList()
    var duplicates = 0
        private set
    var lifted = 0
        private set

    suspend fun submit(op: KnowledgeOp): LedgerEntry {
        currentCoroutineContext().ensureActive()
        val entry = base.submit(op, ACTOR)
        submitted += entry
        if (entry.verdict.accepted) {
            when (op) {
                is KnowledgeOp.Add -> added += op.claim.id
                is KnowledgeOp.Rewrite -> added += op.to.id
                else -> Unit
            }
            emit(DistillEvent.Submitted(entry))
        } else {
            emit(DistillEvent.Rejected(entry))
        }
        return entry
    }

    /** Organizes everything the workers of [area] proposed. */
    suspend fun organize(area: String, proposals: List<DistillProposal>) {
        for (proposal in proposals) {
            currentCoroutineContext().ensureActive()
            val claim = when (proposal) {
                is DistillProposal.Propose -> continue
                is DistillProposal.Confirm -> active(proposal.id)?.also { confirm(it) }
                is DistillProposal.Revise -> active(proposal.id)?.also { revise(it, proposal) }
                is DistillProposal.Refute -> active(proposal.id)?.also { refute(it, proposal) }
            }
            if (claim == null) warn("Skipped a proposal of ${proposal.actor} about a claim that is no longer active.")
        }
        val (adds, same) = addNew(area, proposals.filterIsInstance<DistillProposal.Propose>())
        checkConflicts(area, adds, same)
    }

    private fun active(id: String): Claim? = base.snapshot()[id]?.takeIf { it.isActive }

    private suspend fun confirm(claim: Claim) {
        val anchors = current[claim.id]?.takeIf { it.isNotEmpty() } ?: claim.anchors
        if (claim.status == ClaimStatus.Live && anchors == claim.anchors) return
        val judge = judge ?: run {
            if (!confirmWarned) warn("Without decisions, confirmations are not applied; suspect claims stay suspect.")
            confirmWarned = true
            return
        }
        val p = judge.supports(claim.text, evidenceText(anchors) ?: NO_EVIDENCE)
        if (p != null && p >= limits.supportThreshold) submit(KnowledgeOp.Confirm(claim.id, anchors))
        else warn("The confirmation of ${claim.id} was not supported by the evidence (${fmt(p)}); it stays ${statusName(claim.status)}.")
    }

    private suspend fun revise(claim: Claim, proposal: DistillProposal.Revise) {
        if (claim.origin == ClaimOrigin.HUMAN) {
            submit(KnowledgeOp.MarkSuspect(claim.id, "Revision proposed by ${proposal.actor}: ${proposal.text.clipLine(200)} " +
                "(${proposal.reason.clipLine(120)})"))
            return
        }
        val anchors = current[claim.id].orEmpty()
        if (!canRewrite(claim, proposal.text, anchors)) return
        val to = claim.copy(id = base.snapshot().newId(proposal.text, claim.area), text = proposal.text, origin = ClaimOrigin.GENERATED,
            anchors = anchors.ifEmpty { claim.anchors }, status = ClaimStatus.Live, source = null)
        val entry = submit(KnowledgeOp.Rewrite(claim.id, to))
        if (!entry.verdict.accepted && active(claim.id) != null)
            submit(KnowledgeOp.MarkSuspect(claim.id, "Revision not verified: ${proposal.reason.clipLine(200)}"))
    }

    /** The guard checks equivalence separately. A suspect claim also needs support from current source. */
    private suspend fun canRewrite(claim: Claim, text: String, anchors: List<Anchor>): Boolean {
        if (claim.status == ClaimStatus.Live) return true
        val excerpts = if (judge != null && anchors.isNotEmpty()) evidenceText(anchors) else null
        val p = if (excerpts != null) judge?.supports(text, excerpts) else null
        if (p != null && p >= limits.supportThreshold) return true
        warn("The revision of ${claim.id} was not supported by current evidence (${fmt(p)}); it stays suspect.")
        return false
    }

    private suspend fun refute(claim: Claim, proposal: DistillProposal.Refute) {
        val reason = "Refuted by ${proposal.actor}: ${proposal.reason.clipLine(200)}"
        val p = judge?.refutes(claim.text, evidenceText(current[claim.id] ?: claim.anchors) ?: NO_EVIDENCE)
        when {
            p == null -> submit(KnowledgeOp.MarkSuspect(claim.id, "$reason (not verified)"))
            p >= limits.refuteConfidence && claim.origin == ClaimOrigin.GENERATED -> {
                val explanation = "${proposal.reason}; evidence: ${proposal.evidence}".clipLine(600)
                val entry = submit(KnowledgeOp.Retire(claim.id, proposal.reason.clipLine(200), RetireEvidence.Contradicted(p, explanation)))
                if (!entry.verdict.accepted && active(claim.id) != null) submit(KnowledgeOp.MarkSuspect(claim.id, "$reason (${fmt(p)})"))
            }
            // HUMAN claims leave the live set only through the user; a confident refutation makes them suspect.
            p >= limits.suspectConfidence ->submit(KnowledgeOp.MarkSuspect(claim.id, "$reason (${fmt(p)})"))
            else -> warn("The refutation of ${claim.id} by ${proposal.actor} was not confirmed by the evidence (${fmt(p)}); it is unchanged.")
        }
    }

    private class Candidate(val proposal: DistillProposal.Propose, val anchors: List<Anchor>) {
        val text get() = proposal.text
    }

    /** Adds the new claims among [proposals]; returns the claims added and the id pairs judged the same. */
    private suspend fun addNew(area: String, proposals: List<DistillProposal.Propose>): Pair<List<Claim>, Set<Set<String>>> {
        if (proposals.isEmpty()) return emptyList<Claim>() to emptySet()
        val candidates = proposals.mapNotNull { proposal ->
            val anchors = proposal.anchors.mapNotNull { resolveAnchor(it) }.distinctBy { it.kind to it.ref }
            if (anchors.isEmpty()) {
                warn("Dropped a claim proposed by ${proposal.actor}: none of its anchors exist (${proposal.anchors.joinToString { it.ref }}).")
                null
            } else Candidate(proposal, anchors)
        }
        val existing = base.snapshot().inArea(area)
        val texts = existing.map { it.text } + candidates.map { it.text }
        val e = existing.size
        val sets = DisjointSets(texts.size)
        val sameIndexes = judgedSame(texts) { _, b -> b >= e }
        sameIndexes.forEach { (a, b) -> sets.union(a, b) }

        val keep = mutableListOf<Candidate>()
        val rewrites = mutableListOf<Pair<Claim, Candidate>>()
        val merged = HashMap<Candidate, Pair<List<Anchor>, List<Citation>>>()
        for (group in texts.indices.groupBy(sets::find).values.sortedBy { it.first() }) {
            val cands = group.filter { it >= e }.map { candidates[it - e] }
            if (cands.isEmpty()) continue
            val olds = group.filter { it < e }.map { existing[it] }
            val best = cands.maxWith(compareBy<Candidate>({ claimTokens(it.text).size }, { it.text.length }))
            val anchors = cands.flatMap { it.anchors }.distinctBy { it.kind to it.ref }.take(MAX_MERGED)
            val citations = cands.flatMap { it.proposal.citations }.distinct().take(MAX_MERGED)
            merged[best] = anchors to citations
            if (olds.isEmpty()) {
                keep += best
                duplicates += cands.size - 1
            } else {
                duplicates += cands.size
                val target = olds.first()
                if (target.origin == ClaimOrigin.GENERATED && isRicher(best.text, target.text)) rewrites += target to best
            }
        }
        val claims = mutableListOf<Claim>()
        for (candidate in keep) {
            val (anchors, citations) = merged.getValue(candidate)
            val claim = Claim(base.snapshot().newId(candidate.text, area), candidate.text, candidate.proposal.level,
                ClaimOrigin.GENERATED, area, anchors, citations)
            if (submit(KnowledgeOp.Add(claim)).verdict.accepted) claims += claim
        }
        for ((target, candidate) in rewrites) {
            val (anchors, citations) = merged.getValue(candidate)
            val existingAnchors = if (target.status == ClaimStatus.Live) target.anchors else current[target.id].orEmpty()
            val rewriteAnchors = (anchors + existingAnchors).distinctBy { it.kind to it.ref }.take(MAX_MERGED)
            if (!canRewrite(target, candidate.text, rewriteAnchors)) continue
            val to = Claim(base.snapshot().newId(candidate.text, area), candidate.text, candidate.proposal.level, ClaimOrigin.GENERATED,
                area, rewriteAnchors,
                (citations + target.citations).distinct().take(MAX_MERGED), target.tags, target.stability)
            if (submit(KnowledgeOp.Rewrite(target.id, to)).verdict.accepted) claims += to
        }
        val ids = existing.map { it.id } + candidates.map { c -> claims.firstOrNull { it.text == c.text }?.id }
        val same = sameIndexes.mapNotNullTo(HashSet()) { (a, b) -> setOfNotNull(ids[a], ids[b]).takeIf { it.size == 2 } }
        return claims to same
    }

    /** Index pairs of [texts] that state the same claim: normalized exact matches and shortlisted pairs judged equivalent. */
    private suspend fun judgedSame(texts: List<String>, eligible: (Int, Int) -> Boolean): List<Pair<Int, Int>> {
        val judge = judge
        val threshold = if (judge != null) limits.shortlistSimilarity else 1.0
        val pairs = similarPairs(texts, threshold, limits.maxDecisionPairs, eligible)
        val normalized = texts.map(::normalizeClaimText)
        val (exact, asked) = pairs.partition { normalized[it.a] == normalized[it.b] }
        if (judge == null) return exact.map { it.a to it.b }
        val answers = if (asked.isEmpty()) emptyList() else judge.same(asked.map { texts[it.a] to texts[it.b] })
        val confirmed = asked.zip(answers).filter { (_, p) -> p != null && p >= limits.sameThreshold }.map { it.first }
        return (exact + confirmed).map { it.a to it.b }
    }

    private suspend fun checkConflicts(area: String, adds: List<Claim>, same: Set<Set<String>>) {
        val judge = judge ?: return
        if (adds.isEmpty()) return
        val addedIds = adds.mapTo(HashSet()) { it.id }
        val active = base.snapshot().inArea(area)
        val pairs = similarPairs(active.map { it.text }, limits.conflictSimilarity, limits.maxDecisionPairs) { a, b ->
            (active[a].id in addedIds || active[b].id in addedIds) && setOf(active[a].id, active[b].id) !in same
        }
        if (pairs.isEmpty()) return
        val answers = judge.contradict(pairs.map { active[it.a].text to active[it.b].text })
        for ((pair, p) in pairs.zip(answers)) {
            if (p == null || p < limits.contradictThreshold) continue
            val a = active[pair.a]
            val b = active[pair.b]
            if (active(a.id) == null || active(b.id) == null) continue
            submit(KnowledgeOp.Contradict(b.id, a.id, "Judged contradictory (${fmt(p)})"))
        }
    }

    /**
     * Lifts ideas shared by [children] (areas below [parent]) into a parent-level claim: groups of at least
     * [DistillLimits.liftThreshold] live claims from two or more children that state the same idea, at least one of them
     * new in this run. The model words the parent claim; with decisions it must follow from the group and must not
     * duplicate a parent claim. Children are never removed.
     */
    suspend fun lift(parent: String, children: Set<String>) {
        val claims = base.snapshot().active().filter { it.area in children && it.status == ClaimStatus.Live }
        if (claims.mapTo(HashSet()) { it.area }.size < 2) return
        val sets = DisjointSets(claims.size)
        judgedSame(claims.map { it.text }) { a, b -> claims[a].area != claims[b].area }.forEach { (a, b) -> sets.union(a, b) }
        val groups = sets.groups().map { group -> group.map(claims::get) }.filter { members ->
            members.size >= limits.liftThreshold && members.mapTo(HashSet()) { it.area }.size >= 2 && members.any { it.id in added }
        }
        for (members in groups) {
            currentCoroutineContext().ensureActive()
            val (level, text) = askLift(parent, members) ?: continue
            if (text.length > limits.maxClaimChars) {
                warn("Skipped a lifted claim for '$parent': it is longer than ${limits.maxClaimChars} characters.")
                continue
            }
            val judge = judge
            if (judge != null) {
                val p = judge.entails(DistillPrompts.liftPremise(members), text)
                if (p == null || p < limits.sameThreshold) {
                    warn("Skipped a lifted claim for '$parent' that its child claims do not support (${fmt(p)}).")
                    continue
                }
            }
            if (duplicatesParent(parent, text)) continue
            val claim = Claim(base.snapshot().newId(text, parent), text, level, ClaimOrigin.GENERATED, parent,
                members.flatMap { it.anchors }.distinctBy { it.kind to it.ref }.take(MAX_MERGED),
                members.flatMap { it.citations }.distinct().take(MAX_MERGED), tags = setOf(LIFTED_TAG))
            if (submit(KnowledgeOp.Add(claim)).verdict.accepted) lifted++
        }
    }

    private suspend fun duplicatesParent(parent: String, text: String): Boolean {
        val existing = base.snapshot().inArea(parent)
        if (existing.isEmpty()) return false
        return judgedSame(existing.map { it.text } + text) { _, b -> b == existing.size }.isNotEmpty()
    }

    private suspend fun askLift(parent: String, members: List<Claim>): Pair<ClaimLevel, String>? {
        val completion = try {
            complete(CompletionRequest(listOf(LlmMessage.system(DistillPrompts.LIFT_SYSTEM),
                LlmMessage.user(DistillPrompts.liftMessage(parent, members)))))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("Lifting for '${parent.ifEmpty { "(root)" }}' failed: ${e.message ?: e.javaClass.simpleName}")
            return null
        }
        return DistillPrompts.parseLift(completion.content)
    }

    private suspend fun resolveAnchor(anchor: Anchor): Anchor? = try {
        when (val state = resolver.resolve(anchor)) {
            is AnchorState.Present -> anchor.copy(fingerprint = state.fingerprint)
            is AnchorState.Moved -> anchor.copy(ref = state.newRef, fingerprint = state.fingerprint)
            AnchorState.Gone -> null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun evidenceText(anchors: List<Anchor>): String? {
        val excerpts = try {
            evidence.evidence(anchors, limits.evidenceChars)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("Could not read evidence for ${anchors.joinToString { it.ref }}: ${e.message ?: e.javaClass.simpleName}")
            emptyList()
        }
        return DistillPrompts.renderEvidence(excerpts.filter { it.text.isNotBlank() }, limits.evidenceChars).ifEmpty { null }
    }

    companion object {
        const val ACTOR = "organizer"
        /** Tag of claims lifted from child areas. */
        const val LIFTED_TAG = "lifted"
        private const val MAX_MERGED = 8
        private const val NO_EVIDENCE = "(no evidence could be read)"
    }
}

private fun fmt(p: Double?): String = p?.let { "p=" + "%.2f".format(java.util.Locale.ROOT, it) } ?: "no answer"
