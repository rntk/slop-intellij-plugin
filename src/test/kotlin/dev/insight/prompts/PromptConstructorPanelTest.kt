package dev.insight.prompts

import com.intellij.openapi.components.service
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField

class PromptConstructorPanelTest : BasePlatformTestCase() {
    fun `test library components insert at cursor and draft survives reopening`() {
        val directory = FileUtil.createTempDirectory("prompt-library", "test", true)
        val state = project.service<PromptConstructorState>().state
        state.libraryPath = directory.path
        state.draft = "Before After"
        PromptLibrary(directory.toPath()).save(PromptKind.APPENDIX, "snippet", "Middle")
        val panel = PromptConstructorPanel(project)
        try {
            val refresh = descendants(panel).filterIsInstance<JButton>().single { it.text == "Refresh" }
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!refresh.isEnabled && System.nanoTime() < deadline) {
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                Thread.sleep(10)
            }
            assertTrue("Library should load", refresh.isEnabled)
            val list = descendants(panel).filterIsInstance<JList<*>>().single()
            assertEquals(1, list.model.size)
            list.selectedIndex = 0
            val editor = descendants(panel).filterIsInstance<JTextArea>().single { it.isEditable }
            editor.caretPosition = 7
            descendants(panel).filterIsInstance<JButton>().single { it.text == "Insert at cursor" }.doClick()
            assertEquals("Before MiddleAfter", editor.text)
            assertEquals(editor.text, state.draft)
            descendants(panel).filterIsInstance<JButton>().single { it.text == "Append" }.doClick()
            assertEquals("Before MiddleAfter\n\n---\n\nMiddle", editor.text)
            val search = descendants(panel).filterIsInstance<JTextField>().single()
            search.text = "absent"
            assertEquals(0, list.model.size)
            search.text = "snippet"
            assertEquals(1, list.model.size)
            descendants(panel).filterIsInstance<JTabbedPane>().single().selectedIndex = 1
        } finally { panel.dispose() }
        val reopened = PromptConstructorPanel(project)
        try {
            assertEquals("Before MiddleAfter\n\n---\n\nMiddle", descendants(reopened).filterIsInstance<JTextArea>().single { it.isEditable }.text)
        } finally { reopened.dispose(); FileUtil.delete(directory) }
    }

    private fun descendants(container: Container): List<Component> = container.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
}
