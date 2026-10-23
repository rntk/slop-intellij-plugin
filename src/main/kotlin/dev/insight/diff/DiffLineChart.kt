package dev.insight.diff

import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Rectangle
import kotlin.math.roundToInt

/** Exact totals from the changed lines of the snapshot patches, independent of the displayed words. */
internal data class ChangedLineCounts(val deleted: Int = 0, val added: Int = 0)

/** Two bars share a scale so their lengths compare the number of deleted and added lines directly. */
internal class DiffLineChart(private val counts: ChangedLineCounts) {
    fun paint(g: Graphics2D, bounds: Rectangle, deletedColor: Color, addedColor: Color) {
        val g2 = g.create() as Graphics2D
        try {
            g2.translate(bounds.x, bounds.y)
            g2.color = UIUtil.getListBackground()
            g2.fillRect(0, 0, bounds.width, bounds.height)
            g2.font = JBFont.small()
            val metrics = g2.fontMetrics
            val pad = JBUI.scale(6)
            g2.color = UIUtil.getLabelForeground()
            val title = "Changed lines"
            g2.drawString(title, (bounds.width - metrics.stringWidth(title)) / 2, metrics.ascent + pad)
            val max = maxOf(1, counts.deleted, counts.added)
            val barWidth = bounds.width - pad * 2
            val barHeight = JBUI.scale(18)
            val rowHeight = (bounds.height - metrics.height - pad * 3) / 2
            fun bar(label: String, count: Int, sign: String, color: Color, row: Int) {
                val y = metrics.height + pad * 2 + row * rowHeight
                g2.color = color
                g2.drawString(label, pad, y + metrics.ascent)
                val number = "$sign$count"
                g2.drawString(number, bounds.width - pad - metrics.stringWidth(number), y + metrics.ascent)
                val top = y + metrics.height + pad
                g2.color = Color(color.red, color.green, color.blue, 25)
                g2.fillRect(pad, top, barWidth, barHeight)
                g2.color = color
                g2.fillRect(pad, top, (barWidth * count.toDouble() / max).roundToInt(), barHeight)
            }
            bar("Deleted", counts.deleted, "−", deletedColor, 0)
            bar("Added", counts.added, "+", addedColor, 1)
        } finally { g2.dispose() }
    }
}
