package dev.insight.canvas

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.FileEditorManagerTestCase
import com.intellij.testFramework.PlatformTestUtil
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
}
