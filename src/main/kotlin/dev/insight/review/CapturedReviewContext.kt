package dev.insight.review

import com.intellij.openapi.application.EDT
import dev.insight.agent.ToolInputException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lets adapters retain immediate or throttled canvas updates without coupling the context to Swing. */
internal enum class ReviewContextChange {
    SOURCE, SUMMARIES_STARTED, TOPICS_STARTED, TOPIC_SUMMARIES_STARTED, SUMMARIES_SAVED, TOPIC_ASSIGNED,
}

/** A turn's canvas operations always check the captured revision on the EDT before reading or writing live state. */
internal abstract class CapturedReviewContext<S : ReviewSource>(
    private val state: ReviewTabState<S>,
    protected val capturedRevision: Int,
    private val isClosed: () -> Boolean,
    private val onChanged: (ReviewContextChange) -> Unit,
) : ReviewContext {
    init { current() }

    protected fun current() {
        if (isClosed()) throw ToolInputException("This review tab is closed")
        state.check(capturedRevision)
    }

    final override suspend fun showSource(range: ReviewSourceRange): String = withContext(Dispatchers.EDT) {
        current()
        val block = state.showSource(capturedRevision, range)
        onChanged(ReviewContextChange.SOURCE)
        reviewJson.toJson(mapOf("shown" to block.range, "characters" to block.text.length))
    }

    final override suspend fun startSummaries(): ReviewSummaries = withContext(Dispatchers.EDT) {
        current()
        state.startSummaries(capturedRevision).also { onChanged(ReviewContextChange.SUMMARIES_STARTED) }
    }

    final override suspend fun summarize(entries: Map<Int, Summary>): ReviewSummaries = withContext(Dispatchers.EDT) {
        current()
        state.summarize(capturedRevision, entries).also { onChanged(ReviewContextChange.SUMMARIES_SAVED) }
    }

    final override suspend fun startTopics(): ReviewTopics = withContext(Dispatchers.EDT) {
        current()
        state.startTopics().also { onChanged(ReviewContextChange.TOPICS_STARTED) }
    }

    final override suspend fun assignTopic(path: String, label: String): ReviewTopics = withContext(Dispatchers.EDT) {
        current()
        state.assignTopic(capturedRevision, path, label).also { onChanged(ReviewContextChange.TOPIC_ASSIGNED) }
    }

    final override suspend fun startTopicSummaries(): ReviewSummaries = withContext(Dispatchers.EDT) {
        current()
        state.startTopicSummaries(capturedRevision).also { onChanged(ReviewContextChange.TOPIC_SUMMARIES_STARTED) }
    }

    final override suspend fun summarizeTopics(entries: Map<Int, Summary>): ReviewSummaries = withContext(Dispatchers.EDT) {
        current()
        state.summarizeTopics(capturedRevision, entries).also { onChanged(ReviewContextChange.SUMMARIES_SAVED) }
    }
}
