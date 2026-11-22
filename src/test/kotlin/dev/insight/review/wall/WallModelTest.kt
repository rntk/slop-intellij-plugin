package dev.insight.review.wall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallModelTest {
    private val metrics = WallMetrics(charWidth = 8, lineHeight = 16, headerHeight = 24, padding = 4, gap = 20)

    private fun block(vararg rows: String, hidden: Int = 0) =
        WallBlock("a", "a", "", rows.map { WallLine(WallLine.Kind.CONTEXT, it) }, hidden)

    @Test
    fun skimBlockShowsTheKeptCutRowsAndNotesOnlyTheRest() {
        var extraLoaded = false
        val kept = WallBlock(
            "a",
            "a",
            "",
            listOf(WallLine(WallLine.Kind.CONTEXT, " a")),
            3,
            listOf(WallLine(WallLine.Kind.ADDED, "+b")),
            loadMore = { limit ->
                extraLoaded = true
                WallBlock(
                    "a",
                    "a",
                    "",
                    listOf(WallLine(WallLine.Kind.CONTEXT, " a")),
                    3,
                    listOf(
                        WallLine(WallLine.Kind.ADDED, "+b"),
                        WallLine(WallLine.Kind.ADDED, "+c"),
                        WallLine(WallLine.Kind.ADDED, "+d"),
                    ).take(limit - 1),
                )
            },
        )
        val skim = wallSkimBlock(kept)
        assertEquals(listOf(" a", "+b"), skim.lines.map { it.text })
        assertEquals(2, skim.hiddenLines)
        assertTrue(skim.more.isEmpty())

        // Equality ignores the loadMore lambda identity, while wallLoadMoreLines materializes the remaining rows.
        assertEquals(kept, kept.copy(loadMore = null))
        val loaded = wallLoadMoreLines(skim)
        assertTrue(extraLoaded)
        assertEquals(listOf(" a", "+b", "+c", "+d"), loaded.lines.map { it.text })
        assertEquals(0, loaded.hiddenLines)
        assertNull(loaded.loadMore)

        val plain = block("x", hidden = 4)
        assertTrue(wallSkimBlock(plain) === plain)
        assertTrue(wallLoadMoreLines(plain) === plain)
    }

    @Test
    fun skimWidthFillsThePageAtTheZoomButNotBelowTheMinimum() {
        assertEquals(1000 - 40 - 18, wallSkimWidth(1000, 1.0, 20, 18, 300))
        assertEquals(2000 - 40 - 18, wallSkimWidth(1000, 0.5, 20, 18, 300))
        assertEquals(300, wallSkimWidth(200, 1.0, 20, 18, 300))
    }

    @Test
    fun stickyHeaderFollowsTheVisibleTopWithinTheBlock() {
        assertEquals(0, wallStickyOffset(0, 500, 24))
        assertEquals(0, wallStickyOffset(-10, 500, 24))
        assertEquals(120, wallStickyOffset(120, 500, 24))
        assertEquals(476, wallStickyOffset(490, 500, 24))
        assertEquals(0, wallStickyOffset(30, 20, 24))
    }

    @Test
    fun nextTopSkipsTheCurrentPositionEitherWay() {
        val tops = listOf(100, 300, 700)
        assertEquals(300, wallNextTop(tops, 100, forward = true))
        assertEquals(100, wallNextTop(tops, 250, forward = false))
        assertEquals(300, wallNextTop(tops, 302, forward = false))
        // A pixel of rounding still counts as being at the top.
        assertEquals(100, wallNextTop(tops, 301, forward = false))
        assertNull(wallNextTop(tops, 700, forward = true))
        assertNull(wallNextTop(tops, 100, forward = false))
    }

    @Test
    fun sourceLinesAreNumberedWithTabsExpanded() {
        val lines = wallSourceLines("fun a() {\n\treturn 1\n}\n", firstLine = 9)
        assertEquals(listOf(WallLine(WallLine.Kind.SOURCE, "fun a() {", new = 9), WallLine(WallLine.Kind.SOURCE, "    return 1", new = 10),
            WallLine(WallLine.Kind.SOURCE, "}", new = 11)), lines)
        assertEquals(2, wallSourceLines("a\r\nb\r\nc", maxLines = 2).size)
        assertTrue(wallSourceLines("").isEmpty())
    }

    @Test
    fun gutterFitsTheWidestSourceNumberAndWidensTheBlock() {
        val source = WallBlock("a", "a", "", wallSourceLines("x\n".repeat(120)), 0)
        assertEquals(4, wallGutterChars(source))
        assertEquals(0, wallGutterChars(block("+x")))
        val long = WallBlock("b", "b", "", listOf(WallLine.source(100, "x".repeat(79))), 0)
        assertEquals(83 * 8 + 8, wallBlockWidth(listOf(long), metrics))
    }

    @Test
    fun heightCountsHeaderRowsAndTheCutOrEmptyNote() {
        assertEquals(24 + 3 * 16 + 8, wallBlockHeight(block("@@", "-a", "+b"), metrics))
        assertEquals(24 + 3 * 16 + 8, wallBlockHeight(block("@@", "-a", hidden = 1), metrics))
        assertEquals(24 + 16 + 8, wallBlockHeight(block(), metrics))
    }

    @Test
    fun widthFitsTheLongestRowWithinBounds() {
        val short = block("-a", "+b")
        assertEquals(60 * 8 + 8, wallBlockWidth(listOf(short), metrics))
        val long = block("+" + "x".repeat(79))
        assertEquals(80 * 8 + 8, wallBlockWidth(listOf(short, long), metrics))
        val huge = block("+" + "x".repeat(500))
        assertEquals(96 * 8 + 8, wallBlockWidth(listOf(huge), metrics))
    }

    @Test
    fun layoutFillsTheShortestColumnInOrder() {
        val rects = wallLayout(listOf(100, 30, 30, 30), width = 200, columns = 2, gap = 10)
        assertEquals(listOf(WallRect(10, 10, 200, 100), WallRect(220, 10, 200, 30), WallRect(220, 50, 200, 30),
            WallRect(220, 90, 200, 30)), rects)
        assertEquals(WallRect(0, 0, 430, 130), wallExtent(rects, 10))
    }

    @Test
    fun layoutKeepsAtLeastOneColumnAndNoMoreColumnsThanBlocks() {
        val one = wallLayout(listOf(10, 10), width = 200, columns = 0, gap = 10)
        assertEquals(listOf(10, 10), one.map { it.x })
        assertEquals(listOf(10, 30), one.map { it.y })
        val wide = wallLayout(listOf(10, 10), width = 100, columns = 5, gap = 10)
        assertEquals(listOf(10, 120), wide.map { it.x })
        assertTrue(wallLayout(emptyList(), 100, 3, 10).isEmpty())
    }

    @Test
    fun columnsFitTheViewportAndSpreadATallWallToItsShape() {
        // Fits whole in the columns that fit: no more.
        assertEquals(2, wallColumns(List(3) { 50 }, width = 100, gap = 10, viewportWidth = 300, viewportHeight = 300))
        // Too tall in two columns: four make the wall as square as the viewport.
        assertEquals(4, wallColumns(List(16) { 100 }, width = 100, gap = 10, viewportWidth = 300, viewportHeight = 300))
        // A wide viewport spreads twenty tall blocks over as many columns as allowed.
        assertEquals(12, wallColumns(List(20) { 500 }, 100, 10, 1000, 600))
        assertEquals(10, wallColumns(List(20) { 500 }, 100, 10, 1000, 600, maxColumns = 10))
        assertEquals(2, wallColumns(List(2) { 1000 }, 100, 10, 300, 300))
        assertEquals(1, wallColumns(listOf(100), 400, 10, 100, 300))
        assertEquals(2, wallColumns(List(16) { 100 }, 100, 10, 300, 0))
        assertEquals(1, wallColumns(emptyList(), 100, 10, 300, 300))
    }

    @Test
    fun zoomStepsClampAndStopAtOneHundredPercent() {
        assertEquals(1.25, wallZoomBy(1.0, 1.25), 1e-9)
        assertEquals(1.0, wallZoomBy(0.9, 1.25), 1e-9)
        assertEquals(1.0, wallZoomBy(1.2, 0.8), 1e-9)
        assertEquals(WALL_MAX_ZOOM, wallZoomBy(2.8, 1.25), 1e-9)
        assertEquals(WALL_MIN_ZOOM, wallZoomBy(0.11, 0.8), 1e-9)
    }

    @Test
    fun fitAndInitialZoomShowTheWallButStayReadable() {
        assertEquals(0.5, wallFitZoom(WallRect(0, 0, 2000, 1000), 1000, 1000), 1e-9)
        assertEquals(WALL_MAX_ZOOM, wallFitZoom(WallRect(0, 0, 10, 10), 1000, 1000), 1e-9)
        assertEquals(1.0, wallFitZoom(WallRect(0, 0, 0, 0), 1000, 1000), 1e-9)
        assertEquals(WALL_MIN_AUTO_ZOOM, wallInitialZoom(WallRect(0, 0, 2000, 1000), 1000, 1000), 1e-9)
        assertEquals(0.8, wallInitialZoom(WallRect(0, 0, 1250, 500), 1000, 1000), 1e-9)
        assertEquals(1.0, wallInitialZoom(WallRect(0, 0, 500, 500), 1000, 1000), 1e-9)
    }

    @Test
    fun zoomAroundKeepsTheContentUnderThePointer() {
        assertEquals(100.0, wallZoomAround(viewPosition = 0.0, pointer = 100.0, offset = 0.0, from = 1.0, to = 2.0), 1e-9)
        // Below a 30 px label row: the point 100 px into the surface moves to 200 px and stays under the pointer.
        assertEquals(100.0, wallZoomAround(0.0, 130.0, 30.0, 1.0, 2.0), 1e-9)
        assertEquals(100.0, wallZoomAround(300.0, 100.0, 0.0, 2.0, 1.0), 1e-9)
    }

    @Test
    fun readingOrderGoesByRowsThenLeftToRight() {
        val rects = listOf(WallRect(10, 10, 100, 40), WallRect(220, 10, 100, 40), WallRect(10, 200, 100, 40), WallRect(220, 60, 100, 40))
        assertEquals(listOf(0, 1, 3, 2), wallReadingOrder(rects, tolerance = 20))
        // Tops within the tolerance share a row, read left to right.
        val moved = listOf(WallRect(220, 10, 100, 40), WallRect(10, 25, 100, 40), WallRect(10, 200, 100, 40))
        assertEquals(listOf(1, 0, 2), wallReadingOrder(moved, tolerance = 20))
        assertEquals(listOf(0, 1, 2), wallReadingOrder(moved, tolerance = 5))
    }

    @Test
    fun nextBlockWrapsAndStartsAtAnEnd() {
        val order = listOf(2, 0, 1)
        assertEquals(2, wallNextBlock(order, null, forward = true))
        assertEquals(1, wallNextBlock(order, null, forward = false))
        assertEquals(2, wallNextBlock(order, 1, forward = true))
        assertEquals(1, wallNextBlock(order, 2, forward = false))
        assertEquals(1, wallNextBlock(order, 0, forward = true))
        assertEquals(null, wallNextBlock(emptyList(), null, forward = true))
    }

    @Test
    fun focusZoomFitsTheBlockWidthWithinReadableBounds() {
        assertEquals(1.5, wallFocusZoom(500, 1000, current = 0.6, margin = 20), 1e-9)
        assertEquals(1.2, wallFocusZoom(500, 640, current = 0.6, margin = 20), 1e-9)
        assertEquals(1.0, wallFocusZoom(500, 400, current = 0.6, margin = 20), 1e-9)
        // An already readable zoom is not reduced.
        assertEquals(2.0, wallFocusZoom(500, 1000, current = 2.0, margin = 20), 1e-9)
        // A compact block is fitted whole, at a readable zoom.
        assertEquals(1.4, wallFocusZoom(500, 1000, current = 2.0, margin = 20, blockHeight = 400, viewportHeight = 600), 1e-9)
        assertEquals(1.0, wallFocusZoom(500, 1000, current = 0.6, margin = 20, blockHeight = 800, viewportHeight = 600), 1e-9)
    }

    @Test
    fun focusPositionCentresTheBlockOrShowsItsTop() {
        val rect = WallRect(100, 200, 400, 300)
        assertEquals(java.awt.Point(0, 80), wallFocusPosition(rect, 1.0, 0, 30, 800, 600, margin = 20))
        assertEquals(java.awt.Point(1000, 410), wallFocusPosition(WallRect(500, 200, 400, 300), 2.0, 0, 30, 800, 600, margin = 20))
        assertEquals(java.awt.Point(0, 210), wallFocusPosition(WallRect(100, 200, 400, 1000), 1.0, 0, 30, 800, 600, margin = 20))
    }

    @Test
    fun compactHeightIsAShareOfTheViewportInWholeRows() {
        assertEquals(32 + 35 * 16, wallCompactHeight(1000, 1.0, metrics))
        assertEquals(32 + 16 * 16, wallCompactHeight(1000, 2.0, metrics))
        assertEquals(32 + 3 * 16, wallCompactHeight(10, 1.0, metrics))
    }

    @Test
    fun onlyBlocksFarTallerThanTheViewportOpenCompact() {
        assertTrue(wallDefaultCompact(1600, 1000, 1.0))
        assertFalse(wallDefaultCompact(1400, 1000, 1.0))
        assertFalse(wallDefaultCompact(1600, 1000, 0.5))
        assertFalse(wallDefaultCompact(1600, 0, 1.0))
    }

    @Test
    fun bodyScrollStaysWithinTheHiddenRows() {
        assertEquals(50, wallBodyScroll(0, 50, height = 500, cap = 200))
        assertEquals(300, wallBodyScroll(250, 100, height = 500, cap = 200))
        assertEquals(0, wallBodyScroll(10, -50, height = 500, cap = 200))
        assertEquals(0, wallBodyScroll(0, 50, height = 100, cap = 200))
    }

    @Test
    fun rowAtCountsTheBodyScroll() {
        assertNull(wallRowAt(10.0, 0, metrics))
        assertNull(wallRowAt(26.0, 0, metrics))
        assertEquals(0, wallRowAt(28.0, 0, metrics))
        assertEquals(1, wallRowAt(45.0, 0, metrics))
        assertEquals(2, wallRowAt(28.0, 32, metrics))
        // Rows scrolled up under the header are not hit through it.
        assertNull(wallRowAt(20.0, 32, metrics))
    }

    @Test
    fun summaryTextWrapsAtWordsAndKeepsLineBreaks() {
        assertEquals(listOf("one two", "three", "", "four"), wallWrap("one two three\n\nfour", 8))
        assertEquals(listOf("abcd", "efgh", "ij"), wallWrap("abcdefghij", 4))
        assertEquals(listOf("ab", "cdef", "gh"), wallWrap("ab cdefgh", 4).let { listOf(it[0], it[1], it[2]) })
        assertEquals(listOf(""), wallWrap("  ", 10))
    }

    @Test
    fun summaryScrollStaysWithinTheRowsThatDoNotFit() {
        assertEquals(0, wallSummaryScroll(0, -3, total = 10, visible = 4))
        assertEquals(6, wallSummaryScroll(5, 9, total = 10, visible = 4))
        assertEquals(0, wallSummaryScroll(0, 3, total = 3, visible = 4))
        // The body shows what is left under the header and padding, and at least one row.
        assertEquals(4, wallBodyRows(24 + 2 * 4 + 4 * 16, metrics))
        assertEquals(1, wallBodyRows(10, metrics))
    }

    @Test
    fun chunkSelectionAndContinuationTextAreGenerousAndOrdered() {
        assertTrue(WALL_LOAD_CHUNK >= 50)
        val all = (0 until 10).map { "src/File$it.kt" }
        val initial = selectInitialWallPaths(all, limit = 4, pinned = setOf("src/File8.kt"))
        assertEquals(listOf("src/File0.kt", "src/File1.kt", "src/File2.kt", "src/File3.kt", "src/File8.kt"), initial.toList())
        assertEquals(all.toSet(), selectInitialWallPaths(all, limit = 20))

        val batch1 = selectNextWallPaths(all, initial, limit = 3)
        assertEquals(listOf("src/File4.kt", "src/File5.kt", "src/File6.kt"), batch1)
        val loaded = initial + batch1
        val batch2 = selectNextWallPaths(all, loaded, limit = 3)
        assertEquals(listOf("src/File7.kt", "src/File9.kt"), batch2)
        assertTrue(selectNextWallPaths(all, loaded + batch2, limit = 3).isEmpty())

        assertEquals("Load next 60", wallLoadMoreAction(100, 60))
        assertEquals("Load all 40", wallLoadMoreAction(40, 60))
        assertEquals("Load next 60 (60 / 150)", wallLoadMoreHeaderText(60, 150, 60))
        assertEquals("Load all 40 (60 / 100)", wallLoadMoreHeaderText(60, 100, 60))
        assertEquals("+1 more file · 9 of 10 shown", wallMoreCardTitle(9, 10))
        assertEquals("+40 more files · 60 of 100 shown", wallMoreCardTitle(60, 100))
    }

    @Test
    fun activeSummaryPlannerKeepsAllUnderLimitAndPrioritizesActiveAndVisible() {
        val all = (0 until 8).map { "f$it.kt" }.toSet()
        assertEquals(all, selectActiveSummaryPaths(all, emptyMap(), null, "f0.kt", limit = 8))

        val rects = mapOf(
            "f0.kt" to WallRect(5000, 0, 200, 200),
            "f1.kt" to WallRect(10, 10, 200, 200),
            "f2.kt" to WallRect(250, 10, 200, 200),
            "f3.kt" to WallRect(1200, 0, 200, 200),
            "f4.kt" to WallRect(3000, 0, 200, 200),
        )
        val viewport = WallRect(0, 0, 500, 500)
        val selected = selectActiveSummaryPaths(rects.keys, rects, viewport, activePath = "f0.kt", limit = 3)
        assertEquals(setOf("f0.kt", "f1.kt", "f2.kt"), selected)
    }

    @Test
    fun sourceLineCountingAndCappedParsingMatchAcrossLineEndings() {
        assertEquals(0, countWallSourceLines(""))
        assertEquals(0, countWallSourceLines("\n"))
        assertEquals(0, countWallSourceLines("\r\n"))
        assertEquals(2, countWallSourceLines("a\r\nb\r\n"))
        assertEquals(2, countWallSourceLines("a\n\n"))

        val big = (1..1000).joinToString("\r\n") { "line $it" } + "\r\n"
        assertEquals(1000, countWallSourceLines(big))
        val capped = wallSourceLines(big, firstLine = 1, maxLines = 15)
        assertEquals(15, capped.size)
        assertEquals("line 1", capped.first().text)
        assertEquals("line 15", capped.last().text)
        assertEquals(15, capped.last().new)
    }
}

