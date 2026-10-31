package dev.insight.canvas

import org.junit.Assert.assertEquals
import org.junit.Test

class CanvasLayoutTest {
    private val options = CanvasLayoutOptions(left = 0, top = 0, arrowGap = 50, gap = 10, anchorLift = 5)

    @Test
    fun `columns follow depth and the widest card of each column`() {
        val placed = canvasLayout(listOf(
            CanvasLayoutItem("a", null, anchorY = 100, width = 300, height = 80),
            CanvasLayoutItem("b", null, anchorY = 20, width = 200, height = 50),
            CanvasLayoutItem("a1", "a", anchorY = 30, width = 250, height = 40),
            CanvasLayoutItem("a1x", "a1", anchorY = 0, width = 100, height = 40),
        ), rootWidth = 600, options)
        assertEquals(CanvasPlacement(650, 15), placed["b"])
        assertEquals(CanvasPlacement(650, 95), placed["a"])
        assertEquals(CanvasPlacement(650 + 300 + 50, 120), placed["a1"])
        assertEquals(CanvasPlacement(1000 + 250 + 50, 115), placed["a1x"])
    }

    @Test
    fun `cards of a column keep their parents' order and never overlap`() {
        val placed = canvasLayout(listOf(
            CanvasLayoutItem("p", null, 0, 100, 300),
            CanvasLayoutItem("q", null, 0, 100, 100),
            CanvasLayoutItem("q1", "q", 0, 100, 50),
            CanvasLayoutItem("p2", "p", 200, 100, 50),
            CanvasLayoutItem("p1", "p", 10, 100, 50),
        ), rootWidth = 100, options)
        assertEquals(0, placed["p"]!!.y)
        assertEquals(310, placed["q"]!!.y)
        assertEquals(listOf(5, 195, 305), listOf("p1", "p2", "q1").map { placed[it]!!.y })
    }

    @Test
    fun `missing parents and cycles fall back to the first column`() {
        val placed = canvasLayout(listOf(
            CanvasLayoutItem("x", "gone", 0, 100, 10),
            CanvasLayoutItem("c1", "c2", 0, 100, 10),
            CanvasLayoutItem("c2", "c1", 0, 100, 10),
        ), rootWidth = 100, options)
        assertEquals(150, placed["x"]!!.x)
        assertEquals(3, placed.size)
    }
}
