package dev.insight.diff

import dev.insight.review.summarize
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummariesMode
import dev.insight.review.ReviewVocabulary
import dev.insight.review.SourceChunk
import dev.insight.review.SummaryKind
import dev.insight.review.SummaryNode
import dev.insight.review.buildSummaryTree
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JToggleButton
import javax.swing.text.JTextComponent

class SummaryExplorerTest : BasePlatformTestCase() {
    private fun file(path: String) = ReviewFile(path, "/repo/$path", null, "modified", "-old\n+new\n", "1")
    private val files get() = listOf("src/a.kt", "src/nested/b.kt", "src/nested/c.kt", "docs/readme.md").map(::file)

    fun testPagesKeepSummariesAndNavigateWithSingleClicksBackAndBreadcrumbs() {
        val tree = buildSummaryTree(files)
        val summaries = ReviewSummaries(tree, tree.nodes.associate { it.id to "Summary of ${it.identity}." })
        val opened = mutableListOf<String>()
        val canvas = NoteCanvas({ opened += it }, {}).apply { setSize(1600, 900); summariesView.mode = ReviewSummariesMode.EXPLORER }
        fun named(name: String) = descendants(canvas).single { it.name == name }
        fun rows() = descendants(canvas).mapNotNull { it.name }.filter { it.startsWith("summary:") }
        fun clickRow(identity: String, button: Int = MouseEvent.BUTTON1) = click(named("summary:$identity"), button)
        fun back() = (named("summary-explorer-back") as JButton).doClick()
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, false, true)))
            assertEquals(listOf("summary:root"), rows())
            assertFalse((named("summary-explorer-back") as JButton).isEnabled)
            assertTrue(descendants(canvas).filterIsInstance<JTextComponent>().any { it.text == summaries.text(0) })
            clickRow("root", MouseEvent.BUTTON3)
            assertEquals(listOf("summary:root"), rows())
            clickRow("root")
            assertEquals(listOf("summary:directory:docs", "summary:directory:src"), rows())
            clickRow("directory:src")
            assertEquals(listOf("summary:directory:src/nested", "summary:file:src/a.kt"), rows())
            clickRow("file:src/a.kt", MouseEvent.BUTTON2)
            assertTrue(opened.isEmpty())
            clickRow("file:src/a.kt")
            assertEquals(listOf("src/a.kt"), opened)
            assertEquals(listOf("root", "directory:src"), canvas.summariesView.explorerBreadcrumbs(tree).map { it.identity })
            clickRow("directory:src/nested")
            assertEquals(listOf("summary:file:src/nested/b.kt", "summary:file:src/nested/c.kt"), rows())

            // Streaming summaries and view switches retain the folder being read.
            canvas.updateSummaries(SummariesCard(summaries.summarize(mapOf(tree.nodes.last().id to "Updated summary.")), true, false))
            assertEquals(listOf("summary:file:src/nested/b.kt", "summary:file:src/nested/c.kt"), rows())
            for (mode in listOf("outline", "explorer")) (named("summaries-mode:$mode") as JToggleButton).doClick()
            assertEquals(listOf("summary:file:src/nested/b.kt", "summary:file:src/nested/c.kt"), rows())
            back()
            assertEquals(listOf("summary:directory:src/nested", "summary:file:src/a.kt"), rows())
            (named("summary-explorer-crumb:root") as ActionLink).doClick()
            assertEquals(listOf("summary:directory:docs", "summary:directory:src"), rows())
            back()
            assertEquals(listOf("summary:root"), rows())
            assertFalse((named("summary-explorer-back") as JButton).isEnabled)
        } finally { canvas.dispose() }
    }

    fun testFiltersAndSearchResultsChooseAPage() {
        val tree = buildSummaryTree(files)
        val summaries = ReviewSummaries(tree, tree.nodes.associate { it.id to "Summary of ${it.identity}." })
        val state = ChangeSummariesViewState().apply { mode = ReviewSummariesMode.EXPLORER; update(tree) }
        val nested = tree.nodes.single { it.identity == "directory:src/nested" }
        val target = tree.nodes.single { it.path == "src/nested/b.kt" }
        fun visible() = state.explorerEntries(summaries).map { it.identity }.toSet()
        assertEquals(setOf("root"), visible())
        state.browse(nested.identity, tree)
        assertEquals(setOf("file:src/nested/b.kt", "file:src/nested/c.kt"), visible())
        val fork = state.fork()
        fork.explorerBack()
        assertEquals(nested.identity, state.explorerBreadcrumbs(tree).last().identity)
        assertEquals("directory:src", fork.explorerBreadcrumbs(tree).last().identity)

        state.fuzzy = "readme"
        state.updateExplorer(summaries)
        assertEquals(listOf("root"), state.explorerBreadcrumbs(tree).map { it.identity })
        assertEquals(listOf("directory:docs"), state.explorerEntries(summaries).map { it.identity })
        state.fuzzy = "no-such-summary"
        state.updateExplorer(summaries)
        assertTrue(state.explorerEntries(summaries).isEmpty())
        state.clearSearch()

        val rootText = summaries.text(0)!!
        val word = SummaryWord.split(rootText).first()
        val trace = SummaryTrace("root", rootText, word, listOf(word), "nested",
            listOf(SummaryMatch(target.id, target.identity, true, tree.depth(target.id))))
        state.showTrace(tree, trace)
        assertEquals(nested.identity, state.explorerBreadcrumbs(tree).last().identity)
        assertEquals(target.identity, state.selected)
        assertTrue(target.identity in state.explorerEntries(summaries).map { it.identity })
        val search = state.fork()
        search.showTrace(tree, trace)
        search.updateExplorer(summaries)
        assertEquals(listOf(target.identity), search.explorerEntries(summaries).map { it.identity })
    }

    fun testRemovedFoldersReturnToSurvivingAncestorAndEmptyScopesHaveNoRows() {
        val tree = buildSummaryTree(files)
        val state = ChangeSummariesViewState().apply { update(tree); browse("directory:src/nested", tree) }
        val changed = buildSummaryTree(listOf(file("src/a.kt"), file("src/d.kt"), file("docs/readme.md")))
        state.update(changed)
        assertEquals("directory:src", state.explorerBreadcrumbs(changed).last().identity)
        assertEquals(listOf("file:src/a.kt", "file:src/d.kt"), state.explorerEntries(ReviewSummaries(changed)).map { it.identity })
        val canvas = NoteCanvas({}, {}).apply { setSize(1600, 900); summariesView.mode = ReviewSummariesMode.EXPLORER }
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(ReviewSummaries(buildSummaryTree(emptyList())), false, false)))
            assertTrue(descendants(canvas).none { it.name?.startsWith("summary:") == true })
            assertFalse((descendants(canvas).single { it.name == "summary-explorer-back" } as JButton).isEnabled)
        } finally { canvas.dispose() }
    }

    fun testFilesOpenTheirSourceAndPartsRemainAvailableOnTheirOwnPages() {
        val tree = buildSummaryTree(listOf(file("big.kt")), chunks = {
            listOf(SourceChunk(0, 1..10, "first"), SourceChunk(1, 11..20, "second"))
        })
        val opened = mutableListOf<SummaryNode>()
        val canvas = NoteCanvas({}, {}, vocabulary = ReviewVocabulary.CODE, summaryActions = SummaryCanvasActions(openNode = { opened += it }))
            .apply { setSize(1600, 900); summariesView.mode = ReviewSummariesMode.EXPLORER; summariesView.browse("root", tree) }
        fun named(name: String) = descendants(canvas).single { it.name == name }
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(ReviewSummaries(tree), false, false)))
            click(named("summary:file:big.kt"))
            assertEquals(SummaryKind.FILE, opened.single().kind)
            descendants(canvas).filterIsInstance<ActionLink>().single { it.text == "Browse parts" }.doClick()
            click(named("summary:chunk:big.kt:1"))
            assertEquals(11..20, opened.last().lines)
            assertEquals("file:big.kt", canvas.summariesView.explorerBreadcrumbs(tree).last().identity)
            (named("summary-explorer-back") as JButton).doClick()
            assertNotNull(named("summary:file:big.kt"))
        } finally { canvas.dispose() }
    }

    private fun click(component: Component, button: Int = MouseEvent.BUTTON1) {
        component.dispatchEvent(MouseEvent(component, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, button))
    }

    private fun descendants(container: Container): List<Component> = container.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
}
