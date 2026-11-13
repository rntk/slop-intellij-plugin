package dev.insight.review.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.review.ReviewSource
import java.awt.Dimension
import java.awt.Point
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.KeyStroke

class DocumentNavigationTest : BasePlatformTestCase() {
    fun testOverviewKeysMoveCanvas() {
        val fixture = Fixture()
        fixture.press("DOWN")
        val step = fixture.canvas.viewport.viewPosition.y
        assertTrue(step > 0)
        fixture.press("UP")
        assertEquals(Point(), fixture.canvas.viewport.viewPosition)
        fixture.press("PAGE_DOWN")
        assertTrue(fixture.canvas.viewport.viewPosition.y > step)
        fixture.press("PAGE_UP")
        assertEquals(Point(), fixture.canvas.viewport.viewPosition)
        fixture.press("END")
        assertEquals(1600, fixture.canvas.viewport.viewPosition.y)
        fixture.press("HOME")
        fixture.press("RIGHT")
        assertTrue(fixture.canvas.viewport.viewPosition.x > 0)
        fixture.press("LEFT")
        assertEquals(Point(), fixture.canvas.viewport.viewPosition)
        fixture.press("ctrl END")
        assertEquals(Point(1400, 1600), fixture.canvas.viewport.viewPosition)
        fixture.press("meta HOME")
        assertEquals(Point(), fixture.canvas.viewport.viewPosition)
    }

    fun testFocusedBodyOwnsBothAxesIncludingAtEdges() {
        val fixture = Fixture()
        fun press(stroke: String) {
            fixture.press(stroke, fixture.section)
            assertEquals("$stroke keeps canvas still", Point(), fixture.canvas.viewport.viewPosition)
        }
        press("DOWN")
        val step = fixture.body.viewport.viewPosition.y
        assertTrue(step > 0)
        press("UP")
        assertEquals(Point(), fixture.body.viewport.viewPosition)
        press("PAGE_DOWN")
        val page = fixture.body.viewport.viewPosition.y
        assertTrue(page > step)
        assertTrue(page < fixture.body.viewport.height)
        press("PAGE_UP")
        assertEquals(Point(), fixture.body.viewport.viewPosition)
        press("SPACE")
        assertEquals(page, fixture.body.viewport.viewPosition.y)
        press("shift SPACE")
        assertEquals(Point(), fixture.body.viewport.viewPosition)
        press("ctrl END")
        assertEquals(Point(700, 800), fixture.body.viewport.viewPosition)
        for (stroke in listOf("DOWN", "RIGHT", "PAGE_DOWN", "shift PAGE_DOWN", "END")) press(stroke)
        assertEquals(Point(700, 800), fixture.body.viewport.viewPosition)
        press("ctrl HOME")
        for (stroke in listOf("UP", "LEFT", "PAGE_UP", "shift PAGE_UP", "HOME")) press(stroke)
        assertEquals(Point(), fixture.body.viewport.viewPosition)
        press("shift DOWN")
        assertTrue(fixture.body.viewport.viewPosition.x > 0)
    }

    fun testUnscrollableBodyAxisMovesCanvasAndScrollableAxisStaysInBody() {
        val fixture = Fixture(bodyHeight = 200)
        fixture.press("DOWN", fixture.body.viewport.view)
        assertTrue(fixture.canvas.viewport.viewPosition.y > 0)
        val canvas = fixture.canvas.viewport.viewPosition
        fixture.press("RIGHT", fixture.body.viewport.view)
        assertTrue(fixture.body.viewport.viewPosition.x > 0)
        assertEquals(canvas, fixture.canvas.viewport.viewPosition)
    }

    fun testOuterPageHandlesOverflowWhenDocumentDoesNotScrollOnThatAxis() {
        val fixture = Fixture()
        fixture.canvas.viewport.viewSize = fixture.canvas.viewport.extentSize
        val outer = JScrollPane(fixture.canvas)
        outer.viewport.extentSize = Dimension(300, 200)
        outer.viewport.viewSize = Dimension(900, 700)
        fixture.press("END")
        assertEquals(500, outer.viewport.viewPosition.y)
        assertEquals(Point(), fixture.canvas.viewport.viewPosition)
    }

    fun testInitiallyFocusedEditorPageMovesDocument() {
        val fixture = Fixture()
        fixture.navigation.install(fixture.canvas)
        fixture.press("PAGE_DOWN", fixture.canvas)
        assertTrue(fixture.canvas.viewport.viewPosition.y > 0)
        assertEquals(Point(), fixture.body.viewport.viewPosition)
    }

    fun testTextInputsKeepTheirKeysAndNestedScrollBindingsAreInstalled() {
        val fixture = Fixture()
        val text = JTextField()
        fixture.section.add(text)
        fixture.press("DOWN", text)
        assertEquals(Point(), fixture.canvas.viewport.viewPosition)
        assertEquals(Point(), fixture.body.viewport.viewPosition)
        for (component in listOf(fixture.panel, fixture.body)) {
            val key = component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(KeyStroke.getKeyStroke("PAGE_DOWN"))
            assertTrue(key.toString().startsWith("document-navigation:"))
            assertNotNull(component.actionMap.get(key))
        }
    }

    private class Fixture(bodyHeight: Int = 1000) {
        val panel = object : DocumentColumnsPanel(1.0) {
            override fun openFile(file: ReviewSource) {}
            override fun showFile(file: ReviewSource) {}
            override fun isSelected(item: RailItem) = false
            override fun select(item: RailItem) {}
        }
        val section = JPanel().apply { name = "file:Example.kt" }
        val body = JScrollPane(JPanel()).apply {
            name = "document-source-scroll"
            viewport.extentSize = Dimension(300, 200)
            viewport.viewSize = Dimension(1000, bodyHeight)
            verticalScrollBar.unitIncrement = 20
            horizontalScrollBar.unitIncrement = 24
        }
        val canvas = JScrollPane(panel).apply {
            viewport.extentSize = Dimension(600, 400)
            viewport.viewSize = Dimension(2000, 2000)
        }
        val navigation: DocumentNavigation
        init {
            section.add(body)
            panel.add(section)
            navigation = DocumentNavigation(panel)
        }
        fun press(stroke: String, owner: java.awt.Component = panel) = navigation.navigate(owner, KeyStroke.getKeyStroke(stroke))
    }
}
