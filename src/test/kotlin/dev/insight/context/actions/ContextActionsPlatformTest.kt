package dev.insight.context.actions

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.context.ContextAction
import dev.insight.context.ContextActionRegistry
import dev.insight.context.ContextKeys
import dev.insight.context.ContextOrigin
import dev.insight.context.ContextRequest
import dev.insight.context.ContextScopeKind
import dev.insight.context.ContextData
import dev.insight.context.ContextPick
import dev.insight.context.editorPick
import dev.insight.context.toMarkdown
import java.awt.datatransfer.DataFlavor

/** The built-in context actions registered through the extension point, run through [ContextAction.perform]. */
class ContextActionsPlatformTest : BasePlatformTestCase() {
    private val source = """
        class Outer {
            int value;

            void render(int x) {
                int y = <caret>x + 1;
                value = y;
            }
        }
    """.trimIndent()

    private fun pick(): ContextPick {
        val file = myFixture.configureByText("Outer.java", source)
        return editorPick(project, ContextOrigin.EDITOR, myFixture.editor, file.virtualFile, file)
    }

    private fun request(pick: ContextPick, kind: ContextScopeKind, symbolName: String? = null): ContextRequest {
        val context = pick.candidates.first { it.kind == kind && (symbolName == null || it.symbol?.name == symbolName) }
        return ContextRequest(project, context, pick.candidates, pick.origin, pick.data, null)
    }

    private fun action(id: String) = ContextAction.EP_NAME.extensionList.first { it.id == id }

    fun testEveryBuiltInActionIsRegisteredWithAUniqueId() {
        val ids = ContextAction.EP_NAME.extensionList.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        assertTrue(ids.toString(), ids.containsAll(listOf("open.editor", "open.diff", "explore.canvas", "explore.callGraph", "explore.flowChart",
            "explore.sideBySide", "explore.summaryTree", "ask.chat", "copy.markdown")))
        for (action in ContextAction.EP_NAME.extensionList) {
            assertTrue(action.id, action.title.isNotBlank() && !action.title.startsWith("!"))
            assertTrue(action.id, action.group in listOf("Open", "Explore", "Ask", "Copy"))
        }
    }

    fun testRegistryOffersTheActionsOfAFunctionSymbol() {
        val request = request(pick(), ContextScopeKind.SYMBOL, "render")
        val ids = ContextActionRegistry.getInstance().actions(request).map { it.id }
        assertEquals(setOf("open.editor", "explore.canvas", "explore.callGraph", "explore.flowChart", "explore.sideBySide", "ask.chat", "copy.markdown"),
            ids.toSet())
    }

    fun testCopyPutsTheMarkdownOnTheClipboard() {
        val request = request(pick(), ContextScopeKind.SYMBOL, "render")
        action("copy.markdown").perform(request)
        val copied = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
        assertEquals(request.context.toMarkdown(), copied)
        assertTrue(copied!!.contains("void render(int x)"))
    }

    fun testOpenInEditorSelectsTheRange() {
        val pick = pick()
        val request = request(pick, ContextScopeKind.SYMBOL, "render")
        // Move away so that opening has something to do.
        myFixture.editor.caretModel.moveToOffset(0)
        myFixture.editor.selectionModel.removeSelection()
        action("open.editor").perform(request)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        val document = editor.document
        assertEquals(document.getLineStartOffset(3), editor.selectionModel.selectionStart)
        assertEquals(document.getLineEndOffset(6), editor.selectionModel.selectionEnd)
        assertEquals(3, document.getLineNumber(editor.caretModel.offset))
    }

    fun testOpenInEditorFindsAProjectFileWithoutAHostFile() {
        val pick = pick()
        val request = request(pick, ContextScopeKind.LINE)
        val data = ContextData()
        assertNull(data[ContextKeys.VIRTUAL_FILE])
        val bare = ContextRequest(project, request.context, request.candidates, ContextOrigin.CODE_WALL, data, null)
        // The path is project-relative to the in-memory root, so only the absolute path resolves it.
        assertTrue(action("open.editor").isAvailable(bare))
        action("open.editor").perform(bare)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(4, editor.document.getLineNumber(editor.selectionModel.selectionStart))
    }
}
