package dev.insight.knowledge

/**
 * A proposed change to the knowledge base. New claims (in [Add], [Rewrite], [Merge], [Split]) must have unused ids and
 * status Live. Sources must be active. Ops only change statuses and anchors of existing claims, never delete them.
 */
sealed interface KnowledgeOp {
    data class Add(val claim: Claim) : KnowledgeOp
    /** Replaces [fromId] with [to], meaning-equivalent; the source becomes Superseded. */
    data class Rewrite(val fromId: String, val to: Claim) : KnowledgeOp
    /** Replaces two or more [fromIds] with [to], which must imply each of them. */
    data class Merge(val fromIds: List<String>, val to: Claim) : KnowledgeOp
    /** Replaces [fromId] with the parts [to], which together must imply it. */
    data class Split(val fromId: String, val to: List<Claim>) : KnowledgeOp
    data class Retire(val id: String, val reason: String, val evidence: RetireEvidence) : KnowledgeOp
    /** Makes an active claim Suspect (or updates its reason). */
    data class MarkSuspect(val id: String, val reason: String) : KnowledgeOp
    /** Makes an active claim Live with [anchors] (refreshed fingerprints) replacing its anchors. */
    data class Confirm(val id: String, val anchors: List<Anchor>) : KnowledgeOp
    /** Replaces the anchors of an active claim, keeping its status. */
    data class Reanchor(val id: String, val anchors: List<Anchor>) : KnowledgeOp
    /** Records that two active claims conflict: both become Suspect, neither is dropped. */
    data class Contradict(val aId: String, val bId: String, val reason: String) : KnowledgeOp
    /** Makes a Retired or Superseded claim Live again; user only. */
    data class Restore(val id: String) : KnowledgeOp
}

/** Ids an op explicitly takes out of the live set; any other active claim must stay active. */
val KnowledgeOp.removedIds: Set<String>
    get() = when (this) {
        is KnowledgeOp.Retire -> setOf(id)
        is KnowledgeOp.Rewrite -> setOf(fromId)
        is KnowledgeOp.Merge -> fromIds.toSet()
        is KnowledgeOp.Split -> setOf(fromId)
        else -> emptySet()
    }

/** The guard's decision on an op. */
sealed interface Verdict {
    data class Accepted(val notes: List<String> = emptyList()) : Verdict
    data class Rejected(val reason: String) : Verdict
}

val Verdict.accepted: Boolean get() = this is Verdict.Accepted

/**
 * One ledger line: op number [seq] (from 1, gapless), the [op], its [actor] (`user`, `worker:<area>`, `organizer`,
 * `drift`), the time [at] in epoch ms and the [verdict]. Rejected ops are recorded too but never applied.
 */
data class LedgerEntry(val seq: Long, val op: KnowledgeOp, val actor: String, val at: Long, val verdict: Verdict)

/** An op cannot apply to a state, such as an unknown id or a source that is not active. */
class KnowledgeOpException(message: String) : IllegalArgumentException(message)

/** Immutable snapshot of every claim ever accepted, by id in insertion order. */
data class KnowledgeState(val claims: Map<String, Claim> = emptyMap()) {
    operator fun get(id: String): Claim? = claims[id]

    /** Live and Suspect claims. */
    fun active(): List<Claim> = claims.values.filter { it.isActive }

    /** Active claims grouped by area. */
    fun byArea(): Map<String, List<Claim>> = active().groupBy { it.area }

    /** Active claims of exactly [area], or also of areas below it when [nested] (the root holds everything). */
    fun inArea(area: String, nested: Boolean = false): List<Claim> = active().filter {
        it.area == area || nested && (area.isEmpty() || it.area.startsWith(area.trimEnd('/') + "/"))
    }

    /** An unused id for a claim with [text] in [area]. */
    fun newId(text: String, area: String): String = ClaimIds.fresh(text, area) { it in claims }

