package dev.insight.codereview

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
import dev.insight.review.ui.shownView
import dev.insight.review.ui.viewLabels
import dev.insight.review.wall.WallChatPanel
import dev.insight.review.wall.WallView
import java.awt.Component
import java.awt.Container
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem

/** The Code wall shows the source of the files in scope, or of an entry's files, with chats about them. */
class CodeWallPlatformTest : BasePlatformTestCase() {
    private val files = listOf("src/a.kt", "src/b.kt", "src/c.kt").map { codeFile(it, "fun ${it.substringAfterLast('/').first()}() = 1 // $it\n") }

    private fun descendants(container: Container): List<Component> = (if (container is NoteCanvas)
        container.pageKeys.map(container::editorPage) else container.components.toList()).flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }

    private fun blocks(tab: CodeReviewTab) = descendants(tab.canvas).filterIsInstance<WallView.BlockView>()
    private fun links(tab: CodeReviewTab) = descendants(tab.canvas).filterIsInstance<ActionLink>()

    fun `test the view option shows every file and a subset follows the scope`() {
        val tab = CodeReviewTab(project, null, { emptyList() }, { _, _ -> })
        try {
            tab.update(codeSnapshot(*files.toTypedArray()))
            assertEquals(listOf("Summaries", "Topics", "Code wall", "Words"), viewLabels(tab))
            chooseView(tab, "Code wall")
            assertEquals("Code wall", shownView(tab))
            assertNull(tab.state.wallScope!!.paths)
            assertEquals(files.map { it.path }, blocks(tab).map { it.block.path })
            assertEquals("1 line", blocks(tab).first().block.stats)
            assertTrue(descendants(tab.canvas).filterIsInstance<JLabel>().any { it.text == "Code wall · All files in scope · 3 files" })
            assertFalse(links(tab).single { it.text == "Show all files" }.isVisible)

            tab.openCodeWall(listOf(files[2], files[0]), "Subset")
            assertEquals(listOf("src/c.kt", "src/a.kt"), tab.state.wallScope!!.paths)
            assertEquals(listOf("src/c.kt", "src/a.kt"), blocks(tab).map { it.block.path })
            assertTrue(links(tab).single { it.text == "Show all files" }.isVisible)
            // A file that leaves the scope drops off the subset.
            tab.update(codeSnapshot(files[0], files[1]))
            assertEquals(listOf("src/a.kt"), blocks(tab).map { it.block.path })

            links(tab).single { it.text == "Show all files" }.doClick()
            assertNull(tab.state.wallScope!!.paths)
            assertEquals(listOf("src/a.kt", "src/b.kt"), blocks(tab).map { it.block.path })

            descendants(tab).filterIsInstance<JButton>().single { it.text == "Clear tabs" }.doClick()
            assertNull(tab.state.wallScope)
            assertTrue(blocks(tab).isEmpty())
        } finally { Disposer.dispose(tab) }
    }

    fun `test file chats pin their source and the wall chat pins every file`() {
        val requests = CopyOnWriteArrayList<CompletionRequest>()
        val tab = CodeReviewTab(project, null, { emptyList() }, { _, _ -> }, complete = { requests += it; Completion("Reply ${requests.size}.") })
        try {
            tab.update(codeSnapshot(*files.toTypedArray()))
            chooseView(tab, "Code wall")
            val chatTabs = descendants(tab.canvas).filterIsInstance<WallView.ChatTab>()
            assertEquals(files.map { "code-wall-chat-tab:${it.path}" }, chatTabs.map { it.name })
            chatTabs.first().dispatchEvent(MouseEvent(chatTabs.first(), MouseEvent.MOUSE_PRESSED, 0L, InputEvent.BUTTON1_DOWN_MASK,
                2, 2, 1, false, MouseEvent.BUTTON1))
            val panel = descendants(tab.canvas).filterIsInstance<WallChatPanel>().single()
            assertEquals("code-wall-chat:src/a.kt", panel.name)

            panel.send("What does it return?")
            PlatformTestUtil.waitWithEventsDispatching("The reply should finish", { requests.size == 1 && !busy(panel) }, 10 /* seconds */)
            val system = requests.single().messages.first { it.role == LlmRole.SYSTEM }.content
            assertTrue(system.contains("path: src/a.kt"))
            assertTrue(system.contains("1|fun a() = 1 // src/a.kt"))
            assertFalse(system.contains("// src/b.kt"))
            val tools = requests.single().tools.map { it.name }.toSet()
            assertTrue(tools.containsAll(setOf("read_code", "search_code", "list_code_files", "get_code_summaries", "read_file", "search_text")))
            assertTrue(tools.none { it in setOf("add_code_notes", "show_code_source", "find_code_symbols") })
            assertTrue(texts(panel).contains("Reply 1."))
            // The review's own chat is a separate conversation.
            assertTrue(tab.session.messages.none { it.role == ChatRole.USER })

            val link = links(tab).single { it.name == "code-wall-canvas-chat-link" }
            assertEquals("Chat about these files", link.text)
            link.doClick()
            val wallPanel = descendants(tab.canvas).filterIsInstance<WallChatPanel>().single { it.name == "code-wall-canvas-chat" }
            wallPanel.send("How do these relate?")
            PlatformTestUtil.waitWithEventsDispatching("The reply should finish", { requests.size == 2 && !busy(wallPanel) }, 10 /* seconds */)
            val wall = requests[1].messages.first { it.role == LlmRole.SYSTEM }.content
            files.forEach { assertTrue(wall.contains("1|fun ${it.path.substringAfterLast('/').first()}() = 1 // ${it.path}")) }
            assertTrue(texts(wallPanel).contains("Reply 2."))
        } finally { Disposer.dispose(tab) }
    }

    fun `test a folder entry opens its files on the code wall`() {
        val tab = CodeReviewTab(project, null, { emptyList() }, { _, _ -> })
        try {
            tab.update(codeSnapshot(*files.toTypedArray(), codeFile("lib/d.kt", "fun d() = 1\n")))
            tab.canvas.setSize(1600, 900)
            fun layout(component: Container) { component.doLayout(); component.components.filterIsInstance<Container>().forEach(::layout) }
            layout(tab.canvas)
            val items = descendants(tab.canvas).filterIsInstance<JComponent>().mapNotNull { it.componentPopupMenu }.distinct()
                .flatMap { it.components.filterIsInstance<JMenuItem>() }.filter { it.text == "Open on code wall" }
            items.forEach { it.doClick(); if (tab.state.wallScope?.title == "src") return@forEach }
            assertEquals("src", tab.state.wallScope?.title)
            assertEquals(files.map { it.path }, tab.state.wallScope!!.paths)
            assertEquals(files.map { it.path }, blocks(tab).map { it.block.path })
        } finally { Disposer.dispose(tab) }
    }

    private fun texts(panel: WallChatPanel) = descendants(panel).filterIsInstance<JBTextArea>().filter { !it.isEditable }.map { it.text }

    private fun busy(panel: WallChatPanel) = descendants(panel).filterIsInstance<JButton>().single { it.text == "Stop" }.isEnabled

}
