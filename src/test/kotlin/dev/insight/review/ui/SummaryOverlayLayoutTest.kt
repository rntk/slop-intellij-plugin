package dev.insight.review.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane

class SummaryOverlayLayoutTest {
    @Test fun overlayStaysOnSummarySideAsPanelAndDividerResize() {
        val card = JPanel(SummaryOverlayLayout())
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JPanel(), JPanel())
        val overlay = JLabel("Trace").apply { preferredSize = Dimension(500, 20) }
        card.add(split, BorderLayout.CENTER)
        card.add(overlay, "trace-overlay")
        card.setSize(900, 400)
        split.dividerLocation = 300
        card.doLayout()
        assertEquals(900, split.width)
        assertTrue(overlay.x >= split.dividerLocation + split.dividerSize)
        assertEquals(900, overlay.x + overlay.width)
        card.setSize(1200, 400)
        split.dividerLocation = 700
        card.doLayout()
        assertEquals(1200, split.width)
        assertTrue(overlay.x >= split.dividerLocation + split.dividerSize)
        assertEquals(1200, overlay.x + overlay.width)
    }
}
