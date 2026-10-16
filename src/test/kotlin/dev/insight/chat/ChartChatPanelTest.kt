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
        override var storyId: Int? = 10
        val opened = mutableListOf<Pair<Int, Int>>()
        val hovered = mutableListOf<Int?>()
        val openedBeats = mutableListOf<Triple<Int, Int, Int?>>()
        override suspend fun state(offset: Int) = "{}"
        override suspend fun find(query: String, offset: Int) = "{}"
        override suspend fun apply(action: String, revision: Int, nodes: List<Int>) = "{}"
        override fun referenceName(revision: Int, node: Int) = if (revision == this.revision && node == 1) "loadData" else null
        override fun openReference(revision: Int, node: Int): String? {
            opened += revision to node
            return if (revision != this.revision) "The chart was rebuilt after this message" else null
        }
        override fun hoverReference(revision: Int, node: Int?) { hovered += node }
        override fun beatTitle(revision: Int, beat: Int, storyId: Int?) =
            if (revision == this.revision && storyId != null && storyId == this.storyId && beat == 2) "Validation Stage" else null
        override fun openBeat(revision: Int, beat: Int, storyId: Int?): String? {
            openedBeats += Triple(revision, beat, storyId)
            return when {
                revision != this.revision -> "The story was rebuilt after this message"
                storyId == null || storyId != this.storyId -> "A different story is now open on the chart"
                else -> null
            }
        }
    }

    private fun controls(container: Container): List<java.awt.Component> = container.components.flatMap {
        listOf(it) + if (it is Container) controls(it) else emptyList()
    }

    fun `test replies render references, follow-ups are offered once and stale clicks show a notice`() {
        val context = Context()
        val scope = CoroutineScope(SupervisorJob())
        val replies = ArrayDeque(listOf("Starts at [[node:1]], see [[beat:2|Stage 2]], then <b>[[node:7|Store]]</b>.", "Second [[beat:2|New Stage 2]]"))
        val session = object : ChatSession(project, scope, context) {
            override fun createBackend() = object : ChatBackend {
                override suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit): String {
                    if (replies.size == 2) sessionChartTools(context).single { it.definition.name == "suggest_followups" }
                        .call(JsonParser.parseString("""{"questions":["Where is it stored?","Who calls it?"]}""").asJsonObject)
                    else context.storyId = 11
                    return replies.removeFirst()
                }
            }
        }
        val panel = ChatPanel(project, session, chartMode = true)
        try {
            session.send("How does loading work?")
            PlatformTestUtil.waitWithEventsDispatching("Reply should finish", { !session.busy }, 5000)
            assertEquals(3, session.messages.last().revision)
            assertEquals(10, session.messages.last().storyId)
            assertEquals(listOf("Where is it stored?", "Who calls it?"), session.followups)

            val pane = controls(panel).filterIsInstance<JEditorPane>().single()
            assertTrue(pane.text, pane.text.contains("loadData") && pane.text.contains("&lt;b&gt;") && !pane.text.contains("<b>"))
            assertTrue(pane.text, pane.text.contains("node:3:1") && !pane.text.contains("node:3:7"))
            assertTrue(pane.text, pane.text.contains("beat:3:2") && pane.text.contains("Stage 2"))
            fun fire(target: JEditorPane, type: HyperlinkEvent.EventType, link: String) =
                target.hyperlinkListeners.forEach { it.hyperlinkUpdate(HyperlinkEvent(target, type, null, link)) }
            fire(pane, HyperlinkEvent.EventType.ACTIVATED, "beat:3:2")
            assertEquals(listOf(Triple(3, 2, 10)), context.openedBeats)
            fire(pane, HyperlinkEvent.EventType.ENTERED, "node:3:1")
            fire(pane, HyperlinkEvent.EventType.EXITED, "node:3:1")
            assertEquals(listOf(1, null), context.hovered)
            fire(pane, HyperlinkEvent.EventType.ACTIVATED, "node:3:1")
            assertEquals(ChatMessage.ASSISTANT, session.messages.last().role)
            context.revision = 4
            fire(pane, HyperlinkEvent.EventType.ACTIVATED, "node:3:1")
            assertEquals(listOf(3 to 1, 3 to 1), context.opened)
            assertEquals(ChatMessage.NOTICE, session.messages.last().role)
            assertTrue(session.messages.last().text.contains("rebuilt"))
            fire(pane, HyperlinkEvent.EventType.ACTIVATED, "beat:3:2")
            assertEquals(listOf(Triple(3, 2, 10), Triple(3, 2, 10)), context.openedBeats)
            // After the rebuild, the stale reference renders as plain text.
            val rendered = controls(panel).filterIsInstance<JEditorPane>().single()
            assertFalse(rendered.text, rendered.text.contains("node:3:1"))
            assertFalse(rendered.text, rendered.text.contains("beat:3:2"))

            // Restore revision 3 and send a follow-up that replaces the story without rebuilding the graph.
            context.revision = 3
            val buttons = controls(panel).filterIsInstance<JButton>().filter { it.text in session.followups }
            assertEquals(2, buttons.size)
            buttons.first { it.text == "Who calls it?" }.doClick()
            assertTrue(session.followups.isEmpty())
            PlatformTestUtil.waitWithEventsDispatching("Reply should finish", { !session.busy }, 5000)
            assertEquals("Who calls it?", session.messages.last { it.role == ChatMessage.USER }.text)
            assertTrue(session.followups.isEmpty())
            assertTrue(controls(panel).filterIsInstance<JButton>().none { it.text == "Where is it stored?" })
            val panes = controls(panel).filterIsInstance<JEditorPane>()
            assertEquals(2, panes.size)
            assertTrue("Node link in first reply remains active at same revision", panes[0].text.contains("node:3:1"))
            assertFalse("Beat link in first reply is invalidated when story changes", panes[0].text.contains("beat:3:2"))
            assertTrue("Beat link in second reply resolves against new story", panes[1].text.contains("beat:3:2"))
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
