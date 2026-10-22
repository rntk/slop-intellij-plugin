package dev.insight.diff

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Container
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

    private fun descendants(component: Container): List<java.awt.Component> = component.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
}
