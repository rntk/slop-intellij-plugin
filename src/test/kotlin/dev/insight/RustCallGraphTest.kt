package dev.insight

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.callgraph.*
import dev.insight.callgraph.rust.RustCallGraphSupport
import dev.insight.graph.*
import kotlinx.coroutines.CancellationException
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.project.model.impl.TestCargoProjectsServiceImpl
import org.rust.lang.core.psi.*

/** Real Rust PSI, with no Rust toolchain or external crates required. */
class RustCallGraphTest : BasePlatformTestCase() {
    private val support = RustCallGraphSupport()

    private fun function(name: String) =
        PsiTreeUtil.findChildrenOfType(myFixture.file, RsFunction::class.java).single { it.name == name }

    private fun names(elements: List<PsiElement>) = elements.map { (it as PsiNamedElement).name }.toSet()

    fun testFunctionsAndNestedCalls() {
        myFixture.configureByText("main.rs", """
            fn leaf() {}
            fn entry() {
                fn local() { leaf(); }
                local();
                leaf();
            }
        """.trimIndent())
        assertTrue(support.supportsFile(myFixture.file))
        assertTrue(CallGraphLanguageSupport.EP_NAME.extensionList.any { it is RustCallGraphSupport })
        assertEquals(setOf("local", "leaf"), names(CallGraphAnalyzer.callees(function("entry"), false)))
        assertEquals(setOf("leaf"), names(CallGraphAnalyzer.callees(function("local"), false)))
        assertEquals(setOf("entry", "local"), names(CallGraphAnalyzer.callers(function("leaf"), 100)))
        assertEquals(function("entry"), CallGraphAnalyzer.findFunction(myFixture.file.findElementAt(myFixture.file.text.lastIndexOf("leaf();"))))
    }

    fun testSymbolsAndRelations() {
        myFixture.configureByText("main.rs", """
            mod util { pub fn helper() {} }
            use util::helper;
            struct User { value: i32 }
            enum Choice { Empty, Value(i32) }
            trait Named { fn name(&self); }
            impl Named for User { fn name(&self) { helper(); } }
            const LIMIT: i32 = 3;
            fn entry((left, right): (i32, i32)) { let (a, b) = (left, right); helper(); }
        """.trimIndent())
        val declarations = ProjectGraphAnalyzer.declarations(myFixture.file)
        fun has(name: String, kind: SymbolKind) = declarations.any {
            (it as? PsiNamedElement)?.name == name && support.symbolKind(it) == kind
        }
        assertTrue(has("User", SymbolKind.CLASS))
        assertTrue(has("Choice", SymbolKind.CLASS))
        assertTrue(has("Named", SymbolKind.CLASS))
        assertTrue(has("value", SymbolKind.PROPERTY))
        assertTrue(has("Value", SymbolKind.PROPERTY))
        assertTrue(has("LIMIT", SymbolKind.VARIABLE))
        assertTrue(has("left", SymbolKind.PARAMETER))
        assertTrue(has("right", SymbolKind.PARAMETER))
        assertTrue(has("a", SymbolKind.VARIABLE))
        assertTrue(has("b", SymbolKind.VARIABLE))
        val graph = ProjectGraphAnalyzer.build(project).graph
        assertTrue(graph.edges.any { it.kind == RelationKind.CALL })
        assertTrue(graph.edges.any { it.kind == RelationKind.IMPORT })
        assertTrue(graph.edges.any { it.kind == RelationKind.INHERITS })

        val allFunctions = PsiTreeUtil.findChildrenOfType(myFixture.file, RsFunction::class.java)
        val traitMethod = allFunctions.single { (it.parent as? RsMembers)?.parent is RsTraitItem }
        val implMethod = allFunctions.single { (it.parent as? RsMembers)?.parent is RsImplItem }
        val freeFunction = function("entry")
        assertEquals(listOf(traitMethod), support.superMethods(implMethod))
        assertTrue(support.superMethods(traitMethod).isEmpty())
        assertTrue(support.superMethods(freeFunction).isEmpty())
    }

    fun testProgressiveSchematicUnfoldRustFunction() {
        myFixture.configureByText("main.rs", """
            fn leaf() {}
            fn main() {
                leaf();
            }
        """.trimIndent())
        val initial = ProjectGraphAnalyzer.buildScope(project, myFixture.file, declarationsOnly = true)
        val mainNode = initial.graph.nodes.single { it.name == "main" }
        val mainElement = initial.pointers[mainNode.id]!!.element!!
        val branch = ProjectGraphAnalyzer.buildScope(project, mainElement)
        assertTrue(branch.occurrences.any { it.expression == "leaf" })
        assertEquals(setOf("main.rs", "main", "leaf"), branch.graph.nodes.map { it.name }.toSet())
    }

