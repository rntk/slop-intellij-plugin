package dev.insight.knowledge

import java.security.MessageDigest

/** How broad a claim is: one symbol, one file, a module (directory) or a flow across several. */
enum class ClaimLevel { SYMBOL, FILE, MODULE, FLOW }

/** Who wrote a claim. HUMAN claims can only leave the live set through the user (see [RatchetGuard]). */
enum class ClaimOrigin { HUMAN, GENERATED }

/** What an [Anchor] points at. */
enum class AnchorKind { SYMBOL, FILE, DIRECTORY, CALL_EDGE, ENTRYPOINT }

/**
 * A code location a claim is about. [ref] is a project-relative path (FILE, DIRECTORY), an FQN (SYMBOL, ENTRYPOINT) or
 * `callerFqn->calleeFqn` (CALL_EDGE). [fingerprint] is an opaque content hash set by the IDE adapter, used to detect drift.
 */
data class Anchor(val kind: AnchorKind, val ref: String, val fingerprint: String? = null)

/** Where a claim was read from: a project-relative [path] and an optional one-based inclusive line range. */
data class Citation(val path: String, val startLine: Int? = null, val endLine: Int? = null, val note: String? = null)

/** Lifecycle of a claim. [Live] and [Suspect] claims are active; the others have left the live set but are kept. */
sealed interface ClaimStatus {
    data object Live : ClaimStatus
    /** Possibly stale or contradicted; still active, awaiting [KnowledgeOp.Confirm], a rewrite or a retirement. */
    data class Suspect(val reason: String) : ClaimStatus
    data class Retired(val reason: String, val evidence: RetireEvidence) : ClaimStatus
    /** Replaced by the claims [by] through a rewrite, merge or split. */
    data class Superseded(val by: List<String>) : ClaimStatus
}

/** Why a claim may be retired. */
sealed interface RetireEvidence {
    /** Every listed anchor ref was verified gone by the caller. */
    data class AnchorsGone(val refs: List<String>) : RetireEvidence
    /** The code contradicts the claim with [confidence] in 0..1. */
    data class Contradicted(val confidence: Double, val explanation: String) : RetireEvidence {
        init { require(confidence in 0.0..1.0) { "confidence must be in 0..1" } }
    }
    /** The user retired it; only accepted from actor `user`. */
    data class ByUser(val note: String) : RetireEvidence
}

/**
 * One atomic piece of codebase knowledge. [area] is a project-relative path scope (`""` is the root). [tags] are free
 * labels such as `detail` or `volatile`; [stability] is an optional 0..1 estimate of how rarely it changes; [source] is
 * where it was imported from, such as `flows/diff-walkthrough.md`. Claims are never deleted, only change [status].
 */
data class Claim(
    val id: String,
    val text: String,
    val level: ClaimLevel,
    val origin: ClaimOrigin,
    val area: String,
    val anchors: List<Anchor>,
    val citations: List<Citation> = emptyList(),
    val tags: Set<String> = emptySet(),
    val stability: Double? = null,
    val status: ClaimStatus = ClaimStatus.Live,
    val source: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Claim id must not be blank" }
        require(text.isNotBlank()) { "Claim text must not be blank" }
    }

    /** Live or Suspect: part of the knowledge base proper. */
    val isActive: Boolean get() = status is ClaimStatus.Live || status is ClaimStatus.Suspect
}

/** Claim text compared for exact duplicates: trimmed, whitespace collapsed, lower case, without a final period. */
fun normalizeClaimText(text: String): String = text.trim().replace(WHITESPACE, " ").lowercase().removeSuffix(".").trimEnd()

private val WHITESPACE = Regex("\\s+")

/**
 * Claim ids: `c-` and 12 hex digits of SHA-256 over the [area], the normalized text and a salt. The same text in the same
 * area yields the same id for the same salt, so ids are reproducible across runs; [fresh] raises the salt until the id is
 * unused, so a re-added text (say after a retirement) gets a new id.
 */
object ClaimIds {
    fun of(text: String, area: String, salt: Int = 0): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$area\u0000${normalizeClaimText(text)}\u0000$salt".toByteArray())
        return "c-" + digest.take(6).joinToString("") { "%02x".format(it) }
    }

    fun fresh(text: String, area: String, taken: (String) -> Boolean): String =
        generateSequence(0) { it + 1 }.map { of(text, area, it) }.first { !taken(it) }
}

/** What became of an anchor in the current code. */
sealed interface AnchorState {
    data class Present(val fingerprint: String?) : AnchorState
    data class Moved(val newRef: String, val fingerprint: String?) : AnchorState
    data object Gone : AnchorState
}

/** Port resolving anchors against the code; the IDE adapter implements it with PSI and VFS reads. */
fun interface AnchorResolver {
    suspend fun resolve(anchor: Anchor): AnchorState
}
