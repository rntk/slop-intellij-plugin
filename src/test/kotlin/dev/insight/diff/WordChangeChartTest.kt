package dev.insight.diff

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage

class WordChangeChartTest : BasePlatformTestCase() {
    private fun file(path: String, patch: String) = ReviewFile(path, "/$path", null, "modified", patch, path)

    fun testCountsOnlyRemovedAndAddedLinesOfEveryHunk() {
        val changes = wordChanges(listOf(
            file("a.kt", "@@ -1,3 +1,3 @@\n context stays\n-val oldName = load()\n+val newName = load()\n"),
            file("b.kt", "@@ -1,1 +1,2 @@\n+val newName = 2\n+x\n"),
        ))
        val byWord = changes.words.associateBy { it.word }
        assertNull("context lines are not counted", byWord["context"])
        assertNull("single letters are not counted", byWord["x"])
        assertEquals(WordChange("oldName", 1, 0, setOf("a.kt")), byWord["oldName"])
        assertEquals(WordChange("newName", 0, 2, setOf("a.kt", "b.kt")), byWord["newName"])
        assertEquals(WordChange("val", 1, 2, setOf("a.kt", "b.kt")), byWord["val"])
        assertEquals(3, changes.removedWords)
        assertEquals(5, changes.addedWords)
        assertTrue(changes.side(byWord.getValue("oldName")) < 0.5)
        assertTrue(changes.side(byWord.getValue("newName")) > 0.5)
    }

    fun testCanvasShowsChartInItsOwnTabAndPlacesWordsWithoutOverlap() {
        val files = listOf(file("a.kt", "@@ -1,2 +1,2 @@\n" + (1..80).joinToString("") { "-removed$it common\n+added$it common\n" }))
        val canvas = NoteCanvas({}, {}).apply { setSize(1200, 800) }
        try {
            canvas.showNotes(emptyList(), words = wordChanges(files), wordFiles = files)
            assertEquals("Word changes", canvas.tabs.getTitleAt(0))
            val chart = descendants(canvas).filterIsInstance<WordChangeChart>().single()
            chart.setSize(chart.preferredSize)
            chart.paint(BufferedImage(chart.width, chart.height, BufferedImage.TYPE_INT_ARGB).createGraphics())
            val placed = chart.placedWords()
            assertTrue("placed ${placed.size} of 161, chart ${chart.width}x${chart.height}", placed.size > 100)
            placed.values.forEachIndexed { i, a -> placed.values.drop(i + 1).forEach { b -> assertFalse(a.intersects(b)) } }
            val middle = chart.width / 2
            assertTrue(placed.getValue("removed1").centerX < middle)
            assertTrue(placed.getValue("added1").centerX > middle)
            assertTrue(placed.getValue("common").let { it.x < middle && it.x + it.width > middle })
        } finally { canvas.dispose() }
    }

    fun testWordsOfOneSideStayOnThatSideWhenTheOtherHasNoWords() {
        val added = wordChanges(listOf(file("a.kt", "@@ -0,0 +1,40 @@\n" + (1..40).joinToString("") { "+val added$it = shared\n" })))
        assertEquals(0, added.removedWords)
        added.words.forEach { assertTrue("${it.word} at ${added.side(it)}", added.side(it) > 0.5) }
        val removed = wordChanges(listOf(file("a.kt", "@@ -1,40 +0,0 @@\n" + (1..40).joinToString("") { "-val removed$it = shared\n" })))
        removed.words.forEach { assertTrue("${it.word} at ${removed.side(it)}", removed.side(it) < 0.5) }

        val chart = WordChangeChart(added, 1000, 600) {}.apply { setSize(preferredSize) }
        chart.paint(BufferedImage(chart.width, chart.height, BufferedImage.TYPE_INT_ARGB).createGraphics())
        val placed = chart.placedWords()
        assertTrue(placed.isNotEmpty())
        placed.forEach { (word, bounds) -> assertTrue("$word at $bounds", bounds.x >= chart.width / 2) }
    }

