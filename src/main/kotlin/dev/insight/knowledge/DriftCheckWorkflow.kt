package dev.insight.knowledge

import com.intellij.openapi.diagnostic.logger
import dev.insight.decisions.DecisionFn
import dev.insight.llm.CompletionFn
import dev.insight.llm.completeJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val LOG = logger<DriftCheckWorkflow>()

/** A hand-written doc to check: its project-relative [path] and current [text]. */
data class DocInput(val path: String, val text: String)

/**
 * Limits of one drift check. [parallelism] bounds concurrent anchoring, resolving, evidence reads and model requests;
 * [maxClaimsVerified] claims are asked of decisions per run and [maxEvidenceChars] characters of evidence are read in all
 * ([evidenceCharsPerClaim] each); claims beyond either are left unverified and reported as skipped. A statement is accurate
 * at probability [accurateAt] and is too detailed when decisions pick a non-durable level with [detailConfidence].
 */
data class DriftLimits(
    val parallelism: Int = 4,
    val maxClaimsVerified: Int = 150,
    val evidenceCharsPerClaim: Int = 6_000,
    val maxEvidenceChars: Int = 600_000,
    val accurateAt: Double = 0.6,
    val detailConfidence: Double = 0.7,
    /** Docs for which a patch is requested from [CompletionFn]; the rest are reported without suggestions. */
    val maxPatchDocs: Int = 20,
    val patchDocChars: Int = 24_000,
    val patchEvidenceChars: Int = 2_000,
    val patchMaxTokens: Int = 4_000,
) {
    init {
        require(parallelism >= 1 && maxClaimsVerified >= 0 && evidenceCharsPerClaim > 0 && maxEvidenceChars >= 0)
        require(accurateAt in 0.0..1.0 && detailConfidence in 0.0..1.0)
        require(maxPatchDocs >= 0 && patchDocChars > 0 && patchEvidenceChars >= 0 && patchMaxTokens > 0)
    }
}

/**
 * Keeps hand-written docs (such as flow docs under `flows/`) in sync with the code without ever editing them.
 *
 * Doc sync: every statement of a doc becomes a HUMAN claim (level FLOW, area from [flowAreaOf], source the doc path, anchors
 * from [anchorer] with fingerprints from [resolver]); running again adds nothing. Sentences that vanished from a doc are
 * retired, and an edited sentence is a removed one plus a new one. Doc sync is the one place this workflow acts as
 * [RatchetGuard.USER]: the human edited the doc, and HUMAN claims may only leave the live set through the user, so the doc
 * edit is the user's retirement (`ByUser("removed from <doc>")`). Everything else is actor `drift`, which can only mark
 * suspect, confirm and reanchor.
 *
 * Check: per active claim of the docs, anchors are resolved. A gone anchor is a [DriftKind.BROKEN_ANCHOR] finding and
 * MarkSuspect; a moved one is reanchored. A claim whose fingerprint changed, that was never confirmed, or that is suspect is
 * verified with [decide] against [evidence]: inaccurate is [DriftKind.STALE] (MarkSuspect), accurate is [DriftKind.OK]
 * (Confirm with refreshed fingerprints), and accurate but an implementation detail or volatile value is
 * [DriftKind.TOO_DETAILED] (finding only, no op, so it shows again until the doc changes). Existing claims without anchors
 * are tried again and reported as [DriftKind.UNANCHORED] if they still have none. Without [decide] only anchor-based
 * findings are made and a warning says so.
 *
 * With [complete], each doc with stale or broken findings gets suggested replacements ([DocPatch]) in the report. Failures
 * become warnings. Cancellation propagates; findings already delivered to `onFinding` and ops already submitted stay.
 */
