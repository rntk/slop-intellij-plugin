package dev.insight.canvas

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.FileEditorManagerTestCase
import com.intellij.testFramework.PlatformTestUtil
import dev.insight.agent.SourceSide
import dev.insight.context.CodeContext
import dev.insight.context.ContextAction
import dev.insight.context.ContextData
import dev.insight.context.ContextKeys
import dev.insight.context.ContextOrigin
import dev.insight.context.ContextRequest
import dev.insight.context.ContextScopeKind
import dev.insight.context.LinkedFile
import java.awt.Container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class CanvasSessionEditorTest : FileEditorManagerTestCase() {
    private fun <T> background(block: suspend () -> T): T {
        val task = CoroutineScope(Dispatchers.Default).async { runCatching { block() } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Canvas work should finish", { task.isCompleted }, 30)
            return runBlocking { task.await() }.getOrThrow()
        } finally {
            task.cancel()
        }
    }

    private fun relayout(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach(::relayout)
    }

    fun testOpeningSavedSessionsUsesTheSelectedSessionAndReusesTheFileTab() {
        val source = myFixture.configureByText("Flow.java", "class Flow { void entry() {} }").virtualFile
        val first = CanvasSession.new(relativePath(project, source.path))
        val second = first.copy(id = newCanvasId(), title = "Another exploration")
        val manager = FileEditorManager.getInstance(project)
        try {
            assertTrue(background { withContext(Dispatchers.EDT) { showSavedCanvasSession(project, source, first) } })
            val tab = manager.openFiles.filterIsInstance<CanvasSessionFile>().single()
            assertEquals(first.id, tab.session.id)
            assertTrue(background { withContext(Dispatchers.EDT) { showSavedCanvasSession(project, source, second) } })
            assertSame(tab, manager.openFiles.filterIsInstance<CanvasSessionFile>().single())
            assertEquals(second.id, tab.session.id)
            assertTrue(background { withContext(Dispatchers.EDT) { showSavedCanvasSession(project, source, second) } })
            assertEquals(second.id, tab.session.id)
        } finally {
            manager.openFiles.filterIsInstance<CanvasSessionFile>().forEach(manager::closeFile)
        }
    }

    fun testOpenAtLineRevealsTargetLineOnRootCardAndReuseTab() {
        val longCode = buildString {
            append("class LongFlow {\n")
            for (i in 1..100) append("    int field$i = $i;\n")
            append("}\n")
        }
        val source = myFixture.configureByText("LongFlow.java", longCode).virtualFile
        val manager = FileEditorManager.getInstance(project)
        try {
            OpenCanvasSessionAction().open(project, source, line = 65)
            PlatformTestUtil.waitWithEventsDispatching("Canvas tab should open and load", {
                val tab = manager.openFiles.filterIsInstance<CanvasSessionFile>().firstOrNull { it.source == source }
                    ?: return@waitWithEventsDispatching false
                activeCanvasSessionPanel(project, tab)?.ready == true
            }, 30)
            val tab = manager.openFiles.filterIsInstance<CanvasSessionFile>().single { it.source == source }
            val panel = activeCanvasSessionPanel(project, tab)!!
            panel.setSize(1200, 600)
            repeat(2) { relayout(panel) }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            val pane = panel.cardViews.getValue(CANVAS_ROOT).sourcePane!!
            val picked65 = pane.pickedRange
            assertNotNull("Opening at line 65 must highlight it on root card", picked65)
            assertEquals(64, pane.editor.document.getLineNumber(picked65!!.first))
            assertTrue("Opening at line 65 must scroll the root card viewer", pane.editor.scrollingModel.visibleArea.y > 0)

            // Reusing the open tab via explore.canvas (even when FILE scope is chosen alongside LINE candidate) reveals the new line.
            val lineCtx = CodeContext(ContextScopeKind.LINE, "Line 90", "LongFlow.java", source.path, 90..90, SourceSide.CURRENT, "int field89 = 89;")
            val fileCtx = CodeContext(ContextScopeKind.FILE, "File LongFlow.java", "LongFlow.java", source.path, null, SourceSide.CURRENT, longCode)
            val explore = ContextAction.EP_NAME.extensionList.first { it.id == "explore.canvas" }
            explore.perform(ContextRequest(project, fileCtx, listOf(lineCtx, fileCtx), ContextOrigin.OTHER, ContextData(), null))
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            val picked90 = pane.pickedRange
            assertNotNull("Reusing tab at line 90 must update highlight", picked90)
            assertEquals(89, pane.editor.document.getLineNumber(picked90!!.first))
        } finally {
            manager.openFiles.filterIsInstance<CanvasSessionFile>().forEach(manager::closeFile)
        }
    }

    fun testOpenLinkedFilesOnCanvasActionPreservesLineNumbers() {
        fun longClass(name: String): String = buildString {
            append("class $name {\n")
            for (i in 1..80) append("    int val$i = $i;\n")
            append("}\n")
        }
        val first = myFixture.addFileToProject("LinkedFirst.java", longClass("LinkedFirst")).virtualFile
        val second = myFixture.addFileToProject("LinkedSecond.java", longClass("LinkedSecond")).virtualFile
        val manager = FileEditorManager.getInstance(project)
        try {
            val change = CodeContext(
                ContextScopeKind.CHANGE,
                "Links in selection",
                null,
                null,
                null,
                SourceSide.CURRENT,
                "",
                files = listOf("LinkedFirst.java", "LinkedSecond.java"),
            )
            val data = ContextData().put(
                ContextKeys.LINKED_FILES,
                listOf(
                    LinkedFile("LinkedFirst.java", first.path, 50..50),
                    LinkedFile("LinkedSecond.java", second.path, 65..65),
                ),
            )
            val linkedCanvas = ContextAction.EP_NAME.extensionList.first { it.id == "explore.linkedCanvas" }
            linkedCanvas.perform(ContextRequest(project, change, listOf(change), ContextOrigin.OTHER, data, null))
            PlatformTestUtil.waitWithEventsDispatching("Linked files canvas tab should open and load", {
                val tab = manager.openFiles.filterIsInstance<CanvasSessionFile>().firstOrNull { it.source == first }
                    ?: return@waitWithEventsDispatching false
                activeCanvasSessionPanel(project, tab)?.ready == true
            }, 30)
            val tab = manager.openFiles.filterIsInstance<CanvasSessionFile>().single { it.source == first }
            val panel = activeCanvasSessionPanel(project, tab)!!
            panel.setSize(1200, 600)
            repeat(2) { relayout(panel) }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            val rootPane = panel.cardViews.getValue(CANVAS_ROOT).sourcePane!!
            assertEquals(49, rootPane.editor.document.getLineNumber(rootPane.pickedRange!!.first))
            val childView = panel.cardViews.values.single { it.card.filePath.endsWith("LinkedSecond.java") }
            childView.materializeSource()
            repeat(2) { relayout(panel) }
            val childPane = childView.sourcePane!!
            assertEquals(64, childPane.editor.document.getLineNumber(childPane.pickedRange!!.first))
        } finally {
            manager.openFiles.filterIsInstance<CanvasSessionFile>().forEach(manager::closeFile)
        }
    }
}
