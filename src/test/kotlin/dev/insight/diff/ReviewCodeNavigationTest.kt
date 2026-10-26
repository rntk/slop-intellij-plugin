package dev.insight.diff

import com.intellij.diff.tools.fragmented.UnifiedDiffTool
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.simple.SimpleDiffTool
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.FileEditorManagerTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import dev.insight.review.ReviewCodeLocation
import dev.insight.review.ReviewCodeNavigation

class ReviewCodeNavigationTest : FileEditorManagerTestCase() {
    fun testSnapshotValidationIncludesUnsavedEditsAndInvalidCoordinates() {
        val file = myFixture.addFileToProject("src/read.txt", "heading\n\ttarget()\nlast\n").virtualFile
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        val full = ReviewCodeLocation("src/read.txt", document.text, 2, 1, file.path)
        assertEquals(file to document.text.indexOf("target"), full.resolve(project))
        assertNull(full.copy(line = 5).resolve(project))
        assertNull(full.copy(column = 99).resolve(project))
        assertNull(full.copy(path = "missing.txt", absolutePath = null).resolve(project))
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "new line\n") }
        assertNull(full.resolve(project))
        val actions = ReviewCodeNavigation.actions(project) { full }
        for (action in actions) {
            val event = TestActionEvent.createTestEvent(action)
            action.update(event)
            assertFalse(event.presentation.isEnabled)
            action.actionPerformed(event)
        }
        assertEmpty(FileEditorManager.getInstance(project).openFiles.toList())
    }

    fun testSnippetRequiresMatchingWholeFileEvenWhenItsDisplayedLinesMatch() {
        val before = "old heading\n\ttarget()\n"
        val after = "new heading\n\ttarget()\n"
        val source = myFixture.addFileToProject("src/read.txt", after)
        val file = ReviewFile("src/read.txt", source.virtualFile.path, null, "modified", "", "v1", before, after)
        for (old in listOf(false, true)) {
            val range = dev.insight.review.ReviewSourceRange(file.path, 2, 2, if (old) "before" else "after")
            val block = resolveSourceBlock(ReviewSnapshot(listOf(file), emptyList()), range)
            val view = DiffSourceEditor(block, project)
            try {
                assertEquals("\ttarget()", block.text)
                val location = view.locationAt(0, 1)!!
                assertEquals(2, location.line)
                if (old) assertNull(location.resolve(project))
                else assertEquals(source.virtualFile to after.indexOf("target"), location.resolve(project))
                assertNull(view.locationAt(1, 1))
            } finally { view.dispose() }
        }
    }

    fun testPaintedTabsMapToSourceCharactersAndExcludeGuttersAndEmptySpace() {
        val text = "\t\ttarget()"
        fun column(x: Int) = ReviewCodeNavigation.columnAt(text, x) { it.length * 10 }
        assertNull(column(-1))
        assertEquals(0, column(39))
        assertEquals(1, column(40))
        assertEquals(2, column(80))
        assertEquals(3, column(90))
        assertNull(column(160))
    }

    fun testGoToDeclarationDelegatesWithTheRealProjectEditorAndSymbol() {
        val source = myFixture.addFileToProject("src/Caller.java", "class Caller { void target() {} void call() { target(); } }")
        val text = source.text
        val location = ReviewCodeLocation("src/Caller.java", text, 1, text.lastIndexOf("target"), source.virtualFile.path)
        val action = ReviewCodeNavigation.actions(project) { location }.first()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertTrue(event.presentation.isEnabled)
        action.actionPerformed(event)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(source.virtualFile, FileDocumentManager.getInstance().getFile(editor.document))
        assertEquals(text.indexOf("target"), editor.caretModel.offset)
    }

    fun testFindUsagesUsesTheNativeResultsView() {
        val source = myFixture.addFileToProject("src/Caller.java", "class Caller { void call() { int value = 0; value++; value++; } }")
        val text = source.text
        val location = ReviewCodeLocation("src/Caller.java", text, 1, text.indexOf("value"), source.virtualFile.path)
        val action = ReviewCodeNavigation.actions(project) { location }.last()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertTrue(event.presentation.isEnabled)
        action.actionPerformed(event)
        val manager = com.intellij.usages.UsageViewManager.getInstance(project)
        PlatformTestUtil.waitWithEventsDispatching("Native Find Usages results", {
            manager.selectedUsageView?.usages?.size == 2
        }, 10)
        assertEquals(2, manager.selectedUsageView!!.usages.size)
    }

    fun testNativeDiffNavigationMapsBothLayoutsAndRejectsTheHistoricalSide() {
        val before = "class C {\n\tvoid oldName() {}\n}\n"
        val after = "class C {\n\tvoid newName() {}\n}\n"
        val source = myFixture.addFileToProject("src/C.java", after)
        val file = ReviewFile("src/C.java", source.virtualFile.path, null, "modified", "", "v1", before, after)
        val embedded = DiffFileEditor(file, project)
        val switch = com.intellij.diff.impl.DiffRequestProcessor::class.java
            .getDeclaredMethod("switchToDiffTool", com.intellij.diff.DiffTool::class.java).apply { isAccessible = true }
        try {
            for (tool in listOf(SimpleDiffTool.INSTANCE, UnifiedDiffTool.INSTANCE)) {
                switch.invoke(embedded.panel, tool)
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                val viewer = embedded.panel.activeViewer!!
                if (viewer is UnifiedDiffViewer) viewer.rediff(true)
                for (old in listOf(false, true)) {
                    val editor = when (viewer) {
                        is SimpleDiffViewer -> if (old) viewer.editor1 else viewer.editor2
                        is UnifiedDiffViewer -> viewer.editor
                        else -> error("Unexpected viewer")
                    }
                    val needle = if (old) "oldName" else "newName"
                    editor.caretModel.moveToOffset(editor.document.text.indexOf(needle))
                    // Match the action system's UI snapshot, which includes UiDataProvider parents.
                    val context = com.intellij.openapi.actionSystem.impl.Utils.createAsyncDataContext(editor.contentComponent)
                    val event = TestActionEvent.createTestEvent(context)
                    assertNotNull("Request in ${viewer.javaClass.simpleName}", event.getData(com.intellij.diff.tools.util.DiffDataKeys.DIFF_REQUEST))
                    assertNotNull("Editor in ${viewer.javaClass.simpleName}", event.getData(CommonDataKeys.EDITOR))
                    assertNotNull("Viewer in ${viewer.javaClass.simpleName}", event.getData(com.intellij.diff.tools.util.DiffDataKeys.DIFF_VIEWER))
                    val location = ReviewDiffNavigationGroup.location(event)!!
                    assertEquals(2, location.line)
                    assertEquals(6, location.column)
                    assertEquals(if (old) before else after, location.text)
                    if (old) assertNull(location.resolve(project))
                    else assertEquals(source.virtualFile to after.indexOf(needle), location.resolve(project))
                    val group = ActionManager.getInstance().getAction("Insight.ReviewDiffNavigation") as ReviewDiffNavigationGroup
                    group.update(event)
                    assertTrue(event.presentation.isVisible)
                    val action = group.getChildren(event).first()
                    action.update(event)
                    assertEquals(!old, event.presentation.isEnabled)
                }
            }
        } finally { Disposer.dispose(embedded) }
    }
}
