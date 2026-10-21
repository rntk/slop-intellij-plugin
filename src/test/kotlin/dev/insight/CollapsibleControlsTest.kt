package dev.insight

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.ui.CollapsibleControls
import dev.insight.ui.PanelCommand
import javax.swing.JPanel
import javax.swing.JTextField

class CollapsibleControlsTest : BasePlatformTestCase() {
    private fun toolbar(controls: CollapsibleControls) = controls.components.filterIsInstance<ActionToolbar>().single()

    fun `test collapsed settings retain values and persisted visibility can be revealed`() {
        val key = "insight.test.controls"
        val preferences = PropertiesComponent.getInstance(project)
        preferences.unsetValue("$key.search")
        try {
            val target = JPanel()
            val controls = CollapsibleControls(project, key, target)
            val search = JTextField("retained query")
            val section = controls.section("search", "Search", search)
            assertFalse(search.parent.isVisible)
            assertSame(target, toolbar(controls).targetComponent)
            section.reveal()
            assertTrue(search.parent.isVisible)
            assertEquals("retained query", search.text)

            val restored = CollapsibleControls(project, key, target)
            val restoredSearch = JTextField("another query")
            restored.section("search", "Search", restoredSearch)
            assertTrue(restoredSearch.parent.isVisible)
            val toggle = toolbar(restored).actionGroup.getChildren(null).single() as ToggleAction
            toggle.setSelected(TestActionEvent.createTestEvent(toggle), false)
            assertFalse(restoredSearch.parent.isVisible)
            assertEquals("another query", restoredSearch.text)
            assertFalse(preferences.getBoolean("$key.search", false))
        } finally { preferences.unsetValue("$key.search") }
    }

    fun `test commands update existing toolbar and overflow presentations after background work`() {
        var invocations = 0
        val action = PanelCommand("Generate") { invocations++ }
        val toolbarEvent = TestActionEvent.createTestEvent(action)
        val overflowEvent = TestActionEvent.createTestEvent(action)
        action.text = "Cancel"
        action.isEnabled = false
        for (event in listOf(toolbarEvent, overflowEvent)) {
            action.update(event)
            assertEquals("Cancel", event.presentation.text)
            assertFalse(event.presentation.isEnabled)
        }
        action.actionPerformed(toolbarEvent)
        assertEquals(0, invocations)
        action.isEnabled = true
        action.update(overflowEvent)
        assertTrue(overflowEvent.presentation.isEnabled)
        action.actionPerformed(overflowEvent)
        assertEquals(1, invocations)
    }
}
