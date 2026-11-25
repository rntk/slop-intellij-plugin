package dev.insight.knowledge

import com.google.gson.JsonObject
import dev.insight.decisions.DecisionAnswer
import dev.insight.decisions.DecisionFn
import dev.insight.decisions.DecisionQuestion
import dev.insight.decisions.DecisionRequest
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * How suitable a claim is for generated docs. [detail] claims describe implementation and [volatile] ones mention values that
 * change often (counts, limits, defaults, versions); both stay out of docs. [stability] is 0..1, [confidence] 0..1.
 * [decided] is true when the Decisions API produced the answer, false for the heuristic. Never written to the ledger.
 */
data class ClaimClass(
    val detail: Boolean,
    val volatile: Boolean,
    val stability: Double,
    val confidence: Double,
    val decided: Boolean = false,
)

/** Storage for classifications, keyed by [ClaimClassifier.cacheKey]. */
interface ClaimClassCache {
    fun get(key: String): ClaimClass?
    fun put(key: String, value: ClaimClass)
}

class InMemoryClaimClassCache : ClaimClassCache {
    private val map = ConcurrentHashMap<String, ClaimClass>()
    override fun get(key: String): ClaimClass? = map[key]
    override fun put(key: String, value: ClaimClass) { map[key] = value }
    val size: Int get() = map.size
}

/**
 * Classifies claims as detail, volatile and stable. With [decide] it asks the Decisions API in batches; without it, or for
 * claims the API did not answer, it falls back to [heuristic]. Only decided results are cached, by claim id and normalized
 * text, so edited claims are re-asked and heuristic fallbacks are retried later.
 */