    fun testSuperMethodsSelectOnlyActiveCfgDeclaration() {
        myFixture.configureByText("main.rs", """
            struct User;
            trait Named {
                #[cfg(any())]
                fn name(&self);
                #[cfg(all())]
                fn name(&self);
            }
            impl Named for User { fn name(&self) {} }
        """.trimIndent())
        (project.cargoProjects as TestCargoProjectsServiceImpl).configure(myFixture.file.virtualFile)
        val methods = PsiTreeUtil.findChildrenOfType(myFixture.file, RsFunction::class.java)
        val implMethod = methods.single { (it.parent as? RsMembers)?.parent is RsImplItem }
        val traitMethods = methods.filter { (it.parent as? RsMembers)?.parent is RsTraitItem }
        val activeMethod = traitMethods.single { it.text.contains("cfg(all())") }
        assertEquals(listOf(activeMethod), support.superMethods(implMethod))
        val inheritedMethods = mutableListOf<PsiElement>()
        ProjectGraphAnalyzer.relations(myFixture.file) { source, target, kind ->
            if (source == implMethod && kind == RelationKind.INHERITS) inheritedMethods += target
        }
        assertEquals(listOf(activeMethod), inheritedMethods)
    }

    fun testSuperMethodLookupPreservesCancellation() {
        myFixture.configureByText("main.rs", "fn entry() {}")
        val function = function("entry")
        val controlFlow = object : RuntimeException(), ControlFlowException {}
        for (failure in listOf(CancellationException("Cancelled"), ProcessCanceledException(), controlFlow)) {
            val brokenFunction = object : RsFunction by function {
                override fun getName(): String = throw failure
            }
            try {
                support.superMethods(brokenFunction)
                fail("Cancellation and control flow must propagate")
            } catch (actual: RuntimeException) {
                assertSame(failure, actual)
            }
        }
        val brokenFunction = object : RsFunction by function {
            override fun getName(): String = throw IllegalStateException("Broken PSI")
        }
        assertTrue(support.superMethods(brokenFunction).isEmpty())
    }

    fun testMethodsConstructorsAndUnresolvedOccurrences() {
        myFixture.configureByText("main.rs", """
            struct User { value: i32 }
            impl User {
                fn new() -> User { User { value: 0 } }
                fn run(&self) { leaf(); }
            }
            fn leaf() {}
            fn entry() {
                let user = User::new();
                user.run();
                missing();
            }
        """.trimIndent())
        (project.cargoProjects as TestCargoProjectsServiceImpl).configure(myFixture.file.virtualFile)
        assertEquals(setOf("new", "run"), names(CallGraphAnalyzer.callees(function("entry"), false)))
        assertEquals(setOf("leaf"), names(CallGraphAnalyzer.callees(function("run"), false)))
        val graph = ProjectGraphAnalyzer.build(project)
        val missing = graph.occurrences.single { it.expression == "missing" }
        assertEquals(ResolutionStatus.UNRESOLVED, missing.status)
        assertNotNull(missing.unresolvedTarget)
        val method = graph.occurrences.single { it.expression.startsWith("run(") }
        assertEquals("user", method.receiver)
        assertEquals(ResolutionStatus.RESOLVED, method.status)
        assertTrue(graph.occurrences.any { it.expression == "User" && it.targets.isNotEmpty() })
    }

    fun testOtherLanguagesAreIgnored() {
        val file = myFixture.configureByText("notes.txt", "fn main() { missing(); }")
        assertFalse(support.supportsFile(file))
        assertFalse(support.isFunction(file))
        assertNull(support.callSite(file))
    }

    fun testCallsAcrossModulesAndCallableBindings() {
        val helper = myFixture.addFileToProject("util.rs", "pub fn helper() {}")
        myFixture.configureByText("main.rs", """
            mod util;
            use util::helper;
            fn entry(callback: fn()) {
                helper();
                callback();
                let closure = || helper();
                closure();
            }
        """.trimIndent())
        (project.cargoProjects as TestCargoProjectsServiceImpl).configure(myFixture.file.virtualFile)
        assertEquals(setOf("helper"), names(CallGraphAnalyzer.callees(function("entry"), false)))
        val target = PsiTreeUtil.findChildrenOfType(helper, RsFunction::class.java).single()
        assertEquals(setOf("entry"), names(CallGraphAnalyzer.callers(target, 100)))
        val result = ProjectGraphAnalyzer.build(project)
        val callback = result.occurrences.single { it.expression == "callback" }
        assertEquals(1, callback.heldBy.size)
        assertEquals(SymbolKind.PARAMETER, result.graph.nodes[callback.heldBy.single()].kind)
        val file = result.graph.nodes.single { it.name == "main.rs" && it.kind == SymbolKind.FILE }
        val dependency = result.graph.nodes.single { it.name == "util.rs" && it.kind == SymbolKind.FILE }
        assertTrue(CallGraphEdge(file.id, dependency.id, RelationKind.IMPORT) in result.graph.edges)
    }
}
