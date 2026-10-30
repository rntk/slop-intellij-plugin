package dev.insight.callgraph

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SchematicGapSourceTest : BasePlatformTestCase() {
    private fun configure(name: String, text: String): Pair<PsiFile, com.intellij.openapi.editor.Document> {
        val file = myFixture.configureByText(name, text)
        return file to PsiDocumentManager.getInstance(project).getDocument(file)!!
    }

    /** The outermost element below the file starting at [marker], such as a function to use as a scope. */
    private fun outermost(file: PsiFile, marker: String): PsiElement {
        val offset = file.text.indexOf(marker)
        return generateSequence(file.findElementAt(offset)) { it.parent }.takeWhile { it !is PsiFile }.last { it.textRange.startOffset == offset }
    }

    fun `test Go condition is read whole with its call while plain statements stay apart`() {
        val (file, document) = configure("main.go",
            "package main\n\nfunc run() error {\n\terr := load()\n\tif err != nil {\n\t\treturn wrap(err)\n\t}\n\treturn nil\n}\n")
        val scope = outermost(file, "func run")
        assertEquals(GapConstruct(4, 6, 4), gapConstructs(scope, document, 4).first())
        val rows = listOf(CodeRow(2, 4), BlockRow(5, 5, 5, null, listOf(1)), CodeRow(6, 8))
        assertEquals(listOf(GapUnit(2, 3), GapUnit(4, 6, symbols = listOf(5)), GapUnit(7, 7)), schematicGapUnits(scope, document, rows))
    }

    fun `test Rust enum is one construct while its later variants are not`() {
        val (file, document) = configure("lib.rs", "pub(crate) enum Outcome {\n    Done(Report),\n    Failed { reason: String },\n}\n")
        assertEquals(GapConstruct(0, 3, 0), gapConstructs(file, document, 0).first())
        assertEquals(GapConstruct(0, 3, 0), gapConstructs(file, document, 2).first())
        val rows = listOf(CodeRow(0, 0), BlockRow(1, 1, 1, null, emptyList()), CodeRow(2, 4))
        assertEquals(listOf(GapUnit(0, 3, symbols = listOf(1))), schematicGapUnits(file, document, rows))
    }

    fun `test Java arguments are read with their statement and long constructs by their header`() {
        val (file, document) = configure("Example.java",
            "class Example {\n  void run() {\n    call(\n      first,\n      second);\n  }\n}\n")
        assertEquals(GapConstruct(2, 4), gapConstructs(file, document, 3).first())
        assertEquals(listOf(GapConstruct(0, 6, 0)), gapConstructs(file, document, 0))
        // Too long for the budget, the class and method are read by their headers and the statement whole.
        assertEquals(listOf(GapUnit(0, 0, construct = 0..6), GapUnit(1, 1, construct = 1..5), GapUnit(2, 4)),
            schematicGapUnits(file, document, listOf(CodeRow(0, 7)), GapBudget(lines = 3)))
    }

    fun `test closing gap lines start no unit and attributes are read with their field`() {
        val (file, document) = configure("file.rs", "struct WorkspacesFile {\n version: u32,\n #[serde(default)]\n workspaces: Vec<String>,\n}\n\n")
        val rows = listOf(BlockRow(0, 0, 0, null, emptyList()), CodeRow(1, 6))
        val units = schematicGapUnits(file, document, rows)
        assertTrue(units.none { 4 in it && 3 !in it })
        assertTrue(units.any { 2 in it && 3 in it })
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

    fun `test source retains only relevant facts and none from folded bodies`() {
        val file = myFixture.configureByText("Example.java", "class Example {\n int selected;\n void run() {\n  selected++;\n }\n void unrelated(int noise) {}\n}\n")
        val lines = file.text.split('\n')
        val schematic = Schematic(0, file.virtualFile, 0, lines, emptyList())
        val source = schematicGapSource(project, schematic, GapUnit(3, 3))
        assertTrue(source.facts.contains("run"))
        assertTrue(source.facts.contains("selected"))
        assertFalse(source.facts.contains("unrelated"))
        assertFalse(source.facts.contains("noise"))
        assertFalse(source.facts.contains("Impl"))
        val folded = schematicGapSource(project, schematic, GapUnit(1, 4, folded = listOf(3..4)))
        assertTrue(folded.facts.contains("Declared: function run"))
        assertFalse(folded.facts.contains("Reference selected"))
    }

    fun `test gap without an enclosing declaration does not crash on the file parent`() {
        // The PsiFile's parent is a PsiDirectory, whose textRange is null; the owner walk must stop safely.
        val file = myFixture.configureByText("data.txt", "alpha\nbeta\n")
        val schematic = Schematic(0, file.virtualFile, 0, file.text.split('\n'), emptyList())
        val source = schematicGapSource(project, schematic, GapUnit(0, 0))
        assertEquals(file.text.split('\n'), source.lines)
    }

    fun `test stale schematic is rejected before classifying structural gaps`() {
        val file = myFixture.configureByText("Example.java", "class Example {\n}\n")
        val schematic = Schematic(0, file.virtualFile, 0, listOf("class Old {", "}", ""), emptyList())
        try {
            schematicGapSource(project, schematic, GapUnit(1, 1))
            fail("Stale source must be rejected")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("Source changed"))
        }
    }
}
