package dev.insight

import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.insight.callgraph.*
import dev.insight.graph.*

/** Runs the analyzer against real Java PSI using the plugin's registered language support. */
class JavaCallGraphTest : LightJavaCodeInsightFixtureTestCase() {
    // The default descriptor uses mockJDK-1.7, which is not part of the IDE distribution used for plugin tests,
    // so library calls would not resolve. Use the JDK running the tests instead.
    override fun getProjectDescriptor(): LightProjectDescriptor = jdkDescriptor

    private fun method(name: String) =
        PsiTreeUtil.findChildrenOfType(myFixture.file, PsiMethod::class.java).single { it.name == name }

    private fun names(elements: List<Any>) = elements.map { (it as PsiMethod).name }.toSet()

    override fun setUp() {
        VfsRootAccess.allowRootAccess(testRootDisposable, javaHome)
        super.setUp()
        myFixture.configureByText("Sample.java", """
            class Sample {
                Sample() { helper(); }
                void entry() { new Sample(); middle(); Runnable r = this::leaf; System.out.println(); }
                void middle() { leaf(); helper(); class Local { void inner() { leaf(); } } }
                void leaf() { /** {@link #middle} */ int x = 1; }
                static void helper() {}
            }
        """.trimIndent())
    }

    fun `test callees include constructors and method references but not library calls`() {
        assertEquals(setOf("Sample", "middle", "leaf"), names(CallGraphAnalyzer.callees(method("entry"), includeLibraries = false)))
        assertTrue("println" in names(CallGraphAnalyzer.callees(method("entry"), includeLibraries = true)))
    }

    fun `test calls in local classes belong to the local function`() {
        assertEquals(setOf("leaf", "helper"), names(CallGraphAnalyzer.callees(method("middle"), false)))
        assertEquals(setOf("middle", "entry", "inner"), names(CallGraphAnalyzer.callers(method("leaf"), 100)))
    }

    fun `test references in comments are not calls`() {
        assertEquals(setOf("entry"), names(CallGraphAnalyzer.callers(method("middle"), 100)))
    }

    fun `test caret inside a body finds the enclosing function`() {
        val offset = myFixture.file.text.indexOf("int x")
        assertEquals("leaf", (CallGraphAnalyzer.findFunction(myFixture.file.findElementAt(offset)) as PsiMethod).name)
    }

    fun `test external calls retain a shared navigable boundary without analyzing library bodies`() {
        myFixture.configureByText("External.java", """
            class External {
                String run() { return String.valueOf(1) + String.valueOf(2); }
            }
        """.trimIndent())
        val result = ProjectGraphAnalyzer.build(project)
        val calls = result.occurrences.filter { it.expression == "String.valueOf" }
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.status == ResolutionStatus.RESOLVED })
        val target = calls.first().targets.single()
        assertEquals(listOf(target), calls.last().targets)
        val node = result.graph.nodes[target]
        assertFalse(node.inProject)
        assertFalse(node.unresolved)
        assertNotNull(node.signature)
        assertNotNull(result.pointers[target]?.element)
        assertTrue(result.occurrences.none { it.filePath == node.filePath })
        assertTrue(result.graph.edges.none { it.kind == RelationKind.CALL && it.caller == target })
        val schematic = SchematicBuilder.build(result, result.graph.hierarchy(), emptyMap(), calls.first().scope)!!
        assertEquals(listOf(target), schematic.targets)
    }

    fun `test callable references stay distinct from invocation in the project graph`() {
        val result = ProjectGraphAnalyzer.build(project)
        val reference = result.occurrences.single { it.operation == CallOperation.CALLABLE_REFERENCE }
        val target = reference.targets.single()
        assertEquals("leaf", (result.pointers[target]?.element as PsiMethod).name)
        assertTrue(CallGraphEdge(reference.owner, target, RelationKind.REFERENCE) in result.graph.edges)
        assertTrue(CallGraphEdge(reference.owner, target, RelationKind.CALL) !in result.graph.edges)
    }
}

private val javaHome: String = System.getProperty("java.home")

private val jdkDescriptor = object : DefaultLightProjectDescriptor() {
    override fun getSdk() = JavaSdk.getInstance().createJdk("test-jdk", javaHome, false)
}
