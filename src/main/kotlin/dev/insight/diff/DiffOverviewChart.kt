package dev.insight.diff

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import kotlin.math.roundToInt

/** Compact word lists fill opposing corners around the line counts, independently of the word cloud. */
internal class DiffOverviewChart(
    internal val changes: WordChanges,
    private val chartWidth: Int,
    private val chartHeight: Int,
    private val open: (WordChange) -> Unit,
) : JComponent() {
    internal data class Placed(val change: WordChange, val added: Boolean, val text: String, val bounds: Rectangle)
    private val wordFont = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(10))
    private val lineChart = DiffLineChart(changes.lines)
    private val placed by lazy { place(false) + place(true) }
    internal val lineChartBounds: Rectangle
        get() {
            val width = minOf(JBUI.scale(280), chartWidth / 3)
            val height = minOf(JBUI.scale(180), chartHeight / 3)
            return Rectangle((chartWidth - width) / 2, (chartHeight - height) / 2, width, height)
        }

    init {
        preferredSize = Dimension(chartWidth, chartHeight)
        toolTipText = ""
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.button == MouseEvent.BUTTON1) at(e)?.let { open(it.change) } }
        })
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                cursor = if (at(e) == null) Cursor.getDefaultCursor() else Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }
        })
    }

    internal fun placedWords(): List<Placed> = placed
    private fun at(event: MouseEvent) = placed.firstOrNull { it.bounds.contains(event.point) }

    override fun getToolTipText(event: MouseEvent): String? = at(event)?.let {
        val change = it.change
        "<html><b>${escape(change.word)}</b> · −${change.removed} removed · +${change.added} added<br>" +
            change.paths.sorted().take(10).joinToString("<br>") { escape(it) } +
            (if (change.paths.size > 10) "<br>… ${change.paths.size - 10} more" else "") + "</html>"
    } ?: if (lineChartBounds.contains(event.point))
        "Deleted lines: ${changes.lines.deleted} · Added lines: ${changes.lines.added} in this scope" else null

    /** Long words come first at each corner; shorter words fill the remaining room along each row. */
    private fun place(added: Boolean): List<Placed> {
        val pending = changes.words.filter { if (added) it.added > 0 else it.removed > 0 }
            .sortedWith(compareByDescending<WordChange> { it.word.codePointCount(0, it.word.length) }
                .thenBy { it.word })
            .toMutableList()
        val metrics = getFontMetrics(wordFont)
        val gap = JBUI.scale(8)
        val margin = JBUI.scale(6)
        val top = JBUI.scale(30)
        val bottom = chartHeight - JBUI.scale(26)
        val rowHeight = metrics.height + JBUI.scale(1)
        val rows = maxOf(1, (bottom - top) / rowHeight)
        val reserved = lineChartBounds.apply { grow(gap, gap) }
        val result = mutableListOf<Placed>()
        for (row in 0 until rows) {
            val y = if (added) bottom - (row + 1) * rowHeight else top + row * rowHeight
            // A broad outer corner tapers towards the opposite edge, leaving a diagonal gap between the lists.
            var available = (chartWidth * (0.65 - 0.5 * row / maxOf(1.0, rows - 1.0))).roundToInt() - 2 * margin
            if (y < reserved.maxY && y + metrics.height > reserved.y) {
                available = minOf(available, if (added) chartWidth - margin - reserved.maxX.toInt() else reserved.x - margin)
            }
            var used = 0
            while (pending.isNotEmpty()) {
                val index = pending.indexOfFirst { metrics.stringWidth(it.word) <= available - used }
                if (index < 0) break
                val word = pending.removeAt(index)
                val text = word.word
                val width = metrics.stringWidth(text)
                val x = if (added) chartWidth - margin - used - width else margin + used
                result += Placed(word, added, text, Rectangle(x, y, width, metrics.height))
                used += width + gap
            }
        }
        return result
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = UIUtil.getListBackground()
            g2.fillRect(0, 0, width, height)
            g2.font = JBFont.small()
            val margin = JBUI.scale(6)
            g2.color = REMOVED
            g2.drawString("Deleted · ${changes.lines.deleted} lines", margin, g2.fontMetrics.ascent + margin)
            g2.color = ADDED
            val label = "Added · ${changes.lines.added} lines"
            g2.drawString(label, chartWidth - margin - g2.fontMetrics.stringWidth(label), g2.fontMetrics.ascent + margin)
            lineChart.paint(g2, lineChartBounds, REMOVED, ADDED)
            g2.font = wordFont
            val ascent = g2.fontMetrics.ascent
            placed.forEach {
                g2.color = if (it.added) ADDED else REMOVED
                g2.drawString(it.text, it.bounds.x, it.bounds.y + ascent)
            }
            g2.font = JBFont.small()
            g2.color = UIUtil.getContextHelpForeground()
            val candidates = changes.words.sumOf { (if (it.removed > 0) 1 else 0) + (if (it.added > 0) 1 else 0) }
            val omitted = candidates - placed.size
            val caption = "Longest words first from each corner" + if (omitted > 0) " · $omitted entries do not fit" else ""
            g2.drawString(caption, (chartWidth - g2.fontMetrics.stringWidth(caption)) / 2, chartHeight - margin)
        } finally { g2.dispose() }
    }

    private companion object {
        val REMOVED = JBColor(Color(0xC0392B), Color(0xE8836E))
        val ADDED = JBColor(Color(0x1E8449), Color(0x6FCF97))
        fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
