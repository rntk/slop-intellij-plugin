package dev.insight.diff

import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.geom.Arc2D
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Exact totals from the changed lines of the snapshot patches, independent of the displayed words. */
internal data class ChangedLineCounts(val deleted: Int = 0, val added: Int = 0)

/** Radii of two half-circles whose areas compare the counts; the larger count gets [maxRadius]. */
internal fun halfCircleRadii(counts: ChangedLineCounts, maxRadius: Double): Pair<Double, Double> {
    val max = maxOf(counts.deleted, counts.added)
    if (max == 0) return 0.0 to 0.0
    fun radius(count: Int) = maxRadius * sqrt(count.toDouble() / max)
    return radius(counts.deleted) to radius(counts.added)
}

/**
 * Deleted lines as a left half-circle and added lines as a right half-circle, both resting on a shared baseline
 * either side of a vertical axis. Their areas are proportional to the counts, so they compare at a glance.
 */
internal class DiffLineChart(private val counts: ChangedLineCounts) {
    fun paint(g: Graphics2D, bounds: Rectangle, deletedColor: Color, addedColor: Color) {
        val g2 = g.create() as Graphics2D
        try {
            g2.translate(bounds.x, bounds.y)
            g2.color = UIUtil.getListBackground()
            g2.fillRect(0, 0, bounds.width, bounds.height)
            val captionFont = JBFont.small()
            val numberFont = JBFont.label().biggerOn(2f)
            val caption = g2.getFontMetrics(captionFont)
            val number = g2.getFontMetrics(numberFont)
            val pad = JBUI.scale(6)
            g2.font = captionFont
            g2.color = UIUtil.getLabelForeground()
            val title = "Changed lines"
            g2.drawString(title, (bounds.width - caption.stringWidth(title)) / 2, caption.ascent + pad)

            val deletedText = "−${counts.deleted}"
            val addedText = "+${counts.added}"
            val labelWidth = maxOf(number.stringWidth(deletedText), number.stringWidth(addedText),
                caption.stringWidth("deleted"), caption.stringWidth("added"))
            val leader = JBUI.scale(18)
            val gap = JBUI.scale(3)
            val axis = bounds.width / 2.0
            val top = caption.height + pad * 2
            val baseline = bounds.height - pad
            val maxRadius = minOf(
                axis - gap - pad * 3 - leader - labelWidth,
                (baseline - top).toDouble() / 2,
            ).coerceAtLeast(0.0)
            val (deletedRadius, addedRadius) = halfCircleRadii(counts, maxRadius)
            val labelHeight = number.height + caption.height

            fun half(radius: Double, left: Boolean, color: Color, text: String, word: String) {
                val edge = if (left) axis - gap else axis + gap
                val centerY = baseline - radius
                if (radius > 0) {
                    g2.color = color
                    g2.fill(Arc2D.Double(edge - radius, centerY - radius, 2 * radius, 2 * radius,
                        if (left) 90.0 else -90.0, 180.0, Arc2D.PIE))
                }
                val labelY = minOf(maxOf(centerY, top + labelHeight / 2.0), baseline - labelHeight / 2.0).roundToInt()
                val leaderStart = if (left) edge - radius - pad else edge + radius + pad
                val leaderEnd = if (left) leaderStart - leader else leaderStart + leader
                g2.color = color
                val dot = JBUI.scale(2)
                var x = minOf(leaderStart, leaderEnd)
                while (x <= maxOf(leaderStart, leaderEnd) - dot) {
                    g2.fillOval(x.roundToInt(), labelY - dot / 2, dot, dot)
                    x += dot * 2
                }
                val textX = { width: Int -> if (left) (leaderEnd - pad - width).roundToInt() else (leaderEnd + pad).roundToInt() }
                val numberBaseline = labelY - labelHeight / 2 + number.ascent
                g2.font = numberFont
                g2.drawString(text, textX(number.stringWidth(text)), numberBaseline)
                g2.font = captionFont
                g2.color = UIUtil.getContextHelpForeground()
                g2.drawString(word, textX(caption.stringWidth(word)), numberBaseline + number.descent + caption.ascent)
            }
            half(deletedRadius, true, deletedColor, deletedText, "deleted")
            half(addedRadius, false, addedColor, addedText, "added")
        } finally { g2.dispose() }
    }
}
