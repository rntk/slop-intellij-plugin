package dev.insight.chat

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.FileEditorManagerTestCase
import java.awt.Container
import javax.swing.JTextArea

class SourcePointsEditorTest : FileEditorManagerTestCase() {
    fun `test calls append independent editors and comments even for the same file and line`() {
        val points = SourcePoints(project, "Test")
        val manager = FileEditorManager.getInstance(project)
        try {
            val text = "first\nsecond\nthird\n"
            points.show(SourcePoint("src/a.kt", 2, comment = "First comment"), text)
            points.show(SourcePoint("src/a.kt", 2, 3, "Second comment"), text)
            val fileEditor = manager.getEditors(points.file).single()
            val editors = viewers().filter { it.document.text == text }
            assertEquals(2, editors.size)
            assertEquals(listOf(1, 1), editors.map { it.caretModel.logicalPosition.line })
            assertTrue(editors.all { it.isViewer && it.settings.isLineNumbersShown })
            assertEquals(listOf(1, 2), editors.map { it.markupModel.allHighlighters.single().let { mark ->
                assertEquals(HighlighterTargetArea.LINES_IN_RANGE, mark.targetArea)
                assertNotNull(mark.getTextAttributes(EditorColorsManager.getInstance().globalScheme)?.backgroundColor)
                it.document.getLineNumber(mark.endOffset)
            } })
            assertEquals(listOf("First comment", "Second comment"), descendants(fileEditor.component).filterIsInstance<JTextArea>().map { it.text })

            // Closing releases native editors; opening again reconstructs every column from its snapshot.
            manager.closeFile(points.file)
            assertTrue(editors.all { it.isDisposed })
            assertEquals(2, points.points.size)
            manager.openFile(points.file, false)
            assertEquals(2, viewers().count { it.document.text == text })
            points.clear()
            assertFalse(manager.isFileOpen(points.file))
            assertTrue(points.points.isEmpty())
            assertTrue(viewers().none { it.document.text == text })
        } finally { points.dispose() }
        assertFalse(SourcePointsEditorProvider().accept(project, points.file))
    }

    fun `test invalid ranges do not open or append and snapshot documents normalize newlines`() {
        val points = SourcePoints(project, "Test")
        try {
            for (point in listOf(SourcePoint("a.kt", 0), SourcePoint("a.kt", 3), SourcePoint("a.kt", 2, 1))) {
                try { points.show(point, "one\r\ntwo"); fail(point.toString()) } catch (_: ToolInputException) { }
            }
            assertTrue(points.points.isEmpty())
            assertFalse(FileEditorManager.getInstance(project).isFileOpen(points.file))
            points.show(SourcePoint("a.kt", 2), "one\r\ntwo")
            assertEquals("one\ntwo", points.points.single().text)
            assertEquals("one\ntwo", viewers().single { it.document.text == "one\ntwo" }.document.text)
            assertTrue(descendants(FileEditorManager.getInstance(project).getEditors(points.file).single().component).none { it is JTextArea })
        } finally { points.dispose() }
    }

    fun `test end beyond EOF highlights available lines and reports the actual range`() {
        val points = SourcePoints(project, "Test")
        try {
            val result = points.show(SourcePoint("flows/code-review.md", 1, 200), "one\ntwo")
            assertTrue(result, result.startsWith("Showing flows/code-review.md:1-2 "))
            assertEquals(2, points.points.single().point.endLine)
            val editor = viewers().single { it.document.text == "one\ntwo" }
            assertEquals(1, editor.document.getLineNumber(editor.markupModel.allHighlighters.single().endOffset))
        } finally { points.dispose() }
    }

    private fun viewers() = EditorFactory.getInstance().allEditors.filterIsInstance<EditorEx>().filter { it.project == project && !it.isDisposed }
    private fun descendants(container: Container): List<java.awt.Component> = container.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
}
