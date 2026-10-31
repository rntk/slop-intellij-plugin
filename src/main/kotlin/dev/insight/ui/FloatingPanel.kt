package dev.insight.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import javax.swing.JPanel

/** A translucent plate for controls that float over the canvas; it absorbs the pointer so the canvas below does not pan or scroll. */
internal open class FloatingPanel(layout: LayoutManager) : JPanel(layout) {
    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 8)
        val absorb = object : MouseAdapter() {}
        addMouseListener(absorb)
        addMouseMotionListener(absorb)
        addMouseWheelListener(absorb)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(10)
            g2.color = Color(background.red, background.green, background.blue, 225)
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = JBColor.border()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        } finally {
            g2.dispose()
        }
    }
}
