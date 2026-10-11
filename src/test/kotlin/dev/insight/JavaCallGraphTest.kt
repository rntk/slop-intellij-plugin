package dev.insight

import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.insight.callgraph.CallGraphAnalyzer

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
}

private val javaHome: String = System.getProperty("java.home")

private val jdkDescriptor = object : DefaultLightProjectDescriptor() {
    override fun getSdk() = JavaSdk.getInstance().createJdk("test-jdk", javaHome, false)
}
