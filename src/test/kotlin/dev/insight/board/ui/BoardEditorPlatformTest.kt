package dev.insight.board.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextArea
import dev.insight.agent.ChatMessage
import dev.insight.agent.ChatRole
import dev.insight.board.Board
import dev.insight.board.BoardModel
import dev.insight.board.BoardOrigin
import dev.insight.board.BoardStore
import dev.insight.board.ReviewBoardSources
import dev.insight.board.TextBlock
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import javax.swing.AbstractButton
import javax.swing.JLabel

class BoardEditorPlatformTest : BasePlatformTestCase() {
    private fun descendants(component: Component): List<Component> =
        listOf(component) + ((component as? Container)?.components.orEmpty().flatMap(::descendants))

    fun `test the editor shows the board and its chat, and saves the board with the chat when it closes`() {
        val directory = Files.createTempDirectory("boards")
        val store = BoardStore(directory)
        val origin = BoardOrigin("diff", "main..feature")
        val model = BoardModel(Board.new("Retries", origin, id = "board1"))
        val restored = listOf(ChatMessage(ChatRole.USER, "Explain retries"), ChatMessage(ChatRole.ASSISTANT, "Done."))
        val file = BoardFile(project, model, ReviewBoardSources(emptyList()) { null }, origin, restored, store)
        val provider = BoardEditorProvider()
        assertTrue(provider.accept(project, file))
        assertEquals("insight-board", provider.editorTypeId)
        val editor = provider.createEditor(project, file)
        try {
            val all = descendants(editor.component)
            val buttons = all.filterIsInstance<AbstractButton>().map { it.text }
            assertTrue(buttons.containsAll(listOf("Fit", "−", "+", "Export Markdown", "Chat", "Send", "Stop")))
            val labels = all.filterIsInstance<JLabel>().map { it.text }
            assertTrue(labels.contains("Retries"))
            assertTrue(labels.contains("0 blocks · not saved yet"))
            // The restored conversation is shown after the welcome.
            val texts = all.filterIsInstance<JBTextArea>().map { it.text }
            assertTrue(texts.any { it.startsWith("This is an explainer board of main..feature") })
            assertTrue(texts.containsAll(listOf("Explain retries", "Done.")))
            assertSame(all.filterIsInstance<JBTextArea>().single { it.name == "board-chat-input" }, editor.preferredFocusedComponent)

            model.add(TextBlock("t1", "Why"))
            model.setTitle("How retries work")
            val after = descendants(editor.component).filterIsInstance<JLabel>().map { it.text }
            assertTrue(after.contains("1 block · unsaved changes"))
            assertTrue(after.contains("How retries work"))
            assertEquals("Board: How retries work", file.name)

            val toggle = descendants(editor.component).filterIsInstance<AbstractButton>().single { it.text == "Chat" }
            toggle.doClick()
            assertTrue(descendants(editor.component).none { it.name == "board-chat" })
            toggle.doClick()
            assertTrue(descendants(editor.component).any { it.name == "board-chat" })
        } finally {
            Disposer.dispose(editor)
        }
        val saved = store.load("board1")
        assertNotNull(saved)
        assertEquals("How retries work", saved!!.board.title)
        assertEquals(listOf("t1"), saved.board.blocks.map { it.id })
        assertEquals(restored, saved.chat)
    }
}
