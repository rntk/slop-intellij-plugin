package dev.insight.chat

import com.google.gson.JsonParser
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.Container
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent

class ChartChatPanelTest : BasePlatformTestCase() {
    private class Context : ChartChatContext {
        override var revision = 3
        val opened = mutableListOf<Pair<Int, Int>>()
        val hovered = mutableListOf<Int?>()
        override suspend fun state(offset: Int) = "{}"
        override suspend fun find(query: String, offset: Int) = "{}"
        override suspend fun apply(action: String, revision: Int, nodes: List<Int>) = "{}"
        override fun referenceName(revision: Int, node: Int) = if (revision == this.revision && node == 1) "loadData" else null
        override fun openReference(revision: Int, node: Int): String? {
            opened += revision to node
            return if (revision != this.revision) "The chart was rebuilt after this message" else null
        }
        override fun hoverReference(revision: Int, node: Int?) { hovered += node }
    }

    private fun controls(container: Container): List<java.awt.Component> = container.components.flatMap {
        listOf(it) + if (it is Container) controls(it) else emptyList()
    }

    fun `test replies render references, follow-ups are offered once and stale clicks show a notice`() {
        val context = Context()
        val scope = CoroutineScope(SupervisorJob())
        val replies = ArrayDeque(listOf("Starts at [[node:1]], then <b>[[node:7|Store]]</b>.", "Second"))
        val session = object : ChatSession(project, scope, context) {
            override fun createBackend() = object : ChatBackend {
                override suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit): String {
                    if (replies.size == 2) sessionChartTools(context).single { it.definition.name == "suggest_followups" }
                        .call(JsonParser.parseString("""{"questions":["Where is it stored?","Who calls it?"]}""").asJsonObject)
                    return replies.removeFirst()
                }
            }
        }
        val panel = ChatPanel(project, session, chartMode = true)
        try {
            session.send("How does loading work?")
            PlatformTestUtil.waitWithEventsDispatching("Reply should finish", { !session.busy }, 5000)
            assertEquals(3, session.messages.last().revision)
            assertEquals(listOf("Where is it stored?", "Who calls it?"), session.followups)

            val pane = controls(panel).filterIsInstance<JEditorPane>().single()
            assertTrue(pane.text, pane.text.contains("loadData") && pane.text.contains("&lt;b&gt;") && !pane.text.contains("<b>"))
            assertTrue(pane.text, pane.text.contains("node:3:1") && !pane.text.contains("node:3:7"))
            fun fire(type: HyperlinkEvent.EventType, link: String) =
                pane.hyperlinkListeners.forEach { it.hyperlinkUpdate(HyperlinkEvent(pane, type, null, link)) }
            fire(HyperlinkEvent.EventType.ENTERED, "node:3:1")
            fire(HyperlinkEvent.EventType.EXITED, "node:3:1")
            assertEquals(listOf(1, null), context.hovered)
            fire(HyperlinkEvent.EventType.ACTIVATED, "node:3:1")
            assertEquals(ChatMessage.ASSISTANT, session.messages.last().role)
            context.revision = 4
            fire(HyperlinkEvent.EventType.ACTIVATED, "node:3:1")
            assertEquals(listOf(3 to 1, 3 to 1), context.opened)
            assertEquals(ChatMessage.NOTICE, session.messages.last().role)
            assertTrue(session.messages.last().text.contains("rebuilt"))
            // After the rebuild, the stale reference renders as plain text.
            val rendered = controls(panel).filterIsInstance<JEditorPane>().single()
            assertFalse(rendered.text, rendered.text.contains("node:3:1"))

            val buttons = controls(panel).filterIsInstance<JButton>().filter { it.text in session.followups }
            assertEquals(2, buttons.size)
            buttons.first { it.text == "Who calls it?" }.doClick()
            assertTrue(session.followups.isEmpty())
            PlatformTestUtil.waitWithEventsDispatching("Reply should finish", { !session.busy }, 5000)
            assertEquals("Who calls it?", session.messages.last { it.role == ChatMessage.USER }.text)
            assertTrue(session.followups.isEmpty())
            assertTrue(controls(panel).filterIsInstance<JButton>().none { it.text == "Where is it stored?" })
        } finally {
            panel.dispose()
            scope.cancel()
        }
    }

    fun `test project chat keeps plain text replies`() {
        val scope = CoroutineScope(SupervisorJob())
        val session = object : ChatSession(project, scope) {
            override fun createBackend() = object : ChatBackend {
                override suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit) = "See [[node:1]] <b>"
            }
        }
        val panel = ChatPanel(project, session)
        try {
            session.send("Hi")
            PlatformTestUtil.waitWithEventsDispatching("Reply should finish", { !session.busy }, 5000)
            assertNull(session.messages.last().revision)
            assertTrue(controls(panel).none { it is JEditorPane })
            assertTrue(controls(panel).filterIsInstance<com.intellij.ui.components.JBTextArea>().any { it.text == "See [[node:1]] <b>" })
        } finally {
            panel.dispose()
            scope.cancel()
        }
    }
}
