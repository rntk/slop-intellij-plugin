package dev.insight.review.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import dev.insight.codereview.CodeFile
import dev.insight.review.ReviewVocabulary
import dev.insight.review.RadarSeries
import dev.insight.review.buildSummaryTree
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JComponent
import javax.swing.JMenuItem

class LineChartViewTest : BasePlatformTestCase() {
    fun testCodeAndDiffRowsCountsAndBreadcrumbsOfferContextPicker() {
        for (vocabulary in listOf(ReviewVocabulary.CODE, ReviewVocabulary.DIFF)) {
            val files = listOf("src/a.kt", "src/nested/b.kt", "readme.md").map { path ->
                if (vocabulary == ReviewVocabulary.CODE) CodeFile(path, "/repo/$path", "captured source")
                else dev.insight.diff.ReviewFile(path, "/repo/$path", null, "modified",
                    "@@ -1 +1 @@\n-old\n+new", path)
            }
            val canvas = NoteCanvas({}, {}, project = project, vocabulary = vocabulary)
            fun component(name: String) = descendants(canvas).filterIsInstance<JComponent>().single { it.name == name }
            fun assertContextMenu(component: JComponent) {
                val menu = component.componentPopupMenu
                assertNotNull(menu)
                assertEquals(listOf("Context…"), menu.components.filterIsInstance<JMenuItem>().map { it.text })
            }
            try {
                canvas.showNotes(CanvasState(emptyList(), charts = ChartsCard(buildSummaryTree(files))))
                assertContextMenu(component("charts-lines-entry:readme.md"))
                assertContextMenu(component("charts-lines-count:readme.md"))
                assertContextMenu(component("charts-lines-entry:src"))
                assertContextMenu(component("charts-lines-crumb:"))
                (component("charts-lines-entry:src") as ActionLink).doClick()
                assertContextMenu(component("charts-lines-entry:src/a.kt"))
                assertContextMenu(component("charts-lines-crumb:"))
                assertContextMenu(component("charts-lines-crumb:src"))
            } finally { canvas.dispose() }
        }
    }

    fun testDrillDownBreadcrumbsFileOpeningAndChartSwitchRetainDirectory() {
        val files = listOf("src/a.kt", "src/nested/b.kt", "readme.md").map { CodeFile(it, "/repo/$it", "one\ntwo\n") }
        val opened = mutableListOf<String>()
        val canvas = NoteCanvas({ opened += it }, {}, vocabulary = ReviewVocabulary.CODE).apply { setSize(1600, 900) }
        fun components() = descendants(canvas)
        fun entries() = components().filterIsInstance<ActionLink>().filter { it.name?.startsWith("charts-lines-entry:") == true }
        fun click(name: String) = components().filterIsInstance<ActionLink>().single { it.name == name }.doClick()
        fun selector() = components().filterIsInstance<JComboBox<*>>().single { it.name == "charts-type-selector" }
        try {
            canvas.showNotes(CanvasState(emptyList(), charts = ChartsCard(buildSummaryTree(files))))
            assertEquals(listOf("charts-lines-entry:readme.md", "charts-lines-entry:src"), entries().map { it.name })
            click("charts-lines-entry:src")
            assertEquals(listOf("charts-lines-entry:src/a.kt", "charts-lines-entry:src/nested"), entries().map { it.name })
            assertEquals("4 lines · 2 files", components().filterIsInstance<JLabel>().single { it.name == "charts-lines-total" }.text)
            click("charts-lines-entry:src/nested")
            click("charts-lines-entry:src/nested/b.kt")
            assertEquals(listOf("src/nested/b.kt"), opened)
            selector().selectedItem = "Radar"
            assertEquals(1, components().filterIsInstance<RadarChartPanel>().size)
            selector().selectedItem = "Lines of code"
            assertEquals("src/nested", components().filterIsInstance<LineChartPanel>().single().directory.path)
            click("charts-lines-crumb:src")
            val panel = components().filterIsInstance<LineChartPanel>().single().apply { setSize(900, 400); doLayout() }
            val image = BufferedImage(900, 400, BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().also { panel.paint(it) }.dispose()
            click("charts-lines-crumb:")
            assertEquals(2, entries().size)
        } finally { canvas.dispose() }
    }

    fun testDiffMetricSwitcherUpdatesCountsColorsAndRetainsNavigation() {
        val files = listOf("src/a.kt", "readme.md").map {
            dev.insight.diff.ReviewFile(it, "/repo/$it", null, "modified", "@@ -1 +1,2 @@\n-old\n+new\n+extra", it)
        }
        val opened = mutableListOf<String>()
        val canvas = NoteCanvas({ opened += it }, {}, vocabulary = ReviewVocabulary.DIFF)
        fun components() = descendants(canvas)
        fun selector(name: String) = components().filterIsInstance<JComboBox<*>>().single { it.name == name }
        fun total() = components().filterIsInstance<JLabel>().single { it.name == "charts-lines-total" }
        fun assertColor(series: RadarSeries) {
            assertEquals(RadarChartPanel.color(series), total().foreground)
            assertTrue(components().filter { it.name?.startsWith("charts-lines-count:") == true }.all {
                it.foreground == RadarChartPanel.color(series)
            })
        }
        try {
            canvas.showNotes(CanvasState(emptyList(), charts = ChartsCard(buildSummaryTree(files))))
            assertEquals("4 added lines · 2 files", total().text)
            assertColor(RadarSeries.ADDED)
            components().filterIsInstance<ActionLink>().single { it.name == "charts-lines-entry:src" }.doClick()
            selector("charts-lines-series-selector").selectedItem = "Removed lines"
            assertEquals("1 removed lines · 1 files", total().text)
            assertColor(RadarSeries.REMOVED)
            components().filterIsInstance<ActionLink>().single { it.name == "charts-lines-entry:src/a.kt" }.doClick()
            assertEquals(listOf("src/a.kt"), opened)
            selector("charts-type-selector").selectedItem = "Radar"
            selector("charts-type-selector").selectedItem = "Changed lines"
            assertEquals("src", components().filterIsInstance<LineChartPanel>().single().directory.path)
            assertEquals("Removed lines", selector("charts-lines-series-selector").selectedItem)
            assertColor(RadarSeries.REMOVED)
        } finally { canvas.dispose() }
    }

    private fun descendants(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::descendants) else emptyList()
}
