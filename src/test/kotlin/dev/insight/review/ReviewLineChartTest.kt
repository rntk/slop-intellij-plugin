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
        assertEquals(3L, chart.root.value)
        assertEquals(3, chart.root.files)
        assertEquals(listOf("README.md", "src"), chart.children(chart.root).map { it.path })
        val src = chart.directory("src")
        assertEquals(3L, src.value)
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
        assertEquals(20L, chart.root.value)
        assertEquals(listOf(chart.root), chart.breadcrumbs(chart.root))
        assertEquals(chart.root, chart.directory("picked/src/gone"))
        assertEquals(chart.root, chart.directory("elsewhere"))
    }

    @Test fun `size uses UTF-8 bytes and items count each file including empty ones`() {
        val tree = buildSummaryTree(
            listOf(file("src/a.kt", "é\n"), file("src/nested/b.kt", "😀"), file("README.md", "")),
            maxChildren = 2,
            chunks = { listOf(SourceChunk(0, 1..1, "part")) },
        )
        val size = ReviewLineChart(tree, LineChartMetric.SIZE)
        val items = ReviewLineChart(tree, LineChartMetric.ITEMS)
        assertEquals(7L, size.root.value)
        assertEquals(3L, items.root.value)
        assertEquals(listOf("src/a.kt" to 3L, "src/nested" to 4L),
            size.children(size.directory("src")).map { it.path to it.value })
        assertEquals(listOf("README.md" to 1L, "src" to 2L),
            items.children(items.root).map { it.path to it.value })
    }

    @Test fun `removed directory falls back to surviving parent and empty scope is safe`() {
        val chart = ReviewLineChart(buildSummaryTree(listOf(file("src/a.kt", "x"), file("b.kt", "y\nz"))))
        assertEquals("src", chart.directory("src/removed/deeper").path)
        assertEquals(3L, chart.root.value)
        val empty = ReviewLineChart(buildSummaryTree(emptyList()))
        assertEquals(0L, empty.root.value)
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
        val added = ReviewLineChart(tree, LineChartMetric.ADDED)
        val removed = ReviewLineChart(tree, LineChartMetric.REMOVED)
        assertEquals(4L, added.root.value)
        assertEquals(3L, removed.root.value)
        assertEquals(3L, added.directory("src").value)
        assertEquals(3L, removed.directory("src").value)
        assertEquals(listOf("src/nested", "src/a.kt"), added.children(added.directory("src")).map { it.path })
        assertEquals(listOf("src/a.kt", "src/nested"), removed.children(removed.directory("src")).map { it.path })
        assertEquals(0L, removed.children(removed.root).single { it.path == "readme.md" }.value)
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

    private class FakeOutlines(val outlines: Map<String, SourceOutline>) : SourceOutlines {
        val loaded = mutableListOf<String>()
        var ready = false
        override fun cached(file: ReviewSource) = outlines[file.path]?.takeIf { ready }
        override fun load(file: ReviewSource, done: () -> Unit) { loaded += file.path; ready = true; done() }
    }

    private fun symbols(vararg counts: Pair<RadarSymbolKind, Int>) = RadarSymbols(counts.toMap())

    private val method = OutlineDeclaration(RadarSymbolKind.FUNCTION, "run", 3..7, 40, symbols(RadarSymbolKind.FUNCTION to 1))
    private val field = OutlineDeclaration(RadarSymbolKind.CONSTANT, "LIMIT", 2..2, 10, symbols(RadarSymbolKind.CONSTANT to 1))
    private val type = OutlineDeclaration(RadarSymbolKind.CLASS, "Worker", 1..8, 60,
        symbols(RadarSymbolKind.CLASS to 1, RadarSymbolKind.FUNCTION to 1, RadarSymbolKind.CONSTANT to 1), listOf(field, method))

    private fun parsed(path: String, text: String, counts: RadarSymbols) = CodeFile(path, "/repo/$path", text, symbolCounts = counts)

    @Test fun `lenses are offered for the kinds in scope, in lens order`() {
        val files = listOf(parsed("a.kt", "x", symbols(RadarSymbolKind.FUNCTION to 2, RadarSymbolKind.TEST to 1, RadarSymbolKind.CLASS to 1)),
            file("notes.txt", "y"))
        assertEquals(listOf("Classes", "Functions", "Tests"), LineChartMetric.lenses(files).map { it.label })
        assertEquals(LineChartMetric.lens(RadarSymbolKind.TEST), LineChartMetric.lenses(files).last())
        assertEquals("1 class", "1 ${LineChartMetric.lens(RadarSymbolKind.CLASS).unitText(1)}")
        assertEquals("properties", LineChartMetric.lens(RadarSymbolKind.PROPERTY).unitText(2))
    }

    @Test fun `a lens counts captured symbols and hides entries without them`() {
        val tree = buildSummaryTree(listOf(
            parsed("src/a.kt", "a", symbols(RadarSymbolKind.FUNCTION to 3)),
            parsed("src/b.kt", "b", symbols(RadarSymbolKind.CLASS to 1)),
            file("docs/readme.md", "c")))
        val chart = ReviewLineChart(tree, LineChartMetric.lens(RadarSymbolKind.FUNCTION))
        assertEquals(3L, chart.root.value)
        assertEquals(listOf("src"), chart.children(chart.root).map { it.path })
        assertEquals(listOf("src/a.kt" to 3L), chart.children(chart.directory("src")).map { it.path to it.value })
    }

    @Test fun `parsed files unfold into declarations and their members`() {
        val source = parsed("src/a.kt", "1\n2\n3\n4\n5\n6\n7\n8\n", symbols(RadarSymbolKind.CLASS to 1, RadarSymbolKind.FUNCTION to 1))
        val outlines = FakeOutlines(mapOf("src/a.kt" to SourceOutline(listOf(type))))
        val tree = buildSummaryTree(listOf(source, file("src/plain.txt", "x")))
        val chart = ReviewLineChart(tree, LineChartMetric.LINES, outlines)
        val src = chart.directory("src")
        assertEquals(listOf(true, false), chart.children(src).sortedBy { it.path }.map { it.expandable })

        // Unfolding before the outline is parsed shows a placeholder for the file.
        val pending = chart.rows(src, setOf("src/a.kt"))
        assertEquals(ReviewLineChart.Kind.PLACEHOLDER, pending.single { it.depth == 1 }.entry.kind)
        assertEquals("src/a.kt", pending.single { it.depth == 1 }.entry.filePath)

        outlines.ready = true
        val rows = chart.rows(src, setOf("src/a.kt", "src/a.kt#0"))
        assertEquals(listOf("src/plain.txt" to 0, "src/a.kt" to 0, "src/a.kt#0" to 1, "src/a.kt#0/0" to 2, "src/a.kt#0/1" to 2),
            rows.map { it.entry.path to it.depth })
        val worker = rows.single { it.entry.path == "src/a.kt#0" }.entry
        assertEquals(8L, worker.value)
        assertTrue(worker.expandable)
        assertEquals(listOf(1L, 5L), rows.filter { it.depth == 2 }.map { it.entry.value })
        assertEquals("run", chart.declaration("src/a.kt#0/1")?.label)
        assertNull(chart.declaration("src/a.kt#3"))
        assertTrue(chart.hasEntry("src/a.kt#0/1"))
        assertEquals(listOf("src/a.kt"), chart.files("src/a.kt#0/1").map { it.path })

        // A lens hides members without what it counts; sizes use declaration bytes.
        val functions = ReviewLineChart(tree, LineChartMetric.lens(RadarSymbolKind.FUNCTION), outlines)
        assertEquals(listOf("run"), functions.rows(functions.directory("src"), setOf("src/a.kt", "src/a.kt#0"))
            .filter { it.depth == 2 }.map { it.entry.label })
        val size = ReviewLineChart(tree, LineChartMetric.SIZE, outlines)
        assertEquals(60L, size.declaration("src/a.kt#0")?.value)
        // File counts and diff rows have no per-declaration value, so files stay leaves.
        val items = ReviewLineChart(tree, LineChartMetric.ITEMS, outlines)
        assertFalse(items.children(items.directory("src")).any { it.expandable })
    }

    @Test fun `isBeneath covers directories, outlines and members`() {
        assertTrue(ReviewLineChart.isBeneath("src/a.kt", "src"))
        assertTrue(ReviewLineChart.isBeneath("src/a.kt#0", "src/a.kt"))
        assertTrue(ReviewLineChart.isBeneath("src/a.kt#0/1", "src/a.kt#0"))
        assertFalse(ReviewLineChart.isBeneath("src/a.kt#10", "src/a.kt#1"))
        assertFalse(ReviewLineChart.isBeneath("src2/a.kt", "src"))
    }
}