class ClaimClassifier(
    private val decide: DecisionFn?,
    private val cache: ClaimClassCache = InMemoryClaimClassCache(),
    private val batchSize: Int = 8,
) {
    init { require(batchSize > 0) { "batchSize must be positive" } }

    suspend fun classify(claims: List<Claim>): Map<String, ClaimClass> {
        val result = LinkedHashMap<String, ClaimClass>()
        val pending = ArrayList<Claim>()
        for (claim in claims) {
            if (claim.id in result) continue
            val cached = cache.get(cacheKey(claim))
            if (cached != null) result[claim.id] = cached else pending += claim.also { result[claim.id] = heuristic(claim) }
        }
        val fn = decide ?: return result
        for (batch in pending.distinctBy { it.id }.chunked(batchSize)) {
            val answers = try {
                fn(request(batch)).answers
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            for (claim in batch) {
                val decided = fromAnswers(claim, answers) ?: continue
                result[claim.id] = decided
                cache.put(cacheKey(claim), decided)
            }
        }
        return result
    }

    private fun request(batch: List<Claim>): DecisionRequest {
        val state = JsonObject().apply {
            batch.forEach { claim ->
                add(claim.id, JsonObject().apply {
                    addProperty("text", claim.text)
                    addProperty("level", claim.level.name.lowercase())
                    addProperty("area", claim.area)
                })
            }
        }
        val questions = LinkedHashMap<String, DecisionQuestion>()
        for (claim in batch) {
            questions[key(DETAIL, claim)] = DecisionQuestion.Noul(
                "Is the claim `${claim.id}` an implementation detail, something a newcomer reading a high-level overview of the " +
                    "code does not need and that is tied to how one piece of code is written?")
            questions[key(VOLATILE, claim)] = DecisionQuestion.Noul(
                "Does the claim `${claim.id}` mention frequently changing values (counts, limits, defaults, version numbers)?")
            questions[key(STABILITY, claim)] = DecisionQuestion.Score(
                "How long will the claim `${claim.id}` stay true as the code evolves through small changes?",
                STABILITY_LEVELS.map { com.google.gson.JsonPrimitive(it) })
        }
        return DecisionRequest(state, questions)
    }

    private fun fromAnswers(claim: Claim, answers: Map<String, DecisionAnswer>): ClaimClass? {
        val detail = answers[key(DETAIL, claim)] as? DecisionAnswer.Noul ?: return null
        val volatile = answers[key(VOLATILE, claim)] as? DecisionAnswer.Noul ?: return null
        val stability = answers[key(STABILITY, claim)] as? DecisionAnswer.Score ?: return null
        val sureness = listOf(certainty(detail.noul), certainty(volatile.noul), stability.confidence.coerceIn(0.0, 1.0))
        return ClaimClass(
            detail = detail.noul >= 0.5,
            volatile = volatile.noul >= 0.5,
            stability = (stability.score / (STABILITY_LEVELS.size - 1)).coerceIn(0.0, 1.0),
            confidence = sureness.average(),
            decided = true,
        )
    }

    private fun certainty(noul: Double) = (kotlin.math.abs(noul - 0.5) * 2).coerceIn(0.0, 1.0)
    private fun key(kind: String, claim: Claim) = "$kind:${claim.id}"

    companion object {
        private const val DETAIL = "detail"
        private const val VOLATILE = "volatile"
        private const val STABILITY = "stability"
        private val STABILITY_LEVELS = listOf(
            "Changes with almost any edit nearby",
            "Likely to change within weeks",
            "Holds across several refactorings",
            "Holds until the feature is redesigned",
            "Holds for the life of the feature",
        )

        fun cacheKey(claim: Claim): String = claim.id + "\u0000" + normalizeClaimText(claim.text)

        private val NUMBER_UNIT = Regex(
            "\\b\\d+(?:[.,]\\d+)?\\s?(?:ms|s|sec|seconds?|minutes?|min|hours?|kb|mb|gb|kib|mib|px|%|chars?|characters|items|lines|tokens|files|times|retries|threads|entries|bytes)\\b",
            RegexOption.IGNORE_CASE)
        private val VERSION = Regex("\\bv?\\d+\\.\\d+(?:\\.\\d+)*\\b")
        private val CONSTANT = Regex("\\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\\b")
        private val LIMIT_WORDS = Regex("\\b(?:defaults? (?:to|of|is)|at most|at least|up to|limited to|capped at|maximum of|minimum of|timeout of)\\b", RegexOption.IGNORE_CASE)
        private val BARE_NUMBER = Regex("(?<![\\w`.-])\\d{2,}(?![\\w`])")
        private val BACKTICKED = Regex("`[^`]+`")

        /** Rule-based classification used without Decisions: numbers, units, constants, backticked identifiers, tags, level. */
        fun heuristic(claim: Claim): ClaimClass {
            val text = claim.text
            val tagged = claim.tags.map { it.lowercase() }
            val volatileTag = "volatile" in tagged
            val detailTag = "detail" in tagged
            val valueHits = listOf(NUMBER_UNIT, VERSION, CONSTANT, LIMIT_WORDS, BARE_NUMBER).count { it.containsMatchIn(text) }
            val volatile = volatileTag || valueHits > 0
            val words = text.split(Regex("\\s+")).count { it.isNotBlank() }.coerceAtLeast(1)
            val density = BACKTICKED.findAll(text).count().toDouble() / words
            val levelWeight = when (claim.level) {
                ClaimLevel.SYMBOL -> 0.4
                ClaimLevel.FILE -> 0.15
                ClaimLevel.MODULE, ClaimLevel.FLOW -> 0.0
            }
            val detail = detailTag || levelWeight + (density * 2).coerceAtMost(0.5) >= 0.5
            val base = when (claim.level) {
                ClaimLevel.FLOW -> 0.8
                ClaimLevel.MODULE -> 0.7
                ClaimLevel.FILE -> 0.5
                ClaimLevel.SYMBOL -> 0.3
            }
            val stability = claim.stability
                ?: (base - (if (volatile) 0.3 else 0.0) - (if (detail) 0.2 else 0.0)).coerceIn(0.0, 1.0)
            val confidence = if (volatileTag || detailTag) 0.9 else 0.4
            return ClaimClass(detail, volatile, stability, confidence)
        }
    }
}
