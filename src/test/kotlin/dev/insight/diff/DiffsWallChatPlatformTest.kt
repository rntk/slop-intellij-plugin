package dev.insight.diff

import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBTextArea
import dev.insight.agent.ChatRole
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.LlmRole
import dev.insight.review.ui.NoteCanvas
import dev.insight.review.ui.chooseView
import java.awt.Component
import java.awt.Container
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JComponent

/** A Diffs wall block's Chat tab unfolds independent chats about that file, which outlast folding and the wall's scopes. */
class DiffsWallChatPlatformTest : BasePlatformTestCase() {
    private fun file(path: String) = ReviewFile(path, "/project/$path", null, "modified", "@@ -1 +1 @@\n-old\n+new $path\n", path,
        before = "old\n", after = "new $path\n")

    private fun descendants(container: Container): List<Component> = (if (container is NoteCanvas)
        container.pageKeys.map(container::editorPage) else container.components.toList()).flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }

    private fun press(component: JComponent) = component.dispatchEvent(MouseEvent(component, MouseEvent.MOUSE_PRESSED, 0L,
        InputEvent.BUTTON1_DOWN_MASK, 2, 2, 1, false, MouseEvent.BUTTON1))

    fun `test a block's chat tab unfolds independent chats about its file`() {
        val requests = CopyOnWriteArrayList<CompletionRequest>()
        val tab = DiffReviewTab(project, null, { emptyList() }, {}, complete = { requests += it; Completion("Reply ${requests.size}.") })
        try {
            val files = listOf(file("src/a.kt"), file("src/b.kt"))
            tab.update(ReviewSnapshot(files, emptyList()))
            chooseView(tab, "Diffs wall")
            val block = descendants(tab.canvas).filterIsInstance<DiffsWallView.BlockView>().first()
            val surface = block.parent as JComponent
            surface.doLayout()
            fun chatTabs() = descendants(tab.canvas).filterIsInstance<DiffsWallView.ChatTab>()
            fun panels() = descendants(tab.canvas).filterIsInstance<WallChatPanel>()
            assertEquals(files.map { "diffs-wall-chat-tab:${it.path}" }, chatTabs().map { it.name })
            val chatTab = chatTabs().first()
            // The tab hangs off the block's right edge, inside the gap before the next column.
            assertEquals(block.x + block.width - 1, chatTab.x)
            assertTrue(chatTab.y >= block.y && chatTab.y + chatTab.height <= block.y + block.height)
            assertTrue(panels().isEmpty())

            press(chatTab)
            val panel = panels().single()
            assertEquals("diffs-wall-chat:src/a.kt", panel.name)
            surface.doLayout()
            assertEquals(chatTab.x + chatTab.width, panel.x)
            assertEquals(block.y, panel.y)
            // The window is in front of the blocks it reaches over.
            assertSame(panel, surface.getComponent(0))

            panel.send("Why is it new?")
            PlatformTestUtil.waitWithEventsDispatching("The reply should finish", { requests.size == 1 && !panelBusy(panel) }, 10 /* seconds */)
            val request = requests.single()
            val system = request.messages.first { it.role == LlmRole.SYSTEM }.content
            // The block's own diff is pinned; other files are left to the tools.
            assertTrue(system.contains("path: src/a.kt"))
            assertTrue(system.contains("+new src/a.kt"))
            assertFalse(system.contains("+new src/b.kt"))
            val tools = request.tools.map { it.name }.toSet()
            assertTrue(tools.containsAll(setOf("search_diff", "read_diff_files", "read_diff_source", "read_file", "search_text")))
            assertFalse("add_diff_notes" in tools)
            assertTrue(texts(panel).contains("Reply 1."))
            // The review's own chat is a separate conversation.
            assertTrue(tab.session.messages.none { it.role == ChatRole.USER })

            val history = descendants(panel).filterIsInstance<ComboBox<*>>().single()
            val newChat = descendants(panel).filterIsInstance<ActionLink>().single { it.text == "New chat" }
            newChat.doClick()
            assertEquals(2, history.itemCount)
            assertEquals(1, history.selectedIndex)
            assertFalse(texts(panel).contains("Reply 1."))
            // A new chat that is still empty is reused rather than piling up.
            newChat.doClick()
            assertEquals(2, history.itemCount)
            history.selectedIndex = 0
            assertTrue(texts(panel).contains("Reply 1."))

            // Folding keeps the chats; another scope's block for the same file unfolds them again.
            press(chatTab)
            assertTrue(panels().isEmpty())
            tab.openDiffsWall(listOf(files[0]), "Subset")
            assertTrue(panels().isEmpty())
            press(chatTabs().single())
            assertSame(panel, panels().single())
            assertEquals(2, history.itemCount)
            assertTrue(texts(panel).contains("Reply 1."))
        } finally { Disposer.dispose(tab) }
    }

    fun `test the wall's own chat has every diff on the wall in its context`() {
        val requests = CopyOnWriteArrayList<CompletionRequest>()
        val tab = DiffReviewTab(project, null, { emptyList() }, {}, complete = { requests += it; Completion("Reply ${requests.size}.") })
        try {
            val files = listOf(file("src/a.kt"), file("src/b.kt"), file("src/c.kt"))
            tab.update(ReviewSnapshot(files, emptyList()))
            chooseView(tab, "Diffs wall")
            fun link() = descendants(tab.canvas).filterIsInstance<ActionLink>().single { it.name == "diffs-wall-canvas-chat-link" }
            fun panels() = descendants(tab.canvas).filterIsInstance<WallChatPanel>()
            assertEquals("Chat about these changes", link().text)
            assertTrue(link().isVisible)
            assertTrue(panels().isEmpty())

            link().doClick()
            val panel = panels().single()
            assertEquals("diffs-wall-canvas-chat", panel.name)
            val surface = panel.parent as JComponent
            surface.doLayout()
            assertSame(panel, surface.getComponent(0))
            assertTrue(panel.width > 0 && panel.height > 0)
            assertEquals("Hide chat", link().text)

            panel.send("How do these changes relate?")
            PlatformTestUtil.waitWithEventsDispatching("The reply should finish", { requests.size == 1 && !panelBusy(panel) }, 10 /* seconds */)
            val system = requests.single().messages.first { it.role == LlmRole.SYSTEM }.content
            files.forEach { assertTrue(system.contains("+new ${it.path}")) }
            assertTrue("search_diff" in requests.single().tools.map { it.name })
            assertTrue(texts(panel).contains("Reply 1."))
            // The file chats and the review's own chat are separate conversations.
            assertTrue(tab.session.messages.none { it.role == ChatRole.USER })

            // A subset of the change stays in the same chat, whose next question carries only the wall's diffs.
            tab.openDiffsWall(listOf(files[1]), "Subset")
            assertSame(panel, panels().single())
            panel.send("And now?")
            PlatformTestUtil.waitWithEventsDispatching("The reply should finish", { requests.size == 2 && !panelBusy(panel) }, 10 /* seconds */)
            val subset = requests[1].messages.first { it.role == LlmRole.SYSTEM }.content
            assertTrue(subset.contains("+new src/b.kt"))
            assertFalse(subset.contains("+new src/a.kt"))

            link().doClick()
            assertTrue(panels().isEmpty())
            link().doClick()
            assertSame(panel, panels().single())
            assertTrue(texts(panel).contains("Reply 2."))
        } finally { Disposer.dispose(tab) }
    }

    private fun texts(panel: WallChatPanel) = descendants(panel).filterIsInstance<JBTextArea>().filter { !it.isEditable }.map { it.text }

    private fun panelBusy(panel: WallChatPanel) =
        descendants(panel).filterIsInstance<javax.swing.JButton>().single { it.text == "Stop" }.isEnabled
}
