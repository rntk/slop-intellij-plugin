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
    fun `the change map runs last after the summaries it does not reuse and code reviews never run it`() {
        val queue = ReviewPipelineQueue()
        queue.schedule(listOf(ReviewPipeline.CHANGE_MAP, ReviewPipeline.GROUPING, ReviewPipeline.SUMMARIES))
        assertEquals(listOf(ReviewPipeline.SUMMARIES, ReviewPipeline.GROUPING, ReviewPipeline.CHANGE_MAP),
            generateSequence { queue.next(ready = true) { false } }.toList())
        assertEquals(ReviewCommand.CHANGE_MAP, ReviewPipeline.CHANGE_MAP.command)
        assertNull(ReviewPipeline.CHANGE_MAP.follows)
        assertTrue(ReviewPipeline.CHANGE_MAP !in ReviewPipeline.code)
        val codeState = dev.insight.codereview.CodeReviewState()
        assertTrue("A code review has no change map to generate", codeState.isDone(ReviewPipeline.CHANGE_MAP))
    }

    @Test
    fun `code reviews offer no grouping pipelines and diff reviews no code map`() {
        assertEquals(listOf(ReviewPipeline.SUMMARIES, ReviewPipeline.TOPICS, ReviewPipeline.TOPIC_SUMMARIES, ReviewPipeline.CODE_MAP), ReviewPipeline.code)
        assertTrue(ReviewPipeline.CODE_MAP !in ReviewPipeline.diff)
        assertEquals(ReviewPipeline.entries - ReviewPipeline.CODE_MAP, ReviewPipeline.diff)
    }
}
