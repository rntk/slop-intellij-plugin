package dev.insight.diff

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the semantic changes card shows: still counting, failed, or the counted [SemanticChanges]. */
internal sealed interface SemanticResult {
    object Counting : SemanticResult
    data class Failed(val message: String) : SemanticResult
    data class Ready(val changes: SemanticChanges) : SemanticResult
}

/** The three views of the semantic changes card. */
internal enum class SemanticMode(val label: String) { METRICS("Metrics"), COMPLEXITY("Complexity"), CONSTRUCTS("Constructs") }

/** The metrics the complexity scatter can plot per function. */
internal val COMPLEXITY_METRICS = listOf(Metric.CYCLOMATIC, Metric.COGNITIVE, Metric.MAX_NESTING, Metric.CODE_LINES, Metric.PARAMETERS, Metric.FAN_OUT)

/**
 * Construct metrics as words for the word cloud: a construct is removed as often as declarations lost it and added as
 * often as declarations gained it. Files without declarations count by their own change. Constructs that did not change are omitted.
 */
internal fun constructChanges(changes: SemanticChanges): WordChanges {
    val words = Metric.entries.filter { it.construct }.mapNotNull { metric ->
        var removed = 0
        var added = 0
        val paths = linkedSetOf<String>()
        fun count(path: String, delta: Int) {
            if (delta == 0) return
            if (delta > 0) added += delta else removed -= delta
            paths += path
        }
        changes.files.filter { it.skipped == null }.forEach { file ->
            if (file.declarations.isEmpty()) count(file.path, file.delta(metric))
            else file.declarations.forEach { count(it.path, it.delta(metric)) }
        }
        WordChange(metric.label, removed, added, paths).takeIf { it.total > 0 }
    }.sortedWith(compareByDescending<WordChange> { it.total }.thenBy { it.word })
    return WordChanges(words, words.sumOf { it.removed }, words.sumOf { it.added })
}

/** A signed number with a real minus sign; zero has none. */
internal fun signed(value: Int): String = when {
    value > 0 -> "+$value"
    value < 0 -> "−${-value}"
    else -> "0"
}

/**
 * Metric totals before and after the change, grouped by [MetricGroup]. A diverging bar shows each difference relative to
 * the largest one of its group. Metrics that are 0 on both sides are left out; clicking a row opens the diffs of its files.
 */
