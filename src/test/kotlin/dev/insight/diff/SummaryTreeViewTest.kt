package dev.insight.diff

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.review.*
import dev.insight.review.ui.*
import java.awt.Component
import java.awt.Container
import javax.swing.JButton

class SummaryTreeViewTest : BasePlatformTestCase() {
    private fun descendants(root: Container): List<Component> = root.components.flatMap {
        listOf(it) + ((it as? Container)?.let(::descendants) ?: emptyList())
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
        fun button(text: String) = descendants(panel).filterIsInstance<JButton>().single { it.text.endsWith(text) }
        try {
            assertTrue(descendants(panel).none { it is DiffFileEditor })
            button("a.kt").doClick()
            assertEquals(1, descendants(panel).filterIsInstance<DiffFileEditor>().size)
            button("a.kt").doClick()
            assertTrue(descendants(panel).none { it is DiffFileEditor })
            button("Shared purpose").doClick()
            assertTrue(descendants(panel).filterIsInstance<JButton>().none { it.text.endsWith("File behavior") })
            button("Shared purpose").doClick()
            assertNotNull(button("File behavior"))
        } finally { panel.dispose() }
    }
}
