package dev.insight.ui

import com.intellij.util.ui.EmptyIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextPane

class EdgeTabPlacementTest {
    private val size = EdgeTabSize(thickness = 20, fullLength = 120, iconLength = 24, handle = 4)

    @Test
    fun `a free page gets the full tab at the top of the left edge`() {
        assertEquals(EdgeTabSpot(Rectangle(0, 4, 20, 120), EdgeTabForm.FULL, EdgeSide.LEFT), EdgeTabPlacement.place(400, 300, size, emptyList()))
    }

    @Test
    fun `the tab moves down the left edge past a control`() {
        val spot = EdgeTabPlacement.place(400, 300, size, listOf(Rectangle(0, 0, 80, 30)))
        assertEquals(EdgeTabForm.FULL, spot.form)
        assertEquals(EdgeSide.LEFT, spot.side)
        assertEquals(32, spot.bounds.y)
    }

    @Test
    fun `a busy left edge sends the tab to the right edge`() {
        val spot = EdgeTabPlacement.place(400, 300, size, listOf(Rectangle(0, 0, 30, 300)))
        assertEquals(EdgeSide.RIGHT, spot.side)
        assertEquals(380, spot.bounds.x)
    }

    @Test
    fun `where the full tab fits nowhere the icon alone goes between controls`() {
        val controls = listOf(Rectangle(0, 0, 400, 100), Rectangle(0, 130, 400, 170))
        val spot = EdgeTabPlacement.place(400, 300, size, controls)
        assertEquals(EdgeTabSpot(Rectangle(0, 104, 20, 24), EdgeTabForm.ICON, EdgeSide.LEFT), spot)
    }

    @Test
    fun `with no free place a handle covers the least`() {
        val controls = listOf(Rectangle(0, 0, 400, 150), Rectangle(6, 150, 394, 150))
        val spot = EdgeTabPlacement.place(400, 300, size, controls)
        assertEquals(EdgeTabForm.HANDLE, spot.form)
        assertEquals(4, spot.bounds.width)
        assertEquals(152, spot.bounds.y)
    }

    @Test
    fun `the tab stays put while its place is free`() {
        val previous = EdgeTabSpot(Rectangle(0, 100, 20, 120), EdgeTabForm.FULL, EdgeSide.LEFT)
        assertEquals(previous, EdgeTabPlacement.place(400, 300, size, emptyList(), previous))
        assertEquals(4, EdgeTabPlacement.place(400, 300, size, listOf(Rectangle(0, 150, 50, 10)), previous).bounds.y)
    }

    @Test
    fun `host preserves a clear placement after a control moves away`() {
        val button = JButton("Action").apply { setBounds(0, 0, 80, 40) }
        val content = JPanel(null).apply { add(button) }
        val tab = EdgeTab("Scope", EmptyIcon.create(16))
        val host = EdgeTabHost(content, tab).apply { setSize(400, 300); doLayout() }
        val previous = Rectangle(tab.bounds)
        button.setBounds(100, 0, 80, 40)
        host.place()
        assertEquals(previous, tab.bounds)
    }

    @Test
    fun `host avoids selectable read-only text`() {
        val text = JTextPane().apply { isEditable = false; setBounds(0, 0, 80, 100) }
        val content = JPanel(null).apply { add(text) }
        val tab = EdgeTab("Scope", EmptyIcon.create(16))
        val host = EdgeTabHost(content, tab).apply { setSize(400, 300); doLayout() }
        assertEquals(listOf(text.bounds), host.obstacles(tab.edgeSize().thickness))
        assertFalse(tab.bounds.intersects(text.bounds))
    }

    @Test
    fun `a long text is cut with an ellipsis`() {
        assertEquals("abc", ellipsize("abc", 3) { it.length })
        assertEquals("ab…", ellipsize("abcdef", 3) { it.length })
        assertEquals("", ellipsize("abcdef", 0) { it.length })
    }

    @Test
    fun `the host keeps the tab off the content's buttons and lets the content fill it`() {
        val button = JButton("Action")
        val content = JPanel(BorderLayout()).apply {
            add(button, BorderLayout.NORTH)
            add(JLabel("text"), BorderLayout.CENTER)
        }
        val tab = EdgeTab("Scope: project", EmptyIcon.create(16))
        val host = EdgeTabHost(content, tab)
        host.setSize(400, 300)
        host.doLayout()
        content.doLayout()
        host.place()
        assertEquals(Rectangle(0, 0, 400, 300), content.bounds)
        assertEquals(listOf(button.bounds), host.obstacles(tab.edgeSize().thickness))
        assertFalse(tab.bounds.intersects(button.bounds))
        assertEquals(EdgeTabForm.FULL, tab.form)
        // The floating tab sits in a higher layer and before the content in Z-order, so an opaque content panel does not hide it.
        assertEquals(0, host.getComponentZOrder(tab))
        assertEquals(1, host.getComponentZOrder(content))

        content.isOpaque = true
        content.background = Color(10, 20, 30)
        val img = BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        host.paint(g)
        g.dispose()
        val backgroundRgb = content.background.rgb
        val paintedOverContent = (tab.bounds.x until tab.bounds.x + tab.bounds.width).any { x ->
            (tab.bounds.y until tab.bounds.y + tab.bounds.height).any { y -> img.getRGB(x, y) != backgroundRgb }
        }
        org.junit.Assert.assertTrue("The floating tab must paint in front of an opaque content panel", paintedOverContent)
    }
}