internal class SemanticMetricsTable(
    internal val changes: SemanticChanges,
    private val tableWidth: Int,
    private val open: (Metric) -> Unit,
) : JComponent() {
    /** A group header when [metric] is null. */
    internal data class Row(val group: MetricGroup, val metric: Metric?, val before: Int, val after: Int) {
        val delta get() = after - before
        val deltaText get() = signed(delta)
    }

    private val rowHeight = JBUI.scale(22)
    private val rows: List<Row> = MetricGroup.entries.flatMap { group ->
        val metrics = Metric.entries.filter { it.group == group }
            .map { Row(group, it, changes.before[it] ?: 0, changes.after[it] ?: 0) }
            .filter { it.before != 0 || it.after != 0 }
        if (metrics.isEmpty()) emptyList() else listOf(Row(group, null, 0, 0)) + metrics
    }
    private val largest = rows.filter { it.metric != null }.groupBy { it.group }.mapValues { (_, rows) -> rows.maxOf { abs(it.delta) } }
    private var hovered = -1

    init {
        preferredSize = Dimension(tableWidth, rowHeight * (rows.size + 1))
        toolTipText = ""
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.button == MouseEvent.BUTTON1) metricAt(e.y)?.let { open(it.metric!!) } }
            override fun mouseExited(e: MouseEvent) { hovered = -1; repaint() }
        })
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val index = index(e.y)
                if (index != hovered) { hovered = index; repaint() }
                cursor = if (metricAt(e.y) != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            }
        })
    }

    internal fun rows(): List<Row> = rows
    internal fun rowBounds(index: Int) = Rectangle(0, rowHeight * (index + 1), tableWidth, rowHeight)

    private fun index(y: Int) = if (y < rowHeight) -1 else ((y - rowHeight) / rowHeight).takeIf { it < rows.size } ?: -1
    private fun metricAt(y: Int) = rows.getOrNull(index(y))?.takeIf { it.metric != null }

    override fun getToolTipText(event: MouseEvent): String? = metricAt(event.y)?.let { row ->
        val metric = row.metric!!
        "<html><b>${escape(metric.label)}</b> · ${escape(metric.group.label)}<br>${escape(metric.description)}" +
            (if (metric.approximate) "<br>≈ Approximate: references resolve against today's project, so the before side may differ." else "") +
            (if (metric.summed) "" else "<br>Largest value over all files, not a sum.") +
            "<br>${changes.filesChanging(metric).size} files differ · click to open their diffs</html>"
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = UIUtil.getListBackground()
            g2.fillRect(0, 0, width, height)
            val pad = JBUI.scale(8)
            val numberWidth = JBUI.scale(70)
            val beforeRight = tableWidth / 3
            val afterRight = beforeRight + numberWidth
            val deltaRight = afterRight + numberWidth
            val barLeft = deltaRight + pad * 2
            val barWidth = maxOf(JBUI.scale(40), tableWidth - barLeft - pad)
            val center = barLeft + barWidth / 2
            val small = JBFont.small()
            val label = JBFont.label()
            fun text(value: String, right: Int, y: Int, font: java.awt.Font = label) {
                g2.font = font
                g2.drawString(value, right - g2.fontMetrics.stringWidth(value), y + (rowHeight + g2.fontMetrics.ascent - g2.fontMetrics.descent) / 2)
            }
            g2.color = UIUtil.getContextHelpForeground()
            g2.font = small
            text("metric", pad + g2.fontMetrics.stringWidth("metric"), 0, small)
            text("before", beforeRight, 0, small)
            text("after", afterRight, 0, small)
            text("Δ", deltaRight, 0, small)
            text("← less     more →", center + g2.fontMetrics.stringWidth("← less     more →") / 2, 0, small)
            g2.color = JBColor.border()
            g2.drawLine(0, rowHeight - 1, width, rowHeight - 1)
            rows.forEachIndexed { i, row ->
                val y = rowHeight * (i + 1)
                val metric = row.metric
                if (metric == null) {
                    g2.color = Color(128, 128, 128, 28)
                    g2.fillRect(0, y, width, rowHeight)
                    g2.color = UIUtil.getLabelForeground()
                    g2.font = label.deriveFont(java.awt.Font.BOLD)
                    text(row.group.label, pad + g2.fontMetrics.stringWidth(row.group.label), y, g2.font)
                    return@forEachIndexed
                }
                if (i == hovered) {
                    g2.color = Color(128, 128, 128, 40)
                    g2.fillRect(0, y, width, rowHeight)
                }
                g2.color = UIUtil.getLabelForeground()
                val name = metric.label + if (metric.approximate) " ≈" else ""
                g2.font = label
                text(name, pad * 2 + g2.fontMetrics.stringWidth(name), y)
                text(row.before.toString(), beforeRight, y)
                text(row.after.toString(), afterRight, y)
                g2.color = when { row.delta > 0 -> ADDED; row.delta < 0 -> REMOVED; else -> UIUtil.getContextHelpForeground() }
                text(row.deltaText, deltaRight, y)
                val max = largest[row.group] ?: 0
                if (row.delta != 0 && max > 0) {
                    val length = maxOf(1, (abs(row.delta).toDouble() / max * (barWidth / 2)).roundToInt())
                    val barHeight = rowHeight - JBUI.scale(10)
                    g2.fillRect(if (row.delta > 0) center else center - length, y + (rowHeight - barHeight) / 2, length, barHeight)
                }
                g2.color = JBColor.border()
                g2.drawLine(center, y + JBUI.scale(2), center, y + rowHeight - JBUI.scale(2))
            }
        } finally { g2.dispose() }
    }

    private companion object {
        val REMOVED = JBColor(Color(0xC0392B), Color(0xE8836E))
        val ADDED = JBColor(Color(0x1E8449), Color(0x6FCF97))
        fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
