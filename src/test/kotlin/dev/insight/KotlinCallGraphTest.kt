package dev.insight

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.insight.callgraph.CallGraphAnalyzer
import dev.insight.callgraph.ProjectGraphAnalyzer
import dev.insight.callgraph.SymbolKind
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Runs the analyzer against real Kotlin PSI, including calls between Kotlin and Java. */
class KotlinCallGraphTest : LightJavaCodeInsightFixtureTestCase() {
    private fun function(name: String) =
        PsiTreeUtil.findChildrenOfType(myFixture.file, KtNamedFunction::class.java).single { it.name == name }

    private fun names(elements: List<PsiElement>) = elements.map { (it as PsiNamedElement).name }.toSet()

    override fun setUp() {
        super.setUp()
        myFixture.addClass("""
            public class JavaSide {
                public static void callKotlin() { SampleKt.leaf(); }
                public static void javaHelper() {}
            }
        """.trimIndent())
        myFixture.configureByText("Sample.kt", """
            class Box(val value: Int) {
                operator fun plus(other: Box) = Box(value + other.value)
            }

            fun entry() {
                middle()
                val f = ::leaf
                Box(1) + Box(2)
                JavaSide.javaHelper()
            }

            fun middle() {
                leaf()
                fun local() { entry() }
            }

            fun leaf() { val x = 1 }
        """.trimIndent())
    }

    fun `test callees include functions, callable references, operators, constructors and Java methods`() {
        assertEquals(setOf("middle", "leaf", "plus", "Box", "javaHelper"), names(CallGraphAnalyzer.callees(function("entry"), false)))
    }

    fun `test calls in local functions belong to the local function`() {
        assertEquals(setOf("leaf"), names(CallGraphAnalyzer.callees(function("middle"), false)))
        assertEquals(setOf("local"), names(CallGraphAnalyzer.callers(function("entry"), 100)))
    }

    fun `test callers include Kotlin and Java call sites mapped to source declarations`() {
        val callers = CallGraphAnalyzer.callers(function("leaf"), 100)
        assertEquals(setOf("entry", "middle", "callKotlin"), names(callers))
        assertTrue(callers.any { it is PsiMethod && it.name == "callKotlin" })
    }

    fun `test invoke operators resolve to invoke and function-typed values continue at their binding`() {
        myFixture.addFileToProject("Values.kt", """
            class Invokable { operator fun invoke(x: Int) = x }
            class Holder(val handler: (String) -> String)

            fun use(inv: Invokable, holder: Holder, callback: () -> Unit) {
                inv(1)
                holder.handler("c")
                callback()
            }
        """.trimIndent())
        val result = ProjectGraphAnalyzer.build(project)
        fun call(expression: String) = result.occurrences.single { it.expression == expression && it.filePath.endsWith("Values.kt") }
        val invoke = call("inv")
        assertTrue(result.graph.nodes[invoke.targets.single()].name.startsWith("invoke"))
        for ((expression, kind) in listOf("handler" to SymbolKind.PROPERTY, "callback" to SymbolKind.PARAMETER)) {
            val held = call(expression)
            assertTrue(held.toString(), held.targets.isEmpty())
            val binding = result.graph.nodes[held.heldBy.single()]
            assertEquals(expression, binding.name)
            assertEquals(kind, binding.kind)
        }
        assertTrue(result.graph.nodes.none { it.unresolved && it.filePath.orEmpty().endsWith("Values.kt") })
    }

    fun `test Java callee written in Kotlin resolves to the Kotlin declaration`() {
        val callKotlin = myFixture.findClass("JavaSide").findMethodsByName("callKotlin", false).single()
        assertTrue(CallGraphAnalyzer.callees(callKotlin, false).single() is KtNamedFunction)
    }
}
