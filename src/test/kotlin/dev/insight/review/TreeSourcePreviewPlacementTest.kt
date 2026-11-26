package dev.insight.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeSourcePreviewPlacementTest {
    private fun place(anchorTop: Int, naturalHeight: Int, anchorLeft: Int = 40, anchorRight: Int = 300, width: Int = 400) =
        treePreviewPlacement(
            viewWidth = 1200, viewHeight = 800,
            anchorTop = anchorTop, anchorBottom = anchorTop + 24, anchorLeft = anchorLeft, anchorRight = anchorRight,
            width = width, naturalHeight = naturalHeight, margin = 10, gap = 16, minWidth = 300, minHeight = 100,
        )

    @Test
    fun shortContentOpensBesideItsRowOnTheRight() {
        val placement = place(anchorTop = 400, naturalHeight = 150)
        assertEquals(PreviewSide.RIGHT, placement.side)
        assertEquals(1200 - 10, placement.right)
        assertEquals(400, placement.y)
        assertEquals(150, placement.height)
    }

    @Test
    fun shortContentNearTheBottomSlidesUpButStillCoversItsRow() {
        val placement = place(anchorTop = 760, naturalHeight = 150)
        assertEquals(800 - 10, placement.bottom)
        assertTrue(placement.y <= 760 && placement.bottom >= 784)
    }

    @Test
    fun contentTallerThanTheBoardFillsItsHeightFromTheTop() {
        val placement = place(anchorTop = 500, naturalHeight = 5000)
        assertEquals(10, placement.y)
        assertEquals(780, placement.height)
    }

    @Test
    fun tallContentFavorsTheTopAndBarelyMovesBetweenRows() {
        val upper = place(anchorTop = 300, naturalHeight = 700)
        val lower = place(anchorTop = 500, naturalHeight = 700)
        assertTrue(upper.y < 100)
        assertTrue(kotlin.math.abs(upper.y - lower.y) < 40)
        for (placement in listOf(upper, lower)) assertTrue(placement.y >= 10 && placement.bottom <= 790)
        assertTrue(lower.y <= 500 && lower.bottom >= 524)
    }

    @Test
    fun mediumContentMovesTowardTheTopMoreThanShortContent() {
        val short = place(anchorTop = 500, naturalHeight = 200)
        val medium = place(anchorTop = 500, naturalHeight = 450)
        assertEquals(500, short.y)
        assertTrue(medium.y < short.y)
        assertTrue(medium.bottom >= 524)
    }

    @Test
    fun docksLeftOnlyWhenTheRightWouldCoverItsRow() {
        assertEquals(PreviewSide.LEFT, place(anchorTop = 100, naturalHeight = 200, anchorLeft = 700, anchorRight = 1000).side)
        // No room on the left either: stays right.
        assertEquals(PreviewSide.RIGHT, place(anchorTop = 100, naturalHeight = 200, anchorLeft = 200, anchorRight = 1000).side)
    }

    @Test
    fun widthStaysWithinTheBoard() {
        val placement = treePreviewPlacement(500, 400, 100, 124, 0, 50, 2000, 300, 10, 16, 300, 100)
        assertEquals(480, placement.width)
        assertEquals(10, placement.x)
    }

    @Test
    fun pointerHeadingForTheWindowIsRecognized() {
        val window = PreviewPlacement(800, 100, 390, 600, PreviewSide.RIGHT)
        assertTrue(headingToPreview(400, 380, 420, 390, window, 24))
        assertFalse(headingToPreview(400, 380, 380, 390, window, 24))
        assertFalse(headingToPreview(400, 380, 402, 460, window, 24))
    }

    @Test
    fun patchCountsSkipHeaders() {
        assertEquals(2 to 1, patchLineCounts("--- a/x\n+++ b/x\n@@ -1 +1,2 @@\n-old\n+new\n+more\n context"))
    }
}
