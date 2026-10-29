package dev.insight.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JLabel
import javax.swing.SwingUtilities

class ComponentMouseEventsTest {
    @Test
    fun `existing and added children report gestures but unrelated and removed children do not`() = onEdt {
        val child = interactivePanel()
        val branch = JPanel().apply { add(child) }
        val root = JPanel().apply { add(branch) }
        val seen = mutableListOf<Component>()
        val events = ComponentMouseEvents(root) { seen += it.component }
        try {
            move(child)
            val added = interactivePanel()
            branch.add(added)
            move(added)
            move(JPanel())
            root.remove(branch)
            move(child)
            move(added)
            assertEquals(listOf(child, added), seen)

            root.add(branch)
            move(child)
            assertEquals(listOf(child, added, child), seen)
        } finally {
            events.dispose()
        }
    }

    @Test
    fun `drag and release outside bounds are observed and disposal preserves existing listeners`() = onEdt {
        val child = JPanel()
        val root = JPanel().apply { add(child) }
        val existing = object : MouseAdapter() {}
        child.addMouseListener(existing)
        val seen = mutableListOf<Int>()
        val events = ComponentMouseEvents(root) { seen += it.id }
        child.dispatchEvent(event(child, MouseEvent.MOUSE_PRESSED))
        child.dispatchEvent(event(child, MouseEvent.MOUSE_DRAGGED, -100))
        child.dispatchEvent(event(child, MouseEvent.MOUSE_RELEASED, -100))
        assertEquals(listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_DRAGGED, MouseEvent.MOUSE_RELEASED), seen)

        events.dispose()
        events.dispose()
        move(child)
        val added = JPanel()
        root.add(added)
        move(added)
        assertEquals(3, seen.size)
        assertEquals(listOf(existing), child.mouseListeners.toList())
        assertTrue(child.mouseMotionListeners.isEmpty())
        assertTrue(root.containerListeners.isEmpty())
    }

    @Test
    fun `passive labels keep delivering clicks to their interactive ancestor`() = onEdt {
        val title = JLabel("Pin this block")
        val lineCount = JLabel("3 lines")
        val wheelOnly = JPanel().apply { addMouseWheelListener {} }
        val box = interactivePanel().apply {
            add(title)
            add(lineCount)
            add(wheelOnly)
        }
        val root = JPanel().apply { add(box) }
        val events = ComponentMouseEvents(root) {}
        try {
            // A mouse listener on these children makes Swing target them instead of the clickable box.
            for (passive in listOf(title, lineCount, wheelOnly)) {
                assertTrue(passive.mouseListeners.isEmpty())
                assertTrue(passive.mouseMotionListeners.isEmpty())
            }
            assertEquals(2, box.mouseListeners.size)
        } finally {
            events.dispose()
        }
    }

    private fun interactivePanel() = JPanel().apply { addMouseListener(object : MouseAdapter() {}) }

    private fun move(component: Component) = component.dispatchEvent(event(component, MouseEvent.MOUSE_MOVED))

    private fun event(component: Component, id: Int, x: Int = 1) =
        MouseEvent(component, id, 0, 0, x, 1, 0, false)

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
}
