package dev.insight.review.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBTabbedPane
import dev.insight.codereview.CodeFile
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewVocabulary
import dev.insight.review.buildSummaryTree
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JComboBox
import javax.swing.JLabel

class CircleChartViewTest : BasePlatformTestCase() {
    fun testCirclesDrillDownOnClickOpenFilesWalkUpAndShareTheDirectory() {
        val files = listOf("src/a.kt", "src/nested/b.kt", "readme.md").map { CodeFile(it, "/repo/$it", "one\ntwo\n") }
        val opened = mutableListOf<String>()
        val canvas = NoteCanvas({ opened += it }, {}, vocabulary = ReviewVocabulary.CODE).apply { setSize(1600, 900) }
        fun components() = descendants(canvas)
        fun selector() = components().filterIsInstance<JComboBox<*>>().single { it.name == "charts-type-selector" }
        fun panel() = components().filterIsInstance<CircleChartPanel>().single()
        fun click(path: String) {
            val circles = panel().canvas.apply { setSize(preferredSize); doLayout() }
            val point = checkNotNull(circles.centreOf(path)) { "no circle for $path" }
            // Folders with nested circles are clicked on their rim, files at their centre.
            val target = if (panel().packed!!.flatten().single { it.entry.path == path }.children.isEmpty()) point
                else circles.circleAtRim(path)
            circles.dispatchEvent(MouseEvent(circles, MouseEvent.MOUSE_CLICKED, 0, 0, target.x, target.y, 1, false, MouseEvent.BUTTON1))
        }
        try {
            canvas.showNotes(CanvasState(emptyList(), charts = ChartsCard(ReviewSummaries(buildSummaryTree(files)))))
            selector().selectedItem = "Circles"
            assertNotNull(components().filterIsInstance<JBTabbedPane>().singleOrNull { it.name == "charts-lines-metric-tabs" })
            assertEquals("", panel().directory.path)
            click("src")
            assertEquals("src", panel().directory.path)
            assertEquals("4 lines · 2 files", components().filterIsInstance<JLabel>().single { it.name == "charts-circles-total" }.text)
            click("src/nested")
            assertEquals("src/nested", panel().directory.path)
            click("src/nested/b.kt")
            assertEquals(listOf("src/nested/b.kt"), opened)
            val image = BufferedImage(900, 900, BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().also { panel().apply { setSize(900, 900); doLayout() }.paint(it) }.dispose()
            panel().up()
            assertEquals("src", panel().directory.path)
            selector().selectedItem = "File metrics"
            assertEquals("src", components().filterIsInstance<LineChartPanel>().single().directory.path)
            selector().selectedItem = "Circles"
            components().filterIsInstance<ActionLink>().single { it.name == "charts-circles-crumb:" }.doClick()
            assertEquals("", panel().directory.path)
        } finally { canvas.dispose() }
    }

    private fun CircleChartPanel.CircleCanvas.circleAtRim(path: String): java.awt.Point {
        val centre = centreOf(path)!!
        // Walk right from the centre to the last point still inside this folder but outside its children.
        var x = centre.x
        var last = centre
        while (x < width) {
            val hit = circleAt(x, centre.y) ?: break
            if (hit.entry.path == path) last = java.awt.Point(x, centre.y)
            x++
        }
        return last
    }

    private fun descendants(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::descendants) else emptyList()
}
