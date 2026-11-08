package dev.insight.review.ui

import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import junit.framework.TestCase

class SummaryTreeMotionTest : TestCase() {
    fun testEasingStartsAndEndsGentlyAndStaysInRange() {
        assertEquals(0.0, SummaryTreeMotion.ease(-1.0), 0.0)
        assertEquals(0.0, SummaryTreeMotion.ease(0.0), 0.0)
        assertEquals(0.5, SummaryTreeMotion.ease(0.5), 1e-9)
        assertEquals(1.0, SummaryTreeMotion.ease(2.0), 0.0)
        assertTrue(SummaryTreeMotion.ease(0.1) < 0.1)
        assertTrue(SummaryTreeMotion.ease(0.9) > 0.9)
    }

    fun testNewPartsStartAtTheirNearestShownAncestorAndLeavingPartsHeadForTheNearestSurvivor() {
        val parents = mapOf<String, String?>("root" to null, "a" to "root", "b" to "a")
        val shown = mapOf("root" to Rectangle(10, 20, 5, 5), "a" to Rectangle(100, 200, 5, 5))
        assertEquals(Point(100, 200), SummaryTreeMotion.origin("b", Point(1, 1), parents, shown))
        assertEquals(Point(10, 20), SummaryTreeMotion.origin("a", Point(1, 1), parents, mapOf("root" to shown.getValue("root"))))
        assertEquals(Point(1, 1), SummaryTreeMotion.origin("b", Point(1, 1), parents, emptyMap()))
        assertEquals(Point(10, 20), SummaryTreeMotion.destination("b", Point(7, 7), parents, mapOf("root" to shown.getValue("root"))))
        assertEquals(Point(7, 7), SummaryTreeMotion.destination("b", Point(7, 7), parents, emptyMap()))
    }

    fun testAnchorIsTheVisiblePartNearestTheViewCenter() {
        val shown = mapOf("far" to Rectangle(0, 0, 10, 10), "near" to Rectangle(95, 95, 10, 10), "hidden" to Rectangle(500, 500, 10, 10))
        val view = Rectangle(0, 0, 200, 200)
        assertEquals("near", SummaryTreeMotion.pickAnchor(shown, shown.keys, view))
        assertNull(SummaryTreeMotion.pickAnchor(shown, setOf("hidden"), view))
    }

    fun testViewFollowsTheAnchorWithinTheExtent() {
        val extent = Dimension(1000, 800)
        val port = Dimension(300, 200)
        assertEquals(Point(150, 380), SummaryTreeMotion.followAnchor(Point(200, 400), Point(50, 20), extent, port))
        assertEquals(Point(0, 0), SummaryTreeMotion.followAnchor(Point(10, 10), Point(50, 20), extent, port))
        assertEquals(Point(700, 600), SummaryTreeMotion.followAnchor(Point(990, 790), Point(50, 20), extent, port))
        assertEquals(Point(0, 0), SummaryTreeMotion.followAnchor(Point(500, 500), Point(0, 0), Dimension(100, 100), port))
    }

    fun testRevealScrollsLeastAndLeavesAVisiblePartAlone() {
        val extent = Dimension(2000, 2000)
        val port = Dimension(300, 200)
        assertEquals(Point(50, 70), SummaryTreeMotion.reveal(Rectangle(60, 80, 20, 10), Point(50, 70), port, extent, 5))
        assertEquals(Point(50, 70), SummaryTreeMotion.reveal(Rectangle(60, 80, 20, 10), Point(50, 70), port, extent, 5))
        assertEquals(Point(1000 + 20 + 5 - 300, 70), SummaryTreeMotion.reveal(Rectangle(1000, 80, 20, 10), Point(50, 70), port, extent, 5))
        assertEquals(Point(0, 0), SummaryTreeMotion.reveal(Rectangle(2, 2, 20, 10), Point(50, 70), port, extent, 5))
    }
}
