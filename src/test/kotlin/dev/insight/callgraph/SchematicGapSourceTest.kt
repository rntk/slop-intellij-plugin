package dev.insight.callgraph

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SchematicGapSourceTest : BasePlatformTestCase() {
    fun `test Rust struct closing gap is skipped while attributes remain meaningful`() {
        val file = myFixture.configureByText("file.rs", "struct WorkspacesFile {\n version: u32,\n #[serde(default)]\n workspaces: Vec<String>,\n}\n\n")
        val schematic = Schematic(0, file.virtualFile, 0, file.text.split('\n'), emptyList())
        assertTrue(schematicGapSource(project, schematic, CodeRow(4, 5)).skipSummary)
        val attribute = schematicGapSource(project, schematic, CodeRow(2, 2))
        assertFalse(attribute.skipSummary)
        val prompt = gapPrompt(attribute, CodeRow(2, 2))
        assertTrue(prompt.substringAfter("</target>").contains("workspaces: Vec&lt;String&gt;"))
    }

    fun `test closing braces skip but expressions comments and unknown syntax do not`() {
        val file = myFixture.configureByText("Example.java", "class Example {\n void run() {\n  int x = 1;\n  x++;\n  // explanation\n }\n}\n")
        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        fun range(line: Int) = TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))
        assertTrue(structuralGap(file, range(5)))
        assertTrue(structuralGap(file, range(6)))
        for (line in 0..4) assertFalse(structuralGap(file, range(line)))
        val unknown = myFixture.configureByText("unknown.txt", "}")
        assertFalse(structuralGap(unknown, unknown.textRange))
    }

    fun `test source retains only relevant facts and skips before collecting them`() {
        val file = myFixture.configureByText("Example.java", "class Example {\n int selected;\n void run() {\n  selected++;\n }\n void unrelated(int noise) {}\n}\n")
        val lines = file.text.split('\n')
        val schematic = Schematic(0, file.virtualFile, 0, lines, emptyList())
        val source = schematicGapSource(project, schematic, CodeRow(3, 3))
        assertFalse(source.skipSummary)
        assertTrue(source.facts.contains("run"))
        assertTrue(source.facts.contains("selected"))
        assertFalse(source.facts.contains("unrelated"))
        assertFalse(source.facts.contains("noise"))
        assertFalse(source.facts.contains("Impl"))
        val structural = schematicGapSource(project, schematic, CodeRow(4, 4))
        assertTrue(structural.skipSummary)
        assertEquals("", structural.facts)
    }

    fun `test gap without an enclosing declaration does not crash on the file parent`() {
        // The PsiFile's parent is a PsiDirectory, whose textRange is null; the owner walk must stop safely.
        val file = myFixture.configureByText("data.txt", "alpha\nbeta\n")
        val schematic = Schematic(0, file.virtualFile, 0, file.text.split('\n'), emptyList())
        val source = schematicGapSource(project, schematic, CodeRow(0, 0))
        assertFalse(source.skipSummary)
        assertEquals(file.text.split('\n'), source.lines)
    }

    fun `test stale schematic is rejected before classifying structural gaps`() {
        val file = myFixture.configureByText("Example.java", "class Example {\n}\n")
        val schematic = Schematic(0, file.virtualFile, 0, listOf("class Old {", "}", ""), emptyList())
        try {
            schematicGapSource(project, schematic, CodeRow(1, 1))
            fail("Stale source must be rejected")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("Source changed"))
        }
    }
}
