package dev.insight.review.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.diff.ReviewFile
import dev.insight.diff.DiffFileEditor
import dev.insight.codereview.CodeFile
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewTopics
import dev.insight.review.buildSummaryTree
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.JPanel

class HierarchyPreviewTest : BasePlatformTestCase() {
    private val files get() = listOf("src/a.kt", "src/b.kt", "docs/readme.md").map {
        ReviewFile(it, "/repo/$it", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1")
    }

    fun testNativeDiffAndSourceViewersAreDisposedWhenScopeChanges() {
        val summaries = ReviewSummaries(buildSummaryTree(files))
        val chart = ChangeSummaryHierarchyPanel(SummariesCard(summaries, false, true), ChangeSummariesViewState(),
            1000, 1.0, ChangeSummaryActions({}, {}, {}, {}))
        val preview = HierarchyPreviewPanel(chart, JPanel(), project)
        try {
            preview.showFiles(files.take(1))
            val diff = descendants(preview).filterIsInstance<DiffFileEditor>().single()
            preview.showFiles(listOf(CodeFile("src/c.kt", "/repo/src/c.kt", "val captured = 1\n")))
            assertTrue(diff.isDisposed)
            val source = descendants(preview).filterIsInstance<DiffSourceEditor>().single()
            assertEquals("val captured = 1\n", source.editor.document.text)
            preview.showFiles(emptyList())
            assertTrue(source.editor.isDisposed)
        } finally { Disposer.dispose(preview) }
    }

    fun testSummaryHoverAndClickFollowScopeAndReleasePreviousViewers() {
        val summaries = ReviewSummaries(buildSummaryTree(files))
        val chart = ChangeSummaryHierarchyPanel(SummariesCard(summaries, false, true), ChangeSummariesViewState(),
            1000, 1.0, ChangeSummaryActions({}, {}, {}, {}))
        exercise(chart, "node:root", "node:directory:src", "summary:file:src/a.kt")
    }

    fun testTopicsAndFoldedSummaryUseOnlyTheirDescendantFiles() {
        val files = files
        val topics = ReviewTopics(files).assign(files[0].path, "Product>Storage")
            .assign(files[1].path, "Product>Storage").assign(files[2].path, "Docs>Guide")
        val card = TopicsCard(topics, ReviewSummaries(buildSummaryTree(files)), canRun = true)
        val state = ChangeTopicsViewState()
        state.selectLevel(0, state.fullOutline(card))
        val chart = ChangeTopicHierarchyPanel(card, state, 1000, 1.0, ChangeTopicActions({}, {}))
        exercise(chart, "topic:0:product", "summary:0:product", "topic:0:docs", listOf("docs/readme.md"))
    }

    private fun exercise(chart: HierarchyColumnsPanel, broad: String, narrower: String, leaf: String,
                         leafPaths: List<String> = listOf("src/a.kt")) {
        var released = 0
        var remembered = emptyList<String>()
        val preview = HierarchyPreviewPanel(chart, JPanel(), project, remember = { remembered = it }, createViewer = {
            object : JPanel(), Disposable { override fun dispose() { released++ } }
        })
        fun event(name: String, type: Int) {
            val component = chart.components.single { it.name == name }
            component.dispatchEvent(MouseEvent(component, type, 0, 0, 2, 2, 1, false, MouseEvent.BUTTON1))
        }
        fun shown() = descendants(preview).mapNotNull { it.name?.takeIf { name -> name.startsWith("hierarchy-preview:") } }
            .map { it.removePrefix("hierarchy-preview:") }
        try {
            event(broad, MouseEvent.MOUSE_ENTERED)
            val expectedBroad = chart.previewScopes.entries.single { it.key.name == broad }.value.map { it.path }
            assertEquals(expectedBroad, shown())
            event(narrower, MouseEvent.MOUSE_CLICKED)
            assertEquals(listOf("src/a.kt", "src/b.kt"), shown())
            event(leaf, MouseEvent.MOUSE_ENTERED)
            assertEquals(leafPaths, shown())
            assertEquals(leafPaths, remembered)
            val before = released
            event(leaf, MouseEvent.MOUSE_ENTERED)
            assertEquals("Repeated hover keeps viewers", before, released)
        } finally { Disposer.dispose(preview) }
        assertTrue(released > 0)
    }

    private fun descendants(component: Container): List<Component> = component.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
}
