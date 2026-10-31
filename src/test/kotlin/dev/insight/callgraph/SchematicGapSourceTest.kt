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
        assertEquals(GapConstruct(0, 3, 0, keepWhole = true), gapConstructs(file, document, 0).first())
        assertEquals(GapConstruct(0, 3, 0, keepWhole = true), gapConstructs(file, document, 2).first())
        val rows = listOf(CodeRow(0, 0), BlockRow(1, 1, 1, null, emptyList()), CodeRow(2, 4))
        assertEquals(listOf(GapUnit(0, 3, symbols = listOf(1))), schematicGapUnits(file, document, rows))
    }

    fun `test Go declaration-only types ignore size and symbol limits but stay separate`() {
        for (kind in listOf("struct", "interface")) {
            val fields = (1..6).joinToString("\n") { if (kind == "struct") "    Field$it string" else "    Method$it()" }
            val (file, document) = configure("types.go", "package main\n\ntype Large $kind {\n$fields\n}\ntype Other struct { Value int }\n")
            val rows = listOf(CodeRow(2, 2)) + (3..8).map { BlockRow(it, it, it, it, emptyList()) } + CodeRow(9, 10)
            val budget = GapBudget(lines = 1, chars = 1, declarations = 0, glance = 0)
            assertEquals(listOf(GapUnit(2, 9, symbols = (3..8).toList()), GapUnit(10, 10)),
                schematicGapUnits(file, document, rows, budget))
            val scope = outermost(file, "type Large")
            assertEquals(listOf(GapUnit(2, 9, symbols = (3..8).toList())),
                schematicGapUnits(scope, document, rows.dropLast(1) + CodeRow(9, 9), budget))
            val namedScope = generateSequence(file.findElementAt(file.text.indexOf("Large"))) { it.parent }
                .first { it is com.goide.psi.GoTypeSpec }
            assertEquals(listOf(GapUnit(2, 9, symbols = (3..8).toList())),
                schematicGapUnits(namedScope, document, rows.dropLast(1) + CodeRow(9, 9), budget))
        }
        val (funcFile, funcDoc) = configure("func.go", "package main\n\ntype Handler func(\n    req Request,\n) Response\n")
        assertEquals(listOf(GapUnit(2, 4)),
            schematicGapUnits(funcFile, funcDoc, listOf(CodeRow(3, 3)), GapBudget(lines = 1, chars = 1, declarations = 0, glance = 0)))
    }

    fun `test Rust declaration-only types grow from fields and variants beyond the budget`() {
        for ((kind, member) in listOf(
            "struct" to "field: String,",
            "union" to "field: u32,",
            "enum" to "Variant { field: String },",
            "trait" to "fn call(&self);\n    type Output;",
        )) {
            val (file, document) = configure("types.rs", "pub $kind Large {\n    $member\n}\nfn run() {\n    work();\n}\n")
            val budget = GapBudget(lines = 1, chars = 1, declarations = 0, glance = 0)
            val end = document.getLineNumber(file.text.indexOf("fn run")) - 1
            assertEquals(listOf(GapUnit(0, end)), schematicGapUnits(file, document, listOf(CodeRow(1, 1)), budget))
            val units = schematicGapUnits(file, document, listOf(CodeRow(0, end + 3)), GapBudget(lines = 1))
            assertEquals(GapUnit(0, end), units.first())
            assertTrue(units.any { it.construct == (end + 1)..(end + 3) })
        }
        val (aliasFile, aliasDoc) = configure("alias.rs", "pub type Handler =\n    fn(\n        Request,\n    ) -> Response;\n")
        assertEquals(listOf(GapUnit(0, 3)),
            schematicGapUnits(aliasFile, aliasDoc, listOf(CodeRow(2, 2)), GapBudget(lines = 1, chars = 1, declarations = 0, glance = 0)))
        val (defaultTrait, defaultDoc) = configure("default.rs", "trait WithBody {\n    fn run(&self) {\n        work();\n    }\n}\n")
        assertTrue(schematicGapUnits(defaultTrait, defaultDoc, listOf(CodeRow(0, 4)), GapBudget(lines = 1)).any { it.construct == 0..4 })
    }

    fun `test Java declaration-only types stay whole while classes with method bodies do not`() {
        val budget = GapBudget(lines = 1, chars = 1, declarations = 0, glance = 0)
        for (source in listOf(
            "interface Large {\n  void m1();\n  void m2();\n}\n",
            "@interface Large {\n  String m1();\n  int m2() default 0;\n}\n",
            "enum Large {\n  FIRST,\n  SECOND\n}\n",
            "record Large(\n  String first,\n  int second\n) {}\n",
            "class Large {\n  String first;\n  int second;\n}\n",
        )) {
            val (file, document) = configure("Large.java", source)
            assertEquals(source, listOf(GapUnit(0, 3)), schematicGapUnits(file, document, listOf(CodeRow(1, 1)), budget))
            val scope = outermost(file, source.substringBefore(" "))
            assertEquals(source, listOf(GapUnit(0, 3, symbols = listOf(1, 2))),
                schematicGapUnits(scope, document, listOf(CodeRow(0, 0), BlockRow(1, 1, 1, 1, emptyList()), BlockRow(2, 2, 2, 2, emptyList()), CodeRow(3, 3)), budget))
        }
    }

    fun `test Kotlin declaration-only types and aliases stay whole while classes with bodies do not`() {
        val budget = GapBudget(lines = 1, chars = 1, declarations = 0, glance = 0)
        for (source in listOf(
            "data class Large(\n  val first: String,\n  val second: Int,\n)\n",
            "enum class Large {\n  FIRST,\n  SECOND,\n}\n",
            "interface Large {\n  fun m1()\n  fun m2()\n}\n",
            "object Large {\n  const val FIRST = 1\n  const val SECOND = 2\n}\n",
            "typealias Large =\n  (\n    String,\n  ) -> Int\n",
        )) {
            val (file, document) = configure("Large.kt", source)
            assertEquals(source, listOf(GapUnit(0, 3)), schematicGapUnits(file, document, listOf(CodeRow(1, 1)), budget))
            val scope = outermost(file, source.substringBefore(" "))
            assertEquals(source, listOf(GapUnit(0, 3)), schematicGapUnits(scope, document, listOf(CodeRow(0, 3)), budget))
        }
        val (withBody, bodyDoc) = configure("WithBody.kt", "class WithBody {\n  fun run() {\n    work()\n  }\n}\n")
        assertTrue(schematicGapUnits(withBody, bodyDoc, listOf(CodeRow(0, 4)), GapBudget(lines = 1)).any { it.construct == 0..4 })
    }

    fun `test Python declaration-only classes protocols and type aliases stay whole`() {
        val budget = GapBudget(lines = 1, chars = 1, declarations = 0, glance = 0)
        for (source in listOf(
            "class Large:\n    first: str\n    second: int = 1\n",
            "class Large(Protocol):\n    def m1(self) -> None: ...\n    def m2(self) -> None: pass\n",
            "type Large = (\n    str\n    | int\n)\n",
        )) {
            val (file, document) = configure("types.py", source)
            val end = document.lineCount - 2
            assertEquals(source, listOf(GapUnit(0, end)), schematicGapUnits(file, document, listOf(CodeRow(1, 1)), budget))
            val scope = outermost(file, source.substringBefore(" "))
            assertEquals(source, listOf(GapUnit(0, end)), schematicGapUnits(scope, document, listOf(CodeRow(0, end)), budget))
        }
        val (withBody, bodyDoc) = configure("service.py", "class Service:\n    def run(self):\n        work()\n")
        assertTrue(schematicGapUnits(withBody, bodyDoc, listOf(CodeRow(0, 2)), GapBudget(lines = 1)).any { it.construct == 0..2 })
    }

    fun `test TypeScript declaration-only types stay whole while classes with method bodies do not`() {
        val budget = GapBudget(lines = 1, chars = 1, declarations = 0, glance = 0)
        for (source in listOf(
            "export interface Large {\n  first: string;\n  second(): void;\n}\n",
            "export default interface Large {\n  first: string;\n  second: number;\n}\n",
            "export enum Large {\n  First,\n  Second,\n}\n",
            "export type Large = {\n  first: string;\n  second: number;\n};\n",
            "export class Large {\n  first: string;\n  second: number;\n}\n",
        )) {
            val (file, document) = configure("types.ts", source)
            assertEquals(source, listOf(GapUnit(0, 3)), schematicGapUnits(file, document, listOf(CodeRow(1, 1)), budget))
            val scope = outermost(file, "export")
            assertEquals(source, listOf(GapUnit(0, 3)), schematicGapUnits(scope, document, listOf(CodeRow(0, 3)), budget))
            val namedScope = generateSequence(file.findElementAt(file.text.indexOf("Large"))) { it.parent }
                .first { it is com.intellij.psi.PsiNamedElement && it.name == "Large" }
            assertEquals(source, listOf(GapUnit(0, 3)), schematicGapUnits(namedScope, document, listOf(CodeRow(0, 3)), budget))
        }
        val (withBody, bodyDoc) = configure("service.ts", "export class Service {\n  run() {\n    work();\n  }\n}\n")
        assertTrue(schematicGapUnits(withBody, bodyDoc, listOf(CodeRow(0, 4)), GapBudget(lines = 1)).any { it.construct == 0..4 })
    }

    fun `test grouped Go types do not exempt their container or neighboring function`() {
        val (file, document) = configure("types.go",
            "package main\ntype (\n    Data struct {\n        Value string\n    }\n    Other int\n)\nfunc run() {\n    work()\n}\n")
        val units = schematicGapUnits(file, document, listOf(CodeRow(1, 9)), GapBudget(lines = 1, chars = 1))
        assertTrue(units.toString(), units.contains(GapUnit(2, 4)))
        assertTrue(units.toString(), units.none { it.first <= 2 && it.last >= 5 })
        assertTrue(units.toString(), units.none { it.first == 7 && it.last == 9 && it.construct == null })
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
