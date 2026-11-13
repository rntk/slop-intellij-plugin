package dev.insight.diff

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.testFramework.FileEditorManagerTestCase
import com.intellij.testFramework.replaceService
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import com.intellij.ui.tabs.JBTabs
import dev.insight.codereview.CodeReviewPanel
import dev.insight.review.ChatFocus
import java.awt.Container
import javax.swing.JPanel
import javax.swing.JRootPane
import dev.insight.review.ui.ReviewTab
import dev.insight.review.ui.ReviewTabsController

class CanvasChatActivationPlatformTest : FileEditorManagerTestCase() {
    fun testDiffChatPreservesCanvasWhenWindowShowsImmediately() = checkActivation(DiffReviewPanel(project), "Diff Review", false)
    fun testDiffChatPreservesCanvasWhenWindowShowsLater() = checkActivation(DiffReviewPanel(project), "Diff Review", true)
    fun testCodeChatPreservesCanvasWhenWindowShowsImmediately() = checkActivation(CodeReviewPanel(project), "Code Review", false)
    fun testCodeChatPreservesCanvasWhenWindowShowsLater() = checkActivation(CodeReviewPanel(project), "Code Review", true)

    private fun checkActivation(panel: JPanel, id: String, deferred: Boolean) {
        JRootPane().contentPane.add(panel)
        val disposable = Disposer.newDisposable()
        var activation: Runnable? = null
        var hidden = 0
        val window = object : ToolWindowHeadlessManagerImpl.MockToolWindow(project) {
            override fun getId() = id
            override fun activate(runnable: Runnable?, autoFocusContents: Boolean, forced: Boolean) {
                val show = Runnable {
                    project.messageBus.syncPublisher(ToolWindowManagerListener.TOPIC).toolWindowShown(this)
                    runnable?.run()
                }
                if (deferred) activation = show else show.run()
            }
            override fun hide() { hidden++ }
            override fun hide(runnable: Runnable?) { hidden++ }
        }
        project.replaceService(ToolWindowManager::class.java, object : ToolWindowHeadlessManagerImpl(project) {
            override fun getToolWindow(toolWindowId: String?) = if (toolWindowId == id) window else super.getToolWindow(toolWindowId)
        }, disposable)
        val manager = FileEditorManager.getInstance(project)
        fun canvas(tab: JPanel) = (tab as ReviewTab<*, *>).canvasFile
        try {
            val (hub, start) = when (panel) {
                is DiffReviewPanel -> panel.hub to panel.start
                is CodeReviewPanel -> panel.hub to panel.start
                else -> error("Unexpected review panel")
            }
            val tabs = descendants(hub.page).filterIsInstance<JBTabs>().single()
            val originalTab = tabs.selectedInfo!!.component as ReviewTab<*, *>
            val original = canvas(originalTab)
            assertTrue("The hub opens first", manager.isFileOpen(hub.file))
            assertFalse("The views open from their tiles", manager.isFileOpen(original))
            start.continueButton.doClick()
            assertTrue(manager.isFileOpen(hub.file))
            val controller = panel.javaClass.getDeclaredField("tabs").apply { isAccessible = true }.get(panel) as ReviewTabsController<*>
            controller.openCanvas(true)
            assertTrue(manager.isFileOpen(original))
            controller.openChat(ChatFocus("a.kt", listOf("a.kt")), null)
            val chat = canvas(tabs.selectedInfo!!.component as JPanel)
            assertNotSame(original, chat)
            assertFalse("Selecting the focused chat must preserve the original canvas", manager.isFileOpen(chat))
            if (deferred) {
                assertNotNull("Tool window activation must be pending", activation)
                activation!!.run()
                activation = null
            }
            assertTrue(manager.isFileOpen(original))
            assertFalse("Showing the window for To Chat must preserve the original canvas", manager.isFileOpen(chat))
            assertEquals("Showing the window for To Chat keeps it open", 0, hidden)
            manager.closeFile(hub.file)
            project.messageBus.syncPublisher(ToolWindowManagerListener.TOPIC).toolWindowShown(window)
            assertTrue("A later ordinary window show must open the hub", manager.isFileOpen(hub.file))
            assertFalse(manager.isFileOpen(chat))
            assertEquals("An ordinary window show hides the side panel again", 1, hidden)

            // The Open chat tile selects its tab and shows its chat in the window, which stays open.
            originalTab.tileGrid.click("chat")
            assertSame(originalTab, tabs.selectedInfo!!.component)
            if (deferred) {
                assertNotNull("Tool window activation must be pending", activation)
                activation!!.run()
            }
            assertEquals("Opening the chat keeps the window open", 1, hidden)
            assertTrue(descendants(panel).any { it === originalTab.chat })
        } finally {
            Disposer.dispose(panel as com.intellij.openapi.Disposable)
            Disposer.dispose(disposable)
            Disposer.dispose(window.disposable)
        }
    }

    private fun descendants(container: Container): List<java.awt.Component> = container.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
}
