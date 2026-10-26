package dev.insight

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.SmartPointerManager
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.insight.callgraph.*
import javax.swing.JButton
import java.awt.Container

class SourceCallChainEditorTest : LightJavaCodeInsightFixtureTestCase() {
    fun `test source columns use live documents and native highlighting and dispose editors`() {
        val psiFile = myFixture.configureByText("Flow.java", """
            class Flow {
                void entry() { branch(); }
                void branch() { leaf(); }
                void leaf() {}
            }
        """.trimIndent())
        val methods = (psiFile as com.intellij.psi.PsiJavaFile).classes.single().methods
        val pointers = SmartPointerManager.getInstance(project)
        val graph = CallGraph(methods.mapIndexed { id, method -> CallGraphNode(id, method.name, "Flow", "Flow.java", id, true) },
            setOf(CallGraphEdge(0, 1), CallGraphEdge(1, 2)), false)
        val result = CallGraphResult(graph, methods.map(pointers::createSmartPsiElementPointer))
        val file = SourceCallChainFile(result, graph.hierarchy().sourceCallChain(0), "entry")
        val factory = EditorFactory.getInstance()
        val existing = factory.allEditors.toSet()
        val provider = SourceCallChainEditorProvider()
        assertTrue(provider.accept(project, file))
        val editor = provider.createEditor(project, file)
        try {
            val viewers = factory.allEditors.filter { it !in existing }
            assertTrue(viewers.isNotEmpty())
            val document = FileDocumentManager.getInstance().getDocument(psiFile.virtualFile)
            viewers.forEach { viewer ->
                assertTrue(viewer.isViewer)
                assertSame(document, viewer.document)
                assertTrue(viewer.settings.isLineNumbersShown)
                assertTrue(viewer.markupModel.allHighlighters.any { it.getTextAttributes(EditorColorsManager.getInstance().globalScheme)?.backgroundColor != null })
                assertTrue(viewer.highlighter.createIterator(0).tokenType != null)
            }
            fun buttons(component: Container): List<JButton> = component.components.flatMap {
                when (it) {
                    is JButton -> listOf(it)
                    is Container -> buttons(it)
                    else -> emptyList()
                }
            }
            val links = buttons(editor.component).filter { it.text.startsWith("Calls →") }
            assertEquals(listOf("Calls → 2. branch", "Calls → 3. leaf"), links.map { it.text })
            links.first().doClick()
        } finally {
            editor.dispose()
        }
        assertEquals(existing, factory.allEditors.toSet())
    }

    fun `test callers are columns before the selected function with called by links`() {
        val psiFile = myFixture.configureByText("Flow.java", """
            class Flow {
                void entry() { branch(); }
                void branch() { leaf(); }
                void leaf() {}
            }
        """.trimIndent())
        val methods = (psiFile as com.intellij.psi.PsiJavaFile).classes.single().methods
        val pointers = SmartPointerManager.getInstance(project)
        val graph = CallGraph(methods.mapIndexed { id, method -> CallGraphNode(id, method.name, "Flow", "Flow.java", id, true) },
            setOf(CallGraphEdge(0, 1), CallGraphEdge(1, 2)), false)
        val result = CallGraphResult(graph, methods.map(pointers::createSmartPsiElementPointer))
        val editor = SourceCallChainEditorProvider().createEditor(project, SourceCallChainFile(result, graph.hierarchy().sourceCallChain(1), "branch"))
        try {
            fun buttons(component: Container): List<JButton> = component.components.flatMap {
                when (it) {
                    is JButton -> listOf(it)
                    is Container -> buttons(it)
                    else -> emptyList()
                }
            }
            val texts = buttons(editor.component).map { it.text }
            assertEquals(listOf("← Called by 1. entry", "← Called by 2. branch"), texts.filter { it.startsWith("← Called by") })
            assertEquals(listOf("Calls → 2. branch", "Calls → 3. leaf"), texts.filter { it.startsWith("Calls →") })
            assertEquals("2. branch", (editor.preferredFocusedComponent as javax.swing.JComboBox<*>).selectedItem)
        } finally {
            editor.dispose()
        }
    }

    fun `test more links add columns next to the function and close removes them`() {
        val psiFile = myFixture.configureByText("Flow.java", """
            class Flow {
                void entry() { branch(); }
                void branch() { leaf(); }
                void leaf() {}
            }
        """.trimIndent())
        val methods = (psiFile as com.intellij.psi.PsiJavaFile).classes.single().methods
        val pointers = SmartPointerManager.getInstance(project)
        val graph = CallGraph(methods.mapIndexed { id, method -> CallGraphNode(id, method.name, "Flow", "Flow.java", id, true) },
            setOf(CallGraphEdge(0, 1), CallGraphEdge(1, 2)), false)
        val result = CallGraphResult(graph, methods.map(pointers::createSmartPsiElementPointer))
        val factory = EditorFactory.getInstance()
        val existing = factory.allEditors.toSet()
        val editor = SourceCallChainEditorProvider().createEditor(project, SourceCallChainFile(result, graph.hierarchy().sourceCallChain(0, limit = 1), "entry"))
        try {
            fun buttons(component: Container): List<JButton> = component.components.flatMap {
                when (it) {
                    is JButton -> listOf(it)
                    is Container -> buttons(it)
                    else -> emptyList()
                }
            }
            assertEquals(listOf("Calls → 2. branch"), buttons(editor.component).map { it.text }.filter { it.startsWith("Calls →") })
            buttons(editor.component).single { it.text == "+ Show 1 more called functions" }.doClick()
            assertEquals(listOf("Calls → 2. branch", "Calls → 3. leaf"), buttons(editor.component).map { it.text }.filter { it.startsWith("Calls →") })
            assertEquals(3, (editor.preferredFocusedComponent as javax.swing.JComboBox<*>).itemCount)
            buttons(editor.component).filter { it.text == "Close" }.last().doClick()
            assertEquals(2, (editor.preferredFocusedComponent as javax.swing.JComboBox<*>).itemCount)
            assertTrue(buttons(editor.component).any { it.text == "+ Show 1 more called functions" })
        } finally {
            editor.dispose()
        }
        assertEquals(existing, factory.allEditors.toSet())
    }

    fun `test missing source renders without creating an editor`() {
        val graph = CallGraph(listOf(CallGraphNode(0, "deleted", "", "", 0, true)), emptySet(), false)
        val file = SourceCallChainFile(CallGraphResult(graph, listOf(null)), graph.hierarchy().sourceCallChain(0), "deleted")
        val factory = EditorFactory.getInstance()
        val before = factory.allEditors.toSet()
        val editor = SourceCallChainEditorProvider().createEditor(project, file)
        try { assertEquals(before, factory.allEditors.toSet()) } finally { editor.dispose() }
    }
}
