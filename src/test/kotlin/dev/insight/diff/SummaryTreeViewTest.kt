package dev.insight.diff

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.review.*
import dev.insight.review.ui.*
import java.awt.Component
import java.awt.Container
import com.intellij.util.ui.UIUtil
import javax.swing.JButton

class SummaryTreeViewTest : BasePlatformTestCase() {
    private fun descendants(root: Container): List<Component> = root.components.flatMap {
        listOf(it) + ((it as? Container)?.let(::descendants) ?: emptyList())
    }

    fun testNodesShowTheirDirectoryAndReviewedBranchesDim() {
        val files = listOf("src/a.kt", "src/b.kt").map { ReviewFile(it, "/repo/$it", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1", "old\n", "new\n") }
        val tree = buildSummaryTree(files)
        val src = tree.nodes.single { it.path == "src" }
        val a = tree.nodes.single { it.path == "src/a.kt" }
        val b = tree.nodes.single { it.path == "src/b.kt" }
        val summaries = ReviewSummaries(tree, mapOf(
            tree.root.id to Summary(listOf(SummarySegment("Whole change", listOf(SummarySource.Claim(0, 0), SummarySource.Claim(0, 1))))),
            src.id to Summary(listOf(
                SummarySegment("Cache", listOf(SummarySource.Claim(0, 0))),
                SummarySegment("Retries", listOf(SummarySource.Claim(1, 0))),
            )),
            a.id to Summary(listOf(SummarySegment("Reads storage", listOf(SummarySource.Rows(1, 3))))),
            b.id to Summary(listOf(SummarySegment("Repeats failures", listOf(SummarySource.Rows(1, 3))))),
        ))
        val marks = ReviewedFiles().apply { restore(files) }
        val review = SummaryReviewPresentation(summaries, marks, emptyMap()) { _, _ -> }
        val panel = ChangeSummaryTreePanel(SummariesCard(summaries, false, false), ChangeSummariesViewState(), 1.0,
            ChangeSummaryActions({}, {}, {}, {}, review = review), project)
        fun button(text: String) = descendants(panel).filterIsInstance<JButton>().single { it.text.contains(text) }
        fun dimmed(text: String) = button(text).foreground == UIUtil.getContextHelpForeground()
        try {
            assertTrue(button("Cache").text.contains("<small>") && button("Cache").text.contains("src"))
            assertFalse(button("Whole change").text.contains("<small>"))
            assertFalse(dimmed("Cache"))
            marks.set(listOf("src/a.kt"), true)
            updateReviewPresentation(panel)
            assertTrue(dimmed("Cache"))
            assertFalse(dimmed("Retries"))
            assertFalse(dimmed("Whole change"))
            marks.set(listOf("src/b.kt"), true)
            updateReviewPresentation(panel)
            assertTrue(dimmed("Retries"))
            assertTrue(dimmed("Whole change"))
        } finally { panel.dispose() }
    }

    fun testBranchesFoldAndFileLeavesUnfoldActualDiffs() {
        val file = ReviewFile("a.kt", "/repo/a.kt", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1", "old\n", "new\n")
        val tree = buildSummaryTree(listOf(file))
        val summaries = ReviewSummaries(tree, mapOf(
            0 to Summary(listOf(SummarySegment("Shared purpose", listOf(SummarySource.Claim(0, 0))))),
            tree.root.children.single() to Summary(listOf(SummarySegment("File behavior", listOf(SummarySource.Rows(1, 3))))),
        ))
        val state = ChangeSummariesViewState()
        val panel = ChangeSummaryTreePanel(SummariesCard(summaries, false, false), state, 1.0,
            ChangeSummaryActions({}, {}, {}, {}), project)
        fun button(text: String) = descendants(panel).filterIsInstance<JButton>().single { it.text.contains(text) }
        try {
            assertTrue(descendants(panel).none { it is DiffFileEditor })
            button("a.kt").doClick()
            assertEquals(1, descendants(panel).filterIsInstance<DiffFileEditor>().size)
            val editor = descendants(panel).filterIsInstance<DiffFileEditor>().single()
            val key = state.treeOpenedFiles.single()
            panel.resizeDiff(key, 1000, 500)
            assertSame(editor, descendants(panel).filterIsInstance<DiffFileEditor>().single())
            assertEquals(java.awt.Dimension(1000, 500), editor.parent.size)
            assertEquals(java.awt.Dimension(1000, 500), state.fork().treeDiffSizes[key])
            val before = panel.preferredSize
            panel.zoomTo(1.5)
            assertEquals(java.awt.Dimension(1500, 750), editor.parent.size)
            assertSame(editor, descendants(panel).filterIsInstance<DiffFileEditor>().single())
            assertTrue(panel.preferredSize.width > before.width)
            assertEquals(1.5, state.treeZoom)
            panel.zoomTo(100.0)
            assertEquals(3.0, panel.zoom)
            panel.actionMap.get("typed 0").actionPerformed(null)
            assertEquals(1.0, panel.zoom)
            button("a.kt").doClick()
            assertTrue(descendants(panel).none { it is DiffFileEditor })
            button("Shared purpose").doClick()
            assertTrue(descendants(panel).filterIsInstance<JButton>().none { it.text.contains("File behavior") })
            button("Shared purpose").doClick()
            assertNotNull(button("File behavior"))
            button("a.kt").doClick()
            val reopened = descendants(panel).filterIsInstance<DiffFileEditor>().single()
            assertEquals(java.awt.Dimension(1000, 500), reopened.parent.size)
            panel.resizeDiff(key, 0, 0)
            assertEquals(java.awt.Dimension(320, 160), reopened.parent.size)
        } finally { panel.dispose() }
    }
}
