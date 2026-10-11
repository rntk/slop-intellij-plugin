package dev.insight

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.insight.callgraph.*
import java.awt.Dimension
import java.awt.Point
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JViewport

class CallGraphCanvasTest : LightJavaCodeInsightFixtureTestCase() {
    private fun canvas(onOpen: (Int) -> Unit = {}): Pair<CallGraphCanvas, JViewport> {
        val canvas = CallGraphCanvas({}, onOpen)
        canvas.show(CallGraph((0..79).map { CallGraphNode(it, "node$it", "", "", it % 4, true) }, emptySet(), false))
        val viewport = JViewport().apply {
            size = Dimension(320, 160)
            view = canvas
            viewSize = canvas.preferredSize
        }
        return canvas to viewport
    }

    private fun mouse(canvas: CallGraphCanvas, type: Int, point: Point, clicks: Int = 1): MouseEvent =
        MouseEvent(canvas, type, System.currentTimeMillis(), 0, point.x, point.y, clicks, false, MouseEvent.BUTTON1)

    fun `test wheel zoom preserves point under cursor and consumes scrolling`() {
        val (canvas, viewport) = canvas()
        viewport.viewPosition = Point(100, 100)
        val anchor = Point(300, 200)
        val screen = Point(anchor.x - viewport.viewPosition.x, anchor.y - viewport.viewPosition.y)
        val wheel = MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
            anchor.x, anchor.y, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1)
        canvas.mouseWheelListeners.forEach { it.mouseWheelMoved(wheel) }
        assertTrue(wheel.isConsumed)
        assertEquals(1.15, canvas.zoom, 0.0001)
        assertEquals(screen.x.toDouble(), anchor.x * canvas.zoom - viewport.viewPosition.x, 1.0)
        assertEquals(screen.y.toDouble(), anchor.y * canvas.zoom - viewport.viewPosition.y, 1.0)
        canvas.zoomAt(100.0, anchor)
        assertEquals(2.0, canvas.zoom)
        canvas.zoomAt(0.001, anchor)
        assertEquals(0.15, canvas.zoom)
        assertTrue(viewport.viewPosition.x >= 0 && viewport.viewPosition.y >= 0)
    }

    fun `test drag pans without selecting or opening a node and double click still navigates`() {
        var opened: Int? = null
        val (canvas, viewport) = canvas { opened = it }
        val click = Point(30, 30)
        canvas.mouseListeners.forEach { it.mousePressed(mouse(canvas, MouseEvent.MOUSE_PRESSED, click, 2)) }
        canvas.mouseListeners.forEach { it.mouseReleased(mouse(canvas, MouseEvent.MOUSE_RELEASED, click, 2)) }
        assertEquals(0, canvas.selected)
        assertEquals(0, opened)
        opened = null
        viewport.viewPosition = Point(100, 100)
        canvas.mouseListeners.forEach { it.mousePressed(mouse(canvas, MouseEvent.MOUSE_PRESSED, Point(120, 120), 2)) }
        canvas.mouseMotionListeners.forEach { it.mouseDragged(mouse(canvas, MouseEvent.MOUSE_DRAGGED, Point(90, 100), 2)) }
        canvas.mouseListeners.forEach { it.mouseReleased(mouse(canvas, MouseEvent.MOUSE_RELEASED, Point(90, 100), 2)) }
        assertEquals(Point(130, 120), viewport.viewPosition)
        assertEquals(0, canvas.selected)
        assertNull(opened)
    }

    fun `test arrow keys pan and clamp to graph bounds`() {
        val (canvas, viewport) = canvas()
        viewport.viewPosition = Point(100, 100)
        canvas.actionMap.get("RIGHT").actionPerformed(null)
        assertTrue(viewport.viewPosition.x > 100)
        val afterArrow = viewport.viewPosition.x
        canvas.actionMap.get("shift RIGHT").actionPerformed(null)
        assertTrue(viewport.viewPosition.x - afterArrow > afterArrow - 100)
        repeat(30) { canvas.actionMap.get("LEFT").actionPerformed(null) }
        assertEquals(0, viewport.viewPosition.x)
        repeat(30) { canvas.actionMap.get("shift DOWN").actionPerformed(null) }
        assertEquals(canvas.preferredSize.height - viewport.extentSize.height, viewport.viewPosition.y)
    }

    fun `test right click selects the node and shows its menu and Enter opens the selection`() {
        var opened: Int? = null
        var menuFor: Int? = null
        val canvas = CallGraphCanvas({}, { opened = it }, popup = { menuFor = it; null })
        canvas.show(CallGraph(listOf(CallGraphNode(0, "a", "", "", 0, true), CallGraphNode(1, "b", "", "", 1, true)), setOf(CallGraphEdge(0, 1)), false),
            mapOf(CallGraphEdge(0, 1) to 12))
        val press = MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 30, 30, 1, true, MouseEvent.BUTTON3)
        canvas.mouseListeners.forEach { it.mousePressed(press) }
        assertEquals(0, menuFor)
        assertEquals(0, canvas.selected)
        canvas.actionMap.get("open").actionPerformed(null)
        assertEquals(0, opened)
    }

    fun `test highlighting a file keeps it, its neighbours and the selection in focus`() {
        val canvas = CallGraphCanvas({}, {})
        canvas.show(CallGraph((0..4).map { CallGraphNode(it, "n$it", "", "", it, true) },
            setOf(CallGraphEdge(0, 1), CallGraphEdge(2, 3), CallGraphEdge(3, 4)), false))
        assertNull(canvas.focus())
        canvas.highlight(FileGraphHighlight(setOf(0), setOf(1)))
        assertEquals(setOf(0, 1), canvas.focus())
        canvas.select(3)
        assertEquals(setOf(0, 1, 2, 3, 4), canvas.focus())
        canvas.highlight(FileGraphHighlight(emptySet(), emptySet()))
        assertNull(canvas.focus())
    }
}
