package dev.insight.diff

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JComboBox
import java.awt.Container
import java.awt.image.BufferedImage
import dev.insight.review.ui.NoteCanvas

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

    fun testBothViewsShareOneTabAndFollowScopeChanges() {
        val files = listOf(file("a.kt", "@@ -1 +1 @@\n-oldOne\n+newOne\n"), file("b.kt", "@@ -0,0 +1 @@\n+newTwo\n"))
        val counts = wordChanges(files)
        val cards = DiffCanvasCards({})
        val canvas = NoteCanvas({}, {}, extension = cards)
        try {
            canvas.showNotes(cards, DiffCanvasContent(words = counts, wordFiles = files))
            canvas.focusWords()
            assertEquals(1, canvas.tabs.tabCount)
            assertEquals("Words", canvas.tabs.getTitleAt(0))
            val chart = descendants(canvas).filterIsInstance<WordChangeChart>().single()

            chooseView(canvas, 1)
            assertEquals(1, canvas.tabs.tabCount)
            assertEquals(0, canvas.tabs.selectedIndex)
            val overview = descendants(canvas).filterIsInstance<DiffOverviewChart>().single()
            assertSame(counts, overview.changes)
            assertTrue(descendants(canvas).any { it is WordBalanceChart })

            chooseView(canvas, 0)
            assertSame(chart, descendants(canvas).filterIsInstance<WordChangeChart>().single())
            chooseView(canvas, 1)
            assertSame(overview, descendants(canvas).filterIsInstance<DiffOverviewChart>().single())
            cards.chooseWordScope(wordScopes(files).single { it.path == "b.kt" })
            val scoped = descendants(canvas).filterIsInstance<DiffOverviewChart>().single().changes
            assertEquals(setOf("newTwo"), scoped.words.map { it.word }.toSet())
            assertEquals(ChangedLineCounts(0, 1), scoped.lines)

            chooseView(canvas, 0)
            assertSame(scoped, descendants(canvas).filterIsInstance<WordChangeChart>().single().changes)
        } finally { canvas.dispose() }
    }

    fun testChangesWithoutWordsStillShowTheLineChart() {
        val files = listOf(file("a.kt", "@@ -1 +1,2 @@\n-\n+\n+}\n"))
        val counts = wordChanges(files)
        assertTrue(counts.words.isEmpty())
        val cards = DiffCanvasCards({})
        val canvas = NoteCanvas({}, {}, extension = cards)
        try {
            canvas.showNotes(cards, DiffCanvasContent(words = counts, wordFiles = files))
            chooseView(canvas, 1)
            val chart = descendants(canvas).filterIsInstance<DiffOverviewChart>().single()
            assertEquals(ChangedLineCounts(1, 2), chart.changes.lines)
            chart.setSize(chart.preferredSize)
            val graphics = BufferedImage(chart.width, chart.height, BufferedImage.TYPE_INT_ARGB).createGraphics()
            try { chart.paint(graphics) } finally { graphics.dispose() }
        } finally { canvas.dispose() }
    }

    private fun chooseView(canvas: NoteCanvas, index: Int) {
        descendants(canvas).filterIsInstance<JComboBox<*>>().single { it.name == "words-view" }.selectedIndex = index
    }

    private fun descendants(component: Container): List<java.awt.Component> = component.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
}
