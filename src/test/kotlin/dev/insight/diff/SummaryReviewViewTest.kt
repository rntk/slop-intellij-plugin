package dev.insight.diff

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.insight.review.ReviewSummaries
import dev.insight.review.Summary
import dev.insight.review.SummarySegment
import dev.insight.review.SummarySource
import dev.insight.review.summarize
import dev.insight.review.ReviewSummariesMode
import dev.insight.review.ReviewTopics
import dev.insight.review.ReviewedFiles
import dev.insight.review.SummaryKind
import dev.insight.review.buildSummaryTree
import java.awt.Component
import java.awt.Container
import java.awt.Font
import javax.swing.JCheckBox
import javax.swing.text.StyleConstants
import dev.insight.review.ui.CanvasState
import dev.insight.review.ui.ChangeTopicActions
import dev.insight.review.ui.ChangeTopicHierarchyPanel
import dev.insight.review.ui.ChangeTopicsMode
import dev.insight.review.ui.ChangeTopicsPanel
import dev.insight.review.ui.ChangeTopicsViewState
import dev.insight.review.ui.NoteCanvas
import dev.insight.review.ui.SummariesCard
import dev.insight.review.ui.SummaryCanvasActions
import dev.insight.review.ui.SummaryReviewPresentation
import dev.insight.review.ui.SummaryReviewSession
import dev.insight.review.ui.SummaryWord
import dev.insight.review.ui.TopicsCard
import dev.insight.review.ui.WrappedSummaryText

class SummaryReviewViewTest : BasePlatformTestCase() {
    private val files = listOf("src/a.kt", "src/b.kt", "docs/readme.md").map {
        ReviewFile(it, "/repo/$it", null, "modified", "patch", "1")
    }
    private fun descendants(root: Container): List<Component> = root.components.flatMap {
        listOf(it) + ((it as? Container)?.let(::descendants) ?: emptyList())
    }

