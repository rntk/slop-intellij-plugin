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

    fun testWheelScrollsEachAxisAndForwardsAtEdgesAndForZoom() = viewer { pane ->
        val editor = pane.editor
        fun wheel(modifiers: Int) {
            val event = MouseWheelEvent(editor.contentComponent, MouseWheelEvent.MOUSE_WHEEL, 0,
                modifiers, 10, 10, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1)
            editor.contentComponent.mouseWheelListeners.forEach { it.mouseWheelMoved(event) }
        }
        wheel(InputEvent.SHIFT_DOWN_MASK)
        assertTrue(editor.scrollingModel.visibleArea.x > 0)
        assertEquals(0, editor.scrollingModel.visibleArea.y)
        wheel(0)
        assertTrue(editor.scrollingModel.visibleArea.y > 0)
        assertEquals(0, forwarded)
        scrollCanvasViewer(editor, 1, 1, edge = true)
        wheel(0)
        wheel(InputEvent.SHIFT_DOWN_MASK)
        assertEquals(2, forwarded)
        wheel(InputEvent.CTRL_DOWN_MASK)
        wheel(InputEvent.META_DOWN_MASK)
        assertEquals(4, forwarded)
    }

    fun testUnboundedVerticalAxisBelongsToCanvas() = viewer { pane ->
        pane.maxHeight = Int.MAX_VALUE
        assertEquals(Point(0, 50), scrollCanvasViewer(pane.editor, 0, 50))
        assertEquals(0, pane.editor.scrollingModel.visibleArea.y)
    }
}
