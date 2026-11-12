package dev.insight.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPipelineQueueTest {
    @Test
    fun `pipelines run in their fixed order, once each, skipping finished ones`() {
        val queue = ReviewPipelineQueue()
        queue.schedule(listOf(ReviewPipeline.GROUPING, ReviewPipeline.TOPIC_SUMMARIES, ReviewPipeline.SUMMARIES, ReviewPipeline.TOPICS))
        val done = setOf(ReviewPipeline.TOPICS)
        assertNull(queue.next(ready = false) { it in done })
        assertEquals(ReviewPipeline.SUMMARIES, queue.next(ready = true) { it in done })
        assertEquals(ReviewPipeline.TOPIC_SUMMARIES, queue.next(ready = true) { it in done })
        assertEquals(ReviewPipeline.GROUPING, queue.next(ready = true) { it in done })
        assertNull(queue.next(ready = true) { it in done })
        assertTrue(queue.scheduled.isEmpty())
    }

    @Test
    fun `selection round-trips and ignores unknown names`() {
        val selection = setOf(ReviewPipeline.GROUPING_SUMMARIES, ReviewPipeline.SUMMARIES)
        assertEquals("SUMMARIES,GROUPING_SUMMARIES", ReviewPipeline.format(selection))
        assertEquals(selection, ReviewPipeline.parse("SUMMARIES, GONE ,GROUPING_SUMMARIES"))
        assertTrue(ReviewPipeline.parse(null).isEmpty())
    }

    @Test
    fun `code reviews offer no grouping pipelines`() {
        assertEquals(listOf(ReviewPipeline.SUMMARIES, ReviewPipeline.TOPICS, ReviewPipeline.TOPIC_SUMMARIES), ReviewPipeline.code)
    }
}
