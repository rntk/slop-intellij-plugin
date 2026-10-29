package dev.insight.chat

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.testFramework.FileEditorManagerTestCase
import dev.insight.agent.TraceDocument
import dev.insight.agent.TraceKey

class FileTraceNavigatorTest : FileEditorManagerTestCase() {
    fun `test navigation acquires read access from an unlocked EDT callback`() {
        val source = myFixture.addFileToProject("src/read.txt", "first\nsecond\nthird\n").virtualFile
        val navigator = FileTraceNavigator(project)
        val manager = FileEditorManager.getInstance(project)
        val application = ApplicationManager.getApplication()
        for (kind in listOf("Source", "Before")) {
            val trace = TraceDocument(TraceKey("src/read.txt", kind), "first\nsecond\nthird\n")
            // Platform tests normally hold a write-intent lock; real Swing callbacks may not.
            IdeEventQueue.getInstance().threadingSupport
                .releaseTheAcquiredWriteIntentLockThenExecuteActionAndTakeWriteIntentLockBack {
                    application.assertIsDispatchThread()
                    assertFalse("The link callback has no implicit read access", application.isReadAccessAllowed)
                    navigator.open(trace, 2..3)
                }
            val file = manager.openFiles.single()
            if (kind == "Source") assertEquals(source, file) else assertFalse(file.isWritable)
            assertEquals("second\nthird", manager.selectedTextEditor!!.selectionModel.selectedText)
            manager.closeFile(file)
        }
    }

    fun `test unchanged source opens the file with the clicked range selected`() {
        val source = myFixture.addFileToProject("src/read.txt", "first\nsecond\nthird\nfourth\n").virtualFile
        val text = FileDocumentManager.getInstance().getDocument(source)!!.text
        val trace = TraceDocument(TraceKey("src/read.txt", "Source"), text)
        val navigator = FileTraceNavigator(project)
        val manager = FileEditorManager.getInstance(project)
        navigator.open(trace, 2..3)
        assertEquals(listOf(source), manager.openFiles.toList())
        val editor = manager.selectedTextEditor!!
        assertEquals("second\nthird", editor.selectionModel.selectedText)
        assertEquals(1, editor.caretModel.logicalPosition.line)
        navigator.open(trace, 4..4)
        assertEquals(1, manager.openFiles.size)
        assertEquals("fourth", editor.selectionModel.selectedText)
    }

    fun `test changed and removed files open a reusable read only snapshot`() {
        val source = myFixture.addFileToProject("src/read.txt", "first\nsecond\nthird\n").virtualFile
        val live = FileDocumentManager.getInstance().getDocument(source)!!
        val text = live.text
        WriteCommandAction.runWriteCommandAction(project) { live.setText("edited\n") }
        val trace = TraceDocument(TraceKey("src/read.txt", "Source"), text)
        val navigator = FileTraceNavigator(project)
        val manager = FileEditorManager.getInstance(project)
        navigator.open(trace, 2..3)
        val snapshot = manager.openFiles.single()
        assertNotSame(source, snapshot)
        assertFalse(snapshot.isWritable)
        assertEquals(true, snapshot.getUserData(FileEditorManagerKeys.FORBID_PREVIEW_TAB))
        assertEquals(text, manager.selectedTextEditor!!.document.text)
        assertEquals("second\nthird", manager.selectedTextEditor!!.selectionModel.selectedText)
        navigator.open(trace, 1..1)
        assertEquals(listOf(snapshot), manager.openFiles.toList())
        assertEquals("first", manager.selectedTextEditor!!.selectionModel.selectedText)
        manager.closeFile(snapshot)
        WriteCommandAction.runWriteCommandAction(project) { source.delete(this) }
        navigator.open(trace, 3..3)
        assertEquals(listOf(snapshot), manager.openFiles.toList())
        assertEquals("third", manager.selectedTextEditor!!.selectionModel.selectedText)
    }

    fun `test before and diff ranges use their own snapshot even when live content matches`() {
        val source = myFixture.addFileToProject("src/read.txt", "first\nsecond\n").virtualFile
        val navigator = FileTraceNavigator(project)
        val manager = FileEditorManager.getInstance(project)
        for (kind in listOf("Before", "Diff")) {
            val trace = TraceDocument(TraceKey("src/read.txt", kind), "first\nsecond\n")
            navigator.open(trace, 2..2)
            val snapshot = manager.openFiles.single()
            assertNotSame(source, snapshot)
            assertTrue(snapshot.name.contains(kind.lowercase()))
            assertEquals("second", manager.selectedTextEditor!!.selectionModel.selectedText)
            manager.closeFile(snapshot)
        }
        navigator.open(TraceDocument(TraceKey("src/read.txt", "After"), "first\nsecond\n"), 1..1)
        assertEquals(listOf(source), manager.openFiles.toList())
    }
}
