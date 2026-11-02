package dev.insight.canvas

import com.intellij.util.ui.JBUI
import dev.insight.ui.zoomed
import java.awt.Font
import javax.swing.JPanel
import javax.swing.SwingUtilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasZoomComponentsTest {
    @Test fun `titles stay readable and grow when zooming in`() {
        val base = Font(Font.DIALOG, Font.BOLD, JBUI.scale(14))
        val title = CanvasTitleLabel("Example.kt", null)
        for (zoom in listOf(0.05, 0.25, 0.5, 1.0, 3.0)) {
            title.font = base.zoomed(zoom)
            assertEquals(maxOf(base.size2D, (base.size2D * zoom).toFloat()), title.font.size2D, 0.01f)
            assertTrue(title.font.isBold)
        }
    }

    @Test fun `tab bounds shrink with the card and still allow switching at minimum zoom`() {
        SwingUtilities.invokeAndWait {
            var zoom = 1.0
            val tabs = CanvasChatTabs { zoom }
            val base = Font(Font.DIALOG, Font.PLAIN, JBUI.scale(14))
            repeat(3) { tabs.addTab("Chat ${it + 1}", JPanel()) }
            fun bounds(scale: Double): java.awt.Rectangle {
                zoom = scale
                tabs.font = base.zoomed(scale)
                tabs.setSize((1000 * scale).toInt(), 100)
                tabs.doLayout()
                return tabs.getBoundsAt(0)
            }
            val normal = bounds(1.0)
            val half = bounds(0.5)
            assertTrue(half.width <= normal.width * 0.65)
            assertTrue(half.height <= normal.height * 0.65)
            val tiny = bounds(0.05)
            assertTrue(tiny.width < half.width)
            assertTrue(tiny.height < half.height)
            tabs.selectedIndex = 2
            tabs.doLayout()
            val selected = tabs.getBoundsAt(2)
            assertEquals(2, tabs.indexAtLocation(selected.x + selected.width / 2, selected.y + selected.height / 2))
        }
    }
}
