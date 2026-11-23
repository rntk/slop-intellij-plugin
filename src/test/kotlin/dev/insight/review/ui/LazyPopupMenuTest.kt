package dev.insight.review.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu

class LazyPopupMenuTest {
    @Test
    fun `items are built once, on first read`() {
        var builds = 0
        val menu = LazyPopupMenu { builds++; JPopupMenu().apply { add(JMenuItem("A")); add(JMenuItem("B")) } }
        assertEquals(0, builds)
        assertEquals(listOf("A", "B"), menu.components.map { (it as JMenuItem).text })
        assertEquals(2, menu.componentCount)
        assertEquals(2, menu.subElements.size)
        assertEquals(1, builds)
    }

    @Test
    fun `an entry shares one menu across its parts and builds it only when read`() {
        var builds = 0
        val label = JLabel("a")
        val entry = JPanel().apply { add(label) }
        installSummaryEntryMenu(entry) { builds++; JPopupMenu().apply { add(JMenuItem("Context…")) } }
        assertEquals(0, builds)
        assertSame(entry.componentPopupMenu, label.componentPopupMenu)
        assertEquals("Context…", (label.componentPopupMenu.getComponent(0) as JMenuItem).text)
        assertEquals(1, builds)
    }
}
