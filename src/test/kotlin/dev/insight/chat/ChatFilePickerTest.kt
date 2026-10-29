package dev.insight.chat

import com.intellij.openapi.application.runWriteAction
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.KeyStroke

class ChatFilePickerTest : BasePlatformTestCase() {
    private fun controls(container: Container): List<Component> = container.components.flatMap {
        listOf(it) + if (it is Container) controls(it) else emptyList()
    }

    private fun key(component: JComponent, stroke: String) {
        val name = component.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(stroke))
        component.actionMap.get(name).actionPerformed(ActionEvent(component, 0, name.toString()))
    }

    private fun awaitResults(list: JBList<*>, expected: String) {
        PlatformTestUtil.waitWithEventsDispatching("File search should find $expected", {
            (0 until list.model.size).any { list.model.getElementAt(it) == expected }
        }, 5 /* seconds */)
    }

    fun `test button and at sign insert paths in regular and chart chats`() {
        myFixture.addFileToProject("src/ChatPanel.kt", "class ChatPanel")
        myFixture.addFileToProject("test/ChatPanelTest.kt", "class ChatPanelTest")
        for (chartMode in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob())
            val panel = ChatPanel(project, ChatSession(project, scope, ProjectChatBackendFactory(project)), chartMode = chartMode)
            try {
                val picker = controls(panel).filterIsInstance<ChatFilePicker>().single()
                val query = controls(picker).filterIsInstance<JBTextField>().single()
                val list = controls(picker).filterIsInstance<JBList<*>>().single()
                panel.input.text = "Explain this: "
                panel.input.caretPosition = panel.input.document.length
                controls(panel).filterIsInstance<JButton>().single { it.text == "Add file…" }.doClick()
                assertTrue(picker.isVisible)
                query.text = "ChatPanel"
                awaitResults(list, "src/ChatPanel.kt")
                assertEquals("src/ChatPanel.kt", list.selectedValue)
                key(query, "DOWN")
                assertEquals("test/ChatPanelTest.kt", list.selectedValue)
                key(query, "UP")
                key(query, "ENTER")
                assertEquals("Explain this: src/ChatPanel.kt ", panel.input.text)
                assertFalse(picker.isVisible)

                // Type @ in the middle of a draft; Enter replaces the marker and preserves both sides.
                panel.input.text = "Compare  with this"
                panel.input.caretPosition = 8
                panel.input.replaceSelection("@")
                PlatformTestUtil.waitWithEventsDispatching("@ should open search", { picker.isVisible }, 5 /* seconds */)
                query.text = "PanelTest"
                awaitResults(list, "test/ChatPanelTest.kt")
                key(query, "ENTER")
                assertEquals("Compare test/ChatPanelTest.kt with this", panel.input.text)
                assertFalse(picker.isVisible)
            } finally {
                panel.dispose()
                scope.cancel()
            }
        }
    }

    fun `test escape close button selection and no results preserve the draft`() {
        myFixture.addFileToProject("src/One.kt", "class One")
        val scope = CoroutineScope(SupervisorJob())
        val panel = ChatPanel(project, ChatSession(project, scope, ProjectChatBackendFactory(project)))
        try {
            val picker = controls(panel).filterIsInstance<ChatFilePicker>().single()
            val query = controls(picker).filterIsInstance<JBTextField>().single()
            val list = controls(picker).filterIsInstance<JBList<*>>().single()
            panel.input.text = "Read placeholder please"
            panel.input.select(5, 16)
            picker.open()
            query.text = "One"
            awaitResults(list, "src/One.kt")
            key(query, "ESCAPE")
            assertFalse(picker.isVisible)
            assertEquals("Read placeholder please", panel.input.text)

            picker.open()
            query.text = "One"
            awaitResults(list, "src/One.kt")
            key(query, "ENTER")
            assertEquals("Read src/One.kt please", panel.input.text)

            picker.open()
            query.text = "nothing-matches-this"
            PlatformTestUtil.waitWithEventsDispatching("Search should report no results", { list.emptyText.text == "No matching project files" }, 5 /* seconds */)
            key(query, "ENTER")
            assertTrue(picker.isVisible)
            controls(picker).filterIsInstance<JButton>().single().doClick()
            assertFalse(picker.isVisible)
            assertEquals("Read src/One.kt please", panel.input.text)

            panel.input.text = "user"
            panel.input.caretPosition = 4
            panel.input.replaceSelection("@")
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertFalse("Email addresses should not open file search", picker.isVisible)

            panel.input.text = "Ask "
            panel.input.caretPosition = 4
            panel.input.replaceSelection("@")
            PlatformTestUtil.waitWithEventsDispatching("@ should open search", { picker.isVisible }, 5 /* seconds */)
            key(panel.input, "ESCAPE")
            assertFalse(picker.isVisible)
            assertEquals("Ask @", panel.input.text)
        } finally {
            panel.dispose()
            scope.cancel()
        }
    }

    fun `test path cache follows file creation rename and deletion`() {
        val file = myFixture.addFileToProject("src/Original.kt", "class Original").virtualFile
        val scope = CoroutineScope(SupervisorJob())
        val panel = ChatPanel(project, ChatSession(project, scope, ProjectChatBackendFactory(project)))
        try {
            val picker = controls(panel).filterIsInstance<ChatFilePicker>().single()
            val query = controls(picker).filterIsInstance<JBTextField>().single()
            val list = controls(picker).filterIsInstance<JBList<*>>().single()
            picker.open()
            query.text = "Original"
            awaitResults(list, "src/Original.kt")
            picker.close()
            runWriteAction { file.rename(this, "Renamed.kt") }
            myFixture.addFileToProject("src/Added.kt", "class Added")
            picker.open()
            query.text = "Renamed"
            awaitResults(list, "src/Renamed.kt")
            query.text = "Added"
            awaitResults(list, "src/Added.kt")
            picker.close()
            runWriteAction { file.delete(this) }
            picker.open()
            query.text = "Renamed"
            PlatformTestUtil.waitWithEventsDispatching("Deleted file should disappear", { list.emptyText.text == "No matching project files" }, 5 /* seconds */)
            assertEquals(0, list.model.size)
        } finally {
            panel.dispose()
            scope.cancel()
        }
    }
}
