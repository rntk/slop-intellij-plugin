package dev.insight.diff

import junit.framework.TestCase
import java.awt.image.BufferedImage

class WordBalanceChartTest : TestCase() {
    private fun file(patch: String) = ReviewFile("a.kt", "/a.kt", null, "modified", patch, "a.kt")

    fun testWordsRemovedAndAddedEquallyOftenAreMatched() {
        val changes = wordChanges(listOf(file("@@ -1,6 +1,6 @@\n-catch one\n-catch two\n-catch gone\n+catch one\n+catch two\n+catch fresh\n+fresh\n")))
        // catch 3/3, one 1/1, two 1/1 are matched; gone is removed only; fresh is added twice.
        assertEquals(WordBalance(removed = 1, matched = 5, added = 2), changes.balance)
    }

    fun testMatchedCountsFrequencyAndIgnoresTheSurplus() {
        val balance = wordChanges(listOf(file("@@ -1 +1 @@\n-catch catch catch catch catch\n+catch catch\n"))).balance
        assertEquals(WordBalance(removed = 3, matched = 2, added = 0), balance)
    }

    fun testMatchedWordsAreCountedBeyondTheListedWords() {
        val body = (1..10).joinToString("") { "-word$it\n+word$it\n" }
        val changes = wordChanges(listOf(file("@@ -1,10 +1,10 @@\n$body")), limit = 3)
        assertEquals(3, changes.words.size)
        assertEquals(WordBalance(0, 10, 0), changes.balance)
        assertEquals(1.0, changes.balance.matchedShare, 0.0)
    }

    fun testMatchedShareOfRealChangesAndNoChange() {
        assertEquals(0.0, wordChanges(listOf(file("@@ -1 +1 @@\n-oldWord\n+newWord\n"))).balance.matchedShare, 0.0)
        assertEquals(WordBalance(0, 0, 0), wordChanges(emptyList()).balance)
        assertEquals(0.0, WordBalance(0, 0, 0).matchedShare, 0.0)
        assertEquals(0.5, WordBalance(1, 1, 1).matchedShare, 1e-9)
    }

    fun testChartPaintsAllSegments() {
        val chart = WordBalanceChart(WordBalance(10, 80, 5), 600).apply { setSize(preferredSize) }
        val graphics = BufferedImage(chart.width, chart.height, BufferedImage.TYPE_INT_ARGB).createGraphics()
        try { chart.paint(graphics) } finally { graphics.dispose() }
    }
}