class DriftCheckWorkflow(
    private val base: KnowledgeBase,
    private val anchorer: ClaimAnchorer,
    private val resolver: AnchorResolver,
    private val evidence: EvidenceProvider,
    private val decide: DecisionFn?,
    private val complete: CompletionFn?,
    private val limits: DriftLimits = DriftLimits(),
) {
    suspend fun check(
        docs: List<DocInput>,
        progress: KnowledgeProgress,
        onFinding: (DriftFinding) -> Unit = {},
    ): DriftReport = Run(docs, progress, onFinding).execute()

    /** A statement of a doc and the claim that stands for it. */
    private class Tracked(val claimId: String, val doc: DocInput, val statement: DocClaim, val newlyAdded: Boolean = false)

    private inner class Run(
        private val docs: List<DocInput>,
        private val progress: KnowledgeProgress,
        private val onFinding: (DriftFinding) -> Unit,
    ) {
        private val gate = Semaphore(limits.parallelism)
        private val findings = mutableListOf<DriftFinding>()
        private val warnings = linkedSetOf<String>()
        private val failures = linkedMapOf<String, Pair<Int, String>>()
        private val evidenceByClaim = ConcurrentHashMap<String, String>()
        private val added = AtomicInteger()
        private val retired = AtomicInteger()
        private val reanchored = AtomicInteger()
        private val verified = AtomicInteger()
        private val skippedByLimit = AtomicInteger()
        private val skippedNoEvidence = AtomicInteger()
        private val unchanged = AtomicInteger()
        private val evidenceRemaining = AtomicInteger(limits.maxEvidenceChars)
        private val confirmedBefore: Set<String> = base.entries()
            .filter { it.verdict.accepted && it.op is KnowledgeOp.Confirm }.map { (it.op as KnowledgeOp.Confirm).id }.toSet()

        suspend fun execute(): DriftReport {
            val tracked = mutableListOf<Tracked>()
            docs.forEachIndexed { index, doc ->
                progress.report("Syncing docs", index, docs.size)
                tracked += syncDoc(doc)
            }
            progress.report("Syncing docs", docs.size, docs.size)
            if (decide == null) warn("Decisions are not configured: only anchors were checked, so statements that no longer match the code were not detected.")

            var done = 0
            progress.report("Checking statements", 0, tracked.size)
            coroutineScope {
                for (item in tracked) launch {
                    gate.withPermit { guarded("Checking a statement") { checkClaim(item) } }
                    progress.report("Checking statements", synchronized(this@Run) { ++done }, tracked.size)
                }
            }
            val patches = proposePatches()
            return report(tracked.size, patches)
        }

        // ---- doc sync -------------------------------------------------------------------------------------------

        private suspend fun syncDoc(doc: DocInput): List<Tracked> {
            val parsed = parseDocClaims(doc.text).distinctBy { normalizeClaimText(it.text) }
            val area = flowAreaOf(doc.path)
            val before = base.snapshot()
            val ours = before.claims.values.filter { it.origin == ClaimOrigin.HUMAN && it.source == doc.path }
            val activeByKey = ours.filter { it.isActive }.groupBy { normalizeClaimText(it.text) }
            // A claim the user retired or replaced themselves stays gone while the sentence is in the doc; only a retirement
            // caused by removing the sentence from the doc is undone by writing it back.
            val deliberate = ours.filter { claim ->
                when (val status = claim.status) {
                    is ClaimStatus.Retired -> !status.reason.startsWith(REMOVED_PREFIX)
                    is ClaimStatus.Superseded -> true
                    else -> false
                }
            }.map { normalizeClaimText(it.text) }.toSet()
            val keys = parsed.map { normalizeClaimText(it.text) }.toSet()

            for ((key, claims) in activeByKey) if (key !in keys) for (claim in claims) {
                val note = "$REMOVED_PREFIX${doc.path}"
                if (submit(KnowledgeOp.Retire(claim.id, note, RetireEvidence.ByUser(note)), RatchetGuard.USER, "Retiring '${claim.text.take(60)}'"))
                    retired.incrementAndGet()
            }

            val tracked = mutableListOf<Tracked>()
            val toAdd = mutableListOf<DocClaim>()
            for (statement in parsed) {
                val key = normalizeClaimText(statement.text)
                val have = activeByKey[key]?.firstOrNull()
                when {
                    have != null -> tracked += Tracked(have.id, doc, statement)
                    key in deliberate -> Unit
                    else -> {
                        val twin = before.inArea(area).firstOrNull { normalizeClaimText(it.text) == key }
                        if (twin != null) warn("${doc.path}:${statement.startLine} repeats a claim already held from ${twin.source ?: "elsewhere"}; not added again.")
                        else toAdd += statement
                    }
                }
            }
            if (toAdd.isEmpty()) return tracked

            val prepared = coroutineScope {
                toAdd.map { statement -> async { gate.withPermit { statement to guarded("Anchoring") { anchorsFor(statement, doc.path) } } } }.awaitAll()
            }
            for ((statement, anchors) in prepared) {
                val claim = Claim(
                    id = base.snapshot().newId(statement.text, area),
                    text = statement.text,
                    level = ClaimLevel.FLOW,
                    origin = ClaimOrigin.HUMAN,
                    area = area,
                    anchors = anchors.orEmpty(),
                    citations = listOf(Citation(doc.path, statement.startLine, statement.endLine)),
                    tags = if (statement.kind == DocClaimKind.ENTRY_POINT) setOf(TAG_DOC, TAG_ENTRY_POINT) else setOf(TAG_DOC),
                    source = doc.path,
                )
                if (submit(KnowledgeOp.Add(claim), RatchetGuard.USER, "Adding ${doc.path}:${statement.startLine}")) {
                    added.incrementAndGet()
                    tracked += Tracked(claim.id, doc, statement, newlyAdded = true)
                }
            }
            return tracked
        }

        private suspend fun anchorsFor(statement: DocClaim, docPath: String): List<Anchor> =
            anchorer.anchors(statement.text, docPath).distinct().map { anchor ->
                when (val state = resolver.resolve(anchor)) {
                    is AnchorState.Present -> state.fingerprint?.let { anchor.copy(fingerprint = it) } ?: anchor
                    is AnchorState.Moved -> anchor.copy(ref = state.newRef, fingerprint = state.fingerprint ?: anchor.fingerprint)
                    AnchorState.Gone -> anchor
                }
            }

        // ---- check ----------------------------------------------------------------------------------------------

        private suspend fun checkClaim(item: Tracked) {
            var claim = base.snapshot()[item.claimId]?.takeIf { it.isActive } ?: return
            var newlyAnchored = false
            if (claim.anchors.isEmpty() && !item.newlyAdded) {
                val retry = guarded("Anchoring") { anchorsFor(item.statement, item.doc.path) }.orEmpty()
                if (retry.isNotEmpty() && submit(KnowledgeOp.Reanchor(claim.id, retry), ACTOR, "Reanchoring ${claim.id}")) {
                    reanchored.incrementAndGet()
                    newlyAnchored = true
                    claim = claim.copy(anchors = retry)
                }
            }
            if (claim.anchors.isEmpty()) {
                emit(item, claim, DriftKind.UNANCHORED, "The statement names no code, so it cannot be checked automatically.")
                return
            }
            val resolved = claim.anchors.map { it to resolver.resolve(it) }
            val gone = resolved.filter { it.second == AnchorState.Gone }.map { it.first }
            val moved = resolved.any { it.second is AnchorState.Moved }
            var anchors = resolved.map { (anchor, state) ->
                if (state is AnchorState.Moved) anchor.copy(ref = state.newRef) else anchor
            }
            if (moved) {
                if (submit(KnowledgeOp.Reanchor(claim.id, anchors), ACTOR, "Reanchoring ${claim.id}")) reanchored.incrementAndGet()
            }
            if (gone.isNotEmpty()) {
                val reason = "Anchor no longer exists: " + gone.joinToString(", ") { it.ref }
                if ((claim.status as? ClaimStatus.Suspect)?.reason != reason) submit(KnowledgeOp.MarkSuspect(claim.id, reason), ACTOR, "Marking ${claim.id} suspect")
                emit(item, claim.copy(anchors = anchors), DriftKind.BROKEN_ANCHOR, reason)
                return
            }
            val current = resolved.map { (anchor, state) ->
                when (state) {
                    is AnchorState.Present -> state.fingerprint
                    is AnchorState.Moved -> state.fingerprint
                    AnchorState.Gone -> null
                } to anchor.fingerprint
            }
            val changed = current.any { (now, was) -> now != null && now != was }
            val needsVerification = newlyAnchored || changed || claim.id !in confirmedBefore || claim.status is ClaimStatus.Suspect
            if (!needsVerification) { unchanged.incrementAndGet(); return }
            val decide = decide ?: return
            anchors = anchors.zip(current) { anchor, (now, _) -> if (now != null) anchor.copy(fingerprint = now) else anchor }
            verify(item, claim, anchors, decide)
        }

        private fun reserveEvidence(): Int {
            while (true) {
                val remaining = evidenceRemaining.get()
                if (remaining <= 0) return 0
                val budget = minOf(limits.evidenceCharsPerClaim, remaining)
                if (evidenceRemaining.compareAndSet(remaining, remaining - budget)) return budget
            }
        }

        private suspend fun verify(item: Tracked, claim: Claim, refreshed: List<Anchor>, decide: DecisionFn) {
            if (verified.incrementAndGet() > limits.maxClaimsVerified) { verified.decrementAndGet(); skippedByLimit.incrementAndGet(); return }
            val budget = reserveEvidence()
            if (budget == 0) { verified.decrementAndGet(); skippedByLimit.incrementAndGet(); return }
            var used = 0
            val found = try {
                evidence.evidence(refreshed, budget).also { excerpts -> used = excerpts.sumOf { it.text.length } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                verified.decrementAndGet()
                fail("Reading evidence", e)
                return
            } finally {
                evidenceRemaining.addAndGet(budget - used)
            }
            if (used == 0) { verified.decrementAndGet(); skippedNoEvidence.incrementAndGet(); return }
            evidenceByClaim[claim.id] = found.joinToString(" | ") { "${it.ref}: ${it.text}" }.take(limits.patchEvidenceChars)

            val response = try {
                decide(driftVerifyRequest(item.doc.path, item.statement.section, claim.text, found))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Verification", e)
                return
            }
            val verdict = readDriftVerdict(response, limits.detailConfidence)
            val p = verdict.accurate
            if (p == null) { fail("Verification", IllegalStateException("decisions gave no answer to the accuracy question")); return }
            val shown = "%.2f".format(java.util.Locale.ROOT, p)
            when {
                p < limits.accurateAt -> {
                    val reason = "The code no longer supports this statement (accuracy $shown)." +
                        if (verdict.tooDetailed) " It is also ${detailWords(verdict.detailKind)}." else ""
                    if ((claim.status as? ClaimStatus.Suspect)?.reason != reason) submit(KnowledgeOp.MarkSuspect(claim.id, reason), ACTOR, "Marking ${claim.id} suspect")
                    emit(item, claim.copy(anchors = refreshed), DriftKind.STALE, reason)
                }
                verdict.tooDetailed -> emit(item, claim.copy(anchors = refreshed), DriftKind.TOO_DETAILED,
                    "Accurate (accuracy $shown), but ${detailWords(verdict.detailKind)}; flow docs should stay abstract and avoid such details.")
                else -> {
                    submit(KnowledgeOp.Confirm(claim.id, refreshed), ACTOR, "Confirming ${claim.id}")
                    emit(item, claim.copy(anchors = refreshed), DriftKind.OK, "Accurate (accuracy $shown).")
                }
            }
        }

        private fun detailWords(kind: String?) = if (kind == DETAIL_VOLATILE) "a frequently changing value" else "an implementation detail"

        private fun emit(item: Tracked, claim: Claim, kind: DriftKind, reason: String) {
            val finding = DriftFinding(kind, claim.id, item.doc.path, item.statement.startLine, item.statement.endLine,
                claim.text, claim.anchors, reason, item.statement.section)
            synchronized(findings) {
                findings += finding
                try {
                    onFinding(finding)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("Drift finding listener failed", e)
                }
            }
        }

        // ---- patches --------------------------------------------------------------------------------------------

        private suspend fun proposePatches(): List<DocPatch> {
            val complete = complete ?: return emptyList()
            val needy = docs.mapNotNull { doc ->
                val flagged = synchronized(findings) { findings.filter { it.doc == doc.path && (it.kind == DriftKind.STALE || it.kind == DriftKind.BROKEN_ANCHOR) } }
                if (flagged.isEmpty()) null else doc to flagged
            }
            if (needy.isEmpty()) return emptyList()
            if (needy.size > limits.maxPatchDocs) warn("Suggested edits were made for ${limits.maxPatchDocs} of ${needy.size} docs with drift (limit).")
            val chosen = needy.take(limits.maxPatchDocs)
            var done = 0
            progress.report("Proposing edits", 0, chosen.size)
            val results = coroutineScope {
                chosen.map { (doc, flagged) ->
                    async {
                        val patches = gate.withPermit { guarded("Proposing edits") { patchesFor(complete, doc, flagged) } }.orEmpty()
                        progress.report("Proposing edits", synchronized(this@Run) { ++done }, chosen.size)
                        patches
                    }
                }.awaitAll()
            }
            return results.flatten()
        }

        private suspend fun patchesFor(complete: CompletionFn, doc: DocInput, flagged: List<DriftFinding>): List<DocPatch> {
            val user = driftPatchUserMessage(doc.path, doc.text, flagged, evidenceByClaim, limits.patchDocChars)
            val answer = completeJson(complete, PATCH_SYSTEM_PROMPT, user, PATCH_SCHEMA, limits.patchMaxTokens, attempts = 2)
            val parsed = parseDocPatches(answer, doc.path, doc.text.lines(), flagged.map { it.startLine..it.endLine })
            if (parsed.problems.isNotEmpty())
                warn("Dropped ${parsed.problems.size} suggested edits for ${doc.path}: ${parsed.problems.take(3).joinToString("; ")}")
            return parsed.patches
        }

        // ---- plumbing -------------------------------------------------------------------------------------------

        private suspend fun submit(op: KnowledgeOp, actor: String, what: String): Boolean = try {
            val entry = base.submit(op, actor)
            (entry.verdict as? Verdict.Rejected)?.let { warn("$what was rejected: ${it.reason}") }
            entry.verdict.accepted
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(what, e)
            false
        }

        /** Runs [block]; a failure other than cancellation is recorded and yields null. */
        private suspend fun <T> guarded(what: String, block: suspend () -> T): T? = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(what, e)
            null
        }

        private fun fail(what: String, e: Throwable) {
            LOG.warn("Doc drift check: $what failed", e)
            synchronized(failures) {
                val (count, _) = failures[what] ?: (0 to "")
                failures[what] = (count + 1) to (e.message ?: e.javaClass.simpleName)
            }
        }

        private fun warn(message: String) { synchronized(warnings) { warnings += message } }

        private fun report(claims: Int, patches: List<DocPatch>): DriftReport {
            val order = docs.withIndex().associate { it.value.path to it.index }
            val sorted = synchronized(findings) { findings.toList() }
                .sortedWith(compareBy({ order[it.doc] ?: Int.MAX_VALUE }, { it.startLine }, { it.kind.ordinal }))
            val notes = buildList {
                addAll(synchronized(warnings) { warnings.toList() })
                synchronized(failures) { failures.forEach { (what, v) -> add("$what failed for ${v.first} ${if (v.first == 1) "item" else "items"} (last: ${v.second}).") } }
                if (skippedByLimit.get() > 0) add("${skippedByLimit.get()} statements were not verified because a run limit was reached (${limits.maxClaimsVerified} verifications, ${limits.maxEvidenceChars} evidence characters).")
                if (skippedNoEvidence.get() > 0) add("${skippedNoEvidence.get()} statements were not verified because no evidence could be read for their anchors.")
            }
            val counts = DriftCounts(
                docs = docs.size, claims = claims, added = added.get(), retired = retired.get(), reanchored = reanchored.get(),
                verified = verified.get(), skipped = skippedByLimit.get() + skippedNoEvidence.get(), unchanged = unchanged.get(),
                byKind = sorted.groupingBy { it.kind }.eachCount(),
            )
            return DriftReport(sorted, patches.sortedWith(compareBy({ order[it.doc] ?: Int.MAX_VALUE }, { it.startLine })), notes, counts)
        }
    }

    companion object {
        /** The actor of every check op except doc sync. */
        const val ACTOR = "drift"
        const val TAG_DOC = "doc"
        const val TAG_ENTRY_POINT = "entry-point"
        internal const val REMOVED_PREFIX = "removed from "
    }
}
