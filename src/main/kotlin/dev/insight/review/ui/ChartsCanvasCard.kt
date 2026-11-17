package dev.insight.review.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.context.ReviewDestination
import dev.insight.review.RadarChart
import dev.insight.review.RadarSeries
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.ReviewVocabulary
import dev.insight.review.ReviewSource
import dev.insight.review.SummaryNode
import dev.insight.review.summaryRadar
import dev.insight.ui.zoomScaled
import dev.insight.ui.zoomed
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ScrollPaneConstants

/** The Charts page's content: the scope's folder and file tree, which the charts measure. */
internal data class ChartsCard(val tree: ReviewSummaryTree)

/** Chart selection and navigation, kept while the page is rebuilt. */
internal class ChartsViewState {
    /** The radar's folder level, 1 being the root's entries; null picks the first level with a few entries. */
    var radarLevel: Int? = null
    var chart: String? = null
    var linesPath: String? = null
    var linesSeries = RadarSeries.ADDED
    var radarSymbols = true
    /** Whether the radar spaces its rings logarithmically. */
    var radarLog = false
    var radarSeries: RadarSeries? = null
}

/**
 * The Charts page: charts that compare how much the folders and files of the review hold. Chart and
 * option changes rebuild only this card, through [refresh].
 */
internal class ChartsCanvasCard(
    private val vocabulary: ReviewVocabulary,
    private val open: (String) -> Unit,
    private val refresh: () -> Unit,
    private val close: (() -> Unit)? = null,
    private val project: Project? = null,
    private val openOnCanvas: ((SummaryNode) -> Unit)? = null,
    private val openOnWall: ((List<ReviewSource>, String) -> Unit)? = null,
    private val reviewDestinations: () -> Map<ReviewDestination, () -> Unit> = { emptyMap() },
) {
    val view = ChartsViewState()
    private val zoom = 1.0

    fun card(content: ChartsCard): JPanel {
        val lines = view.chart != "Radar"
        val diff = vocabulary == ReviewVocabulary.DIFF
        val linesTitle = if (diff) "Changed lines" else "Lines of code"
        val chart by lazy { summaryRadar(content.tree, view.radarLevel, view.radarSymbols) }
        val width = scaled(RadarChartPanel.DEFAULT_WIDTH)
        val header = JPanel(BorderLayout(scaled(8), 0)).apply {
            name = "charts-controls"
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(JLabel("Charts").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                toolTipText = if (diff) "Explore added or removed lines by directory, or compare changes on the radar"
                    else "Compare folder and file sizes; explore source lines or the radar"
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(4), 0)).apply {
                isOpaque = false
                add(JComboBox(arrayOf(linesTitle, "Radar")).apply {
                    name = "charts-type-selector"
                    font = scaledFont(JBFont.small())
                    selectedItem = if (lines) linesTitle else "Radar"
                    addActionListener { view.chart = selectedItem as String; refresh() }
                })
                if (lines && diff) add(JComboBox(arrayOf(RadarSeries.ADDED.label, RadarSeries.REMOVED.label)).apply {
                    name = "charts-lines-series-selector"
                    font = scaledFont(JBFont.small())
                    selectedItem = view.linesSeries.label
                    toolTipText = "Count added or removed lines in the selected diff"
                    addActionListener {
                        view.linesSeries = if (selectedIndex == 0) RadarSeries.ADDED else RadarSeries.REMOVED
                        refresh()
                    }
                })
                if (!lines && chart.levels > 1) add(radarLevels(chart))
                if (!lines && vocabulary == ReviewVocabulary.CODE) add(checkBox("Symbols", "charts-radar-symbols", view.radarSymbols,
                    "Compare PSI declarations (including constructors) and comment blocks; uncheck to compare lines") {
                    view.radarSymbols = it
                })
                if (!lines) add(checkBox("Log scale", "charts-radar-log", view.radarLog,
                    "Space the rings logarithmically, so small folders stay visible beside a large one") { view.radarLog = it })
                close?.let { remove ->
                    add(InplaceButton(IconButton("Remove charts", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { remove() }
                        .apply { cursor = Cursor.getDefaultCursor() })
                }
            }, BorderLayout.WEST)
        }
        val inner = width - scaled(2 * PADDING + 4) - scaled(16)
        val body = JPanel(VerticalLayout(scaled(8))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            if (lines) add(LineChartPanel(content.tree, view.linesPath, vocabulary.rootName, { path ->
                view.linesPath = path
                refresh()
            }, open, if (diff) view.linesSeries else RadarSeries.SOURCE,
                contextMenu = project?.let { { path -> contextMenu(content.tree, path) } }))
            else {
                val status = JLabel(radarStatus(chart, view.radarSeries)).apply {
                    name = "charts-radar-status"
                    font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                }
                add(status)
                add(RadarChartPanel(chart, inner, zoom, view.radarLog, open, view.radarSeries) { selected ->
                    view.radarSeries = selected
                    status.text = radarStatus(chart, selected)
                })
            }
        }
        val bodyScroll = JBScrollPane(body).apply {
            border = null; isOpaque = false; viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            preferredSize = Dimension(width, minOf(body.preferredSize.height, scaled(MAX_HEIGHT)))
            verticalScrollBar.unitIncrement = scaled(16)
        }
        return JPanel(BorderLayout()).apply {
            background = UIUtil.getListBackground()
            border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(1))
            add(header, BorderLayout.NORTH)
            add(bodyScroll, BorderLayout.CENTER)
        }
    }

    private fun contextMenu(tree: ReviewSummaryTree, path: String): JPopupMenu = JPopupMenu().apply {
        val node = tree.nodes.firstOrNull { it.path == path } ?: tree.root
        addSummaryContextItem(this, project, node, tree.nodes.mapNotNull { it.file },
            openOnCanvas, openOnWall, reviewDestinations())
    }

    private fun checkBox(text: String, name: String, selected: Boolean, tooltip: String, set: (Boolean) -> Unit) =
        JCheckBox(text, selected).apply {
            this.name = name
            isOpaque = false
            font = scaledFont(JBFont.small())
            cursor = Cursor.getDefaultCursor()
            toolTipText = tooltip
            addActionListener { set(isSelected); refresh() }
        }

    /** The radar's folder level: 1 compares the root's entries, the last compares every file. */
    private fun radarLevels(chart: RadarChart): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, scaled(4), 0)).apply {
        isOpaque = false
        val selector = JComboBox((1..chart.levels).map { if (it == chart.levels) "Files" else "$it" }.toTypedArray()).apply {
            name = "charts-radar-level-selector"
            font = scaledFont(JBFont.small())
            selectedIndex = chart.level - 1
            toolTipText = "Compare the folders at this level (the root's entries are level 1); Files compares every file"
            addActionListener {
                view.radarLevel = selectedIndex + 1
                refresh()
            }
        }
        add(JLabel("Level").apply {
            font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.emptyRight(4)
            labelFor = selector
        })
        add(selector)
    }

    private fun scaled(value: Int) = zoomScaled(value, zoom)
    private fun scaledFont(font: java.awt.Font) = font.zoomed(zoom)

    private companion object {
        const val PADDING = 12
        const val MAX_HEIGHT = 820
    }
}
