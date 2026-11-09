package dev.insight.diff

import junit.framework.TestCase
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage

class DiffLineChartTest : TestCase() {
    fun testHalfCircleAreasAreProportionalToTheCounts() {
        val (deleted, added) = halfCircleRadii(ChangedLineCounts(deleted = 40, added = 10), 100.0)
        assertEquals(100.0, deleted, 1e-9)
        assertEquals(50.0, added, 1e-9)
        assertEquals(4.0, deleted * deleted / (added * added), 1e-9)
    }

    fun testMissingSideHasNoHalfCircle() {
        assertEquals(0.0 to 80.0, halfCircleRadii(ChangedLineCounts(deleted = 0, added = 3), 80.0))
        assertEquals(0.0 to 0.0, halfCircleRadii(ChangedLineCounts(), 80.0))
    }

    fun testPaintsInSmallAndEmptyBounds() {
        listOf(ChangedLineCounts(40, 10), ChangedLineCounts(), ChangedLineCounts(0, 7)).forEach { counts ->
            listOf(Rectangle(0, 0, 280, 180), Rectangle(10, 10, 40, 30)).forEach { bounds ->
                val graphics = BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB).createGraphics()
                try { DiffLineChart(counts).paint(graphics, bounds, Color.RED, Color.GREEN) } finally { graphics.dispose() }
            }
        }
    }
}
