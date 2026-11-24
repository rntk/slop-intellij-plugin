package dev.insight.review.ui

import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.ReviewLineChart
import dev.insight.review.scopedRadar
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

internal fun scopedChartPreview(
    chart: ReviewLineChart,
    entry: ReviewLineChart.Entry,
    kind: LineChartPreviewKind,
    width: Int,
    zoom: Double,
    logarithmic: Boolean,
    open: (String) -> Unit,
): JComponent {
    val title = when (kind) {
        LineChartPreviewKind.CIRCLES -> "Circles"
        LineChartPreviewKind.RADAR -> "Radar"
        else -> error("Expected a scoped chart preview")
    }
    return JPanel(BorderLayout()).apply {
        name = "charts-lines-${title.lowercase()}:${entry.path}"
        background = UIUtil.getListBackground()
        val location = entry.path.ifEmpty { entry.label }
        add(JLabel("$location · ${chart.metric.label}").apply {
            name = "charts-lines-chart-preview-title"
            accessibleContext.accessibleName = "$title for $location · ${chart.metric.label}"
            toolTipText = text
            font = JBFont.label().asBold()
            border = JBUI.Borders.empty(8)
        }, BorderLayout.NORTH)
        if (kind == LineChartPreviewKind.CIRCLES) {
            add(scopedCirclePreview(chart, entry, width, open), BorderLayout.CENTER)
        } else {
            val radar = scopedRadar(chart, entry)
            add(if (radar.chart.axes.isEmpty()) JLabel("Nothing to measure here") else
                RadarChartPanel(radar, width, zoom, logarithmic, open), BorderLayout.CENTER)
        }
    }
}

private fun scopedCirclePreview(
    chart: ReviewLineChart,
    scope: ReviewLineChart.Entry,
    width: Int,
    open: (String) -> Unit,
): JComponent = JPanel(BorderLayout()).apply {
    isOpaque = false
    fun show(path: String, focus: Boolean) {
        removeAll()
        val panel = CircleChartPanel(chart, path, scope.label, minOf(width, JBUI.scale(680)),
            navigate = { show(it, true) }, open = open, scope = scope)
        add(panel, BorderLayout.CENTER)
        revalidate()
        repaint()
        if (focus) SwingUtilities.invokeLater { panel.requestFocusInWindow() }
    }
    show(scope.path, false)
}
