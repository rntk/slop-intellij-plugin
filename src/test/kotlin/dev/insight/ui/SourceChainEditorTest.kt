package dev.insight.ui

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.FileEditorManagerTestCase
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container
import java.awt.Point
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.JTextArea

class SourceChainEditorTest : FileEditorManagerTestCase() {
    private fun descendants(component: Component): List<Component> = listOf(component) +
        (component as? Container)?.components.orEmpty().flatMap(::descendants)

    private fun layout(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach(::layout)
        }
    }

    fun testProviderOpensRepeatedFilesAtIndependentPositionsUsingUnsavedDocumentsAndReleasesEditors() {
        val source = myFixture.configureByText("Flow.java", "class Flow {\n void entry() {}\n void step() {}\n}").virtualFile
        val document = FileDocumentManager.getInstance().getDocument(source)!!
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "\n// unsaved") }
        val before = EditorFactory.getInstance().allEditors.toSet()
        val manager = FileEditorManager.getInstance(project)
        openSourceChain(project, listOf(
            SourceChainLocation("Flow.java", "entry", 1, file = source, summary = "Calls <step>.\nKeeps the flow moving."),
            SourceChainLocation("Flow.java", "step", 2, 3, source)), "entry → step")
        val file = manager.openFiles.filterIsInstance<SourceChainFile>().single()
        try {
            assertEquals("Side by side: entry → step", file.name)
            assertFalse(file.isWritable)
            val summaries = descendants(manager.getSelectedEditor(file)!!.component).filterIsInstance<JTextArea>()
                .filter { it.name == "source-chain-summary" }
            assertEquals(listOf("Calls <step>.\nKeeps the flow moving."), summaries.map { it.text })
            assertFalse(summaries.single().isEditable)
            assertTrue(summaries.single().lineWrap)
            val viewers = EditorFactory.getInstance().allEditors.filter { it !in before }
            assertEquals(2, viewers.size)
            assertEquals(listOf(1, 2), viewers.map { it.caretModel.logicalPosition.line }.sorted())
            viewers.forEach {
                assertTrue(it.isViewer)
                assertSame(document, it.document)
                assertTrue(it.document.text.endsWith("// unsaved"))
            }
            val ranged = viewers.single { it.caretModel.logicalPosition.line == 2 }
            assertTrue(ranged.markupModel.allHighlighters.any {
                it.startOffset == document.getLineStartOffset(2) && it.endOffset == document.getLineEndOffset(3)
            })
        } finally {
            manager.closeFile(file)
        }
        assertEquals(before, EditorFactory.getInstance().allEditors.toSet())
    }

    fun testMissingSourcesAndStaleLinesDoNotPreventOtherColumnsFromOpening() {
        val source = myFixture.configureByText("Short.java", "class Short {}").virtualFile
        val before = EditorFactory.getInstance().allEditors.toSet()
        val editor = SourceChainEditorProvider().createEditor(project, SourceChainFile(listOf(
            SourceChainLocation("gone.java", "gone", 50, summary = " \n"),
            SourceChainLocation("Short.java", "short", 500, 600, source)), "stale"))
        try {
            assertTrue(descendants(editor.component).filterIsInstance<JLabel>().any { it.text == "Source is no longer available." })
            assertFalse(descendants(editor.component).any { it.name == "source-chain-summary" })
            val viewer = EditorFactory.getInstance().allEditors.single { it !in before }
            assertEquals(0, viewer.caretModel.logicalPosition.line)
            val picker = descendants(editor.component).filterIsInstance<JComboBox<*>>().single()
            picker.selectedIndex = 1
            assertEquals(0, viewer.caretModel.logicalPosition.line)
        } finally {
            Disposer.dispose(editor)
        }
        assertEquals(before, EditorFactory.getInstance().allEditors.toSet())
    }

    fun testLongChainsLoadOnlyNearbyEditorsAndReleaseThemWhenMovingBetweenSteps() {
        val source = myFixture.configureByText("Many.java", (0..20).joinToString("\n") { "// line $it" }).virtualFile
        val before = EditorFactory.getInstance().allEditors.toSet()
        val editor = SourceChainEditorProvider().createEditor(project, SourceChainFile(
            (0..20).map { SourceChainLocation("Many.java", "Step $it", it, file = source) }, "long chain"))
        try {
            assertTrue(EditorFactory.getInstance().allEditors.count { it !in before } <= 3)
            editor.component.setSize(1100, 800)
            layout(editor.component)
            val picker = descendants(editor.component).filterIsInstance<JComboBox<*>>().single()
            picker.selectedIndex = 18
            val shown = EditorFactory.getInstance().allEditors.filter { it !in before }
            assertTrue(shown.any { it.caretModel.logicalPosition.line == 18 })
            assertTrue(shown.size <= 6)
            assertFalse(shown.any { it.caretModel.logicalPosition.line == 0 })
        } finally {
            Disposer.dispose(editor)
        }
        assertEquals(before, EditorFactory.getInstance().allEditors.toSet())
    }

    fun testEmptySourceDocumentsCanBeLoadedAndSelected() {
        val source = myFixture.configureByText("Empty.java", "").virtualFile
        val document = FileDocumentManager.getInstance().getDocument(source)!!
        assertEquals(0, document.lineCount)
        val before = EditorFactory.getInstance().allEditors.toSet()
        val editor = SourceChainEditorProvider().createEditor(project, SourceChainFile(listOf(
            SourceChainLocation("Empty.java", "first", 50, 60, source),
            SourceChainLocation("Empty.java", "second", 100, 110, source)), "empty"))
        try {
            val picker = descendants(editor.component).filterIsInstance<JComboBox<*>>().single()
            picker.selectedIndex = 1
            UIUtil.dispatchAllInvocationEvents()
            val viewers = EditorFactory.getInstance().allEditors.filter { it !in before }
            assertEquals(2, viewers.size)
            viewers.forEach {
                assertSame(document, it.document)
                assertEquals(0, it.caretModel.offset)
                assertEmpty(it.markupModel.allHighlighters.toList())
            }
        } finally {
            Disposer.dispose(editor)
        }
        assertEquals(before, EditorFactory.getInstance().allEditors.toSet())
    }

    fun testExplicitNavigationOverridesSavedScrollWhilePassiveLoadingRestoresIt() {
        val source = myFixture.configureByText("Scroll.java", (0..500).joinToString("\n") { "// line $it" }).virtualFile
        val before = EditorFactory.getInstance().allEditors.toSet()
        val editor = SourceChainEditorProvider().createEditor(project, SourceChainFile(
            (0..20).map { SourceChainLocation("Scroll.java", "Step $it", it + 10, file = source) }, "scroll"))
        try {
            editor.component.setSize(1100, 800)
            layout(editor.component)
            UIUtil.dispatchAllInvocationEvents()
            val picker = descendants(editor.component).filterIsInstance<JComboBox<*>>().single()
            val scroll = descendants(editor.component).filterIsInstance<JScrollPane>().first()
            fun firstViewer() = EditorFactory.getInstance().allEditors.single {
                it !in before && it.caretModel.logicalPosition.line == 10
            }
            val original = firstViewer()
            original.scrollingModel.disableAnimation()
            original.scrollingModel.scrollVertically(2000)
            val savedOffset = original.scrollingModel.verticalScrollOffset
            assertTrue(savedOffset > 1000)

            picker.selectedIndex = 18
            assertTrue(original.isDisposed)
            scroll.viewport.viewPosition = Point(0, 0)
            layout(editor.component)
            UIUtil.dispatchAllInvocationEvents()
            val restored = firstViewer()
            assertEquals(savedOffset, restored.scrollingModel.verticalScrollOffset)

            picker.selectedIndex = 18
            assertTrue(restored.isDisposed)
            picker.selectedIndex = 0
            layout(editor.component)
            UIUtil.dispatchAllInvocationEvents()
            val focused = firstViewer()
            assertTrue(focused.scrollingModel.verticalScrollOffset < savedOffset)
            assertTrue(focused.scrollingModel.visibleArea.contains(focused.offsetToXY(focused.caretModel.offset)))
        } finally {
            Disposer.dispose(editor)
        }
        assertEquals(before, EditorFactory.getInstance().allEditors.toSet())
    }
}