    /** The state after [op]; throws [KnowledgeOpException] when it cannot apply. Does not check permissions or meaning. */
    fun apply(op: KnowledgeOp): KnowledgeState {
        val next = LinkedHashMap(claims)
        fun active(id: String): Claim {
            val claim = next[id] ?: throw KnowledgeOpException("Unknown claim id '$id'")
            if (!claim.isActive) throw KnowledgeOpException("Claim $id is ${statusName(claim.status)}, not active")
            return claim
        }
        fun add(claim: Claim) {
            if (claim.id in next) throw KnowledgeOpException("Claim id ${claim.id} is already used")
            if (claim.status != ClaimStatus.Live) throw KnowledgeOpException("New claim ${claim.id} must be Live")
            next[claim.id] = claim
        }
        fun set(claim: Claim, status: ClaimStatus) { next[claim.id] = claim.copy(status = status) }
        when (op) {
            is KnowledgeOp.Add -> add(op.claim)
            is KnowledgeOp.Rewrite -> { val from = active(op.fromId); add(op.to); set(from, ClaimStatus.Superseded(listOf(op.to.id))) }
            is KnowledgeOp.Merge -> {
                if (op.fromIds.distinct().size != op.fromIds.size || op.fromIds.size < 2)
                    throw KnowledgeOpException("A merge needs at least two distinct source claims")
                val sources = op.fromIds.map(::active)
                add(op.to)
                sources.forEach { set(it, ClaimStatus.Superseded(listOf(op.to.id))) }
            }
            is KnowledgeOp.Split -> {
                if (op.to.isEmpty()) throw KnowledgeOpException("A split needs at least one part")
                val from = active(op.fromId)
                op.to.forEach(::add)
                set(from, ClaimStatus.Superseded(op.to.map { it.id }))
            }
            is KnowledgeOp.Retire -> set(active(op.id), ClaimStatus.Retired(op.reason, op.evidence))
            is KnowledgeOp.MarkSuspect -> set(active(op.id), ClaimStatus.Suspect(op.reason))
            is KnowledgeOp.Confirm -> next[op.id] = active(op.id).copy(status = ClaimStatus.Live, anchors = op.anchors)
            is KnowledgeOp.Reanchor -> next[op.id] = active(op.id).copy(anchors = op.anchors)
            is KnowledgeOp.Contradict -> {
                if (op.aId == op.bId) throw KnowledgeOpException("A claim cannot contradict itself")
                val a = active(op.aId)
                val b = active(op.bId)
                set(a, ClaimStatus.Suspect("Contradicts ${b.id}: ${op.reason}"))
                set(b, ClaimStatus.Suspect("Contradicts ${a.id}: ${op.reason}"))
            }
            is KnowledgeOp.Restore -> {
                val claim = next[op.id] ?: throw KnowledgeOpException("Unknown claim id '${op.id}'")
                if (claim.isActive) throw KnowledgeOpException("Claim ${op.id} is already active")
                set(claim, ClaimStatus.Live)
            }
        }
        return KnowledgeState(next)
    }

    companion object {
        val EMPTY = KnowledgeState()

        /**
         * Rebuilds the state from [entries] in seq order, applying accepted entries only. An accepted entry that no longer
         * applies (a hand-edited ledger) is skipped, so the result is the same for the same entries.
         */
        fun replay(entries: List<LedgerEntry>): KnowledgeState = entries.sortedBy { it.seq }
            .filter { it.verdict.accepted }
            .fold(EMPTY) { state, entry -> try { state.apply(entry.op) } catch (_: KnowledgeOpException) { state } }
    }
}

/**
 * The ratchet invariant between [before] and [after] applying [op]: returns a violation, or null when it holds. No claim
 * may disappear; an existing claim keeps its id, text, area, level and origin; a claim active before stays active unless
 * [op] names it in [removedIds].
 */
fun checkRatchet(before: KnowledgeState, after: KnowledgeState, op: KnowledgeOp): String? {
    val removed = op.removedIds
    for ((id, old) in before.claims) {
        val new = after.claims[id] ?: return "Claim $id disappeared; claims are never deleted"
        if (new.text != old.text || new.area != old.area || new.level != old.level || new.origin != old.origin)
            return "Claim $id changed its text or scope in place; use a rewrite"
        if (old.isActive && !new.isActive && id !in removed)
            return "Claim $id became ${statusName(new.status)} without the op naming it"
    }
    return null
}

internal fun statusName(status: ClaimStatus): String = when (status) {
    ClaimStatus.Live -> "live"
    is ClaimStatus.Suspect -> "suspect"
    is ClaimStatus.Retired -> "retired"
    is ClaimStatus.Superseded -> "superseded"
}
