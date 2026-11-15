package dev.insight.review

import dev.insight.codereview.CodeFile
import dev.insight.diff.ReviewFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewRadarTest {
    private fun diff(path: String, added: Int, removed: Int) = ReviewFile(path, "/repo/$path", null, "modified",
        "diff --git a/$path b/$path\n--- a/$path\n+++ b/$path\n@@ -1 +1 @@\n" + "-old\n".repeat(removed) + "+new\n".repeat(added) + " same\n",
        "$path:$added:$removed")

    @Test
    fun symbolCountsAggregateAcrossSummaryAndTopicLevels() {
        val symbols = RadarSymbols(mapOf(RadarSymbolKind.FUNCTION to 2, RadarSymbolKind.CONSTANT to 1, RadarSymbolKind.COMMENT to 3))
        val files = listOf(
            CodeFile("src/a.kt", "/repo/src/a.kt", "a\n", symbolCounts = symbols),
            CodeFile("src/b.kt", "/repo/src/b.kt", "b\n", symbolCounts = symbols),
            CodeFile("notes.txt", "/repo/notes.txt", "notes\n"),
        )
        val tree = buildSummaryTree(files)
        val summary = summaryRadar(tree, 1)
        assertTrue(RadarSeries.FUNCTIONS in summary.series)
        assertTrue(RadarSeries.SOURCE !in summary.series)
        assertEquals(4, summary.total.symbols[RadarSymbolKind.FUNCTION])
        assertEquals(6, summary.total.symbols[RadarSymbolKind.COMMENT])
        assertEquals(summary.total, summaryRadar(tree, summary.levels).total)
        assertEquals(listOf(RadarSeries.SOURCE), summaryRadar(tree, 1, symbols = false).series)
        val topics = topicOutline(ReviewTopics(files, mapOf("src/a.kt" to "Code", "src/b.kt" to "Code")))
        assertEquals(summary.total, topicRadar(topics, 0).total)
        assertEquals(summary.total, topicRadar(topics, topics.depth).total)
        assertEquals("6 symbols · 6 comments", radarValueText(summary.total, summary.series))
        assertEquals("6 comments", radarValueText(summary.total, summary.series, RadarSeries.COMMENTS))
        assertEquals("4 functions", radarValueText(summary.total, summary.series, RadarSeries.FUNCTIONS))
    }

    @Test
    fun symbolCappingKeepsTheLargestSymbolCounts() {
        val axes = (1..5).map {
            RadarAxis("a$it", "a$it", "a$it", emptyList(), RadarLines(source = 100 - it,
                symbols = RadarSymbols(mapOf(RadarSymbolKind.FUNCTION to it))))
        }
        val result = capped(axes, 3, listOf(RadarSeries.FUNCTIONS))
        assertEquals(listOf("a4", "a5", "Other (3)"), result.map { it.label })
        assertEquals(15, result.sumOf { it.lines.symbols[RadarSymbolKind.FUNCTION] })
    }

    @Test
    fun countsPatchRowsButNotFileHeaders() {
        assertEquals(RadarLines(added = 3, removed = 2), radarLines(diff("a.kt", 3, 2)))
    }

    @Test
    fun countsSourceLines() {
        assertEquals(3, radarLines(CodeFile("a.kt", "/repo/a.kt", "a\nb\nc\n")).source)
        assertEquals(2, radarLines(CodeFile("b.kt", "/repo/b.kt", "a\nb")).source)
        assertEquals(0, radarLines(CodeFile("c.kt", "/repo/c.kt", "")).source)
    }

    @Test
    fun summaryLevelsSumFoldersAndKeepShallowFiles() {
        val files = listOf(diff("src/app/a.kt", 1, 0), diff("src/app/b.kt", 2, 1), diff("src/lib/c.kt", 4, 0), diff("docs/d.md", 0, 5),
            diff("README.md", 1, 1))
        val tree = buildSummaryTree(files)
        val top = summaryRadar(tree, 1)
        assertEquals(listOf(RadarSeries.ADDED, RadarSeries.REMOVED), top.series)
        assertEquals(listOf("docs/", "src/", "README.md"), top.axes.map { it.label })
        assertEquals(RadarLines(added = 7, removed = 1), top.axes.single { it.label == "src/" }.lines)
        assertEquals("README.md", top.axes.last().path)
        val middle = summaryRadar(tree, 2)
        assertEquals(listOf("d.md", "app/", "lib/", "README.md"), middle.axes.map { it.label })
        val leaves = summaryRadar(tree, top.levels)
        assertEquals(files.size, leaves.axes.size)
        assertEquals(RadarLines(added = 8, removed = 7), leaves.total)
    }

    @Test
    fun summaryRadarStartsAtTheFirstLevelWithAFewSpokes() {
        val files = listOf("root/a/x.kt", "root/b/y.kt", "root/c/z.kt").map { CodeFile(it, "/repo/$it", "x\n") }
        val chart = summaryRadar(buildSummaryTree(files), null)
        assertEquals(listOf(RadarSeries.SOURCE), chart.series)
        assertEquals(3, chart.axes.size)
    }

    @Test
    fun topicLevelsSumTopicsAndShareOnePendingSpoke() {
        val files = listOf(diff("a.kt", 1, 0), diff("b.kt", 2, 0), diff("c.kt", 3, 0), diff("d.kt", 4, 0))
        val topics = ReviewTopics(files, mapOf("a.kt" to "UI > Panels", "b.kt" to "UI > Charts", "c.kt" to "Model > Counts"))
        val outline = topicOutline(topics)
        val broad = topicRadar(outline, 0)
        assertEquals(listOf("Model", "UI", "Awaiting topic"), broad.axes.map { it.label })
        assertEquals(3, broad.axes.single { it.label == "UI" }.lines.added)
        val every = topicRadar(outline, outline.depth)
        assertEquals(files.map { it.path }.toSet(), every.axes.mapNotNull { it.path }.toSet())
    }

    @Test
    fun manySpokesMergeTheSmallestIntoOther() {
        val axes = (1..50).map { RadarAxis("a$it", "a$it", "a$it", emptyList(), RadarLines(source = it)) }
        val capped = capped(axes, 10)
        assertEquals(10, capped.size)
        assertEquals("Other (41)", capped.last().label)
        assertTrue(capped.dropLast(1).all { it.lines.source > 41 })
        assertEquals(axes.sumOf { it.lines.source }, capped.sumOf { it.lines.source })
    }

    @Test
    fun scalesAreBoundedAndRingsInvertThem() {
        assertEquals(0.0, radarScale(0, 100, true), 0.0)
        assertEquals(1.0, radarScale(100, 100, false), 0.0)
        assertEquals(0.5, radarScale(50, 100, false), 1e-9)
        assertTrue(radarScale(10, 1000, true) > radarScale(10, 1000, false))
        assertEquals(1000, radarRingValue(4, 4, 1000, true))
        assertEquals(500, radarRingValue(2, 4, 1000, false))
    }
}
