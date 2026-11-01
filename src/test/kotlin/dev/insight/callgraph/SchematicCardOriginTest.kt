package dev.insight.callgraph

import com.google.gson.JsonParser
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.ui.components.ActionLink
import dev.insight.graph.SymbolKind
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.JCheckBox
import javax.swing.JLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class SchematicCardOriginTest : LightJavaCodeInsightFixtureTestCase() {
    private fun descendants(component: Container): List<Component> = component.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }

    private fun <T> background(block: suspend () -> T): T {
        val task = CoroutineScope(Dispatchers.Default).async { runCatching { block() } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Canvas tool should finish", { task.isCompleted }, 20)
            return runBlocking { task.await() }.getOrThrow()
        } finally {
            task.cancel()
        }
    }

    private fun click(component: Component) {
        val event = MouseEvent(component, MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false, MouseEvent.BUTTON1)
        component.mouseListeners.forEach { it.mouseClicked(event) }
    }

    fun testChatMarksOnlyCardsItOpensAndKeepsTheMarkAcrossFoldingAndViewChanges() {
        myFixture.configureByText("Flow.java", "class Flow { void entry() { step(); } void step() {} }")
        val result = ProjectGraphAnalyzer.build(project)
        val root = result.graph.nodes.single { it.kind == SymbolKind.FILE }.id
        val entry = result.graph.nodes.single { it.kind == SymbolKind.FUNCTION && it.name.startsWith("entry(") }.id
        val step = result.graph.nodes.single { it.kind == SymbolKind.FUNCTION && it.name.startsWith("step(") }.id
        val editor = SchematicEditorProvider().createEditor(project, SchematicFile(result, root))
        try {
            val panel = editor.component as SchematicPanel
            fun title(name: String) = descendants(panel).filterIsInstance<JLabel>()
                .last { it.text.orEmpty().contains("<code>$name(") }
            fun badge(title: JLabel): JLabel {
                val card = generateSequence<Component>(title) { it.parent }
                    .first { it.javaClass.simpleName == "SummaryCard" } as Container
                return descendants(card).filterIsInstance<JLabel>().first { it.name == "schematic-card-origin" }
            }
            PlatformTestUtil.waitWithEventsDispatching("File should load", {
                descendants(panel).filterIsInstance<JLabel>().any { it.text.orEmpty().contains("<code>entry(") }
            }, 10)
            val entryTitle = title("entry")
            val entryBadge = badge(entryTitle)
            assertFalse("Hover previews have no LLM mark", entryBadge.isVisible)
            click(entryTitle)

            val show = schematicCanvasTools(panel.chatCanvas).single { it.definition.name == "show_call_chain" }
            val args = JsonParser.parseString("""{"symbol_ids":[$entry,$step],"open_last":true}""").asJsonObject
            background { show.call(args) }
            val stepTitle = title("step")
            val stepBadge = badge(stepTitle)
            assertFalse("Following the reader's existing card must keep its origin", entryBadge.isVisible)
            assertTrue("The chat's new card must be marked", stepBadge.isVisible)
            assertEquals("LLM", stepBadge.text)
            assertEquals("Opened by the LLM through chat tools", stepBadge.toolTipText)

            click(stepTitle)
            assertTrue("Folding keeps the origin in the header", stepBadge.isVisible)
            click(stepTitle)
            background { show.call(args) }
            val sourceToggle = descendants(panel).filterIsInstance<JCheckBox>().single { it.name == "schematic-native-editor" }
            sourceToggle.doClick()
            panel.zoomTo(0.75)
            sourceToggle.doClick()
            assertTrue("Repeated tool calls, zoom and view changes keep the origin", stepBadge.isVisible)

            val stepCard = generateSequence<Component>(stepTitle) { it.parent }
                .first { it.javaClass.simpleName == "SummaryCard" } as Container
            descendants(stepCard).filterIsInstance<ActionLink>().first { it.text == "Unpin" }.doClick()
            assertFalse(stepBadge.isVisible)
            descendants(stepCard).filterIsInstance<ActionLink>().first { it.text == "Pin" }.doClick()
            assertFalse("A closed card reopened by the reader has no LLM mark", stepBadge.isVisible)
            background { show.call(args) }
            assertFalse("Showing the reopened manual card must not relabel it", stepBadge.isVisible)
        } finally {
            Disposer.dispose(editor)
        }
    }
}
