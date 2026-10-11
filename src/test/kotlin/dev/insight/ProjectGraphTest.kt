package dev.insight

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.insight.callgraph.*

class ProjectGraphTest : LightJavaCodeInsightFixtureTestCase() {
    fun `test full project includes disconnected symbols and cross file relationships`() {
        myFixture.addClass("""
            package sample;
            public class Target {
                public static int value;
                public static void work() {}
                public void unused() {}
            }
        """.trimIndent())
        myFixture.configureByText("Source.java", """
            import sample.Target;
            class Source extends Target {
                int field = Target.value;
                void entry() { Target.work(); }
            }
        """.trimIndent())
        val graph = ProjectGraphAnalyzer.build(project).graph
        fun node(name: String, kind: SymbolKind) = graph.nodes.single { it.name.startsWith(name) && it.kind == kind }
        val sourceFile = graph.nodes.single { it.kind == SymbolKind.FILE && it.name == "Source.java" }
        val source = node("Source", SymbolKind.CLASS)
        val target = node("Target", SymbolKind.CLASS)
        val entry = node("entry", SymbolKind.FUNCTION)
        val work = node("work", SymbolKind.FUNCTION)
        val field = node("field", SymbolKind.PROPERTY)
        val value = node("value", SymbolKind.PROPERTY)
        assertTrue(graph.projectGraph)
        assertFalse(graph.truncated)
        node("unused", SymbolKind.FUNCTION)
        assertTrue(CallGraphEdge(sourceFile.id, source.id, RelationKind.CONTAINS) in graph.edges)
        assertTrue(CallGraphEdge(source.id, entry.id, RelationKind.CONTAINS) in graph.edges)
        assertTrue(CallGraphEdge(sourceFile.id, target.id, RelationKind.IMPORT) in graph.edges)
        assertTrue(CallGraphEdge(source.id, target.id, RelationKind.INHERITS) in graph.edges)
        assertTrue(CallGraphEdge(entry.id, work.id, RelationKind.CALL) in graph.edges)
        assertTrue(CallGraphEdge(field.id, value.id, RelationKind.REFERENCE) in graph.edges)
        val highlighted = graph.highlightFile(sourceFile.filePath)
        assertTrue(setOf(sourceFile.id, source.id, entry.id, field.id).all { it in highlighted.owned })
        assertTrue(setOf(target.id, work.id, value.id).all { it in highlighted.related })
        assertFalse(node("unused", SymbolKind.FUNCTION).id in highlighted.related)
        val switched = graph.highlightFile(target.filePath)
        assertTrue(work.id in switched.owned)
        assertTrue(entry.id in switched.related)
        assertFalse(entry.id in switched.owned)

        val files = graph.atDetail(GraphDetail.FILE)
        assertTrue(files.graph.nodes.all { it.kind == SymbolKind.FILE })
        val sourceNode = files.graph.nodes.single { it.name == "Source.java" }
        val targetNode = files.graph.nodes.single { it.name == "Target.java" }
        assertEquals(sourceFile.id, files.sources[sourceNode.id])
        assertEquals(1, files.graph.edges.count { it.caller == sourceNode.id && it.callee == targetNode.id })
        assertEquals(RelationKind.INHERITS, files.graph.edges.single { it.caller == sourceNode.id }.kind)
        assertTrue(targetNode.level > sourceNode.level)
    }

    fun `test Kotlin properties classes and nested functions are declarations`() {
        myFixture.configureByText("Symbols.kt", """
            class Box(val value: Int) {
                val doubled = value * 2
                fun run() {
                    fun local() = value
                    local()
                }
            }
            typealias Alias = Box
            fun unused() {}
        """.trimIndent())
        val declarations = ProjectGraphAnalyzer.declarations(myFixture.file)
        val graph = ProjectGraphAnalyzer.build(project).graph
        assertEquals(1, graph.nodes.count { it.kind == SymbolKind.FILE })
        assertTrue(declarations.size >= 8)
        assertTrue(graph.nodes.any { it.name.startsWith("value") && it.kind == SymbolKind.PROPERTY })
        val run = graph.nodes.single { it.name.startsWith("run") }
        val local = graph.nodes.single { it.name.startsWith("local") }
        assertTrue(CallGraphEdge(run.id, local.id, RelationKind.CONTAINS) in graph.edges)
        assertTrue(CallGraphEdge(run.id, local.id, RelationKind.CALL) in graph.edges)
        assertEquals(graph.nodes.map { it.id }.toSet(), graph.highlightFile(myFixture.file.virtualFile.path).owned)
    }

    fun `test application graph includes more than the function exploration limit`() {
        val methods = (1..170).joinToString("\n") { "void disconnected$it() {}" }
        myFixture.configureByText("Large.java", "class Large { $methods }")
        val graph = ProjectGraphAnalyzer.build(project).graph
        assertEquals(170, graph.nodes.count { it.kind == SymbolKind.FUNCTION })
        assertFalse(graph.truncated)
    }

    fun `test project graph resolves Kotlin calls to Java declarations`() {
        myFixture.addClass("package sample; public class JavaSide { public static void helper() {} }")
        myFixture.configureByText("Caller.kt", "import sample.JavaSide\nfun entry() { JavaSide.helper() }")
        val graph = ProjectGraphAnalyzer.build(project).graph
        val entry = graph.nodes.single { it.kind == SymbolKind.FUNCTION && it.name.startsWith("entry") }
        val helper = graph.nodes.single { it.kind == SymbolKind.FUNCTION && it.name.startsWith("helper") }
        assertTrue(CallGraphEdge(entry.id, helper.id, RelationKind.CALL) in graph.edges)
    }
}
