package dev.insight.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffsWallModelTest {
    private fun file(path: String, patch: String, previousPath: String? = null) =
        ReviewFile(path, "/$path", previousPath, "modified", patch, path)

    private val metrics = WallMetrics(charWidth = 8, lineHeight = 16, headerHeight = 24, padding = 4, gap = 20)

    @Test
    fun linesKeepTheirMarkersAndNumberBothSides() {
        val lines = wallLines("@@ -3,3 +3,3 @@ fun a()\n same\n-old\n+new\n\tend\n[After: no newline at end of file]\n")
        assertEquals(listOf(WallLine.Kind.HUNK, WallLine.Kind.CONTEXT, WallLine.Kind.REMOVED, WallLine.Kind.ADDED,
            WallLine.Kind.CONTEXT, WallLine.Kind.NOTE), lines.map { it.kind })
        assertEquals("@@ -3,3 +3,3 @@ fun a()", lines[0].text)
        assertEquals(WallLine(WallLine.Kind.CONTEXT, " same", 3, 3), lines[1])
        assertEquals(WallLine(WallLine.Kind.REMOVED, "-old", old = 4), lines[2])
        assertEquals(WallLine(WallLine.Kind.ADDED, "+new", new = 4), lines[3])
        // A tab-led line is context; tabs are expanded so monospace columns line up.
        assertEquals(WallLine(WallLine.Kind.CONTEXT, "    end", 5, 5), lines[4])
    }

    @Test
    fun textBeforeTheFirstHunkIsANote() {
        val lines = wallLines("diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n")
        assertEquals(listOf(WallLine.Kind.NOTE, WallLine.Kind.NOTE, WallLine.Kind.NOTE, WallLine.Kind.HUNK,
            WallLine.Kind.REMOVED, WallLine.Kind.ADDED), lines.map { it.kind })
    }

    @Test
    fun blockCountsChangesTitlesRenamesAndCapsLines() {
        val patch = "@@ -1,2 +1,3 @@\n-a\n+b\n+c\n d\n"
        val block = wallBlock(file("src/New.kt", patch, previousPath = "src/Old.kt"), maxLines = 3)
        assertEquals("src/Old.kt → src/New.kt", block.title)
        assertEquals(2, block.added)
        assertEquals(1, block.removed)
        assertEquals(3, block.lines.size)
        assertEquals(2, block.hiddenLines)
        assertEquals("src/A.kt", wallBlock(file("src/A.kt", patch, previousPath = "src/A.kt")).title)
        assertEquals(0, wallBlock(file("src/A.kt", patch)).hiddenLines)
    }

    @Test
    fun heightCountsHeaderRowsAndTheCutOrEmptyNote() {
        val patch = "@@ -1 +1 @@\n-a\n+b\n"
        assertEquals(24 + 3 * 16 + 8, wallBlockHeight(wallBlock(file("a", patch)), metrics))
        assertEquals(24 + 3 * 16 + 8, wallBlockHeight(wallBlock(file("a", patch), maxLines = 2), metrics))
        assertEquals(24 + 16 + 8, wallBlockHeight(wallBlock(file("a", "")), metrics))
    }

    @Test
    fun widthFitsTheLongestRowWithinBounds() {
        val short = wallBlock(file("a", "@@ -1 +1 @@\n-a\n+b\n"))
        assertEquals(60 * 8 + 8, wallBlockWidth(listOf(short), metrics))
        val long = wallBlock(file("b", "@@ -1 +1 @@\n+" + "x".repeat(79) + "\n"))
        assertEquals(80 * 8 + 8, wallBlockWidth(listOf(short, long), metrics))
        val huge = wallBlock(file("c", "@@ -1 +1 @@\n+" + "x".repeat(500) + "\n"))
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
}