    fun testReviewControlsDimFilesInEverySummaryModeAndCanUnmark() {
        for (mode in ReviewSummariesMode.entries.filter { it != ReviewSummariesMode.DIFF }) {
            val state = DiffReviewState().apply { update(ReviewSnapshot(files, emptyList())) }
            val tree = state.summaries.tree
            val node = tree.nodes.single { it.path == "docs/readme.md" }
            val texts = tree.nodes.associate { it.id to "Summary for ${it.path.ifEmpty { "root" }}" }
            val summaries = ReviewSummaries(tree, texts)
            val canvas = NoteCanvas({}, {}, reviewed = state.reviewed, summaryActions = SummaryCanvasActions(markReviewed = state::setReviewed)).apply {
                setSize(1600, 900)
                summariesView.mode = mode
                if (mode == ReviewSummariesMode.EXPLORER) summariesView.browse("directory:docs", tree)
            }
            try {
                canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, false, true)))
                fun control() = descendants(canvas).filterIsInstance<JCheckBox>().single { it.name == "reviewed:${node.identity}" }
                fun area() = descendants(canvas).filterIsInstance<WrappedSummaryText>().single { it.text == texts[node.id] }
                assertFalse(control().isSelected)
                val live = control()
                live.doClick()
                assertSame("$mode: a toggle restyles the live card, keeping its reading position", live, control())
                assertTrue(state.reviewed.isReviewed(node))
                assertTrue(control().isSelected)
                assertEquals(UIUtil.getContextHelpForeground(), StyleConstants.getForeground(area().styledDocument.getCharacterElement(0).attributes))
                assertEquals(texts[node.id], area().text)
                control().doClick()
                assertFalse(state.reviewed.isReviewed(node))
                assertFalse(control().isSelected)
                assertFalse(area().styledDocument.getCharacterElement(0).attributes.isDefined(StyleConstants.Foreground))
            } finally { canvas.dispose() }
        }
    }

    fun testDimmingUsesOccurrencesAndPreservesTextWordSizesAndSelection() {
        val tree = buildSummaryTree(files)
        val text = "Add cache; add retries."
        val summaries = ReviewSummaries(tree, mapOf(0 to text))
        val marks = ReviewedFiles().apply { restore(files) }
        val review = SummaryReviewPresentation(summaries, marks, mapOf(0 to setOf(0, 1))) { _, _ -> }
        val area = WrappedSummaryText(text, 500, Font(Font.DIALOG, Font.PLAIN, 14), mapOf("cache" to 1.0))
        val document = area.styledDocument
        val size = document.getFont(document.getCharacterElement(text.indexOf("cache")).attributes).size
        area.select(text.indexOf("cache"), text.indexOf("cache") + 5)
        review.decorate(area, tree.root)
        assertEquals(text, area.text)
        assertEquals("cache", area.selectedText)
        assertEquals(size, document.getFont(document.getCharacterElement(text.indexOf("cache")).attributes).size)
        assertEquals(UIUtil.getContextHelpForeground(), StyleConstants.getForeground(document.getCharacterElement(0).attributes))
        assertFalse(document.getCharacterElement(text.indexOf("add")).attributes.isDefined(StyleConstants.Foreground))
    }

    fun testTopicViewsShareReviewMarksAndDimFileSummaries() {
        val tree = buildSummaryTree(files)
        val summaries = ReviewSummaries(tree, tree.nodes.associate { it.id to "Summary ${it.path}" })
        var topics = ReviewTopics(files)
        files.forEach { topics = topics.assign(it.path, "Application > Behavior") }
        val card = TopicsCard(topics, summaries, true)
        val reading = card.reading
        val node = reading.tree.nodes.single { it.path == files[0].path && it.kind == SummaryKind.FILE }
        val reviewed = ReviewedFiles().apply { restore(files); set(node.files, true) }
        for (mode in ChangeTopicsMode.entries) {
            val state = ChangeTopicsViewState(mode = mode)
            val review = SummaryReviewPresentation(reading, reviewed, emptyMap()) { entry, value -> reviewed.set(entry.files, value) }
            val actions = ChangeTopicActions({}, {}, review = review)
            val panel = if (mode == ChangeTopicsMode.DOCUMENT) ChangeTopicsPanel(card, state, 1800, 1.0, actions)
                else ChangeTopicHierarchyPanel(card, state, 1800, 1.0, actions)
            val area = descendants(panel).filterIsInstance<WrappedSummaryText>().single { it.text == summaries.text(tree.nodes.single { it.path == files[0].path }.id) }
            assertEquals(UIUtil.getContextHelpForeground(), StyleConstants.getForeground(area.styledDocument.getCharacterElement(0).attributes))
            assertTrue(descendants(panel).filterIsInstance<JCheckBox>().single { it.name == "reviewed:${node.identity}" }.isSelected)
        }
    }

    fun testDimmingFollowsMarksAndSummaryVersions() {
        val tree = buildSummaryTree(files)
        val src = tree.nodes.single { it.path == "src" }
        val a = tree.nodes.single { it.path == "src/a.kt" }
        val b = tree.nodes.single { it.path == "src/b.kt" }
        val summaries = ReviewSummaries(tree)
            .summarize(tree.nodes.filter { it.children.isEmpty() }
                .associate { it.id to Summary(listOf(SummarySegment("Edits ${it.path}.", listOf(SummarySource.Rows(1, 1))))) })
            .summarize(mapOf(src.id to Summary(listOf(
                SummarySegment("Caching", listOf(SummarySource.Claim(0, 0))), SummarySegment(" and "),
                SummarySegment("retries", listOf(SummarySource.Claim(1, 0))),
            ))))
        val reviewed = ReviewedFiles().apply { restore(files) }
        val session = SummaryReviewSession(reviewed)
        fun dimmed(value: ReviewSummaries): List<String> {
            val presentation = session.presentation(value) { _, _ -> }
            val area = javax.swing.JTextPane().apply { text = value.text(src.id) }
            presentation.decorate(area, src)
            val doc = area.styledDocument
            return SummaryWord.split(area.text).filter { word ->
                doc.getCharacterElement(word.start).attributes.isDefined(StyleConstants.Foreground)
            }.map { it.text }
        }
        assertEquals(emptyList<String>(), dimmed(summaries))
        reviewed.set(listOf(a.path), true)
        assertEquals(listOf("Caching"), dimmed(summaries))
        reviewed.set(listOf(b.path), true)
        assertEquals(listOf("Caching", "and", "retries"), dimmed(summaries))
        reviewed.set(listOf(a.path), false)
        assertEquals(listOf("retries"), dimmed(summaries))
        // A version without citations dims only what is reviewed as a whole.
        assertEquals(emptyList<String>(), dimmed(ReviewSummaries(tree, mapOf(src.id to "Caching and retries"))))
    }
}
