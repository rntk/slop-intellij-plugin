package dev.insight.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanvasCardResizeTest {
    @Test
    fun `handles lie within the zone at the right and bottom edges, the corner twice as deep`() {
        fun at(x: Int, y: Int) = canvasResizeEdge(x, y, width = 400, height = 300, zone = 6)
        assertEquals(CanvasResizeEdge.RIGHT, at(395, 100))
        assertEquals(CanvasResizeEdge.RIGHT, at(399, 0))
        assertEquals(CanvasResizeEdge.BOTTOM, at(100, 294))
        assertEquals(CanvasResizeEdge.CORNER, at(399, 299))
        assertEquals(CanvasResizeEdge.CORNER, at(388, 288))
        assertNull(at(393, 100))
        assertNull(at(100, 100))
        assertNull(at(0, 299 - 6))
        assertNull(at(400, 100))
        assertNull(at(-1, 299))
        assertNull(canvasResizeEdge(399, 299, 400, 300, zone = 0))
    }

    @Test
    fun `dragging changes only what the handle resizes, within bounds`() {
        assertEquals(CardSize(width = 900), canvasResizedSize(null, CanvasResizeEdge.RIGHT, 1030, 700, -130, 50))
        assertEquals(CardSize(1030, 750), canvasResizedSize(CardSize(width = 1030), CanvasResizeEdge.BOTTOM, 1030, 700, 999, 50))
        assertEquals(CardSize(1100, 750), canvasResizedSize(CardSize(height = 300), CanvasResizeEdge.CORNER, 1030, 700, 70, 50))
        assertEquals(CardSize(CARD_MIN_WIDTH, CARD_MIN_HEIGHT), canvasResizedSize(null, CanvasResizeEdge.CORNER, 1030, 700, -5000, -5000))
        assertEquals(CardSize(CARD_MAX_WIDTH, 300), canvasResizedSize(CardSize(height = 300), CanvasResizeEdge.RIGHT, 1030, 700, 9000, 0))
    }

    @Test
    fun `double-clicking a handle resets what it resizes`() {
        val size = CardSize(800, 600)
        assertEquals(CardSize(height = 600), canvasResetSize(size, CanvasResizeEdge.RIGHT))
        assertEquals(CardSize(width = 800), canvasResetSize(size, CanvasResizeEdge.BOTTOM))
        assertNull(canvasResetSize(size, CanvasResizeEdge.CORNER))
        assertNull(canvasResetSize(CardSize(width = 800), CanvasResizeEdge.RIGHT))
        assertNull(canvasResetSize(null, CanvasResizeEdge.BOTTOM))
    }

    @Test
    fun `the viewer takes what a resized height leaves, keeping a few lines`() {
        assertEquals(640, canvasViewerMaxHeight(null, chrome = 300, minViewer = 96, autoMax = 640))
        assertEquals(900, canvasViewerMaxHeight(1200, chrome = 300, minViewer = 96, autoMax = 640))
        assertEquals(100, canvasViewerMaxHeight(400, chrome = 300, minViewer = 96, autoMax = 640))
        assertEquals(96, canvasViewerMaxHeight(200, chrome = 300, minViewer = 96, autoMax = 640))
    }

    @Test
    fun `sizes are bounded and automatic when neither dimension is set`() {
        assertNull(CardSize().bounded())
        assertEquals(CardSize(width = CARD_MIN_WIDTH), CardSize(width = 1).bounded())
        assertEquals(CardSize(height = CARD_MAX_HEIGHT), CardSize(height = Int.MAX_VALUE).bounded())
    }
}
