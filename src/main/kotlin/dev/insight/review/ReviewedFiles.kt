package dev.insight.review

/** Review marks describe evidence, so changed source or patches automatically become unreviewed. */
class ReviewedFiles {
    private var fileKeys: Map<String, String> = emptyMap()
    private val marks = mutableSetOf<String>()
    val keys: Set<String> get() = marks.toSet()
    var revision: Int = 0
        private set

    internal fun restore(sources: List<ReviewSource>, saved: Set<String> = keys) {
        fileKeys = sources.associate { it.path to key(it) }
        marks.clear()
        marks.addAll(saved.intersect(fileKeys.values.toSet()))
        revision++
    }

    fun isReviewed(path: String): Boolean = fileKeys[path]?.let { it in marks } == true

    fun isReviewed(node: SummaryNode): Boolean = node.files.isNotEmpty() && node.files.all(::isReviewed)

    /** Returns every targeted evidence key, including marks already in the requested local state. */
    fun set(paths: List<String>, reviewed: Boolean): List<String> {
        val targeted = paths.mapNotNull(fileKeys::get)
        targeted.forEach { if (reviewed) marks.add(it) else marks.remove(it) }
        revision++
        return targeted
    }

    companion object {
        internal fun key(file: ReviewSource): String = reviewFingerprint("reviewed", file.path, file.fingerprint)
    }
}

