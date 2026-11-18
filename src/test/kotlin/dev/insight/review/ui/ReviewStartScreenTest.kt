package dev.insight.review.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.review.ReviewPipeline
import dev.insight.review.ReviewVocabulary
import javax.swing.JPanel

class ReviewStartScreenTest : BasePlatformTestCase() {
    fun testBulkSelectionUsesThePipelinesOfferedByEachReview() {
        for ((vocabulary, pipelines) in listOf(
            ReviewVocabulary.CODE to ReviewPipeline.code,
            ReviewVocabulary.DIFF to ReviewPipeline.diff,
        )) {
            val preferences = PropertiesComponent.getInstance(project)
            val key = "Insight.${vocabulary.canvasTitle.replace(" ", "")}.StartPipelines"
            preferences.setValue(key, ReviewPipeline.SUMMARIES.name)
            val screen = createScreen(vocabulary, pipelines)
            try {
                assertEquals("Check all", screen.toggleAllButton.text)
                screen.toggleAllButton.doClick()
                assertEquals(pipelines, screen.selectedPipelines.toList())
                assertEquals(pipelines.toSet(), ReviewPipeline.parse(preferences.getValue(key)))
                assertEquals("Uncheck all", screen.toggleAllButton.text)
                assertEquals("Start review and generate", screen.continueButton.text)

                screen.toggleAllButton.doClick()
                assertTrue(screen.selectedPipelines.isEmpty())
                assertTrue(ReviewPipeline.parse(preferences.getValue(key)).isEmpty())
                assertEquals("Check all", screen.toggleAllButton.text)
                assertEquals("Start review", screen.continueButton.text)
            } finally {
                Disposer.dispose(screen)
                preferences.unsetValue(key)
            }
        }
    }

    fun testFillSelectionsRequireTheirOwnParent() {
        val key = "Insight.DiffReview.StartPipelines"
        val preferences = PropertiesComponent.getInstance(project)
        preferences.setValue(key, "TOPIC_SUMMARIES,GROUPING,GROUPING_SUMMARIES")
        val screen = createScreen(ReviewVocabulary.DIFF, ReviewPipeline.diff)
        try {
            val topics = screen.pipelineBoxes.getValue(ReviewPipeline.TOPICS)
            val topicSummaries = screen.pipelineBoxes.getValue(ReviewPipeline.TOPIC_SUMMARIES)
            val grouping = screen.pipelineBoxes.getValue(ReviewPipeline.GROUPING)
            val groupingSummaries = screen.pipelineBoxes.getValue(ReviewPipeline.GROUPING_SUMMARIES)
            assertFalse(topicSummaries.isSelected)
            assertFalse(topicSummaries.isEnabled)
            assertTrue(groupingSummaries.isSelected)
            assertTrue(groupingSummaries.isEnabled)

            topics.doClick()
            assertTrue(topicSummaries.isEnabled)
            assertFalse(topicSummaries.isSelected)
            topicSummaries.doClick()
            grouping.doClick()
            assertFalse(groupingSummaries.isSelected)
            assertFalse(groupingSummaries.isEnabled)
            assertTrue(topicSummaries.isSelected)
            assertEquals(setOf(ReviewPipeline.TOPICS, ReviewPipeline.TOPIC_SUMMARIES),
                ReviewPipeline.parse(preferences.getValue(key)))

            topics.doClick()
            assertFalse(topicSummaries.isSelected)
            assertFalse(topicSummaries.isEnabled)
            assertTrue(screen.selectedPipelines.isEmpty())
        } finally {
            Disposer.dispose(screen)
            preferences.unsetValue(key)
        }
    }

    private fun createScreen(vocabulary: ReviewVocabulary, pipelines: List<ReviewPipeline>) =
        ReviewStartScreen(project, vocabulary, ReviewStartText("Review", "", "Source", "", "Files", ""),
            JPanel(), JPanel(), pipelines) {}
}
