package dev.insight.review.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.codereview.CodeFile
import dev.insight.diff.ReviewFile
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewTopics
import dev.insight.review.ReviewVocabulary
import dev.insight.review.buildSummaryTree
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JMenuItem

class RadarViewTest : BasePlatformTestCase() {
    private val diffs = listOf("src/a.kt", "src/b.kt", "lib/c.kt", "docs/readme.md").map {
        ReviewFile(it, "/repo/$it", null, "modified", "@@ -1 +1,2 @@\n-old\n+new\n+more\n", "1")
    }
    private val sources = diffs.map { CodeFile(it.path, it.absolutePath, "one\ntwo\nthree\n") }

    fun testRadarShowsLineCountsByLevelInChartsAndTopics() {
        for (vocabulary in listOf(ReviewVocabulary.DIFF, ReviewVocabulary.CODE)) {
            val files = if (vocabulary == ReviewVocabulary.DIFF) diffs else sources
            for (topics in listOf(false, true)) {
                val summaries = ReviewSummaries(buildSummaryTree(files))
                val state = if (topics) CanvasState(emptyList(), topics = TopicsCard(ReviewTopics(files)
                    .assign("src/a.kt", "UI>Panels").assign("src/b.kt", "UI>Charts").assign("lib/c.kt", "Model>Counts")
                    .assign("docs/readme.md", "Docs>Guide"), summaries, canRun = true))
                    else CanvasState(emptyList(), charts = ChartsCard(summaries.tree))
                val opened = mutableListOf<String>()
                val canvas = NoteCanvas({ opened += it }, {}, project = project, vocabulary = vocabulary).apply { setSize(1600, 900) }
                fun radar() = descendants(canvas).filterIsInstance<RadarChartPanel>().single()
                try {
                    canvas.showNotes(state)
                    selectRadar(canvas)
                    if (topics) selectTopicsMode(descendants(canvas), "Radar")
                    val chart = radar().chart
                    assertEquals(3, chart.axes.size)
                    val initialPanel = radar().apply { setSize(800, 700) }
                    if (!topics) {
                        assertNull(chart.axes.first().path)
                        assertEquals("Context…", (initialPanel.contextMenuAt(Point(400, 120))?.getComponent(0) as? JMenuItem)?.text)
                    }
                    val status = descendants(canvas).filterIsInstance<JLabel>().single { it.name?.endsWith("radar-status") == true }
                    assertTrue(status.text, status.text.contains(if (vocabulary == ReviewVocabulary.DIFF) "+8 −4" else "12 lines"))
                    val levels = descendants(canvas).filterIsInstance<JComboBox<*>>().single {
                        it.name == (if (topics) "topic-level-selector" else "charts-radar-level-selector")
                    }
                    levels.selectedIndex = levels.itemCount - 1
                    assertEquals(4, radar().chart.axes.size)
                    assertTrue(radar().chart.axes.all { it.path != null })
                    descendants(canvas).filterIsInstance<JCheckBox>().single { it.name?.endsWith("radar-log") == true }.doClick()
                    val panel = radar().apply { setSize(800, 700) }
                    val image = BufferedImage(800, 700, BufferedImage.TYPE_INT_ARGB)
                    image.createGraphics().also { panel.paint(it) }.dispose()
                    // The first spoke points up from the center.
                    val top = MouseEvent(panel, MouseEvent.MOUSE_CLICKED, 0, 0, 400, 120, 1, false, MouseEvent.BUTTON1)
                    assertNotNull(panel.getToolTipText(top))
                    if (!topics) assertEquals("Context…", (panel.contextMenuAt(top.point)?.getComponent(0) as? JMenuItem)?.text)
                    panel.mouseListeners.forEach { it.mouseClicked(top) }
                    assertEquals(listOf(panel.chart.axes.first().path), opened)
                } finally { canvas.dispose() }
            }
        }
    }

