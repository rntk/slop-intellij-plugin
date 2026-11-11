package dev.insight.review.wall

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.diff.DIFFS_WALL_LABELS
import java.awt.Container
import java.awt.Point
import java.awt.event.ActionEvent
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.KeyStroke

class WallNavigationPlatformTest : BasePlatformTestCase() {
    fun testFocusedNavigationScrollsTheBodyAndKeepsTheCanvasStillAtTheEdges() = withWall { wall, pane, surface ->
        val first = wall.blocks.first()
        assertTrue(first.active && first.isCompact)
        press(surface, "DOWN")
        assertEquals("An active overview card does not take navigation", Point(), first.bodyScroll)
        press(surface, "ctrl EQUALS")
        press(surface, "RIGHT")
        assertEquals("Zooming manually does not enter Tab focus mode", Point(), first.bodyScroll)
        press(surface, "ctrl 0")
        press(surface, "TAB")
        val canvas = pane.viewport.viewPosition
        fun stationary(stroke: String) {
            press(surface, stroke)
            assertEquals("$stroke must keep the focused canvas still", canvas, pane.viewport.viewPosition)
        }
        stationary("DOWN")
        val line = first.bodyScroll.y
        assertTrue(line > 0)
        stationary("UP")
        assertEquals(0, first.bodyScroll.y)
        stationary("SPACE")
        val page = first.bodyScroll.y
        assertTrue(page > line)
        stationary("shift SPACE")
        assertEquals(0, first.bodyScroll.y)
        stationary("PAGE_DOWN")
        assertEquals(page, first.bodyScroll.y)
        stationary("PAGE_UP")
        assertEquals(0, first.bodyScroll.y)
        stationary("END")
        val bottom = first.bodyScroll.y
        assertTrue(bottom > page)
        stationary("DOWN")
        stationary("SPACE")
        stationary("END")
        assertEquals(bottom, first.bodyScroll.y)
        stationary("HOME")
        stationary("UP")
        stationary("PAGE_UP")
        assertEquals(0, first.bodyScroll.y)
        stationary("RIGHT")
        val horizontal = first.bodyScroll.x
        assertTrue(horizontal > 0)
        stationary("LEFT")
        assertEquals(0, first.bodyScroll.x)
        stationary("shift PAGE_DOWN")
        assertTrue(first.bodyScroll.x > horizontal)
        stationary("shift PAGE_UP")
        assertEquals(0, first.bodyScroll.x)
        stationary("ctrl END")
        val end = first.bodyScroll
        assertEquals(bottom, end.y)
        assertTrue(end.x > horizontal)
        stationary("RIGHT")
        assertEquals(end, first.bodyScroll)
        stationary("ctrl HOME")
        assertEquals(Point(), first.bodyScroll)

        press(surface, "TAB")
        assertTrue(wall.blocks[1].active)
        press(surface, "DOWN")
        assertTrue(wall.blocks[1].bodyScroll.y > 0)
        assertEquals("Navigation belongs to the new card", Point(), first.bodyScroll)
        press(surface, "ESCAPE")
        val before = wall.blocks[1].bodyScroll
        // Supply scrollable page space explicitly; this headless fixture has no window to validate it.
        pane.viewport.viewSize = java.awt.Dimension(pane.viewport.width + 200, pane.viewport.height + 200)
        pane.viewport.viewPosition = Point()
        press(surface, "DOWN")
        assertEquals(before, wall.blocks[1].bodyScroll)
        assertTrue("Escape restores canvas scrolling", pane.viewport.viewPosition.y > 0)
    }

    fun testFocusedSummaryNavigationUsesSummaryRowsAndPreservesDiffScroll() = withWall { wall, pane, surface ->
        val first = wall.blocks.first()
        press(surface, "TAB")
        press(surface, "DOWN")
        val diffScroll = first.bodyScroll
        val image = java.awt.image.BufferedImage(first.width, first.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try { first.paint(graphics) } finally { graphics.dispose() }
        val toggle = first.summaryToggle!!
        fun toggle() = first.dispatchEvent(java.awt.event.MouseEvent(first, java.awt.event.MouseEvent.MOUSE_PRESSED,
            0L, 0, toggle.centerX.toInt(), toggle.centerY.toInt(), 1, false, java.awt.event.MouseEvent.BUTTON1))
        toggle()
        assertTrue(first.showsSummary)
        assertEquals(Point(), first.bodyScroll)
        val canvas = pane.viewport.viewPosition
        press(surface, "SPACE")
        assertTrue(first.bodyScroll.y > 0)
        press(surface, "END")
        val end = first.bodyScroll
        press(surface, "DOWN")
        assertEquals(end, first.bodyScroll)
        press(surface, "HOME")
        assertEquals(Point(), first.bodyScroll)
        assertEquals(canvas, pane.viewport.viewPosition)
        toggle()
        assertEquals(diffScroll, first.bodyScroll)
    }

    private fun withWall(test: (WallView, JScrollPane, JComponent) -> Unit) {
        val wall = WallView(DIFFS_WALL_LABELS, { _, _ -> }, {})
        try {
            val blocks = listOf("a.kt", "b.kt").map { path ->
                WallBlock(path, path, "", (1..300).map { WallLine.source(it, "line $it " + "x".repeat(160)) }, 0)
            }
            wall.show(WallContent(WallScope(null, "All files"), blocks,
                mapOf("a.kt" to (1..500).joinToString("\n") { "Summary line $it" })))
            val pane = JScrollPane(wall.card)
            pane.setSize(1400, 900)
            fun layout(component: Container) {
                component.doLayout()
                component.components.filterIsInstance<Container>().forEach(::layout)
            }
            layout(pane)
            test(wall, pane, wall.blocks.first().parent as JComponent)
        } finally { Disposer.dispose(wall) }
    }

    private fun press(surface: JComponent, stroke: String) = surface.getActionForKeyStroke(KeyStroke.getKeyStroke(stroke))!!
        .actionPerformed(ActionEvent(surface, 0, null))
}
