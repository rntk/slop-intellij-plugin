package dev.insight

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.insight.callgraph.*
import dev.insight.enrichment.liftCalls
import dev.insight.graph.*

class SchematicBuilderTest : LightJavaCodeInsightFixtureTestCase() {
    private val source = """
        class Flow {
            private final Store store = Store.open();

            void entry(int kind) {
                int x = 1;
                if (kind > 0) {
                    check(
                        kind);
                }
                store.save(x);
            }

            void check(int kind) {}
        }

        class Store {
            static Store open() { return new Store(); }
            void save(int x) {}
        }
    """.trimIndent()

    private fun build(text: String = source, name: (CallGraphNode) -> Boolean): Pair<CallGraphResult, Schematic> {
        myFixture.configureByText("Flow.java", text)
        val result = ProjectGraphAnalyzer.build(project)
        val calls = liftCalls(result.graph).edges.filter { it.kind == RelationKind.CALL }.groupBy { it.caller }
        val id = result.graph.nodes.single(name).id
        return result to SchematicBuilder.build(result, result.graph.hierarchy(), calls, id)!!
    }

    private fun CallGraphResult.name(id: Int) = graph.nodes[id].name.substringBefore('(')

    fun `test gap context includes PSI knowledge and rejects changed source`() {
        val (_, schematic) = build { it.name.substringBefore('(') == "entry" }
        val gap = schematic.gaps.first { unit -> (unit.first..unit.last).any { schematic.text(it).contains("int x = 1") } }
        // The condition after it is read whole, with the call inside it, rather than as a dangling `if (kind > 0) {`.
        val condition = schematic.gaps.single { unit -> schematic.text(unit.first).contains("if (kind > 0)") }
        assertEquals("}", schematic.text(condition.last).trim())
        assertTrue(condition.symbols.isNotEmpty())
        val context = schematicGapSource(project, schematic, gap)
        assertEquals(source.lines(), context.lines)
        assertTrue(context.facts.contains("Language: Java", ignoreCase = true))
        assertTrue(context.facts.contains("Declared: variable x"))
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, "// changed\n")
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        try {
            schematicGapSource(project, schematic, gap)
            fail("Changed source must not be mixed with snapshot excerpts")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("Source changed"))
        }
    }

    fun `test a file folds its methods and shows field initializers calling project code as blocks`() {
        val (result, schematic) = build { it.kind == SymbolKind.FILE }
        assertEquals(source.lines(), schematic.lines)
        val blocks = schematic.rows.filterIsInstance<BlockRow>()
        assertEquals(listOf("open", "entry", "check", "open", "save"), blocks.flatMap { it.targets }.map { result.name(it) })
        val entry = blocks.single { it.declared?.let { id -> result.name(id) } == "entry" }
        assertEquals("    void entry(int kind) {", schematic.text(entry.headline))
        assertEquals(source.lines().indexOfFirst { it.trim() == "}" && it.startsWith("    }") }, entry.last)
        // Every line is covered once, in order.
        assertEquals((0 until source.lines().size).toList(), schematic.rows.flatMap { (it.first..it.last).toList() })
    }


    fun `test a method shows its calls on the lines where they start`() {
        val (result, schematic) = build { it.kind == SymbolKind.FUNCTION && it.name.startsWith("entry") }
        val blocks = schematic.rows.filterIsInstance<BlockRow>()
        assertEquals(listOf("check" to "check(", "save" to "store.save(x);"),
            blocks.map { block -> result.name(block.calls.single()) to schematic.text(block.headline).trim() })
        assertTrue(blocks.all { it.declared == null && it.first == it.last })
        assertEquals("    void entry(int kind) {", schematic.text(schematic.firstLine))
    }
}
