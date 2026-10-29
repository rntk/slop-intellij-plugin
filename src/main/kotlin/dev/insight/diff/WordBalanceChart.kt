package dev.insight.diff

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.JComponent
import kotlin.math.roundToInt

/**
 * Changed words by how much of them only moved. A word removed as often as it is added is [matched]; the rest of its
 * occurrences are [removed] or [added] for good. Mostly matched words suggest a refactoring that reshaped the same code.
 */
internal data class WordBalance(val removed: Int, val matched: Int, val added: Int) {
    val total get() = removed + matched + added

    /** The share of all removed and added words that are matched on the other side. */
    val matchedShare get() = if (total == 0) 0.0 else 2.0 * matched / (total + matched)
}

/** A stacked bar of net removed, matched and net added words, in proportion to their counts. */
internal class WordBalanceChart(internal val balance: WordBalance, private val chartWidth: Int) : JComponent() {
    init {
        preferredSize = Dimension(chartWidth, JBUI.scale(HEIGHT))
        toolTipText = "<html>Words removed and added equally often count as matched, as when code only moves.<br>" +
            "Net removed: ${balance.removed} · Matched: ${balance.matched} (on each side) · Net added: ${balance.added}</html>"
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = UIUtil.getListBackground()
            g2.fillRect(0, 0, width, height)
            g2.font = JBFont.small()
            val metrics = g2.fontMetrics
            val margin = JBUI.scale(6)
            g2.color = UIUtil.getContextHelpForeground()
            g2.drawString("Word balance", margin, metrics.ascent)
            val summary = "${(balance.matchedShare * 100).roundToInt()}% of changed words occur on both sides"
            g2.drawString(summary, chartWidth - margin - metrics.stringWidth(summary), metrics.ascent)
            val top = metrics.height + JBUI.scale(4)
            val barWidth = chartWidth - 2 * margin
            val barHeight = height - top - margin
            val total = balance.total
            if (total == 0) return
            var done = 0
            var x = margin
            fun segment(count: Int, color: Color, text: String) {
                if (count == 0) return
                done += count
                val end = margin + (barWidth * done.toDouble() / total).roundToInt()
                g2.color = color
                g2.fillRect(x, top, end - x, barHeight)
                if (metrics.stringWidth(text) + 2 * margin <= end - x) {
                    g2.color = Color.WHITE
                    g2.drawString(text, x + (end - x - metrics.stringWidth(text)) / 2, top + (barHeight + metrics.ascent) / 2 - 1)
                }
                x = end
            }
            segment(balance.removed, REMOVED, "−${balance.removed}")
            segment(balance.matched, MATCHED, "${balance.matched}")
            segment(balance.added, ADDED, "+${balance.added}")
        } finally { g2.dispose() }
    }

    private companion object {
        const val HEIGHT = 44
        val REMOVED = JBColor(Color(0xC0392B), Color(0xE8836E))
        val ADDED = JBColor(Color(0x1E8449), Color(0x6FCF97))
        val MATCHED = JBColor(Color(0x7F8C8D), Color(0x9AA0A6))
    }
}
