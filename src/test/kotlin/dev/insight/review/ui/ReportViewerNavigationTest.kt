package dev.insight.review.ui

import com.intellij.openapi.ui.ComboBox
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.KeyStroke

class ReportViewerNavigationTest : BasePlatformTestCase() {
    fun testReaderKeysMoveBothAxesAndKeepOuterScrollStillAtEdges() {
        val fixture = Fixture()
        fun press(stroke: String, owner: java.awt.Component = fixture.pane) {
            fixture.navigation.navigate(owner, KeyStroke.getKeyStroke(stroke))
            assertEquals("$stroke must keep outer scroll still", Point(), fixture.outer.viewport.viewPosition)
        }

        press("DOWN")
        val step = fixture.body.verticalScrollBar.value
        assertTrue(step > 0)
        press("KP_DOWN")
        assertTrue(fixture.body.verticalScrollBar.value > step)
        press("KP_UP")
        assertEquals(step, fixture.body.verticalScrollBar.value)
        press("UP")
        assertEquals(0, fixture.body.verticalScrollBar.value)

        press("PAGE_DOWN")
        val page = fixture.body.verticalScrollBar.value
        assertTrue(page > step)
        assertTrue(page < fixture.body.viewport.height)
        press("PAGE_UP")
        assertEquals(0, fixture.body.verticalScrollBar.value)
        press("SPACE")
        assertEquals(page, fixture.body.verticalScrollBar.value)
        press("shift SPACE")
        assertEquals(0, fixture.body.verticalScrollBar.value)

        press("END")
        val bottom = fixture.body.verticalScrollBar.value
        assertEquals(800, bottom)
        for (stroke in listOf("DOWN", "SPACE", "END")) press(stroke)
        assertEquals(bottom, fixture.body.verticalScrollBar.value)
        press("HOME")
        for (stroke in listOf("UP", "PAGE_UP", "HOME")) press(stroke)
        assertEquals(0, fixture.body.verticalScrollBar.value)

        press("RIGHT")
        val horizontal = fixture.body.horizontalScrollBar.value
        assertTrue(horizontal > 0)
        press("KP_RIGHT")
        assertTrue(fixture.body.horizontalScrollBar.value > horizontal)
        press("KP_LEFT")
        assertEquals(horizontal, fixture.body.horizontalScrollBar.value)
        press("LEFT")
        assertEquals(0, fixture.body.horizontalScrollBar.value)

        press("shift RIGHT")
        assertEquals(horizontal, fixture.body.horizontalScrollBar.value)
        press("shift LEFT")
        assertEquals(0, fixture.body.horizontalScrollBar.value)
        press("shift DOWN")
        assertEquals(horizontal, fixture.body.horizontalScrollBar.value)
        press("shift UP")
        assertEquals(0, fixture.body.horizontalScrollBar.value)

        press("shift PAGE_DOWN")
        val hPage = fixture.body.horizontalScrollBar.value
        assertTrue(hPage > horizontal)
        assertTrue(hPage < fixture.body.viewport.width)
        press("shift PAGE_UP")
        assertEquals(0, fixture.body.horizontalScrollBar.value)

        press("ctrl END")
        assertEquals(Point(700, 800), fixture.body.viewport.viewPosition)
        press("meta HOME")
        assertEquals(Point(), fixture.body.viewport.viewPosition)
    }

    fun testUnscrollableAxisFallsBackToOuterScrollPane() {
        val verticalOnly = Fixture(bodyWidth = 300, bodyHeight = 1000)
        verticalOnly.navigation.navigate(verticalOnly.pane, KeyStroke.getKeyStroke("RIGHT"))
        assertTrue(verticalOnly.outer.viewport.viewPosition.x > 0)
        assertEquals(0, verticalOnly.body.horizontalScrollBar.value)
        val outerX = verticalOnly.outer.viewport.viewPosition.x
        verticalOnly.navigation.navigate(verticalOnly.pane, KeyStroke.getKeyStroke("shift PAGE_DOWN"))
        assertTrue(verticalOnly.outer.viewport.viewPosition.x > outerX)
        verticalOnly.navigation.navigate(verticalOnly.pane, KeyStroke.getKeyStroke("DOWN"))
        assertTrue(verticalOnly.body.verticalScrollBar.value > 0)
        assertEquals(0, verticalOnly.outer.viewport.viewPosition.y)

        val horizontalOnly = Fixture(bodyWidth = 1000, bodyHeight = 200)
        horizontalOnly.navigation.navigate(horizontalOnly.pane, KeyStroke.getKeyStroke("DOWN"))
        assertTrue(horizontalOnly.outer.viewport.viewPosition.y > 0)
        assertEquals(0, horizontalOnly.body.verticalScrollBar.value)
        val outerY = horizontalOnly.outer.viewport.viewPosition.y
        horizontalOnly.navigation.navigate(horizontalOnly.pane, KeyStroke.getKeyStroke("PAGE_DOWN"))
        assertTrue(horizontalOnly.outer.viewport.viewPosition.y > outerY)
        horizontalOnly.navigation.navigate(horizontalOnly.pane, KeyStroke.getKeyStroke("RIGHT"))
        assertTrue(horizontalOnly.body.horizontalScrollBar.value > 0)
        assertEquals(0, horizontalOnly.outer.viewport.viewPosition.x)
    }

