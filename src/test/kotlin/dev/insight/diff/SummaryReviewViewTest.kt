package dev.insight.diff

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.Job
import java.awt.Component
import java.awt.Container
import java.awt.Font
import javax.swing.JCheckBox
import javax.swing.text.StyleConstants

class SummaryReviewViewTest : BasePlatformTestCase() {
    private val files = listOf("src/a.kt", "src/b.kt", "docs/readme.md").map {
        ReviewFile(it, "/repo/$it", null, "modified", "patch", "1")
    }
    private fun descendants(root: Container): List<Component> = root.components.flatMap {
        listOf(it) + ((it as? Container)?.let(::descendants) ?: emptyList())
    }

    fun testReviewControlsDimFilesInEverySummaryModeAndCanUnmark() {
        for (mode in ChangeSummariesMode.entries) {
            val state = DiffReviewState().apply { update(ReviewSnapshot(files, emptyList())) }
            val tree = state.summaries.tree
            val node = tree.nodes.single { it.path == "docs/readme.md" }
            val texts = tree.nodes.associate { it.id to "Summary for ${it.path.ifEmpty { "root" }}" }
            val summaries = ChangeSummaries(tree, texts)
            val canvas = NoteCanvas({}, {}, reviewed = state.reviewed, summaryActions = SummaryCanvasActions(markReviewed = state::setReviewed)).apply {
                setSize(1600, 900)
                summariesView.mode = mode
                if (mode == ChangeSummariesMode.EXPLORER) summariesView.browse("directory:docs", tree)
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
        val summaries = ChangeSummaries(tree, mapOf(0 to text))
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
        val summaries = ChangeSummaries(tree, tree.nodes.associate { it.id to "Summary ${it.path}" })
        var topics = ChangeTopics(files)
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

    fun testLateLinkResultsCannotOverrideUnmarkedFilesOrNewSummaryVersions() {
        val tree = buildSummaryTree(files)
        val summaries = ChangeSummaries(tree, mapOf(0 to "Caching and retries"))
        val reviewed = ReviewedFiles().apply { restore(files); set(listOf(files[0].path), true) }
        val callbacks = mutableListOf<(Result<ReviewDimming>) -> Unit>()
        val jobs = mutableListOf<Job>()
        val launcher = object : SummaryReviewLauncher {
            override fun launch(summaries: ChangeSummaries, reviewed: Set<String>, done: (Result<ReviewDimming>) -> Unit): Job {
                callbacks += done
                return Job().also { jobs += it }
            }
        }
        var updates = 0
        val session = SummaryReviewSession(reviewed, launcher) { updates++ }
        try {
            session.presentation(summaries) { _, _ -> }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals(1, callbacks.size)
            reviewed.set(listOf(files[0].path), false)
            session.presentation(summaries) { _, _ -> }
            callbacks[0](Result.success(mapOf(0 to setOf(0))))
            assertEquals(0, updates)
            assertTrue(jobs[0].isCancelled)
            reviewed.set(listOf(files[0].path), true)
            session.presentation(summaries) { _, _ -> }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            val newer = ChangeSummaries(tree, mapOf(0 to "Different words"))
            session.presentation(newer) { _, _ -> }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            callbacks[1](Result.success(mapOf(0 to setOf(0))))
            assertEquals(0, updates)
            callbacks.last()(Result.failure(IllegalStateException("Service unavailable")))
            assertEquals(1, updates)
            assertEquals("Service unavailable", session.error)
            assertFalse(session.running)
            val old = callbacks.last()
            session.stop()
            session.presentation(newer) { _, _ -> }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            old(Result.success(mapOf(0 to setOf(0))))
            assertEquals("A closed session must not publish after reopening the same text", 1, updates)
            assertTrue(session.running)
        } finally { session.stop() }
    }
}
