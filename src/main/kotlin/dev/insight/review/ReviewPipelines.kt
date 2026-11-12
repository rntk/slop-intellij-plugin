package dev.insight.review

/**
 * A generation pipeline a review can run through its chat command. The Start page schedules several, and [ReviewPipelineQueue]
 * runs them one by one, in this order: file summaries come before the topic and grouping summaries that reuse them.
 */
internal enum class ReviewPipeline(val command: ReviewCommand, val label: String, val tooltip: String) {
    SUMMARIES(ReviewCommand.SUMMARIES, "Summaries", "Summarize every file and folder, up to the whole review"),
    TOPICS(ReviewCommand.TOPICS, "Topics", "Classify every file by code subsystem and capability"),
    TOPIC_SUMMARIES(ReviewCommand.TOPIC_SUMMARIES, "Fill topic summaries", "Fill missing summaries for every topic and subtopic"),
    GROUPING(ReviewCommand.GROUPING, "Grouping", "Group every changed file by shared change cause and affected code"),
    GROUPING_SUMMARIES(ReviewCommand.GROUPING_SUMMARIES, "Fill grouping summaries", "Fill missing summaries for every group and subgroup");

    companion object {
        /** Grouping reads a diff, so code reviews offer only the other pipelines. */
        val code: List<ReviewPipeline> = entries.filter { it.command in ReviewCommand.code }

        /** Persisted [selection] text: pipeline names separated by commas; unknown names are dropped. */
        fun parse(text: String?): Set<ReviewPipeline> =
            text.orEmpty().split(',').mapNotNullTo(linkedSetOf()) { name -> entries.firstOrNull { it.name == name.trim() } }

        fun format(selection: Collection<ReviewPipeline>): String = selection.sortedBy { it.ordinal }.joinToString(",") { it.name }
    }
}

/** Whether [pipeline] has nothing left to generate in [state], so a scheduled run skips it rather than regenerating it. */
internal fun ReviewTabState<*>.isDone(pipeline: ReviewPipeline): Boolean = when (pipeline) {
    ReviewPipeline.SUMMARIES -> summaries.complete
    ReviewPipeline.TOPICS -> topics.complete
    ReviewPipeline.TOPIC_SUMMARIES -> topics.labels.isEmpty() || topicSummaries.complete
    ReviewPipeline.GROUPING -> grouping.complete
    ReviewPipeline.GROUPING_SUMMARIES -> grouping.labels.isEmpty() || groupingSummaries.complete
}

/**
 * Pipelines scheduled to run one after another. Each starts at most once per [schedule]: one that stops or fails is not
 * retried, so the next one still runs. A pipeline that is already done when its turn comes is skipped.
 */
internal class ReviewPipelineQueue {
    private val pending = sortedSetOf<ReviewPipeline>()
    val scheduled: Set<ReviewPipeline> get() = pending.toSet()

    /** Adds [pipelines] to those still waiting. */
    fun schedule(pipelines: Collection<ReviewPipeline>) { pending += pipelines }

    fun clear() = pending.clear()

    /**
     * The pipeline to start now, removed from the queue; null while not [ready] or when nothing is left. Pipelines [done]
     * reports finished are dropped on the way.
     */
    fun next(ready: Boolean, done: (ReviewPipeline) -> Boolean): ReviewPipeline? {
        if (!ready) return null
        while (pending.isNotEmpty()) {
            val first = pending.first()
            pending.remove(first)
            if (!done(first)) return first
        }
        return null
    }
}
