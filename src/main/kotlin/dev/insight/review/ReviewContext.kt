package dev.insight.review

import dev.insight.agent.ToolInputException

/** Evidence-independent canvas operations exposed by a review turn. */
interface ReviewContext {
    suspend fun showSource(range: ReviewSourceRange): String = throw ToolInputException("Source blocks are unavailable")
    suspend fun addNotes(notes: List<ReviewNote>): String
    suspend fun startSummaries(): ReviewSummaries = throw ToolInputException("Review summaries are unavailable")
    suspend fun summarize(entries: Map<Int, Summary>): ReviewSummaries = throw ToolInputException("Review summaries are unavailable")
    suspend fun startTopics(): ReviewTopics = throw ToolInputException("Review topics are unavailable")
    suspend fun assignTopic(path: String, label: String): ReviewTopics = throw ToolInputException("Review topics are unavailable")
    suspend fun startTopicSummaries(): ReviewSummaries = throw ToolInputException("Topic summaries are unavailable")
    suspend fun summarizeTopics(entries: Map<Int, Summary>): ReviewSummaries = throw ToolInputException("Topic summaries are unavailable")
}
