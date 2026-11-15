package dev.insight.context

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.agent.SourceSide

/** The scopes of the regular editor entry: selection, line, enclosing symbols and file at the caret. */
class EditorPickTest : BasePlatformTestCase() {
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

    fun testCaretGivesLineSymbolsAndFileNarrowToWide() {
        val pick = pick()
        assertEquals(listOf(ContextScopeKind.LINE, ContextScopeKind.SYMBOL, ContextScopeKind.SYMBOL, ContextScopeKind.FILE), pick.candidates.map { it.kind })
        assertEquals(listOf("Line 5", "Function render", "Class Outer"), pick.candidates.take(3).map { it.label })
        assertEquals("int y = x + 1;", pick.candidates[0].text.trim())
        assertEquals(4..7, pick.candidates[1].lines)
        assertEquals(1, pick.defaultIndex)
        assertTrue(pick.candidates.all { it.path!!.endsWith("Outer.java") && it.side == SourceSide.CURRENT })
        assertEquals(myFixture.editor.document.text, pick.candidates.last().text)
        assertEquals(ContextOrigin.EDITOR, pick.origin)
    }

    fun testDataCarriesFileEditorAndOffset() {
        val pick = pick()
        assertEquals(myFixture.file.virtualFile, pick.data[ContextKeys.VIRTUAL_FILE])
        assertSame(myFixture.file, pick.data[ContextKeys.PSI_FILE])
        assertSame(myFixture.editor, pick.data[ContextKeys.EDITOR])
        assertEquals(myFixture.caretOffset, pick.data[ContextKeys.OFFSET])
    }

    fun testSelectionComesFirstAndIsTheDefault() {
        val file = myFixture.configureByText("Outer.java", source)
        val document = myFixture.editor.document
        myFixture.editor.selectionModel.setSelection(document.getLineStartOffset(4), document.getLineStartOffset(6))
        val pick = editorPick(project, ContextOrigin.EDITOR, myFixture.editor, file.virtualFile, file)
        val first = pick.candidates.first()
        assertEquals(ContextScopeKind.SELECTION, first.kind)
        // The selection ends where line 7 starts, so it covers lines 5 and 6 only.
        assertEquals(5..6, first.lines)
        assertEquals(0, pick.defaultIndex)
    }

    fun testWithoutPsiOnlyTextScopesRemain() {
        val file = myFixture.configureByText("notes.txt", "one\ntwo\n")
        myFixture.editor.caretModel.moveToOffset(5)
        val pick = editorPick(project, ContextOrigin.IDE_DIFF, myFixture.editor, file.virtualFile, null)
        assertEquals(listOf(ContextScopeKind.LINE, ContextScopeKind.FILE), pick.candidates.map { it.kind })
        assertEquals("two", pick.candidates[0].text)
        assertNull(pick.data[ContextKeys.PSI_FILE])
        assertEquals(ContextOrigin.IDE_DIFF, pick.origin)
    }
}
