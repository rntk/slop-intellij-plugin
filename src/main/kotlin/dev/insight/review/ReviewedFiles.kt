package dev.insight.review

/** Review marks describe evidence, so changed source or patches automatically become unreviewed. */
class ReviewedFiles {
    private var files: Map<String, ReviewSource> = emptyMap()
    private val marks = mutableSetOf<String>()
    val keys: Set<String> get() = marks.toSet()
    var revision: Int = 0
        private set

    internal fun restore(sources: List<ReviewSource>, saved: Set<String> = keys) {
        files = sources.associateBy { it.path }
        marks.clear()
        marks.addAll(saved.intersect(sources.map(::key).toSet()))
        revision++
    }

    fun isReviewed(node: SummaryNode): Boolean = node.files.isNotEmpty() &&
        node.files.all { path -> files[path]?.let { key(it) in marks } == true }

    /** Returns every targeted evidence key, including marks already in the requested local state. */
    fun set(paths: List<String>, reviewed: Boolean): List<String> {
        val targeted = paths.mapNotNull(files::get).map(::key)
        targeted.forEach { if (reviewed) marks.add(it) else marks.remove(it) }
        revision++
        return targeted
    }

    companion object {
        internal fun key(file: ReviewSource): String = reviewFingerprint("reviewed", file.path, file.fingerprint)
    }
}

