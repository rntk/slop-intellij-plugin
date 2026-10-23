package dev.insight.diff

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.InplaceButton
import java.awt.Container
import java.awt.image.BufferedImage

class DiffOverviewChartTest : BasePlatformTestCase() {
    private fun file(path: String, patch: String) = ReviewFile(path, "/$path", null, "modified", patch, path)
    private fun chart(words: List<WordChange>) = DiffOverviewChart(
        WordChanges(words, words.sumOf { it.removed }, words.sumOf { it.added }), 1000, 600, {},
    ).apply { setSize(preferredSize) }

    fun testDenseListsFillOppositeCornersWithoutOverlappingTheChartOrEachOther() {
        val words = (1..100).map { WordChange("word" + "Long".repeat(it % 4) + it, it % 5 + 1, it % 3 + 1, setOf("a.kt")) }
        val chart = chart(words)
        val placed = chart.placedWords()
        assertEquals(200, placed.size)
        placed.forEachIndexed { i, entry ->
            assertTrue(chart.bounds.contains(entry.bounds))
            assertFalse(chart.lineChartBounds.intersects(entry.bounds))
            placed.drop(i + 1).forEach { assertFalse("${entry.text} and ${it.text}", entry.bounds.intersects(it.bounds)) }
        }
        val removed = placed.filterNot { it.added }
        val added = placed.filter { it.added }
        assertTrue(removed.first().bounds.x < 10)
        assertTrue(removed.first().bounds.y < 40)
        assertTrue(added.first().bounds.maxX > chart.width - 10)
        assertTrue(added.first().bounds.maxY > chart.height - 50)
        assertEquals(words.maxOf { it.word.length }, removed.first().change.word.length)
        assertEquals(words.maxOf { it.word.length }, added.first().change.word.length)
        assertTrue("Removed rows extend downwards", removed.last().bounds.y > removed.first().bounds.y)
        assertTrue("Added rows extend upwards", added.last().bounds.y < added.first().bounds.y)
        val graphics = BufferedImage(chart.width, chart.height, BufferedImage.TYPE_INT_ARGB).createGraphics()
        try { chart.paint(graphics) } finally { graphics.dispose() }
    }

    fun testEachListUsesOnlyItsOwnWordsIncludingSharedWords() {
        val chart = chart(listOf(WordChange("removed", 3, 0, setOf("a.kt")), WordChange("added", 0, 4, setOf("a.kt")),
            WordChange("shared", 2, 8, setOf("a.kt"))))
        assertEquals(setOf("removed", "shared"), chart.placedWords().filterNot { it.added }.map { it.text }.toSet())
        assertEquals(setOf("added", "shared"), chart.placedWords().filter { it.added }.map { it.text }.toSet())
        assertTrue(chart(listOf(WordChange("onlyAdded", 0, 1, setOf("a.kt")))).placedWords().all { it.added })
        assertTrue(chart(emptyList()).placedWords().isEmpty())
    }

    fun testBothViewsAreSeparateAndFollowScopeChangesAndIndependentRemoval() {
        val files = listOf(file("a.kt", "@@ -1 +1 @@\n-oldOne\n+newOne\n"), file("b.kt", "@@ -0,0 +1 @@\n+newTwo\n"))
        val counts = wordChanges(files)
        var removeWords = 0
        var removeOverview = 0
        val canvas = NoteCanvas({}, {}, removeWords = { removeWords++ }, removeDiffOverview = { removeOverview++ })
        try {
            canvas.showNotes(emptyList(), words = counts, wordFiles = files, diffOverview = counts)
            assertEquals(listOf("Word changes", "Diff overview"), (0 until canvas.tabs.tabCount).map { canvas.tabs.getTitleAt(it) })
            assertEquals(1, descendants(canvas).filterIsInstance<WordChangeChart>().size)
            assertEquals(1, descendants(canvas).filterIsInstance<DiffOverviewChart>().size)
            canvas.focusDiffOverview()
            assertEquals("Diff overview", canvas.tabs.getTitleAt(canvas.tabs.selectedIndex))
            canvas.chooseWordScope(wordScopes(files).single { it.path == "b.kt" })
            assertEquals(setOf("newTwo"), descendants(canvas).filterIsInstance<WordChangeChart>().single().changes.words.map { it.word }.toSet())
            assertEquals(setOf("newTwo"), descendants(canvas).filterIsInstance<DiffOverviewChart>().single().changes.words.map { it.word }.toSet())
            assertEquals(ChangedLineCounts(0, 1), descendants(canvas).filterIsInstance<DiffOverviewChart>().single().changes.lines)
            descendants(canvas).filterIsInstance<InplaceButton>().single { it.toolTipText == "Remove diff overview" }.doClick()
            assertEquals(1, removeOverview)
            assertEquals(0, removeWords)
            canvas.showNotes(emptyList(), words = counts, wordFiles = files)
            assertEquals("Word changes", canvas.tabs.getTitleAt(0))
            assertEquals(1, canvas.tabs.tabCount)
            assertTrue(descendants(canvas).none { it is DiffOverviewChart })
            canvas.showNotes(emptyList(), wordFiles = files, diffOverview = counts)
            assertEquals("Diff overview", canvas.tabs.getTitleAt(0))
            assertTrue(descendants(canvas).none { it is WordChangeChart })
        } finally { canvas.dispose() }
    }

    fun testVisibilityIsIndependentAndClearTabsClearsBoth() {
        val state = DiffReviewState()
        state.showWords()
        assertFalse(state.diffOverviewVisible)
        state.showDiffOverview()
        state.hideWords()
        assertTrue(state.diffOverviewVisible)
        state.showWords()
        state.hideDiffOverview()
        assertTrue(state.wordsVisible)
        state.showDiffOverview()
        state.clearNotes()
        assertFalse(state.wordsVisible)
        assertFalse(state.diffOverviewVisible)
    }

    fun testChangesWithoutWordsStillShowTheLineChart() {
        val files = listOf(file("a.kt", "@@ -1 +1,2 @@\n-\n+\n+}\n"))
        val counts = wordChanges(files)
        assertTrue(counts.words.isEmpty())
        val canvas = NoteCanvas({}, {})
        try {
            canvas.showNotes(emptyList(), wordFiles = files, diffOverview = counts)
            val chart = descendants(canvas).filterIsInstance<DiffOverviewChart>().single()
            assertEquals(ChangedLineCounts(1, 2), chart.changes.lines)
            chart.setSize(chart.preferredSize)
            val graphics = BufferedImage(chart.width, chart.height, BufferedImage.TYPE_INT_ARGB).createGraphics()
            try { chart.paint(graphics) } finally { graphics.dispose() }
        } finally { canvas.dispose() }
    }

    private fun descendants(component: Container): List<java.awt.Component> = component.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
}
