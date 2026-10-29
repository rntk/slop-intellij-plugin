package dev.insight.diff

import dev.insight.review.summarize
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.review.ReviewSummaries
import dev.insight.review.SummaryKind
import dev.insight.review.buildSummaryTree
import java.awt.Component
import java.awt.Container
import javax.swing.JLabel
import dev.insight.review.ui.CanvasState
import dev.insight.review.ui.ChangeSummariesViewState
import dev.insight.review.ui.NoteCanvas
import dev.insight.review.ui.SearchCrumb
import dev.insight.review.ui.SummariesCard
import dev.insight.review.ui.SummaryContext
import dev.insight.review.ui.SummaryLinks
import dev.insight.review.ui.SummaryWord
import dev.insight.review.ui.summaryNodeTitle
import dev.insight.review.ui.levelsBelow
import dev.insight.review.ui.selectSummariesMode

class SummaryMergedFoldersTest : BasePlatformTestCase() {
    private fun file(path: String) = ReviewFile(path, "/repo/$path", null, "modified", "-old\n+new\n", "1")
    private val files get() = listOf("src/main/kotlin/dev/insight/a.kt", "src/main/kotlin/dev/insight/b.kt", "README.md").map(::file)

    private fun summaries(): ReviewSummaries {
        val tree = buildSummaryTree(files)
        val leaves = tree.nodes.filter { it.children.isEmpty() }.associate { it.id to "Summary of ${it.identity}." }
        val insight = tree.nodes.single { it.path == "src/main/kotlin/dev/insight" }
        return ReviewSummaries(tree).summarize(leaves).summarize(mapOf(insight.id to "Insight folder."))
            .summarize(mapOf(tree.root.id to "Everything."))
    }

    fun testChainOfSingleFoldersShowsAsItsLastFolder() {
        val tree = summaries().tree
        fun node(path: String) = tree.nodes.single { it.kind == SummaryKind.DIRECTORY && it.path == path }
        val insight = node("src/main/kotlin/dev/insight")
        // The tree keeps every folder; only the display merges them.
        assertEquals(listOf(true, true, true, true), listOf("src", "src/main", "src/main/kotlin", "src/main/kotlin/dev").map { tree.isMerged(node(it).id) })
        assertFalse(tree.isMerged(insight.id))
        assertEquals(insight, tree.shown(node("src").id))
        assertEquals(listOf("directory:src/main/kotlin/dev/insight", "file:README.md"), tree.shownChildren(tree.root).map { it.identity })
        assertEquals("src/main/kotlin/dev/insight", tree.shownLabel(insight))
        assertEquals("src/main/kotlin/dev/insight/", summaryNodeTitle(insight, tree))
        assertEquals(tree.root, tree.shownParent(insight))
        assertEquals(1, tree.shownDepth(insight.id))
        assertEquals(2, tree.shownDepth(insight.children.first()))
        assertEquals(2, ChangeSummariesViewState.branchLevels(tree))
    }

