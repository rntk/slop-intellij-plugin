package dev.insight.review.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.codereview.CodeFile
import dev.insight.diff.ReviewFile
import dev.insight.review.ReviewSource
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewTopics
import dev.insight.review.ReviewVocabulary
import dev.insight.review.ReviewedFiles
import dev.insight.review.buildSummaryTree
import dev.insight.review.summarize
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JPanel

class TopicsFillSummariesTest : BasePlatformTestCase() {
    fun testFillSummariesInDocumentAndHierarchyForDiffTopicsGroupingAndCodeTopics() {
        val diff = ReviewFile("Entry.kt", "/repo/Entry.kt", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1")
        val code = CodeFile("Entry.kt", "/repo/Entry.kt", "fun run() {}")
        checkFillSummaries(diff, ReviewVocabulary.DIFF, TopicsCardLabels("Topics", "Topics"))
        checkFillSummaries(diff, ReviewVocabulary.DIFF, TopicsCardLabels("Grouping", "Grouping",
            command = "/grouping", singular = "group", plural = "groups"))
        checkFillSummaries(code, ReviewVocabulary.CODE, TopicsCardLabels("Topics", "Topics"))
    }

    private fun checkFillSummaries(file: ReviewSource, vocabulary: ReviewVocabulary, labels: TopicsCardLabels) {
        val topics = ReviewTopics(listOf(file)).assign(file.path, "Storage>Wallet")
        val content = TopicsCard(topics, ReviewSummaries(buildSummaryTree(listOf(file))), canRun = true)
        var fills = 0
        val card = TopicsCanvasCard(vocabulary, ReviewedFiles(), SourceCanvasActions(), SummaryCanvasActions(),
            TopicCanvasActions(fillTopicSummaries = { fills++ }),
            TopicsCanvasActions({}, {}, {}, { _, _ -> }, { _, _ -> }, {}), labels = labels)
        fun button(content: TopicsCard, running: Boolean = false): JButton {
            card.update(content)
            val panel = card.card(content, 1.0, JPanel(), running, null, null, {})
            return descendants(panel).filterIsInstance<JButton>().single { it.text == "Fill summaries" }
        }
        try {
            for (mode in ChangeTopicsMode.entries) {
                card.view.mode = mode
                val fill = button(content)
                assertTrue(fill.isEnabled)
                val before = fills
                fill.doClick()
                assertEquals(before + 1, fills)
                assertFalse(button(content.copy(canRun = false)).isEnabled)
                assertFalse(button(content, running = true).isEnabled)
                val complete = content.topicSummaries.summarize(content.topicSummaries.tree.nodes.associate { it.id to "Summary." })
                assertFalse(button(content.copy(topicSummaries = complete)).isEnabled)
                val unclassified = ReviewTopics(listOf(file))
                assertFalse(button(TopicsCard(unclassified, content.summaries, canRun = true)).isEnabled)
            }
        } finally { card.dispose() }
    }

    private fun descendants(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::descendants) else emptyList()
}
