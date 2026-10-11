package dev.insight

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.python.psi.PyFunction
import dev.insight.callgraph.*

/**
 * Runs the analyzer against real Python PSI. PythonCore only loads in the test IDE together with the bundled
 * `org.toml.lang` plugin (see build.gradle.kts); without it `.py` files are plain text and these tests fail.
 */
class PythonCallGraphTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        assertEquals("PythonCore is not loaded in the test IDE", "Python",
            FileTypeManager.getInstance().getFileTypeByFileName("a.py").name)
    }

    private fun function(name: String, file: com.intellij.psi.PsiFile = myFixture.file) =
        PsiTreeUtil.findChildrenOfType(file, PyFunction::class.java).single { it.name == name }

    private fun names(elements: List<PsiElement>) = elements.map { (it as PsiNamedElement).name }.toSet()

    fun `test callees include functions, methods, constructors and skip decorators`() {
        myFixture.configureByText("sample.py", """
            def deco(f):
                return f

            class Box:
                def __init__(self, value):
                    self.value = value
                    self.reset()

                def reset(self):
                    self.value = 0

                @deco
                def twice(self):
                    return self.value * 2

            class Empty:
                pass

            def leaf():
                pass

            def middle():
                leaf()
                def local():
                    entry()

            def entry():
                middle()
                b = Box(1)
                b.twice()
                Empty()
                print("x")
        """.trimIndent())
        assertEquals(setOf("middle", "__init__", "twice"), names(CallGraphAnalyzer.callees(function("entry"), false)))
        assertEquals(setOf("reset"), names(CallGraphAnalyzer.callees(function("__init__"), false)))
        assertTrue(CallGraphAnalyzer.callees(function("twice"), false).isEmpty())
    }

    fun `test nested functions own their calls and callers`() {
        myFixture.configureByText("nested.py", """
            def leaf():
                pass

            def entry():
                def local():
                    leaf()
                local()
        """.trimIndent())
        assertEquals(setOf("local"), names(CallGraphAnalyzer.callees(function("entry"), false)))
        assertEquals(setOf("leaf"), names(CallGraphAnalyzer.callees(function("local"), false)))
        assertEquals(setOf("local"), names(CallGraphAnalyzer.callers(function("leaf"), 100)))
        assertEquals(setOf("entry"), names(CallGraphAnalyzer.callers(function("local"), 100)))
    }

    fun `test callers across files and for methods and constructors`() {
        val lib = myFixture.addFileToProject("lib.py", """
            class Service:
                def __init__(self):
                    self.ready = True

                def run(self):
                    pass

            def helper():
                pass
        """.trimIndent())
        myFixture.configureByText("app.py", """
            from lib import Service, helper

            def main():
                s = Service()
                s.run()
                helper()

            def other():
                helper()
        """.trimIndent())
        assertEquals(setOf("main", "other"), names(CallGraphAnalyzer.callers(function("helper", lib), 100)))
        assertEquals(setOf("main"), names(CallGraphAnalyzer.callers(function("run", lib), 100)))
        assertEquals(setOf("main"), names(CallGraphAnalyzer.callers(function("__init__", lib), 100)))
        assertEquals(setOf("__init__", "run", "helper"), names(CallGraphAnalyzer.callees(function("main"), false)))
    }

    fun `test project graph has declarations, imports, calls and inheritance`() {
        myFixture.addFileToProject("base.py", """
            class Base:
                def work(self):
                    pass

            def helper():
                pass
        """.trimIndent())
        myFixture.addFileToProject("child.py", """
            import base
            from base import Base, helper

            LIMIT = 1
            LIMIT = 2

            class Child(Base):
                kind = "child"

                def __init__(self, size):
                    self.size = size
                    self.size = size + 1
                    total = 0
                    total = total + 1

                def run(self):
                    helper()
                    self.work()
        """.trimIndent())
        val graph = ProjectGraphAnalyzer.build(project).graph
        fun node(name: String, kind: SymbolKind) = graph.nodes.singleOrNull { it.name.startsWith(name) && it.kind == kind } ?: error("$name $kind in " + graph.nodes.map { it.name + ":" + it.kind })
        val child = graph.nodes.single { it.kind == SymbolKind.FILE && it.name == "child.py" }
        val base = graph.nodes.single { it.kind == SymbolKind.FILE && it.name == "base.py" }
        val childClass = node("Child", SymbolKind.CLASS)
        val baseClass = node("Base", SymbolKind.CLASS)
        val run = node("run", SymbolKind.FUNCTION)
        val work = node("work", SymbolKind.FUNCTION)
        val helper = node("helper", SymbolKind.FUNCTION)
        assertTrue(graph.projectGraph)
        assertEquals(1, graph.nodes.count { it.kind == SymbolKind.VARIABLE && it.name.startsWith("LIMIT") })
        assertEquals(1, graph.nodes.count { it.kind == SymbolKind.VARIABLE && it.name.startsWith("total") })
        assertEquals(1, graph.nodes.count { it.kind == SymbolKind.PROPERTY && it.name.startsWith("size") })
        node("kind", SymbolKind.PROPERTY)
        assertEquals(1, graph.nodes.count { it.kind == SymbolKind.PARAMETER && it.name.startsWith("size") })
        assertTrue(graph.nodes.none { it.kind == SymbolKind.PARAMETER && it.name.startsWith("self") })
        assertTrue(CallGraphEdge(child.id, childClass.id, RelationKind.CONTAINS) in graph.edges)
        assertTrue(CallGraphEdge(childClass.id, run.id, RelationKind.CONTAINS) in graph.edges)
        assertTrue(CallGraphEdge(child.id, baseClass.id, RelationKind.IMPORT) in graph.edges)
        assertTrue(CallGraphEdge(child.id, base.id, RelationKind.IMPORT) in graph.edges)
        assertTrue(CallGraphEdge(childClass.id, baseClass.id, RelationKind.INHERITS) in graph.edges)
        assertTrue(CallGraphEdge(run.id, helper.id, RelationKind.CALL) in graph.edges)
        assertTrue(CallGraphEdge(run.id, work.id, RelationKind.CALL) in graph.edges)

        val files = graph.atDetail(GraphDetail.FILE)
        assertTrue(files.graph.nodes.all { it.kind == SymbolKind.FILE })
        val childNode = files.graph.nodes.single { it.name == "child.py" }
        val baseNode = files.graph.nodes.single { it.name == "base.py" }
        assertEquals(1, files.graph.edges.count { it.caller == childNode.id && it.callee == baseNode.id })
    }
}
