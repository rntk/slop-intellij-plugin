package dev.insight.enrichment

import java.text.Normalizer
import java.util.Locale

/** Repairs missing and overlapping markers deterministically, retaining exactly one topic per line. */
object CanvasTopicParser {
    fun parse(response: String, lineCount: Int): List<CanvasTopic> {
        require(lineCount > 0)
        data class Claim(val path: String, val start: Int, val end: Int, val ordinal: Int)
        val canonical = linkedMapOf<String, String>()
        val claims = mutableListOf<Claim>()
        for (raw in response.lineSequence()) {
            val line = raw.trim().removePrefix("- ")
            val separator = line.lastIndexOf(':')
            if (separator < 1) continue
            val path = line.substring(0, separator).split('>', ':').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(">")
            if (path.isEmpty()) continue
            val key = Normalizer.normalize(path, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
            val label = canonical.getOrPut(key) { path }
            for (token in line.substring(separator + 1).split(',')) {
                val match = Regex("^\\s*(\\d+)\\s*(?:-\\s*(\\d+))?\\s*$").matchEntire(token) ?: continue
                val first = match.groupValues[1].toLongOrNull() ?: continue
                val last = match.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull() ?: first
                val a = first.coerceIn(0, (lineCount - 1).toLong()).toInt()
                val b = last.coerceIn(0, (lineCount - 1).toLong()).toInt()
                claims += Claim(label, minOf(a, b), maxOf(a, b), claims.size)
            }
        }
        require(claims.isNotEmpty()) { "No valid topic ranges found in response" }
        val segments = mutableListOf<Pair<String, CanvasRange>>()
        var next = 0
        for (claim in claims.sortedWith(compareBy({ it.start }, { it.ordinal }))) {
            if (claim.end < next) continue
            var start = maxOf(next, claim.start)
            if (start > next) {
                if (segments.isEmpty()) start = 0
                else {
                    val previous = segments.removeLast()
                    segments += previous.first to previous.second.copy(end = start - 1)
                }
            }
            segments += claim.path to CanvasRange(start, claim.end)
            next = claim.end + 1
        }
        if (next < lineCount) {
            val previous = segments.removeLast()
            segments += previous.first to previous.second.copy(end = lineCount - 1)
        }
        return segments.groupBy({ it.first }, { it.second }).map { (path, ranges) ->
            val merged = mutableListOf<CanvasRange>()
            for (range in ranges) {
                val previous = merged.lastOrNull()
                if (previous != null && previous.end + 1 == range.start) merged[merged.lastIndex] = previous.copy(end = range.end)
                else merged += range
            }
            CanvasTopic(path, merged, inferred = true)
        }.sortedBy { it.ranges.first().start }
    }
}
