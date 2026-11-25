package dev.insight.knowledge.ide

/**
 * Splits [max] characters over texts of the given [lengths] fairly: short texts keep their whole length and the rest share
 * what remains equally. The result sums to at most [max] and never exceeds a text's own length.
 */
internal fun allocateBudget(lengths: List<Int>, max: Int): List<Int> {
    if (lengths.sum() <= max) return lengths
    val allocation = IntArray(lengths.size)
    var remaining = max.coerceAtLeast(0)
    var left = lengths.size
    for (index in lengths.indices.sortedBy { lengths[it] }) {
        val share = remaining / left
        allocation[index] = minOf(lengths[index], share)
        remaining -= allocation[index]
        left--
    }
    return allocation.toList()
}

/** [text] cut to at most [limit] characters, at a line end when that keeps over half of it, marked with an ellipsis. */
internal fun capText(text: String, limit: Int): String {
    if (text.length <= limit) return text
    if (limit <= 0) return ""
    if (limit == 1) return "…"
    val head = text.take(limit - 1)
    val lineEnd = head.lastIndexOf('\n')
    return (if (lineEnd > head.length / 2) head.substring(0, lineEnd) else head).trimEnd() + "…"
}
