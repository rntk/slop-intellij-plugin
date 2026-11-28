package dev.insight.agent

/** Project-local Insight state excluded from automatic evidence collection. */
object ProjectDataPaths {
    private val generatedInsightDirectories = setOf(
        "review-cache", "canvas-sessions", "boards",
    )

    /** [path] is project-relative or absolute; unknown `.insight` children remain ordinary project content. */
    @JvmStatic
    fun isInternal(path: String): Boolean = isInternal(path, includeLedger = true)

    /** The repository ledger is authored history and can be inspected in a tracked diff. */
    internal fun isPrivateInTrackedDiff(path: String): Boolean = isInternal(path, includeLedger = false)

    private fun isInternal(path: String, includeLedger: Boolean): Boolean {
        val normalized = path.replace('\\', '/')
        if (normalized != ".insight" && !normalized.startsWith(".insight/") && "/.insight/" !in normalized) return false
        val segments = normalized.split('/')
        return segments.indices.any { i ->
            segments[i] == ".insight" && (segments.getOrNull(i + 1) in generatedInsightDirectories ||
                includeLedger && segments.getOrNull(i + 1) == "knowledge" && segments.getOrNull(i + 2) == "ledger.jsonl")
        }
    }
}
