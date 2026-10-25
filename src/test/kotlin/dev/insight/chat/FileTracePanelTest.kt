package dev.insight.chat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.labels.LinkLabel
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.LlmMessage
import dev.insight.llm.LlmToolCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.HeadlessException
import java.awt.event.MouseEvent
import javax.swing.JComboBox
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class FileTracePanelTest : BasePlatformTestCase() {
    private fun controls(container: Container): List<Component> = container.components.flatMap {
        listOf(it) + if (it is Container) controls(it) else emptyList()
    }

    private fun layout(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach { layout(it) }
    }

    fun `test long file table scrolls in a narrow panel and refresh preserves position`() {
        val source = (1..200).joinToString("\n") { "Line $it " + "source content ".repeat(20) }
        val trace = FileTrace("Inspect files")
        trace.register((1..80).map { TraceFile("src/File$it.kt", source = source) })
        val files = JsonArray().apply {
            (1..80).forEach { index -> add(JsonObject().apply {
                addProperty("path", "src/File$index.kt")
                addProperty("code", source)
            }) }
        }
        runBlocking {
            val data = JsonObject().apply { add("files", files) }
            trace.complete(CompletionRequest(listOf(LlmMessage.user(data.toString())))) { Completion("Answer") }
        }
        val panel = FileTracePanel(project)
        panel.render(listOf(trace))
        for ((width, height) in listOf(360 to 240, 360 to 480, 600 to 480)) {
            panel.setSize(width, height)
            repeat(3) { layout(panel) }
            val pane = controls(panel).filterIsInstance<JScrollPane>().single()
            val table = controls(panel).filterIsInstance<JTable>().single()
            assertEquals(80, table.rowCount)
            assertEquals("100.0%", table.getValueAt(0, 2))
            assertEquals("1–200", table.getValueAt(0, 3))
            assertTrue(panel.bounds.contains(SwingUtilities.convertRectangle(pane.parent, pane.bounds, panel)))
            assertTrue(pane.viewport.extentSize.height >= 40)
            assertTrue(pane.verticalScrollBar.isVisible)
            pane.verticalScrollBar.value = pane.verticalScrollBar.maximum
            assertTrue(pane.viewport.viewRect.y + pane.viewport.extentSize.height >= pane.viewport.viewSize.height)
            val position = pane.viewport.viewPosition
            panel.render(listOf(trace))
            repeat(3) { layout(panel) }
            assertEquals(position, pane.viewport.viewPosition)
            assertTrue("Excerpts belong in the editor, not in the file table", controls(panel).filterIsInstance<JTextArea>().isEmpty())
        }
        assertEquals("Rendering must not call the model", 1, trace.snapshot().requests.size)
    }

    fun `test range links keep disjoint and partial lines separate and work after sorting`() {
        val trace = FileTrace("Read ranges")
        trace.register(listOf(TraceFile("z.kt", source = "one\ntwo\nthree\nfour\nfive"), TraceFile("a.kt", source = "alpha")))
        runBlocking {
            trace.complete(CompletionRequest(listOf(LlmMessage.tool("read", """{"ranges":[
                {"path":"z.kt","text":"1| one\n2| two\n4| fo"},
                {"path":"a.kt","text":"1| alpha"}
            ]}""")))) { Completion("Answer") }
        }
        val opened = mutableListOf<Pair<TraceKey, IntRange>>()
        val panel = FileTracePanel(project) { document, lines -> opened += document.key to lines }
        panel.render(listOf(trace))
        panel.setSize(600, 400)
        repeat(3) { layout(panel) }
        val table = controls(panel).filterIsInstance<JTable>().single()
        table.rowSorter.toggleSortOrder(0)
        assertEquals("a.kt", table.getValueAt(0, 0))
        assertEquals("z.kt", table.getValueAt(1, 0))
        assertEquals("42.1%", table.getValueAt(1, 2))
        assertTrue(table.editCellAt(1, 3))
        val links = controls(table.editorComponent as Container).filterIsInstance<LinkLabel<*>>()
        assertEquals(listOf("1–2", "4 (partial)"), links.map { it.text })
        links[1].doClick()
        assertEquals(listOf(TraceKey("z.kt", "Source") to (4..4)), opened)
        assertEquals("1–2; partial: 4", table.getValueAt(1, 3))
        assertFalse(table.isEditing)

        // Dispatch a real single click through JTable to the correct link after sorting.
        val cell = table.getCellRect(1, 3, true)
        val x = cell.x + 10
        val y = cell.y + 8
        try {
            table.dispatchEvent(MouseEvent(table, MouseEvent.MOUSE_PRESSED, 1, 0, x, y, 1, false, MouseEvent.BUTTON1))
        } catch (_: HeadlessException) {
            // BasicTableUI checks the system shortcut key after forwarding the press to the editor.
            assertTrue(GraphicsEnvironment.isHeadless())
        }
        table.dispatchEvent(MouseEvent(table, MouseEvent.MOUSE_RELEASED, 2, 0, x, y, 1, false, MouseEvent.BUTTON1))
        assertEquals(TraceKey("z.kt", "Source") to (1..2), opened.last())
        assertEquals(2, opened.size)
    }

    fun `test Files tab combines accepted reads by turn and clears with chat`() {
        val scope = CoroutineScope(SupervisorJob())
        val session = object : ChatSession(project, scope) {
            override fun createBackend() = object : ChatBackend {
                override suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit): String {
                    val complete = tracedFileCompletion(listOf(TraceFile("a.kt", source = "first\nsecond\n"))) { Completion("Answer") }
                    complete(CompletionRequest(listOf(LlmMessage.user("""{"path":"a.kt","excerpt":"first"}"""))))
                    complete(CompletionRequest(listOf(LlmMessage.user("No source in final context"))))
                    return "Answer"
                }
            }
        }
        val panel = ChatPanel(project, session, showDashboard = false, showFileTrace = true)
        try {
            session.send("Explain first")
            PlatformTestUtil.waitWithEventsDispatching("Reply should finish", { !session.busy }, 10)
            val tabs = controls(panel).filterIsInstance<JTabbedPane>().single()
            assertEquals("Files", tabs.getTitleAt(1))
            tabs.selectedIndex = 1
            val files = controls(panel).filterIsInstance<FileTracePanel>().single()
            val table = controls(files).filterIsInstance<JTable>().single()
            val turns = controls(files).filterIsInstance<JComboBox<*>>().single()
            assertEquals(1, table.rowCount)
            assertEquals("a.kt", table.getValueAt(0, 0))
            assertEquals("45.5%", table.getValueAt(0, 2))
            assertEquals("1", table.getValueAt(0, 3))
            session.send("Another turn")
            PlatformTestUtil.waitWithEventsDispatching("Second reply should finish", { !session.busy }, 10)
            files.render(session.fileTraces)
            assertEquals(2, turns.itemCount)
            turns.selectedIndex = 0
            assertEquals("45.5%", table.getValueAt(0, 2))
            session.clear()
            files.render(session.fileTraces)
            assertEquals(0, table.rowCount)
            assertEquals(0, turns.itemCount)
            assertTrue(session.fileTraces.isEmpty())
        } finally {
            panel.dispose()
            session.discard()
            scope.cancel()
        }
    }

    fun `test project tool output is visible in chat and read ranges appear in Files`() {
        myFixture.addFileToProject("flows/AGENTS.md", "Keep it brief.\nUse a couple of links.")
        val scope = CoroutineScope(SupervisorJob())
        val session = object : ChatSession(project, scope) {
            override fun createBackend(): ChatBackend {
                var round = 0
                return LlmChatBackend({
                    when (round++) {
                        0 -> Completion("", toolCalls = listOf(LlmToolCall("read", "read_file", JsonObject().apply {
                            addProperty("path", "flows/AGENTS.md")
                            addProperty("end_line", 200)
                        })))
                        1 -> Completion("", toolCalls = listOf(LlmToolCall("missing", "read_file", JsonObject().apply {
                            addProperty("path", "missing.md")
                        })))
                        else -> Completion("Done")
                    }
                }, projectContextTools(project), projectChatSystemPrompt())
            }
        }
        val panel = ChatPanel(project, session, showDashboard = false, showFileTrace = true)
        try {
            session.send("Check the rules")
            PlatformTestUtil.waitWithEventsDispatching("Tool loop should finish", { !session.busy }, 10)
            val toolMessages = session.messages.filter { it.role == ChatMessage.TOOL }
            assertEquals(2, toolMessages.size)
            assertTrue(controls(panel).filterIsInstance<JTextArea>().any { it.text == toolMessages[0].toolCall!!.result })
            assertTrue(controls(panel).filterIsInstance<JTextArea>().any { it.text == toolMessages[1].toolCall!!.result })
            assertTrue(toolMessages[1].toolCall!!.failed)
            assertTrue(toolMessages[0].toolCall!!.result.contains("Keep it brief."))
            controls(panel).filterIsInstance<JTabbedPane>().single().selectedIndex = 1
            val table = controls(panel).filterIsInstance<JTable>().single()
            val row = (0 until table.rowCount).single { table.getValueAt(it, 0) == "flows/AGENTS.md" }
            assertEquals("Project", table.getValueAt(row, 1))
            assertEquals("100.0%", table.getValueAt(row, 2))
            assertEquals("1–2", table.getValueAt(row, 3))
            val missing = (0 until table.rowCount).single { table.getValueAt(it, 0) == "missing.md" }
            assertEquals("Unknown", table.getValueAt(missing, 2))
            assertEquals(3, session.fileTraces.single().snapshot().requests.size)
        } finally {
            panel.dispose()
            session.discard()
            scope.cancel()
        }
    }
}
