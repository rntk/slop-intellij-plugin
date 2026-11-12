package dev.insight.canvas

import com.intellij.util.ui.JBUI
import dev.insight.ui.zoomed
import java.awt.Font
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

    @Test fun `a title wider than its card shrinks toward the zoomed size before it is cut`() {
        val base = Font(Font.DIALOG, Font.BOLD, JBUI.scale(14))
        val title = CanvasTitleLabel("AVeryLongSymbolNameThatDoesNotFit", null)
        title.font = base.zoomed(0.25)
        val readable = title.font.size2D
        val full = title.getFontMetrics(title.font).stringWidth(title.text)
        assertEquals(readable, title.fittedFont(full + 10).size2D, 0.01f)
        val half = title.fittedFont(full / 2).size2D
        assertTrue(half < readable && half >= base.size2D * 0.25f)
        assertEquals(base.size2D * 0.25f, title.fittedFont(5).size2D, 0.01f)
    }
}
