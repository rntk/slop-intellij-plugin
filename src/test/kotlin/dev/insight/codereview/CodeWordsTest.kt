package dev.insight.codereview

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.image.BufferedImage

class CodeWordsTest : BasePlatformTestCase() {
    fun testCountsEveryWordOfTwoOrMoreCharactersAndTheFilesOfTheMostFrequent() {
        val words = codeWords(listOf(
            codeFile("a.kt", "val total = load(x)\nval count = total + 1\n"),
            codeFile("b.kt", "val other = total\n"),
        ), limit = 3)
        assertEquals(listOf("total", "val", "count"), words.words.map { it.word })
        assertEquals(CodeWord("total", 3, mapOf("a.kt" to 2, "b.kt" to 1)), words.words[0])
        assertEquals(CodeWord("count", 1, mapOf("a.kt" to 1)), words.words[2])
        assertEquals("single letters are not counted", 9, words.totalWords)
        assertEquals(5, words.distinctWords)
    }

    fun testCloudPlacesFrequentWordsInTheMiddleWithoutOverlap() {
        val files = (1..60).map { i -> codeFile("f$i.kt", "common ".repeat(40) + "word$i ".repeat(1 + i % 5)) }
        val cloud = CodeWordCloud(codeWords(files), 1000, 600) { _, _ -> }.apply { setSize(preferredSize) }
        cloud.paint(BufferedImage(cloud.width, cloud.height, BufferedImage.TYPE_INT_ARGB).createGraphics())
        val placed = cloud.placedWords()
        assertEquals(61, placed.size)
        placed.values.forEachIndexed { i, a -> placed.values.drop(i + 1).forEach { b -> assertFalse(a.intersects(b)) } }
        placed.values.forEach { assertTrue(it.toString(), it.x >= 0 && it.y >= 0 && it.maxX <= 1000 && it.maxY <= 600) }
        assertTrue(placed.getValue("common").contains(500, 300))
    }
}
