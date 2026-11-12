package dev.insight.canvas

import com.intellij.util.ui.JBUI
import com.intellij.ide.ui.UISettings
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.SwingUtilities

/**
 * Like schematic callable titles, card names stay readable when the surrounding source shrinks: the font never drops
 * below a readable size. When that is wider than the card, the title shrinks back toward the zoomed size to fit, and
 * only then ends with "…"; the tooltip keeps the whole name.
 */
internal class CanvasTitleLabel(text: String, icon: Icon?) : JLabel(text, icon, LEFT) {
    /** The size the zoom asked for, which the title may shrink to when the readable size does not fit. */
    private var zoomedSize = 0f

    override fun setFont(font: Font?) {
        zoomedSize = font?.size2D ?: 0f
        super.setFont(font?.deriveFont(maxOf(font.size2D, JBUI.scale(14).toFloat())))
    }

    /** The font the title is drawn in at [width]: the label's own font, or a smaller one down to the zoomed size. */
    internal fun fittedFont(width: Int): Font {
        val font = font ?: return super.getFont()
        val room = width - insets.left - insets.right - (icon?.let { it.iconWidth + iconTextGap } ?: 0)
        val needed = getFontMetrics(font).stringWidth(text.orEmpty())
        if (needed <= room || room <= 0 || zoomedSize >= font.size2D) return font
        return font.deriveFont(maxOf(zoomedSize, font.size2D * room / needed))
    }

    override fun paintComponent(g: Graphics) {
        val fitted = fittedFont(width)
        if (fitted == font) return super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            UISettings.setupAntialiasing(g2)
            g2.font = fitted
            val metrics = g2.fontMetrics
            val inner = Rectangle(insets.left, insets.top, width - insets.left - insets.right, height - insets.top - insets.bottom)
            val iconBounds = Rectangle()
            val textBounds = Rectangle()
            val shown = SwingUtilities.layoutCompoundLabel(this, metrics, text, icon, verticalAlignment, horizontalAlignment,
                verticalTextPosition, horizontalTextPosition, inner, iconBounds, textBounds, iconTextGap)
            icon?.paintIcon(this, g2, iconBounds.x, iconBounds.y)
            g2.color = foreground
            g2.drawString(shown, textBounds.x, textBounds.y + metrics.ascent)
        } finally {
            g2.dispose()
        }
    }
}