    fun testInstallOnHostSupportsHostFocusAndReinstallReplacesShortcuts() {
        val fixture = Fixture()
        val host = JPanel(BorderLayout())
        host.add(fixture.outer, BorderLayout.CENTER)

        fixture.navigation.install(fixture.outer, host)
        assertNull(fixture.card.getClientProperty("report.viewer.navigation.actions"))
        val firstActions = host.getClientProperty("report.viewer.navigation.actions") as? List<*>
        assertNotNull(firstActions)
        assertTrue(firstActions!!.isNotEmpty())

        fixture.navigation.navigate(host, KeyStroke.getKeyStroke("PAGE_DOWN"))
        assertTrue(fixture.body.verticalScrollBar.value > 0)

        val rebuilt = Fixture()
        fixture.navigation.dispose()
        rebuilt.navigation.install(fixture.outer, host)
        val secondActions = host.getClientProperty("report.viewer.navigation.actions") as? List<*>
        assertNotNull(secondActions)
        assertEquals(firstActions.size, secondActions!!.size)
        assertNotSame(firstActions.first(), secondActions.first())

        val previousY = fixture.body.verticalScrollBar.value
        rebuilt.navigation.navigate(host, KeyStroke.getKeyStroke("DOWN"))
        assertTrue(rebuilt.body.verticalScrollBar.value > 0)
        assertEquals(previousY, fixture.body.verticalScrollBar.value)
        rebuilt.navigation.dispose()
        assertNull(host.getClientProperty("report.viewer.navigation.actions"))
    }

    fun testInteractiveControlsAreRejectedAndEditorPaneHasFocusedBindings() {
        val fixture = Fixture()
        val filterField = JBTextField()
        val list = JBList(arrayOf("a.md", "b.md"))
        val combo = ComboBox(arrayOf("a.md", "b.md"))
        val editableCombo = ComboBox(arrayOf("a.md", "b.md")).apply { isEditable = true }
        val comboEditor = editableCombo.editor.editorComponent
        val button = JButton("Open")
        val checkBox = JCheckBox("Raw text")
        val toolbar = JPanel().apply {
            add(filterField)
            add(list)
            add(combo)
            add(editableCombo)
            add(button)
            add(checkBox)
        }
        fixture.card.add(toolbar, BorderLayout.NORTH)

        assertTrue(fixture.navigation.accepts(fixture.pane))
        for (control in listOf(filterField, list, combo, editableCombo, comboEditor, button, checkBox)) {
            assertFalse("${control.javaClass.simpleName} must be rejected", fixture.navigation.accepts(control))
            fixture.navigation.navigate(control, KeyStroke.getKeyStroke("DOWN"))
            fixture.navigation.navigate(control, KeyStroke.getKeyStroke("SPACE"))
        }
        assertEquals(Point(), fixture.body.viewport.viewPosition)
        assertEquals(Point(), fixture.outer.viewport.viewPosition)

        val altShiftK = KeyStroke.getKeyStroke(KeyEvent.VK_K, KeyEvent.ALT_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK)
        fixture.pane.getInputMap(JComponent.WHEN_FOCUSED).put(altShiftK, "report-context-picker")
        assertEquals("report-context-picker", fixture.pane.getInputMap(JComponent.WHEN_FOCUSED).get(altShiftK))

        val strokes = listOf(
            "UP", "KP_UP", "DOWN", "KP_DOWN",
            "LEFT", "KP_LEFT", "RIGHT", "KP_RIGHT",
            "shift UP", "shift DOWN", "shift LEFT", "shift RIGHT",
            "PAGE_UP", "PAGE_DOWN", "SPACE", "shift SPACE",
            "shift PAGE_UP", "shift PAGE_DOWN",
            "HOME", "END", "ctrl HOME", "ctrl END", "meta HOME", "meta END",
        )
        for (stroke in strokes) {
            val ks = KeyStroke.getKeyStroke(stroke)
            val key = fixture.pane.getInputMap(JComponent.WHEN_FOCUSED).get(ks)
            assertNotNull("Expected WHEN_FOCUSED binding on JEditorPane for $stroke", key)
            assertTrue(key.toString().startsWith("report-viewer-navigation:"))
            assertNotNull(fixture.pane.actionMap.get(key))
        }
    }

    private class Fixture(bodyWidth: Int = 1000, bodyHeight: Int = 1000) {
        val card = JPanel(BorderLayout())
        val pane = JEditorPane().apply {
            name = REPORT_VIEWER_PANE_NAME
            isEditable = false
        }
        val body = JScrollPane(pane).apply {
            name = REPORT_VIEWER_SCROLL_NAME
            viewport.extentSize = Dimension(300, 200)
            viewport.viewSize = Dimension(bodyWidth, bodyHeight)
            verticalScrollBar.unitIncrement = 16
            horizontalScrollBar.unitIncrement = 16
        }
        val outer = JScrollPane(card).apply {
            viewport.extentSize = Dimension(600, 400)
            viewport.viewSize = Dimension(2000, 2000)
        }
        val navigation: ReportViewerNavigation

        init {
            card.add(body, BorderLayout.CENTER)
            navigation = ReportViewerNavigation(card, body, pane)
            navigation.install(outer)
        }
    }
}
