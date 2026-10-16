package dev.insight

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.callgraph.*
import dev.insight.enrichment.*
import java.awt.Container
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.testFramework.TestActionEvent
import dev.insight.ui.PanelCommand
import javax.swing.JComponent

class CanvasPanelTest : BasePlatformTestCase() {
    private fun controls(container: Container): List<JComponent> = container.components.flatMap {
        listOfNotNull(it as? JComponent) + if (it is Container) controls(it) else emptyList()
    }
    private fun command(panel: CanvasPanel, text: String) = controls(panel)
        .mapNotNull { it.getClientProperty(ActionToolbar.ACTION_TOOLBAR_PROPERTY_KEY) as? ActionToolbar }
        .flatMap { it.actionGroup.getChildren(null).toList() }
        .filterIsInstance<PanelCommand>().single { it.text == text }

    private fun perform(panel: CanvasPanel, text: String) {
        val action = command(panel, text)
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertTrue(event.presentation.isEnabled)
        action.actionPerformed(event)
    }
    private fun result() = CallGraphResult(CallGraph(listOf(
        CallGraphNode(0, "handle", "Service", "Service.kt:12", 0, true, filePath = "/src/Service.kt"),
    ), emptySet(), false), listOf(null))

    fun `test independent reading state clears on rebuild and uses shared actions`() {
        val actions = mutableListOf<String>()
        val first = CanvasPanel(project, { actions += "activate" }, { actions += "refresh" }, { actions += "enrich" })
        val second = CanvasPanel(project, {}, {}, {})
        try {
            assertFalse(command(first, "Generate canvas").isEnabled)
            first.showResult(result()); second.showResult(result())
            assertTrue(command(first, "Generate canvas").isEnabled)
            perform(first, "Refresh"); perform(first, "Enrich")
            first.openFunction(0)
            assertEquals(listOf("refresh", "enrich", "activate"), actions)
            first.readingView.zoom = 1.5
            assertEquals(1.0, second.readingView.zoom)
            first.readingView.data = CanvasData(CanvasDocument(listOf(CanvasLine("fun handle() {}", 0, "/src/Service.kt", 12))),
                listOf(CanvasTopic("Service>Handle", listOf(CanvasRange(0, 0)))))
            first.setBuilding()
            assertNull(first.readingView.data)
            assertFalse(command(first, "Generate canvas").isEnabled)
            first.showResult(result())
            assertTrue(command(first, "Generate canvas").isEnabled)
            assertEquals(1.5, first.readingView.zoom)
        } finally { first.dispose(); second.dispose() }
    }
}
