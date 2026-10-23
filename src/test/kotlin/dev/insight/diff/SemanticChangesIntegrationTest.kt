package dev.insight.diff

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The counting engine feeding the views, without hand-built metrics. */
class SemanticChangesIntegrationTest : BasePlatformTestCase() {
    fun testCountedChangesDriveTableScatterAndConstructCloud() {
        val before = """
            fun check(x: Int): Int {
                return x
            }
            fun gone() = 2
        """.trimIndent() + "\n"
        val after = """
            fun check(x: Int): Int {
                if (x > 10 && x < 20) return 1
                for (i in 0 until x) { if (i == 5) break }
                return x
            }
            fun fresh(a: String) = a.length
        """.trimIndent() + "\n"
        val changes = ApplicationManager.getApplication().runReadAction<SemanticChanges> {
            semanticChanges(project, listOf(ReviewFile("a.kt", "/missing/a.kt", null, "modified", "patch", "a", before, after)))
        }
        assertNull(changes.files.single().skipped)

        val rows = SemanticMetricsTable(changes, 900) {}.rows().filter { it.metric != null }.associateBy { it.metric }
        assertEquals("+2", rows.getValue(Metric.BRANCHES).deltaText)
        assertEquals("+1", rows.getValue(Metric.LOOPS).deltaText)
        assertTrue(rows.getValue(Metric.CYCLOMATIC).delta > 0)

        val points = SemanticComplexityChart(changes, Metric.CYCLOMATIC, 900, 500) {}.placedPoints().associateBy { it.declaration.qualifiedName }
        assertEquals(setOf("check", "gone", "fresh"), points.keys)
        val check = points.getValue("check").declaration
        assertEquals(1, check.value(Metric.CYCLOMATIC, false))
        assertTrue("check got more complex: $check", check.value(Metric.CYCLOMATIC, true)!! >= 5)
        assertNull(points.getValue("fresh").declaration.before)
        assertNull(points.getValue("gone").declaration.after)

        val constructs = constructChanges(changes).words.associateBy { it.word }
        assertEquals(2, constructs.getValue(Metric.BRANCHES.label).added)
        assertEquals(1, constructs.getValue(Metric.LOOPS.label).added)
        assertEquals(1, constructs.getValue(Metric.LOGICAL_OPERATORS.label).added)
    }
}
