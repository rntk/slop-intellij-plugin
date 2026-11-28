package dev.insight.review.ui

import com.intellij.util.ui.JBUI
import java.awt.image.BufferedImage
import java.awt.event.ItemEvent
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewControlsTest {
    private fun paint(component: JComponent) {
        component.setSize(component.preferredSize)
        component.doLayout()
        val image = BufferedImage(component.width.coerceAtLeast(1), component.height.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try { component.paint(g) } finally { g.dispose() }
    }

    @Test
    fun `pill check box keeps check box semantics at pill height`() {
        val box = PillCheckBox("Wrap", name = "wrap", tooltip = "Soft wrap")
        val component: JComponent = box
        assertTrue(component is JCheckBox)
        assertEquals("wrap", box.name)
        assertEquals("Soft wrap", box.toolTipText)
        assertFalse(box.isFocusable)
        assertEquals(JBUI.scale(TREE_CONTROL_HEIGHT_DP), box.preferredSize.height)
        assertTrue(box.preferredSize.width > JBUI.scale(18))

        val actions = mutableListOf<Boolean>()
        val items = mutableListOf<Int>()
        box.addActionListener { actions += box.isSelected }
        box.addItemListener { items += it.stateChange }
        box.doClick()
        box.doClick()
        assertEquals(listOf(true, false), actions)
        assertEquals(listOf(ItemEvent.SELECTED, ItemEvent.DESELECTED), items)

        box.isSelected = true
        box.partial = true
        paint(box)
        box.isEnabled = false
        paint(box)
    }

    @Test
    fun `pill button clicks and keeps pill height`() {
        var clicks = 0
        val button = PillButton("Summarize", name = "summarize", primary = true) { clicks++ }
        assertTrue((button as JComponent) is JButton)
        assertEquals(JBUI.scale(TREE_CONTROL_HEIGHT_DP), button.preferredSize.height)
        button.doClick()
        assertEquals(1, clicks)
        button.isEnabled = false
        button.doClick()
        assertEquals(1, clicks)
        paint(button)
        button.isEnabled = true
        button.primary = false
        paint(button)
    }

    @Test
    fun `pill button height grows with a zoomed font`() {
        val button = PillButton("Back").apply { font = font.deriveFont(font.size2D * 3) }
        assertTrue(button.preferredSize.height > JBUI.scale(TREE_CONTROL_HEIGHT_DP))
    }

    @Test
    fun `pill combo box keeps combo semantics and custom renderers`() {
        val combo = PillComboBox(listOf("a", "b", "c"), name = "mode", prefix = "View")
        assertTrue((combo as JComponent) is JComboBox<*>)
        assertEquals("mode", combo.name)
        assertEquals("View", combo.prefix)
        assertEquals(JBUI.scale(TREE_CONTROL_HEIGHT_DP), combo.preferredSize.height)
        assertFalse(combo.isOpaque)

        val chosen = mutableListOf<Any?>()
        combo.addActionListener { chosen += combo.selectedItem }
        combo.selectedIndex = 2
        combo.selectedItem = "b"
        assertEquals(listOf<Any?>("c", "b"), chosen)

        val narrow = combo.preferredSize.width
        var rendered = 0
        combo.renderer = javax.swing.ListCellRenderer { _, value, _, _, _ -> rendered++; JLabel("Option $value is long") }
        assertTrue(combo.preferredSize.width > narrow)
        paint(combo)
        assertTrue(rendered > 0)
        combo.isEnabled = false
        paint(combo)

        val unprefixed = PillComboBox(listOf("a", "b", "c"))
        assertTrue(unprefixed.preferredSize.width < PillComboBox(listOf("a", "b", "c"), prefix = "View").preferredSize.width)
    }

    @Test
    fun `toolbar row inserts dividers only between non-empty clusters`() {
        val a = JLabel("a")
        val b = JLabel("b")
        val c = JLabel("c")
        val row = pillToolbarRow(listOf(a, b), emptyList(), listOf(c), emptyList())
        assertFalse(row.isOpaque)
        val children = row.components.toList()
        assertEquals(4, children.size)
        assertEquals(listOf(a, b), children.take(2))
        assertTrue(children[2] is ToolbarDivider)
        assertEquals(c, children[3])
        assertEquals(0, pillToolbarRow(emptyList(), emptyList()).componentCount)
        assertEquals(1, pillToolbarRow(listOf(a)).componentCount)
    }

    @Test
    fun `view selector exposes a named pill combo box`() {
        val picked = mutableListOf<String>()
        val selector = viewSelector("words-view", listOf("ONE", "TWO"), "ONE", 4, JBUI.Fonts.smallFont(), { it.lowercase() }, { "tip $it" }) {
            picked += it
        }
        val combo = (selector as java.awt.Container).components.filterIsInstance<JComboBox<*>>().single { it.name == "words-view" }
        assertTrue(combo is PillComboBox<*>)
        assertEquals("tip ONE", combo.toolTipText)
        combo.selectedItem = "TWO"
        assertEquals(listOf("TWO"), picked)
    }
}