    fun testOutlineShowsOneEntryAndOneSummaryForTheChain() {
        val summaries = summaries()
        val canvas = NoteCanvas({}, {}).apply { setSize(1600, 900) }
        fun rows() = descendants(canvas).mapNotNull { it.name }.filter { it.startsWith("summary:") }
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, false, true)))
            selectSummariesMode(descendants(canvas), "outline")
            assertEquals(listOf("summary:root", "summary:directory:src/main/kotlin/dev/insight",
                "summary:file:src/main/kotlin/dev/insight/a.kt", "summary:file:src/main/kotlin/dev/insight/b.kt", "summary:file:README.md"), rows())
            val name = descendants(canvas).single { it.name == "summary-name:directory:src/main/kotlin/dev/insight" } as JLabel
            assertTrue(name.text, name.text.startsWith("src/main/kotlin/dev/insight/ · folder"))
            assertEquals(1, descendants(canvas).filterIsInstance<javax.swing.text.JTextComponent>().count { it.text == "Insight folder." })
        } finally { canvas.dispose() }
    }

    fun testLevelsAndTraceCandidatesSkipMergedFolders() {
        val summaries = summaries()
        val tree = summaries.tree
        val insight = tree.nodes.single { it.path == "src/main/kotlin/dev/insight" }
        val state = ChangeSummariesViewState().apply { update(tree) }
        state.selectLevel(1, tree)
        assertEquals(1, state.shownLevel(tree))
        val word = SummaryWord.split(summaries.text(tree.root.id)!!).first()
        val context = SummaryContext(tree.root.identity, summaries.text(tree.root.id)!!, word, listOf(word), word.text)
        val candidates = SummaryLinks(summaries).search(tree.root.id, context).matches
        assertEquals(listOf(insight.id to 1), candidates.filter { tree[it.id].kind == SummaryKind.DIRECTORY }.map { it.id to it.levelsBelow })
    }

    fun testFuzzyMatchInMergedAncestorKeepsItsDisplayedFolder() {
        val initial = summaries()
        val tree = initial.tree
        val ancestor = tree.nodes.single { it.path == "src" }
        // Regeneration can give an ancestor a summary distinct from the terminal folder's.
        val changed = initial.summarize(mapOf(ancestor.id to "Zebrafish migration."), reuseSingleChildren = false)
        val canvas = NoteCanvas({}, {}).apply {
            setSize(1600, 900)
            summariesView.update(tree)
            summariesView.selectLevel(0, tree)
            summariesView.fuzzy = "zebrafish"
        }
        fun rows() = descendants(canvas).mapNotNull { it.name }.filter { it.startsWith("summary:") }
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(initial, false, true)))
            assertTrue(rows().isEmpty())
            assertTrue(descendants(canvas).any { it.name == "no-matches" })
            canvas.updateSummaries(SummariesCard(changed, false, true))
            assertEquals(listOf("summary:root", "summary:directory:src/main/kotlin/dev/insight"), rows())
            assertFalse(descendants(canvas).any { it.name == "no-matches" })
        } finally { canvas.dispose() }
    }

    fun testExplorerRefreshNormalizesMergedAndNewlyUnmergedBreadcrumbs() {
        val initial = buildSummaryTree(listOf(file("src/a.kt"), file("src/nested/b.kt")))
        val merged = buildSummaryTree(listOf(file("src/nested/b.kt")))
        val state = ChangeSummariesViewState().apply { update(initial); browse("directory:src", initial) }
        state.update(merged)
        assertEquals(listOf("root", "directory:src/nested"), state.explorerBreadcrumbs(merged).map { it.identity })
        assertEquals(listOf("file:src/nested/b.kt"), state.explorerEntries(ReviewSummaries(merged)).map { it.identity })

        state.explorerBack()
        assertEquals(listOf("root"), state.explorerBreadcrumbs(merged).map { it.identity })
        assertEquals(listOf("directory:src/nested"), state.explorerEntries(ReviewSummaries(merged)).map { it.identity })

        state.browse("directory:src/nested", merged)
        state.update(initial)
        assertEquals(listOf("root", "directory:src", "directory:src/nested"), state.explorerBreadcrumbs(initial).map { it.identity })
        state.explorerBack()
        assertEquals(listOf("directory:src/nested", "file:src/a.kt"), state.explorerEntries(ReviewSummaries(initial)).map { it.identity })

        state.browse("directory:src/nested", initial)
        state.update(merged)
        assertEquals(listOf("root", "directory:src/nested"), state.explorerBreadcrumbs(merged).map { it.identity })
        // Browsing a physical ancestor directly also uses its displayed representative.
        state.browse("directory:src", merged)
        assertEquals(listOf("root", "directory:src/nested"), state.explorerBreadcrumbs(merged).map { it.identity })
    }

    fun testFuzzyAncestorMatchRespectsSearchTabExclusions() {
        val initial = summaries()
        val tree = initial.tree
        val ancestor = tree.nodes.single { it.path == "src" }
        val changed = initial.summarize(mapOf(ancestor.id to "Zebrafish migration."), reuseSingleChildren = false)
        val state = ChangeSummariesViewState(filtering = true).apply {
            update(tree)
            fuzzy = "zebrafish"
            crumbs += SearchCrumb("root", "related", setOf("root", ancestor.identity))
        }
        assertTrue(state.shownNodes(changed)!!.isEmpty())
        state.crumbs.clear()
        state.crumbs += SearchCrumb("root", "related", setOf("root", ancestor.identity, tree.shown(ancestor.id).identity))
        assertEquals(setOf("root", tree.shown(ancestor.id).identity), state.shownNodes(changed))
    }

    private fun descendants(container: Container): List<Component> = container.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
}
