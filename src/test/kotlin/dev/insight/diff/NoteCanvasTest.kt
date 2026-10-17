package dev.insight.diff

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import javax.swing.JLabel

class NoteCanvasTest : BasePlatformTestCase() {
    private val note = DiffNote(listOf("src/a.kt"), "A short explanation.")

    fun testZoomKeepsCursorAnchorAndRefreshKeepsView() {
        val canvas = canvas()
        val before = canvas.components.single().bounds
        val anchor = Point(200, 150)
        canvas.zoomAt(2.0, anchor)
        val after = canvas.components.single().bounds
        assertEquals(2 * before.x - anchor.x, after.x)
        assertEquals(2 * before.y - anchor.y, after.y)
        assertEquals(2 * before.width, after.width)
        canvas.showNotes(listOf(note.copy(text = "Updated explanation.")))
        assertEquals(after.location, canvas.components.single().location)
        canvas.zoomAt(100.0)
        assertEquals(2.5, canvas.zoom)
        canvas.zoomAt(0.01)
        assertEquals(0.25, canvas.zoom)
    }

    fun testPanAndHeaderDragAtZoomPreserveWorldPositions() {
        val canvas = canvas()
        val initial = canvas.components.single().location
        canvas.zoomAt(2.0, Point())
        drag(canvas, MouseEvent.BUTTON1, Point(700, 500), Point(620, 440))
        val panned = canvas.components.single().location
        assertEquals(Point(initial.x * 2 - 80, initial.y * 2 - 60), panned)
        // The header label must also act as a drag handle.
        val header = descendants(canvas).filterIsInstance<JLabel>().first { it.text == "1 file · LLM interpretation" }
        drag(header, MouseEvent.BUTTON1, Point(10, 10), Point(70, 50))
        canvas.showNotes(listOf(note))
        assertEquals(Point(panned.x + 60, panned.y + 40), canvas.components.single().location)
        canvas.resetView()
        assertEquals(Point(initial.x + 30, initial.y + 20), canvas.components.single().location)
    }

    fun testMiddleDragPansWithoutMovingCardAndFitRecoversView() {
        val canvas = canvas()
        val initial = canvas.components.single().location
        val header = descendants(canvas).filterIsInstance<JLabel>().first { it.text == "1 file · LLM interpretation" }
        drag(header, MouseEvent.BUTTON2, Point(10, 10), Point(-1500, -1000))
        assertTrue(canvas.components.single().x < 0)
        canvas.fitView()
        assertTrue(canvas.bounds.contains(canvas.components.single().bounds))
        canvas.resetView()
        assertEquals(initial, canvas.components.single().location)
    }

    private fun canvas() = NoteCanvas({}, {}).apply { setSize(1000, 700); showNotes(listOf(note)) }
    private fun descendants(component: Container): List<Component> = component.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
    private fun drag(target: Component, button: Int, from: Point, to: Point) {
        val mask = if (button == MouseEvent.BUTTON1) InputEvent.BUTTON1_DOWN_MASK else InputEvent.BUTTON2_DOWN_MASK
        target.dispatchEvent(MouseEvent(target, MouseEvent.MOUSE_PRESSED, 0, mask, from.x, from.y, 1, false, button))
        target.dispatchEvent(MouseEvent(target, MouseEvent.MOUSE_DRAGGED, 1, mask, to.x, to.y, 0, false, MouseEvent.NOBUTTON))
        target.dispatchEvent(MouseEvent(target, MouseEvent.MOUSE_RELEASED, 2, 0, to.x, to.y, 1, false, button))
    }
}
