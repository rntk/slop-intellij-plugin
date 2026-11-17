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
    fun `children and grandchildren follow a parent pushed down by a growing neighbour`() {
        val above = CanvasLayoutItem("above", null, 0, 100, 100)
        val parent = CanvasLayoutItem("parent", null, 0, 100, 100)
        val child = CanvasLayoutItem("child", "parent", 30, 100, 50)
        val grandchild = CanvasLayoutItem("grandchild", "child", 20, 100, 50)
        val items = listOf(above, parent, child, grandchild)
        val before = canvasLayout(items, 100, options)
        val grown = canvasLayout(listOf(above.copy(height = 500)) + items.drop(1), 100, options)
        for (id in listOf("parent", "child", "grandchild")) {
            assertEquals(before.getValue(id).y + 400, grown.getValue(id).y)
        }
        assertEquals(before, canvasLayout(items, 100, options))
    }

    @Test
    fun `moving an anchor moves its child and descendants`() {
        val parent = CanvasLayoutItem("parent", null, 100, 100, 200)
        val child = CanvasLayoutItem("child", "parent", 30, 100, 50)
        val grandchild = CanvasLayoutItem("grandchild", "child", 20, 100, 50)
        val before = canvasLayout(listOf(parent, child, grandchild), 100, options)
        val moved = canvasLayout(listOf(parent, child.copy(anchorY = 230), grandchild), 100, options)
        assertEquals(before["parent"], moved["parent"])
        for (id in listOf("child", "grandchild")) {
            assertEquals(before.getValue(id).y + 200, moved.getValue(id).y)
        }
    }

    @Test
    fun `grow then shrink or close releases collision space`() {
        val a = CanvasLayoutItem("a", null, 0, 100, 100)
        val b = CanvasLayoutItem("b", null, 200, 100, 100)
        val child = CanvasLayoutItem("child", "b", 30, 100, 50)
        val before = canvasLayout(listOf(a, b, child), 100, options)
        val grown = canvasLayout(listOf(a.copy(height = 500), b, child), 100, options)
        assertEquals(510, grown.getValue("b").y)
        val shrunk = canvasLayout(listOf(a, b, child), 100, options)
        assertEquals(before, shrunk)
        val closed = canvasLayout(listOf(b, child), 100, options)
        assertEquals(before["b"], closed["b"])
        assertEquals(before["child"], closed["child"])
    }

    @Test
    fun `children reorder by current anchors after source layout changes`() {
        val parent = CanvasLayoutItem("parent", null, 0, 100, 300)
        val a = CanvasLayoutItem("a", "parent", 30, 100, 50)
        val b = CanvasLayoutItem("b", "parent", 130, 100, 50)
        val before = canvasLayout(listOf(parent, a, b), 100, options)
        assertEquals(25, before.getValue("a").y)
        assertEquals(125, before.getValue("b").y)
        val moved = canvasLayout(listOf(parent, a.copy(anchorY = 230), b), 100, options)
        assertEquals(225, moved.getValue("a").y)
        assertEquals(125, moved.getValue("b").y)
    }

    @Test
    fun `animated reflow returns upward even when growth reverses mid transition`() {
        val a = CanvasLayoutItem("a", null, 0, 100, 100)
        val b = CanvasLayoutItem("b", null, 200, 100, 100)
        val motion = CanvasMotion(100)
        fun layout(height: Int, now: Long, animate: Boolean) {
            canvasLayout(listOf(a.copy(height = height), b), 100, options).forEach { (id, at) ->
                motion.target(id, at, now, animate)
            }
        }
        layout(100, 0, false)
        val original = motion.position("b", 0)
        layout(500, 0, true)
        val movingDown = motion.position("b", 50)
        org.junit.Assert.assertTrue(movingDown.y > original.y)
        layout(100, 50, true)
        assertEquals(movingDown, motion.position("b", 50))
        assertEquals(original, motion.position("b", 150))
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
