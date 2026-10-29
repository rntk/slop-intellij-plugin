package dev.insight.review.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class InspectionReportTest {
    @Test fun `problems are listed one per line in path order with collapsed messages`() {
        val report = formatInspectionReport("src/", "/repo", 2, listOf("src/Gone.kt"), listOf(
            InspectionProblem("src/B.kt", 3, 1, "WARNING", "Unused\n  variable 'x'"),
            InspectionProblem("src/A.kt", 12, 5, "ERROR", "Unresolved reference 'y'"),
            InspectionProblem("src/A.kt", 2, 9, "WARNING", "Redundant cast"),
        ))
        assertEquals("""
            # IntelliJ IDEA inspection results for src/
            # Paths are relative to /repo; positions are 1-based line:column.
            # Inspected 2 files: 2 warnings, 1 error.
            # Not inspected (missing from the working tree): src/Gone.kt

            src/A.kt:2:9: WARNING: Redundant cast
            src/A.kt:12:5: ERROR: Unresolved reference 'y'
            src/B.kt:3:1: WARNING: Unused variable 'x'

        """.trimIndent(), report)
    }

    @Test fun `a clean scope says so`() {
        assertEquals("""
            # IntelliJ IDEA inspection results for All changes
            # Inspected 1 file: no errors or warnings.

        """.trimIndent(), formatInspectionReport("All changes", null, 1, emptyList(), emptyList()))
    }
}
