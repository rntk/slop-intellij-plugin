package dev.insight.review

import dev.insight.codereview.CodeFile
import org.junit.Assert.*
import org.junit.Test

class ReviewLineChartTest {
    private fun file(path: String, text: String) = CodeFile(path, "/repo/$path", text)

    @Test fun `shows immediate entries and aggregates scoped files without counting chunks twice`() {
        val tree = buildSummaryTree(listOf(file("src/a.kt", "a\nb\n"), file("src/nested/b.kt", "c"),
            file("README.md", "")), maxChildren = 2, chunks = { listOf(SourceChunk(0, 1..1, "part")) })
        val chart = ReviewLineChart(tree)
        assertEquals(3L, chart.root.lines)
        assertEquals(3, chart.root.files)
        assertEquals(listOf("README.md", "src"), chart.children(chart.root).map { it.path })
        val src = chart.directory("src")
        assertEquals(3L, src.lines)
        assertEquals(2, src.files)
        assertEquals(listOf("src/nested", "src/a.kt"), chart.children(src).map { it.path })
        assertEquals(listOf("", "src", "src/nested"), chart.breadcrumbs(chart.directory("src/nested")).map { it.path })
    }

    @Test fun `unfolded directories list their entries beneath them, one level deeper`() {
        val chart = ReviewLineChart(buildSummaryTree(listOf(file("src/a.kt", "a\nb\n"), file("src/nested/b.kt", "c"),
            file("README.md", ""))))
        assertEquals(listOf("README.md" to 0, "src" to 0), chart.rows(chart.root, emptySet()).map { it.entry.path to it.depth })
        // A nested directory stays folded until it is unfolded too; folded ancestors hide unfolded descendants.
        assertEquals(listOf("README.md" to 0, "src" to 0, "src/nested" to 1, "src/a.kt" to 1),
            chart.rows(chart.root, setOf("src")).map { it.entry.path to it.depth })
        assertEquals(listOf("README.md" to 0, "src" to 0, "src/nested" to 1, "src/nested/b.kt" to 2, "src/a.kt" to 1),
            chart.rows(chart.root, setOf("src", "src/nested")).map { it.entry.path to it.depth })
        assertEquals(listOf("README.md", "src"), chart.rows(chart.root, setOf("src/nested")).map { it.entry.path })
        assertEquals(listOf(false, true), chart.rows(chart.root, setOf("src", "README.md")).take(2).map { it.expanded })
    }

    @Test fun `summary groups never hide siblings and common root is absorbed`() {
        val files = (1..20).map { file("picked/src/f$it.kt", "x\n") }
        val chart = ReviewLineChart(buildSummaryTree(files, maxChildren = 2))
        assertEquals("picked/src", chart.root.path)
        assertEquals(20, chart.children(chart.root).size)
        assertEquals(20L, chart.root.lines)
        assertEquals(listOf(chart.root), chart.breadcrumbs(chart.root))
        assertEquals(chart.root, chart.directory("picked/src/gone"))
        assertEquals(chart.root, chart.directory("elsewhere"))
    }

    @Test fun `removed directory falls back to surviving parent and empty scope is safe`() {
        val chart = ReviewLineChart(buildSummaryTree(listOf(file("src/a.kt", "x"), file("b.kt", "y\nz"))))
        assertEquals("src", chart.directory("src/removed/deeper").path)
        assertEquals(3L, chart.root.lines)
        val empty = ReviewLineChart(buildSummaryTree(emptyList()))
        assertEquals(0L, empty.root.lines)
        assertTrue(empty.children(empty.root).isEmpty())
    }

    @Test fun `diff counts selected patch rows and aggregates directories`() {
        fun patch(path: String, rows: String) = dev.insight.diff.ReviewFile(
            path, "/repo/$path", null, "modified",
            "--- a/$path\n+++ b/$path\n@@ -1,2 +1,2 @@\n$rows", path)
        val tree = buildSummaryTree(listOf(
            patch("src/a.kt", "-old\n+new\n+extra\n context"),
            patch("src/nested/b.kt", "-one\n-two\n+three"),
            patch("readme.md", "+hello")))
        val added = ReviewLineChart(tree, RadarSeries.ADDED)
        val removed = ReviewLineChart(tree, RadarSeries.REMOVED)
        assertEquals(4L, added.root.lines)
        assertEquals(3L, removed.root.lines)
        assertEquals(3L, added.directory("src").lines)
        assertEquals(3L, removed.directory("src").lines)
        assertEquals(listOf("src/nested", "src/a.kt"), added.children(added.directory("src")).map { it.path })
        assertEquals(listOf("src/a.kt", "src/nested"), removed.children(removed.directory("src")).map { it.path })
        assertEquals(0L, removed.children(removed.root).single { it.path == "readme.md" }.lines)
    }

    @Test
    fun dotLeaderRightAlignsNumbersInFixedColumns() {
        assertEquals("......1", dotLeader("1", 7))
        assertEquals("..1,234", dotLeader("1,234", 7))
        assertEquals("12,345,678", dotLeader("12,345,678", 7))
    }

    @Test fun `files and hasEntry resolve files, directories, absorbed roots, and missing paths`() {
        val tree = buildSummaryTree(listOf(file("src/a.kt", "a"), file("src/nested/b.kt", "b"), file("README.md", "c")))
        val chart = ReviewLineChart(tree)
        assertTrue(chart.hasEntry(""))
        assertTrue(chart.hasEntry("src"))
        assertTrue(chart.hasEntry("src/nested"))
        assertTrue(chart.hasEntry("src/a.kt"))
        assertFalse(chart.hasEntry("missing"))
        assertEquals(listOf("src/nested/b.kt", "src/a.kt", "README.md"), chart.files("").map { it.path })
        assertEquals(listOf("src/nested/b.kt", "src/a.kt"), chart.files("src").map { it.path })
        assertEquals(listOf("src/nested/b.kt"), chart.files("src/nested").map { it.path })
        assertEquals(listOf("README.md"), chart.files("README.md").map { it.path })
        assertTrue(chart.files("missing").isEmpty())

        val absorbed = ReviewLineChart(buildSummaryTree(listOf(file("picked/src/a.kt", "a"), file("picked/src/sub/b.kt", "b"))))
        assertTrue(absorbed.hasEntry("picked/src"))
        assertFalse(absorbed.hasEntry("picked/src/gone"))
        assertEquals(listOf("picked/src/sub/b.kt", "picked/src/a.kt"), absorbed.files("picked/src").map { it.path })
        assertEquals(listOf("picked/src/sub/b.kt"), absorbed.files("picked/src/sub").map { it.path })
        assertTrue(absorbed.files("picked/src/gone").isEmpty())
    }
}
