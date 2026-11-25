package dev.insight.knowledge

/** Content tokens of claim text: lower case, camelCase split, words of two or more characters without stop words. */
internal fun claimTokens(text: String): Set<String> = text
    .replace(CAMEL, "$1 $2")
    .lowercase()
    .split(NON_WORD)
    .filter { it.length >= 2 && it !in STOP_WORDS }
    .toSet()

/** Normalized token Jaccard of two claim texts in 0..1; identical normalized texts score 1. */
internal fun claimSimilarity(a: String, b: String): Double {
    if (normalizeClaimText(a) == normalizeClaimText(b)) return 1.0
    return jaccard(claimTokens(a), claimTokens(b))
}

internal fun jaccard(a: Set<String>, b: Set<String>): Double {
    if (a.isEmpty() && b.isEmpty()) return 0.0
    val shared = a.count { it in b }
    return shared.toDouble() / (a.size + b.size - shared)
}

/**
 * Whether [candidate] says strictly more than [original]: it keeps nearly all of the original's tokens and adds some.
 * Such a duplicate is proposed as a rewrite, which the guard still verifies.
 */
internal fun isRicher(candidate: String, original: String): Boolean {
    val c = claimTokens(candidate)
    val o = claimTokens(original)
    if (o.isEmpty() || c.size <= o.size) return false
    return o.count { it in c }.toDouble() / o.size >= 0.8
}

/** A pair of item indexes with their similarity. */
internal data class SimilarPair(val a: Int, val b: Int, val similarity: Double)

/**
 * Pairs of [texts] with similarity at least [threshold], most similar first, at most [limit]. [eligible] filters index
 * pairs (a < b) before scoring.
 */
internal fun similarPairs(texts: List<String>, threshold: Double, limit: Int, eligible: (Int, Int) -> Boolean = { _, _ -> true }): List<SimilarPair> {
    val tokens = texts.map(::claimTokens)
    val normalized = texts.map(::normalizeClaimText)
    val pairs = mutableListOf<SimilarPair>()
    for (b in texts.indices) for (a in 0 until b) {
        if (!eligible(a, b)) continue
        val similarity = if (normalized[a] == normalized[b]) 1.0 else jaccard(tokens[a], tokens[b])
        if (similarity >= threshold) pairs += SimilarPair(a, b, similarity)
    }
    return pairs.sortedByDescending { it.similarity }.take(limit)
}

/** Union-find over item indexes. */
internal class DisjointSets(size: Int) {
    private val parent = IntArray(size) { it }

    fun find(i: Int): Int {
        var root = i
        while (parent[root] != root) root = parent[root]
        var node = i
        while (parent[node] != root) { val next = parent[node]; parent[node] = root; node = next }
        return root
    }

    fun union(a: Int, b: Int) { parent[find(a)] = find(b) }

    /** Groups of two or more indexes, each in ascending order, ordered by their first index. */
    fun groups(): List<List<Int>> = parent.indices.groupBy(::find).values.filter { it.size > 1 }.sortedBy { it.first() }
}

private val CAMEL = Regex("([a-z0-9])([A-Z])")
private val NON_WORD = Regex("[^a-z0-9]+")
private val STOP_WORDS = setOf(
    "a", "an", "the", "is", "are", "was", "be", "been", "of", "to", "in", "on", "for", "by", "with", "and", "or", "as",
    "at", "it", "its", "this", "that", "these", "those", "from", "into", "via", "each", "every", "all", "any", "which",
)