    fun testSymbolSeriesAndLineSwitchInChartsAndTopics() {
        val symbols = dev.insight.review.RadarSymbols(mapOf(dev.insight.review.RadarSymbolKind.FUNCTION to 2,
            dev.insight.review.RadarSymbolKind.COMMENT to 1))
        val files = sources.mapIndexed { index, file -> CodeFile(file.path, file.absolutePath, file.text,
            symbolCounts = symbols.takeIf { index < 3 }) }
        for (topics in listOf(false, true)) {
            val summaries = ReviewSummaries(buildSummaryTree(files))
            val state = if (topics) CanvasState(emptyList(), topics = TopicsCard(ReviewTopics(files), summaries, canRun = true))
                else CanvasState(emptyList(), charts = ChartsCard(summaries.tree))
            val canvas = NoteCanvas({}, {}, vocabulary = ReviewVocabulary.CODE).apply { setSize(1600, 900) }
            fun radar() = descendants(canvas).filterIsInstance<RadarChartPanel>().single()
            fun switch() = descendants(canvas).filterIsInstance<JCheckBox>().single { it.name?.endsWith("radar-symbols") == true }
            fun statusLabel() = descendants(canvas).filterIsInstance<JLabel>().single { it.name?.endsWith("radar-status") == true }
            try {
                canvas.showNotes(state)
                selectRadar(canvas)
                if (topics) selectTopicsMode(descendants(canvas), "Radar")
                assertTrue(switch().isSelected)
                assertTrue(dev.insight.review.RadarSeries.FUNCTIONS in radar().chart.series)
                val status = statusLabel().text
                assertTrue(status, status.contains("6 symbols · 3 comments"))
                assertTrue(status, status.contains("3 of 4 files"))
                val panel = radar().apply { setSize(800, 700) }
                val tooltip = panel.getToolTipText(MouseEvent(panel, MouseEvent.MOUSE_MOVED, 0, 0, 400, 120, 0, false))!!
                assertTrue(tooltip, tooltip.contains("Functions:"))
                val commentsPoint = panel.legendPoint(dev.insight.review.RadarSeries.COMMENTS)!!
                val legendTooltip = panel.getToolTipText(MouseEvent(panel, MouseEvent.MOUSE_MOVED, 0, 0, commentsPoint.x, commentsPoint.y, 0, false))!!
                assertTrue(legendTooltip, legendTooltip.contains("Comments") && legendTooltip.contains("3 total"))
                panel.mouseListeners.forEach {
                    it.mouseClicked(MouseEvent(panel, MouseEvent.MOUSE_CLICKED, 0, 0, commentsPoint.x, commentsPoint.y, 1, false, MouseEvent.BUTTON1))
                }
                assertEquals(dev.insight.review.RadarSeries.COMMENTS, panel.selectedSeries)
                assertEquals(listOf(dev.insight.review.RadarSeries.COMMENTS), panel.shownSeries)
                assertTrue(statusLabel().text, statusLabel().text.contains("3 comments"))
                val image = BufferedImage(800, 700, BufferedImage.TYPE_INT_ARGB)
                image.createGraphics().also { panel.paint(it) }.dispose()
                val functionsPoint = panel.legendPoint(dev.insight.review.RadarSeries.FUNCTIONS)!!
                panel.mouseListeners.forEach {
                    it.mouseClicked(MouseEvent(panel, MouseEvent.MOUSE_CLICKED, 0, 0, functionsPoint.x, functionsPoint.y, 1, false, MouseEvent.BUTTON1))
                }
                assertEquals(dev.insight.review.RadarSeries.FUNCTIONS, panel.selectedSeries)
                assertEquals(listOf(dev.insight.review.RadarSeries.FUNCTIONS), panel.shownSeries)
                assertTrue(statusLabel().text, statusLabel().text.contains("6 functions"))
                val selectedFunctionsPoint = panel.legendPoint(dev.insight.review.RadarSeries.FUNCTIONS)!!
                panel.mouseListeners.forEach {
                    it.mouseClicked(MouseEvent(panel, MouseEvent.MOUSE_CLICKED, 0, 0, selectedFunctionsPoint.x, selectedFunctionsPoint.y, 1, false, MouseEvent.BUTTON1))
                }
                assertNull(panel.selectedSeries)
                assertEquals(panel.chart.series, panel.shownSeries)
                assertTrue(statusLabel().text, statusLabel().text.contains("6 symbols · 3 comments"))
                switch().doClick()
                assertEquals(listOf(dev.insight.review.RadarSeries.SOURCE), radar().chart.series)
                switch().doClick()
                assertTrue(dev.insight.review.RadarSeries.COMMENTS in radar().chart.series)
            } finally { canvas.dispose() }
        }
    }

    private fun selectRadar(canvas: NoteCanvas) {
        descendants(canvas).filterIsInstance<JComboBox<*>>().firstOrNull { it.name == "charts-type-selector" }?.selectedItem = "Radar"
    }

    private fun descendants(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::descendants) else emptyList()
}
