package dev.insight.review.ui

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Container
import javax.swing.JLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewTileGridTest {
    private val opened = mutableListOf<String>()

    private fun grid(count: Int = 3, hint: (Int) -> String = { "Hint $it" }): ReviewTileGrid =
        ReviewTileGrid((1..count).map { i -> ReviewTile("t$i", "Tile $i", hint(i)) { opened += "t$i" } })

    private fun descendants(c: Component): List<Component> =
        if (c is Container) c.components.flatMap { listOf(it) + descendants(it) } else emptyList()

    private fun labels(c: Component): List<String> = descendants(c).filterIsInstance<JLabel>().map { it.text }

    private fun twoColumnWidth(): Int =
        2 * JBUI.scale(ReviewTileGrid.MIN_TILE_WIDTH) + JBUI.scale(ReviewTileGrid.TILE_GAP)

    @Test
    fun tileComponentsAreNamedAndAccessible() {
        val g = grid()
        assertEquals(listOf("t1", "t2", "t3"), g.tileComponents.keys.toList())
        for (t in g.tiles) {
            val c = g.tileComponents.getValue(t.id)
            assertEquals("review-tile-${t.id}", c.name)
            assertEquals(t.title, c.accessibleContext.accessibleName)
            assertTrue(g.isTileEnabled(t.id))
        }
    }

    @Test
    fun clickOpensEnabledTileOnly() {
        val g = grid()
        g.click("t2")
        assertEquals(listOf("t2"), opened)
        g.setTileEnabled("t2", false, "Not ready")
        assertFalse(g.isTileEnabled("t2"))
        assertEquals("Not ready", g.tileComponents.getValue("t2").toolTipText)
        g.click("t2")
        assertEquals(listOf("t2"), opened)
        g.setTileEnabled("t2", true)
        assertEquals("Hint 2", g.tileComponents.getValue("t2").toolTipText)
        g.click("t2")
        assertEquals(listOf("t2", "t2"), opened)
    }

    @Test
    fun selectionIsTracked() {
        val g = grid()
        assertNull(g.selectedId)
        g.setSelected("t3")
        assertEquals("t3", g.selectedId)
        g.setSelected(null)
        assertNull(g.selectedId)
    }

    @Test
    fun statsAreRenderedAndReplaced() {
        val g = grid()
        val tile = g.tileComponents.getValue("t1")
        assertTrue(labels(tile).isEmpty())
        g.setStats("t1", listOf(ReviewTileStat("12", "files"), ReviewTileStat("3", "topics")))
        assertEquals(listOf("12", "files", "3", "topics"), labels(tile))
        g.setStats("t1", listOf(ReviewTileStat("7", "files")))
        assertEquals(listOf("7", "files"), labels(tile))
        g.setStats("t1", emptyList())
        assertTrue(labels(tile).isEmpty())
        assertTrue(labels(g.tileComponents.getValue("t2")).isEmpty())
    }

    @Test
    fun tilesWrapToRowsOfEqualHeight() {
        val g = grid(3) { if (it == 1) "A much longer hint ".repeat(12) else "Short" }
        g.setSize(twoColumnWidth(), 1000)
        g.doLayout()
        val a = g.tileComponents.getValue("t1")
        val b = g.tileComponents.getValue("t2")
        val c = g.tileComponents.getValue("t3")
        assertEquals(a.y, b.y)
        assertTrue(b.x > a.x)
        assertTrue(c.y > a.y)
        assertEquals(a.height, b.height)
        assertTrue(a.height >= JBUI.scale(120))
        assertTrue(a.width <= JBUI.scale(ReviewTileGrid.MAX_TILE_WIDTH))
        assertTrue(a.width >= JBUI.scale(ReviewTileGrid.MIN_TILE_WIDTH))
    }

    @Test
    fun preferredHeightGrowsWhenNarrower() {
        val g = grid(4) { "Some explanatory words that need wrapping in a narrow tile ".repeat(3) }
        g.setSize(4 * JBUI.scale(ReviewTileGrid.MAX_TILE_WIDTH), 10)
        g.invalidate()
        val wide = g.preferredSize.height
        g.setSize(JBUI.scale(ReviewTileGrid.MIN_TILE_WIDTH), 10)
        g.invalidate()
        val narrow = g.preferredSize.height
        assertTrue("narrow=$narrow wide=$wide", narrow > wide)
    }

    @Test
    fun scrollableTracksWidthOnly() {
        val g = grid()
        assertTrue(g.scrollableTracksViewportWidth)
        assertFalse(g.scrollableTracksViewportHeight)
    }
}