    fun testScopesListEveryFileAndDirectoryAndLimitTheChart() {
        val files = listOf(
            file("src/a/One.kt", "@@ -1 +1 @@\n-oldOne\n+newOne\n"),
            file("src/b/Two.kt", "@@ -1 +1 @@\n+newTwo\n"),
            file("README.md", "@@ -1 +1 @@\n+readme\n"),
        )
        val scopes = wordScopes(files)
        assertEquals(listOf("", "README.md", "src", "src/a", "src/a/One.kt", "src/b", "src/b/Two.kt"), scopes.map { it.path })
        assertEquals(2, scopes.single { it.path == "src" }.files)
        val canvas = NoteCanvas({}, {}).apply { setSize(1200, 800) }
        try {
            canvas.showNotes(emptyList(), words = wordChanges(files), wordFiles = files)
            fun chartWords() = descendants(canvas).filterIsInstance<WordChangeChart>().single().changes.words.map { it.word }.toSet()
            assertEquals(setOf("oldOne", "newOne", "newTwo", "readme"), chartWords())
            canvas.chooseWordScope(scopes.single { it.path == "src/b" })
            assertEquals(setOf("newTwo"), chartWords())
            assertEquals(ChangedLineCounts(0, 1),
                descendants(canvas).filterIsInstance<WordChangeChart>().single().changes.lines)
            assertEquals("src/b/ · 1", descendants(canvas).filterIsInstance<javax.swing.JButton>().single { it.name == "word-scope" }.text)
            canvas.showNotes(emptyList(), words = wordChanges(files.take(1)), wordFiles = files.take(1))
            assertEquals("a scope without changed files falls back to all files", setOf("oldOne", "newOne"), chartWords())
        } finally { canvas.dispose() }
    }

    fun testLineCountsIncludeBlankAndPunctuationLinesAndIgnoreHeadersContextAndTheWordLimit() {
        val changes = wordChanges(listOf(
            file("a.kt", "diff --git a/a.kt b/a.kt\n--- a/a.kt\n+++ b/a.kt\n" +
                "@@ -1,3 +1,4 @@\n context ignored\n-oldWord oldWord\n-\n+newWord\n+\n+{}\n" +
                "\\ No newline at end of file\n@@ -10 +11 @@\n-x\n+123\n"),
            file("b.kt", "--- /dev/null\n+++ b/b.kt\n@@ -0,0 +1,2 @@\n+otherName\n+}\n"),
        ), limit = 1)
        assertEquals(listOf("oldWord"), changes.words.map { it.word })
        assertEquals(ChangedLineCounts(deleted = 3, added = 6), changes.lines)
    }

    fun testLineCountsHandleEmptyAndOneSidedChanges() {
        assertEquals(ChangedLineCounts(), wordChanges(emptyList()).lines)
        assertEquals(ChangedLineCounts(), wordChanges(listOf(file("image.png", "Binary files differ"))).lines)
        assertEquals(ChangedLineCounts(0, 2), wordChanges(listOf(file("a.kt", "@@ -0,0 +1,2 @@\n+\n+}\n"))).lines)
        assertEquals(ChangedLineCounts(2, 0), wordChanges(listOf(file("a.kt", "@@ -1,2 +0,0 @@\n-\n-}\n"))).lines)
    }

    fun testWordClicksAndTooltipsRemainAvailableAroundTheLineChart() {
        val changes = wordChanges(listOf(file("a.kt", "@@ -1 +1 @@\n-oldWord\n+newWord\n")))
        val opened = mutableListOf<WordChange>()
        val chart = DiffOverviewChart(changes, 1000, 600) { opened += it }.apply { setSize(preferredSize) }
        val word = chart.placedWords().single { it.change.word == "newWord" }.bounds
        fun event(x: Int, y: Int) = MouseEvent(chart, MouseEvent.MOUSE_CLICKED, 0, 0, x, y, 1, false, MouseEvent.BUTTON1)
        val click = event(word.centerX.toInt(), word.centerY.toInt())
        chart.dispatchEvent(click)
        assertEquals(listOf("newWord"), opened.map { it.word })
        assertTrue(chart.getToolTipText(click)!!.contains("+1 added"))
        val center = chart.lineChartBounds
        val chartClick = event(center.centerX.toInt(), center.centerY.toInt())
        chart.dispatchEvent(chartClick)
        assertEquals(1, opened.size)
        assertEquals("Deleted lines: 1 · Added lines: 1 in this scope", chart.getToolTipText(chartClick))
    }

    private fun descendants(component: Container): List<java.awt.Component> = component.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
}
