package dev.insight.canvas

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.awt.Container
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.MouseWheelEvent

class CanvasViewerNavigationTest : LightJavaCodeInsightFixtureTestCase() {
    private fun viewer(test: (CanvasDiffPane) -> Unit) {
        val pane = CanvasDiffPane(project, "Example.java", (1..100).map {
            CanvasDiffRow(CanvasDiffRow.Kind.CONTEXT, "line $it " + "long text ".repeat(40), it, it)
        }) { forwarded++ }
        try {
            pane.maxHeight = 180
            pane.setSize(300, 200)
            fun layout(container: Container) {
                container.doLayout()
                container.components.filterIsInstance<Container>().forEach(::layout)
            }
            layout(pane)
            test(pane)
        } finally {
            Disposer.dispose(pane)
        }
    }

    private var forwarded = 0

    fun testKeysScrollBothAxesAndKeepOwnershipAtEdges() = viewer { pane ->
        val editor = pane.editor
        assertEquals(Point(0, 0), scrollCanvasViewer(editor, 50, 50))
        assertTrue(editor.scrollingModel.visibleArea.x > 0)
        assertTrue(editor.scrollingModel.visibleArea.y > 0)
        scrollCanvasViewer(editor, 1, 1, edge = true)
        val end = editor.scrollingModel.visibleArea.location
        assertEquals(Point(0, 0), scrollCanvasViewer(editor, 50, 50))
        assertEquals(end, editor.scrollingModel.visibleArea.location)
        scrollCanvasViewer(editor, -1, -1, edge = true)
        assertEquals(Point(0, 0), editor.scrollingModel.visibleArea.location)
        scrollCanvasViewer(editor, 0, 1, page = true)
        assertTrue(editor.scrollingModel.visibleArea.y > editor.lineHeight)
    }

    fun testWheelAlwaysForwardsWithoutScrollingOnHover() = viewer { pane ->
        val editor = pane.editor
        fun wheel(modifiers: Int) {
            val event = MouseWheelEvent(editor.contentComponent, MouseWheelEvent.MOUSE_WHEEL, 0,
                modifiers, 10, 10, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1)
            editor.contentComponent.mouseWheelListeners.forEach { it.mouseWheelMoved(event) }
            assertTrue(event.isConsumed)
        }
        wheel(0)
        wheel(InputEvent.SHIFT_DOWN_MASK)
        wheel(InputEvent.CTRL_DOWN_MASK)
        wheel(InputEvent.META_DOWN_MASK)
        assertEquals(4, forwarded)
        assertEquals(Point(0, 0), editor.scrollingModel.visibleArea.location)
    }

    fun testCanScrollCanvasViewerReportsAxes() {
        val narrow = CanvasDiffPane(project, "Short.java", (1..100).map {
            CanvasDiffRow(CanvasDiffRow.Kind.CONTEXT, "line $it", it, it)
        }) {}
        try {
            narrow.maxHeight = 180
            narrow.setSize(300, 200)
            fun layout(container: Container) {
                container.doLayout()
                container.components.filterIsInstance<Container>().forEach(::layout)
            }
            layout(narrow)
            assertFalse(canScrollCanvasViewer(narrow.editor, horizontal = true))
            assertTrue(canScrollCanvasViewer(narrow.editor, horizontal = false))

            narrow.maxHeight = Int.MAX_VALUE
            assertFalse(canScrollCanvasViewer(narrow.editor, horizontal = true))
            assertFalse(canScrollCanvasViewer(narrow.editor, horizontal = false))
        } finally {
            Disposer.dispose(narrow)
        }

        viewer { wide ->
            assertTrue(canScrollCanvasViewer(wide.editor, horizontal = true))
            assertTrue(canScrollCanvasViewer(wide.editor, horizontal = false))
            wide.maxHeight = Int.MAX_VALUE
            assertTrue(canScrollCanvasViewer(wide.editor, horizontal = true))
            assertFalse(canScrollCanvasViewer(wide.editor, horizontal = false))
        }
    }

    fun testCanvasWheelDeltaHandlesZeroWholeAndSmallFractionalTurns() {
        assertEquals(0, canvasWheelDelta(0.0, 20))
        assertEquals(60, canvasWheelDelta(1.0, 20))
        assertEquals(-60, canvasWheelDelta(-1.0, 20))
        assertEquals(1, canvasWheelDelta(0.005, 20))
        assertEquals(-1, canvasWheelDelta(-0.005, 20))
    }

    fun testUnboundedVerticalAxisBelongsToCanvas() = viewer { pane ->
        pane.maxHeight = Int.MAX_VALUE
        assertEquals(Point(0, 50), scrollCanvasViewer(pane.editor, 0, 50))
        assertEquals(0, pane.editor.scrollingModel.visibleArea.y)
    }
}
