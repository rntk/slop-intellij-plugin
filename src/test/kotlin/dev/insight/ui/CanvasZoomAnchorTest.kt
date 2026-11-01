package dev.insight.ui

import java.awt.Point
import javax.swing.JPanel
import org.junit.Assert.assertEquals
import org.junit.Test

class CanvasZoomAnchorTest {
    @Test fun followsActualLayoutInsteadOfAssumingUniformScaling() {
        val content = JPanel(null).apply { setSize(2000, 2000) }
        val card = JPanel(null).apply { setBounds(400, 300, 200, 100) }
        content.add(card)
        val anchor = CanvasZoomAnchor(content, Point(450, 325))
        card.setBounds(100, 80, 40, 40)
        assertEquals(Point(110, 90), anchor.resolve(content, 0.05))
        card.setBounds(400, 300, 200, 100)
        assertEquals(Point(450, 325), anchor.resolve(content, 20.0))
    }

    @Test fun emptySpaceUsesScaleRatio() {
        val content = JPanel(null).apply { setSize(2000, 2000) }
        val anchor = CanvasZoomAnchor(content, Point(1000, 800))
        assertEquals(Point(50, 40), anchor.resolve(content, 0.05))
    }
}
