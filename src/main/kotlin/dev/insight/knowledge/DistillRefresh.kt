package dev.insight.knowledge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The refresh before distilling: [ops] to submit for anchors that moved, changed or are gone, and [current] anchors of
 * every claim that resolved (refreshed fingerprints and refs, gone anchors dropped), used when a claim is confirmed.
 */
internal data class DistillRefreshPlan(
    val ops: List<KnowledgeOp>,
    val current: Map<String, List<Anchor>>,
    val warnings: List<String>,
)

/**
 * Plans the cheap, IDE-only refresh of [claims]: every anchor is resolved with [resolver] (at most [parallelism] at once).
 * All anchors gone retires a GENERATED claim with [RetireEvidence.AnchorsGone] and marks a HUMAN one suspect; some gone
 * or a changed fingerprint marks it suspect; moved anchors are reanchored. Unchanged claims get no op.
 */
internal suspend fun planDistillRefresh(claims: List<Claim>, resolver: AnchorResolver, parallelism: Int): DistillRefreshPlan = coroutineScope {
    val semaphore = Semaphore(parallelism.coerceAtLeast(1))
    val resolved = claims.map { claim ->
        async {
            semaphore.withPermit {
                try {
                    claim to claim.anchors.map { resolver.resolve(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    claim to null
                }
            }
        }
    }.awaitAll()
    val ops = mutableListOf<KnowledgeOp>()
    val current = LinkedHashMap<String, List<Anchor>>()
    val warnings = mutableListOf<String>()
    for ((claim, states) in resolved) {
        if (states == null) {
            warnings += "Could not resolve the anchors of ${claim.id}; it was left unchanged."
            continue
        }
        ops += refreshOps(claim, states)
        current[claim.id] = claim.anchors.zip(states).mapNotNull { (anchor, state) ->
            when (state) {
                is AnchorState.Present -> anchor.copy(fingerprint = state.fingerprint ?: anchor.fingerprint)
                is AnchorState.Moved -> anchor.copy(ref = state.newRef, fingerprint = state.fingerprint)
                AnchorState.Gone -> null
            }
        }
    }
    DistillRefreshPlan(ops, current, warnings)
}

/** The ops one claim needs given its anchors' [states] (same order as its anchors). */
internal fun refreshOps(claim: Claim, states: List<AnchorState>): List<KnowledgeOp> {
    if (claim.anchors.isEmpty()) return emptyList()
    val pairs = claim.anchors.zip(states)
    val gone = pairs.filter { it.second == AnchorState.Gone }.map { it.first.ref }
    if (gone.size == claim.anchors.size) return listOf(
        if (claim.origin == ClaimOrigin.GENERATED) KnowledgeOp.Retire(claim.id, "All anchors are gone", RetireEvidence.AnchorsGone(gone))
        else KnowledgeOp.MarkSuspect(claim.id, "All anchors are gone: ${gone.joinToString()}"),
    )
    fun changed(old: String?, new: String?) = old != null && new != null && old != new
    val changedRefs = pairs.mapNotNull { (anchor, state) ->
        when (state) {
            is AnchorState.Present -> anchor.ref.takeIf { changed(anchor.fingerprint, state.fingerprint) }
            is AnchorState.Moved -> anchor.ref.takeIf { changed(anchor.fingerprint, state.fingerprint) }
            AnchorState.Gone -> null
        }
    }
    val ops = mutableListOf<KnowledgeOp>()
    if (pairs.any { it.second is AnchorState.Moved }) {
        // Moved anchors take their new ref; their old fingerprint stays so a changed body is still noticed until confirmed.
        ops += KnowledgeOp.Reanchor(claim.id, pairs.map { (anchor, state) ->
            if (state is AnchorState.Moved) anchor.copy(ref = state.newRef, fingerprint = anchor.fingerprint ?: state.fingerprint) else anchor
        })
    }
    val reasons = listOfNotNull(
        gone.takeIf { it.isNotEmpty() }?.let { "anchor gone: ${it.joinToString()}" },
        changedRefs.takeIf { it.isNotEmpty() }?.let { "anchor changed: ${it.joinToString()}" },
    )
    if (reasons.isNotEmpty()) {
        val reason = reasons.joinToString("; ").replaceFirstChar(Char::uppercase)
        if ((claim.status as? ClaimStatus.Suspect)?.reason != reason) ops += KnowledgeOp.MarkSuspect(claim.id, reason)
    }
    return ops
}
