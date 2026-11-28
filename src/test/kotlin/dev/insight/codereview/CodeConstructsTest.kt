package dev.insight.codereview

import dev.insight.diff.FileMetrics
import dev.insight.diff.Metric
import junit.framework.TestCase

class CodeConstructsTest : TestCase() {
    private fun file(path: String, vararg counts: Pair<Metric, Int>, skipped: String? = null) =
        FileMetrics(path, "added", emptyMap(), counts.toMap(), emptyList(), skipped)

    fun testCountsConstructsOfEveryFileAndTheFilesHoldingThem() {
        val constructs = codeConstructs(listOf(
            file("a.kt", Metric.BRANCHES to 3, Metric.LOOPS to 1, Metric.CODE_LINES to 40),
            file("b.kt", Metric.BRANCHES to 2, Metric.RETURNS to 4),
            file("c.kt", Metric.LOOPS to 9, skipped = "Not source code"),
        ))
        assertEquals(listOf("if", "return", "loop"), constructs.words.map { it.word })
        assertEquals(CodeWord("if", 5, mapOf("a.kt" to 3, "b.kt" to 2)), constructs.words[0])
        assertEquals("only constructs are counted, not line counts or skipped files", 10, constructs.totalWords)
        assertEquals(3, constructs.distinctWords)
    }

    fun testNoConstructsWhenNothingWasAnalyzed() {
        assertTrue(codeConstructs(listOf(file("a.txt", skipped = "Not source code"))).words.isEmpty())
    }
}
