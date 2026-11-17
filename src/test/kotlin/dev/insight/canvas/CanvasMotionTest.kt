package dev.insight.canvas

import org.junit.Assert.*
import org.junit.Test

class CanvasMotionTest {
    @Test fun `new cards fade and settle exactly at their destination`() {
        val motion = CanvasMotion(100)
        motion.target("a", CanvasPlacement(50, 100), 0, true)
        assertEquals(0f, motion.opacity("a", 0))
        assertEquals(CanvasPlacement(50, 112), motion.position("a", 0))
        assertTrue(motion.opacity("a", 50) in 0.1f..0.9f)
        assertEquals(CanvasPlacement(50, 100), motion.position("a", 100))
        assertEquals(1f, motion.opacity("a", 100))
        assertFalse(motion.active(100))
    }

    @Test fun `layout repeats do not restart transitions and retargeting stays continuous`() {
        val motion = CanvasMotion(100)
        motion.target("a", CanvasPlacement(0, 0), 0, false)
        motion.target("a", CanvasPlacement(100, 100), 0, true)
        val halfway = motion.position("a", 50)
        motion.target("a", CanvasPlacement(100, 100), 50, true)
        assertEquals(halfway, motion.position("a", 50))
        assertEquals(CanvasPlacement(100, 100), motion.position("a", 100))
        motion.target("a", CanvasPlacement(200, 200), 50, true)
        assertEquals(halfway, motion.position("a", 50))
        assertEquals(CanvasPlacement(200, 200), motion.position("a", 150))
    }

    @Test fun `restore and zoom settle immediately and removed cards stop animating`() {
        val motion = CanvasMotion(100)
        motion.target("a", CanvasPlacement(0, 0), 0, true)
        motion.target("a", CanvasPlacement(20, 30), 10, false)
        assertEquals(CanvasPlacement(20, 30), motion.position("a", 10))
        assertEquals(1f, motion.opacity("a", 10))
        assertFalse(motion.active(10))
        motion.target("b", CanvasPlacement(0, 0), 10, true)
        motion.retain(setOf("a"))
        assertFalse(motion.active(10))
    }
}
