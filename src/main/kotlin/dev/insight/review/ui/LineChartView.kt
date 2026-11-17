package dev.insight.review.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.RadarSeries
import dev.insight.review.ReviewLineChart
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.dotLeader
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.util.Locale
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu

/** A number ladder for one directory. Links retain standard keyboard navigation and accessibility. */
internal class LineChartPanel(
    tree: ReviewSummaryTree,
    path: String?,
    rootLabel: String,
    private val navigate: (String) -> Unit,
    private val open: (String) -> Unit,
    val series: RadarSeries = RadarSeries.SOURCE,
    private val contextMenu: ((String) -> JPopupMenu)? = null,
) : JPanel(BorderLayout(0, JBUI.scale(12))) {
    private val chart = ReviewLineChart(tree, series)
    private val lineLabel = series.label.lowercase(Locale.US)
    private val numberColor get() = if (series == RadarSeries.SOURCE) UIUtil.getLabelForeground() else RadarChartPanel.color(series)
    val directory = chart.directory(path)

    init {
        name = "charts-lines"
        isOpaque = false
        add(JPanel(VerticalLayout(JBUI.scale(6))).apply {
            isOpaque = false
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                name = "charts-lines-breadcrumbs"
                isOpaque = false
                chart.breadcrumbs(directory).forEachIndexed { index, entry ->
                    if (index > 0) add(JLabel(" / ").apply { font = chartFont(font); foreground = UIUtil.getContextHelpForeground() })
                    val title = if (index == 0) rootLabel + entry.path.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty() else entry.label
                    add((if (entry.path == directory.path) JLabel(title) else ActionLink(title) { navigate(entry.path) }).apply {
                        name = "charts-lines-crumb:${entry.path}"
                        toolTipText = entry.path.ifEmpty { rootLabel }
                        font = chartFont(font)
                        componentPopupMenu = contextMenu?.invoke(entry.path)
                    })
                }
            })
            add(JLabel("${number(directory.lines)} $lineLabel · ${number(directory.files.toLong())} files").apply {
                name = "charts-lines-total"
                font = chartFont(JBFont.small())
                foreground = numberColor
                toolTipText = if (series == RadarSeries.SOURCE) "Source text lines, including blank lines and comments, in the selected scope"
                    else "${series.label} in the selected diff directory"
            })
            add(JLabel("${if (series == RadarSeries.SOURCE) "Lines of code" else series.label} · Click a directory to explore").apply {
                font = chartFont(JBFont.small())
                foreground = UIUtil.getContextHelpForeground()
            })
        }, BorderLayout.NORTH)
        add(JPanel(VerticalLayout(JBUI.scale(2))).apply {
            isOpaque = false
            val children = chart.children(directory)
            if (children.isEmpty()) add(JLabel("No files in scope").apply { font = chartFont(font) })
            for (entry in children) add(row(entry))
        }, BorderLayout.CENTER)
    }

    private fun row(entry: ReviewLineChart.Entry) = JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4, 0)
        componentPopupMenu = contextMenu?.invoke(entry.path)
        val tooltip = "${entry.path} · ${number(entry.lines)} $lineLabel" +
            if (entry.directory) " · ${entry.files} files · Click to explore" else if (series == RadarSeries.SOURCE) " · Open file" else " · View diff"
        add(object : JComponent() {
            init {
                inheritsPopupMenu = true
                name = "charts-lines-count:${entry.path}"
                foreground = numberColor
                preferredSize = Dimension(JBUI.scale(240), JBUI.scale(24))
                toolTipText = tooltip
            }
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                // A shared monospace grid anchored at the right edge keeps digits, separators, and dots aligned across rows.
                g.font = numberFont()
                val metrics = g.fontMetrics
                val cell = metrics.charWidth('0').coerceAtLeast(1)
                val text = number(entry.lines)
                val line = dotLeader(text, width / cell)
                val leader = line.length - text.length
                val x = width - line.length * cell
                val y = (height - metrics.height) / 2 + metrics.ascent
                UISettings.setupAntialiasing(g)
                g.color = UIUtil.getContextHelpForeground()
                g.drawString(line.substring(0, leader), x, y)
                g.color = foreground
                g.drawString(text, x + leader * cell, y)
            }
        }, BorderLayout.WEST)
        add(ActionLink(entry.label + if (entry.directory) "/  ›" else "") {
            if (entry.directory) navigate(entry.path) else open(entry.path)
        }.apply {
            font = chartFont(font)
            inheritsPopupMenu = true
            name = "charts-lines-entry:${entry.path}"
            icon = if (entry.directory) AllIcons.Nodes.Folder else AllIcons.FileTypes.Any_type
            toolTipText = tooltip
            accessibleContext.accessibleName = "${entry.label}, ${number(entry.lines)} $lineLabel, ${if (entry.directory) "directory" else "file"}"
        }, BorderLayout.CENTER)
    }

    private fun number(value: Long) = String.format(Locale.US, "%,d", value)

    private fun chartFont(font: Font): Font = font.deriveFont(font.size2D * 1.1f)

    /** Keep the number column 20% larger than the chart's labels. */
    private fun numberFont(): Font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        .deriveFont(chartFont(JBFont.label()).size2D * 1.2f)
}
