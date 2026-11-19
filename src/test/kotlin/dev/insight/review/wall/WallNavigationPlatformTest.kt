package dev.insight.review.wall

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.diff.DIFFS_WALL_LABELS
import java.awt.Container
import java.awt.Dimension
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JViewport
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
        assertTrue(wall.blocks[1].active)
        val before = wall.blocks[1].bodyScroll
        // Supply scrollable page space explicitly; this headless fixture has no window to validate it.
        pane.viewport.viewSize = java.awt.Dimension(pane.viewport.width + 200, pane.viewport.height + 200)
        pane.viewport.viewPosition = Point()
        press(surface, "DOWN")
        assertEquals(before, wall.blocks[1].bodyScroll)
        assertTrue("Escape restores canvas scrolling", pane.viewport.viewPosition.y > 0)
    }

    fun testHorizontalOnlyCardRoutesVerticalNavigationToCanvas() = withWall { wall, pane, surface ->
        val first = wall.blocks.first()
        press(surface, "TAB")
        press(surface, "C")
        assertFalse(first.isCompact)
        makeCanvasScrollable(pane)
        press(surface, "DOWN")
        val afterKey = pane.viewport.viewPosition
        assertTrue(afterKey.y > 0)
        wheel(first)
        assertTrue(pane.viewport.viewPosition.y > afterKey.y)
        assertEquals(0, first.bodyScroll.y)

        val canvas = pane.viewport.viewPosition
        press(surface, "RIGHT")
        val afterRight = first.bodyScroll.x
        assertTrue(afterRight > 0)
        wheel(surface, modifiers = InputEvent.SHIFT_DOWN_MASK)
        assertTrue(first.bodyScroll.x > afterRight)
        assertEquals(canvas, pane.viewport.viewPosition)

        press(surface, "ctrl END")
        val end = first.bodyScroll
        val canvasEnd = pane.viewport.viewPosition
        assertTrue(canvasEnd.y > canvas.y)
        assertEquals(canvas.x, canvasEnd.x)
        wheel(first, modifiers = InputEvent.SHIFT_DOWN_MASK)
        press(surface, "RIGHT")
        assertEquals(end, first.bodyScroll)
        assertEquals(canvasEnd, pane.viewport.viewPosition)
    }

    fun testVerticalOnlyCardRoutesHorizontalNavigationToCanvas() = withWall(wideRows = false) { wall, pane, surface ->
        val first = wall.blocks.first()
        press(surface, "TAB")
        makeCanvasScrollable(pane)
        press(surface, "RIGHT")
        val afterKey = pane.viewport.viewPosition
        assertTrue(afterKey.x > 0)
        wheel(first, modifiers = InputEvent.SHIFT_DOWN_MASK)
        assertTrue(pane.viewport.viewPosition.x > afterKey.x)
        assertEquals(0, first.bodyScroll.x)

        val canvas = pane.viewport.viewPosition
        press(surface, "END")
        val end = first.bodyScroll
        assertTrue(end.y > 0)
        wheel(first)
        press(surface, "DOWN")
        assertEquals(end, first.bodyScroll)
        assertEquals(canvas, pane.viewport.viewPosition)
    }

    fun testWheelFollowsTabFocusAndSecondEscapeClearsCurrentCard() = withWall { wall, pane, surface ->
        val (first, second) = wall.blocks
        press(surface, "TAB")
        val canvas = pane.viewport.viewPosition
        wheel(second)
        assertTrue(first.bodyScroll.y > 0)
        wheel(surface, modifiers = InputEvent.SHIFT_DOWN_MASK)
        assertTrue(first.bodyScroll.x > 0)
        assertEquals(Point(), second.bodyScroll)
        press(surface, "END")
        val bottom = first.bodyScroll
        wheel(first)
        assertEquals(bottom, first.bodyScroll)
        assertEquals(canvas, pane.viewport.viewPosition)

        press(surface, "ESCAPE")
        assertTrue(first.active)
        pane.viewport.viewSize = Dimension(pane.viewport.width + 200, pane.viewport.height + 200)
        pane.verticalScrollBar.isVisible = true
        pane.viewport.viewPosition = Point()
        wheel(first)
        assertEquals(bottom, first.bodyScroll)
        assertTrue(pane.viewport.viewPosition.y > 0)

        press(surface, "ESCAPE")
        assertFalse(wall.blocks.any { it.active })
        assertFalse(surface.actionMap.get("open").isEnabled)
        pane.viewport.viewPosition = Point()
        wheel(second)
        assertTrue(pane.viewport.viewPosition.y > 0)
        press(surface, "HOME")
        assertEquals(Point(), pane.viewport.viewPosition)
        assertEquals(Point(), second.bodyScroll)
        assertEquals(bottom, first.bodyScroll)

        press(surface, "TAB")
        assertTrue(first.active)
        wheel(second, rotation = -1)
        assertTrue(first.bodyScroll.y < bottom.y)
    }

    private fun makeCanvasScrollable(pane: JScrollPane) {
        pane.viewport.viewSize = Dimension(pane.viewport.width + 200, pane.viewport.height + 200)
        pane.verticalScrollBar.isVisible = true
        pane.horizontalScrollBar.isVisible = true
        pane.viewport.viewPosition = Point()
    }

    private fun wheel(target: JComponent, rotation: Int = 1, modifiers: Int = 0) {
        target.dispatchEvent(MouseWheelEvent(target, MouseEvent.MOUSE_WHEEL, 0L, modifiers, 10, 10, 0, false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation))
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

    fun testClickFocusZoomsToTheSameViewAsTab() = withWall { wall, pane, surface ->
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val (first, second) = wall.blocks
        val canvas = pane.viewport.viewPosition
        val cardSize = second.preferredSize
        clickCard(second)
        assertTrue(second.active)
        press(surface, "DOWN")
        assertTrue(second.bodyScroll.y > 0)
        assertEquals(Point(), first.bodyScroll)
        PlatformTestUtil.waitWithEventsDispatching("Click should zoom to the card", { second.preferredSize.width > cardSize.width }, 5)
        val clickSize = second.preferredSize
        val clickPosition = pane.viewport.viewPosition

        press(surface, "ESCAPE")
        assertTrue(second.active)
        assertEquals(cardSize, second.preferredSize)
        assertEquals(canvas, pane.viewport.viewPosition)
        press(surface, "ESCAPE")
        assertFalse(wall.blocks.any { it.active })
        press(surface, "shift TAB")
        assertTrue(second.active)
        assertEquals(clickSize, second.preferredSize)
        assertEquals(clickPosition, pane.viewport.viewPosition)
    }

    fun testDoubleClickOpensTheRowBeforeClickZoom() {
        val opened = mutableListOf<Pair<String, WallLine?>>()
        withWall(openLine = { path, line -> opened += path to line }) { wall, pane, _ ->
            val card = wall.blocks[1]
            val size = card.preferredSize
            val position = pane.viewport.viewPosition
            val line = card.lineAt(60)
            assertNotNull(line)
            clickCard(card)
            clickCard(card, count = 2)
            assertEquals(listOf(card.block.path to line), opened)
            assertEquals(size, card.preferredSize)
            assertEquals(position, pane.viewport.viewPosition)
        }
    }

    private fun clickCard(target: JComponent, count: Int = 1) {
        for (id in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
            target.dispatchEvent(MouseEvent(target, id, 0L, 0, 20, 60, count, false, MouseEvent.BUTTON1))
        }
    }

    fun testRightClickHandsTheBlockAndRowToTheHost() {
        val seen = mutableListOf<Triple<String, WallLine?, java.awt.Component?>>()
        withWall(onContext = { block, line, point -> seen += Triple(block.path, line, point.component) }) { wall, _, surface ->
            press(surface, "ctrl 0")
            val block = wall.blocks[1]
            val m = block.metrics
            fun event(id: Int, y: Int, trigger: Boolean, button: Int = java.awt.event.MouseEvent.BUTTON3) =
                java.awt.event.MouseEvent(block, id, 0L, 0, 20, y, 1, trigger, button)
            val row5 = m.headerHeight + m.padding + 4 * m.lineHeight + m.lineHeight / 2
            block.dispatchEvent(event(java.awt.event.MouseEvent.MOUSE_PRESSED, row5, true))
            assertEquals(listOf("b.kt"), seen.map { it.first })
            assertEquals(5, seen.single().second?.new)
            assertSame(block, seen.single().third)
            assertTrue("A right-click activates its block", block.active)
            // Windows reports the trigger on release.
            block.dispatchEvent(event(java.awt.event.MouseEvent.MOUSE_RELEASED, row5, true))
            assertEquals(2, seen.size)
            // The header names no row.
            block.dispatchEvent(event(java.awt.event.MouseEvent.MOUSE_PRESSED, 2, true))
            assertNull(seen.last().second)
            assertEquals(3, seen.size)
            // Left clicks and drags still belong to the block.
            block.dispatchEvent(event(java.awt.event.MouseEvent.MOUSE_PRESSED, row5, false, java.awt.event.MouseEvent.BUTTON1))
            block.dispatchEvent(event(java.awt.event.MouseEvent.MOUSE_RELEASED, row5, false, java.awt.event.MouseEvent.BUTTON1))
            block.dispatchEvent(event(java.awt.event.MouseEvent.MOUSE_PRESSED, row5, false))
            assertEquals(3, seen.size)
            assertTrue(wall.blocks.first().let { !it.active })
        }
    }

    fun testRightClickWithoutAHostDoesNothing() = withWall { wall, _, _ ->
        val block = wall.blocks.first()
        block.dispatchEvent(java.awt.event.MouseEvent(block, java.awt.event.MouseEvent.MOUSE_PRESSED, 0L, 0, 20, 60, 1, true, java.awt.event.MouseEvent.BUTTON3))
        assertFalse(block.parent == null)
    }

    private fun withWall(onContext: ((WallBlock, WallLine?, com.intellij.ui.awt.RelativePoint) -> Unit)? = null,
        openLine: (String, WallLine?) -> Unit = { _, _ -> },
        wideRows: Boolean = true,
        test: (WallView, JScrollPane, JComponent) -> Unit) {
        val wall = WallView(DIFFS_WALL_LABELS, openLine, {}, onContext = onContext)
        try {
            val suffix = if (wideRows) " " + "x".repeat(160) else ""
            val blocks = listOf("a.kt", "b.kt").map { path ->
                WallBlock(path, path, "", (1..300).map { WallLine.source(it, "line $it$suffix") }, 0)
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

    fun testSkimStacksWholeFullWidthBlocksWithStickyHeadersFoldsAndJumpsBetweenHunks() {
        val wall = WallView(DIFFS_WALL_LABELS, { _, _ -> }, {})
        try {
            fun rows(prefix: String) = (1..3).flatMap { hunk ->
                listOf(WallLine(WallLine.Kind.HUNK, "@@ -$hunk +$hunk @@")) + (1..40).map { WallLine(WallLine.Kind.ADDED, "+$prefix $hunk.$it", new = it) }
            }
            val all = rows("a")
            val blocks = listOf(WallBlock("a.kt", "a.kt", "", all.take(50), all.size - 50, all.drop(50)), WallBlock("b.kt", "b.kt", "", rows("b"), 0))
            wall.show(WallContent(WallScope(null, "All changes"), blocks, mapOf("a.kt" to "Adds a.")))
            val pane = JScrollPane(wall.card)
            pane.setSize(1400, 900)
            relayout(pane)
            val surface = wall.blocks.first().parent as JComponent
            assertEquals(WallMode.CARDS, wall.mode)
            assertEquals(50, wall.blocks.first().block.lines.size)

            press(surface, "M")
            relayout(pane)
            assertEquals(WallMode.SKIM, wall.mode)
            assertEquals(JViewport.SIMPLE_SCROLL_MODE, pane.viewport.scrollMode)
            val (a, b) = wall.blocks
            assertEquals("The skim column shows the rows the cards cut", all.size, a.block.lines.size)
            assertEquals(a.x, b.x)
            assertTrue("Blocks stack in one column", b.y > a.y + a.height)
            assertTrue("Blocks fill the page's width", a.width > 1200)
            assertFalse(a.isCompact)
            assertEquals(a.fullHeight, a.logical.height)

            // The header sticks to the viewport's top while its block scrolls under it.
            pane.viewport.viewPosition = Point(0, a.y + 300)
            relayout(pane)
            assertTrue(a.stickyTop > 0)
            assertEquals(0, b.stickyTop)

            // N moves to the next hunk header below the sticky header, across files.
            pane.viewport.viewPosition = Point(0, 0)
            val tops = mutableListOf<Int>()
            repeat(5) {
                press(surface, "N")
                tops += pane.viewport.viewPosition.y
            }
            assertEquals(tops.sorted(), tops)
            assertEquals(5, tops.distinct().size)
            press(surface, "shift N")
            assertEquals(tops[3], pane.viewport.viewPosition.y)

            pane.viewport.viewPosition = Point(0, 0)
            relayout(pane)
            press(surface, "S")
            relayout(pane)
            assertTrue("The summary shows above the rows, which keep their place below it", wall.blocks.first().logical.height > a.fullHeight)

            press(surface, "C")
            relayout(pane)
            assertTrue(wall.blocks.first().isFolded)
            assertTrue(wall.blocks.first().height < 100)
            press(surface, "shift C")
            assertTrue(wall.blocks.all { it.isFolded })
            press(surface, "shift C")
            assertTrue(wall.blocks.none { it.isFolded })

            press(surface, "M")
            relayout(pane)
            assertEquals(WallMode.CARDS, wall.mode)
            assertEquals(50, wall.blocks.first().block.lines.size)
            assertEquals(JViewport.BLIT_SCROLL_MODE, pane.viewport.scrollMode)
        } finally { Disposer.dispose(wall) }
    }

    fun testSummarySwitchAsksForAMissingSummaryAndShowsItWhenItArrives() {
        val asked = mutableListOf<String>()
        lateinit var wall: WallView
        val blocks = listOf("a.kt", "b.kt").map { WallBlock(it, it, "", listOf(WallLine.source(1, "line")), 0) }
        val scope = WallScope(null, "All changes")
        wall = WallView(DIFFS_WALL_LABELS, { _, _ -> }, {}, summarize = { path ->
            asked += path
            // b.kt cannot be summarized now; a.kt starts and the host says so in its next content.
            (path == "a.kt").also { if (it) wall.show(WallContent(scope, blocks, summarizing = setOf(path))) }
        })
        try {
            wall.show(WallContent(scope, blocks))
            val pane = JScrollPane(wall.card)
            pane.setSize(1400, 900)
            relayout(pane)
            val surface = wall.blocks.first().parent as JComponent
            val (a, b) = wall.blocks
            assertTrue(a.active)
            press(surface, "S")
            assertEquals(listOf("a.kt"), asked)
            assertTrue("The block shows that its summary is being written", a.showsSummary)

            wall.show(WallContent(scope, blocks, mapOf("a.kt" to "Adds a.")))
            assertTrue("The written summary takes its place", wall.blocks.first().showsSummary)

            repeat(3) { if (!b.active) press(surface, "TAB") }
            assertTrue(b.active)
            press(surface, "S")
            assertEquals(listOf("a.kt", "b.kt"), asked)
            assertFalse("A file that cannot be summarized keeps its rows", b.showsSummary)
        } finally { Disposer.dispose(wall) }
    }

    private fun relayout(component: Container) {
        component.doLayout()
        component.components.filterIsInstance<Container>().forEach(::relayout)
    }

    private fun press(surface: JComponent, stroke: String) = surface.getActionForKeyStroke(KeyStroke.getKeyStroke(stroke))!!
        .actionPerformed(ActionEvent(surface, 0, null))
}
