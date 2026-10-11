package dev.insight

import com.goide.project.GoProjectLibrariesService
import com.goide.psi.GoFunctionOrMethodDeclaration
import com.goide.psi.GoMethodSpec
import com.goide.vgo.configuration.VgoProjectSettings
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.callgraph.*

/**
 * Runs the analyzer against real Go PSI without a Go SDK. Cross-package imports resolve in GOPATH mode
 * (Go modules integration off, a GOPATH library root inside the light project).
 */
class GoCallGraphTest : BasePlatformTestCase() {
    private lateinit var helpers: PsiFile
    private var modules = false

    private fun function(name: String) = listOf(myFixture.file, helpers).flatMap {
        PsiTreeUtil.findChildrenOfType(it, GoFunctionOrMethodDeclaration::class.java)
    }.single { it.name == name }

    private fun names(elements: List<PsiElement>) = elements.map { (it as PsiNamedElement).name }.toSet()

    override fun setUp() {
        super.setUp()
        modules = VgoProjectSettings.getInstance(project).isIntegrationEnabled
        VgoProjectSettings.getInstance(project).setIntegrationEnabled(false)
        GoProjectLibrariesService.getInstance(project).setLibraryRootUrls("temp:///src/gopath")
        myFixture.addFileToProject("gopath/src/example/util/util.go", "package util\n\nfunc Helper() {}\n")
        helpers = myFixture.addFileToProject("helpers.go", """
            package main

            type Named interface { Name() string }

            type Entity interface { Named }

            type Base struct { ID int }

            type User struct {
                Base
                Title string
            }

            func (u *User) Name() string { return format(u.Title) }

            func format(s string) string { return s }
        """.trimIndent())
        myFixture.configureByText("main.go", """
            package main

            import "example/util"

            const limit = 3

            func main() {
                u := &User{Title: "x"}
                var n Named = u
                run(u)
                go func() { log(n.Name()) }()
                util.Helper()
                _ = limit
            }

            func run(u *User) {
                log(u.Name())
            }

            func log(s string) {}
        """.trimIndent())
    }

    override fun tearDown() {
        try {
            GoProjectLibrariesService.getInstance(project).setLibraryRootUrls(emptyList<String>())
            VgoProjectSettings.getInstance(project).setIntegrationEnabled(modules)
        } finally {
            super.tearDown()
        }
    }

    fun `test callees include functions, methods, interface methods, imports and function literal calls`() {
        assertEquals(setOf("run", "log", "Name", "Helper"), names(CallGraphAnalyzer.callees(function("main"), false)))
        assertTrue(CallGraphAnalyzer.callees(function("main"), false).any { it is GoMethodSpec })
        assertEquals(setOf("log", "Name"), names(CallGraphAnalyzer.callees(function("run"), false)))
        assertEquals(setOf("format"), names(CallGraphAnalyzer.callees(function("Name"), false)))
    }

    fun `test callers across files of the same package`() {
        assertEquals(setOf("run"), names(CallGraphAnalyzer.callers(function("Name"), 100)))
        assertEquals(setOf("main", "run"), names(CallGraphAnalyzer.callers(function("log"), 100)))
        assertEquals(setOf("Name"), names(CallGraphAnalyzer.callers(function("format"), 100)))
        val spec = PsiTreeUtil.findChildrenOfType(helpers, GoMethodSpec::class.java).single { it.name == "Name" }
        assertEquals(setOf("main"), names(CallGraphAnalyzer.callers(spec, 100)))
    }

    fun `test project graph`() {
        val graph = ProjectGraphAnalyzer.build(project).graph
        fun node(name: String, kind: SymbolKind, file: String = "") =
            graph.nodes.single { it.name == name && it.kind == kind && it.location.startsWith(file) }
        val mainFile = node("main.go", SymbolKind.FILE)
        val helpersFile = node("helpers.go", SymbolKind.FILE)
        val utilFile = node("util.go", SymbolKind.FILE)
        val user = node("User", SymbolKind.CLASS)
        val base = node("Base", SymbolKind.CLASS)
        val named = node("Named", SymbolKind.CLASS)
        val entity = node("Entity", SymbolKind.CLASS)
        val title = node("Title", SymbolKind.PROPERTY)
        val main = node("main", SymbolKind.FUNCTION)
        val run = node("run", SymbolKind.FUNCTION)
        val helper = node("Helper", SymbolKind.FUNCTION)
        val name = node("Name", SymbolKind.FUNCTION, "helpers.go:14")
        val limit = node("limit", SymbolKind.VARIABLE)
        node("u", SymbolKind.VARIABLE)
        node("u", SymbolKind.PARAMETER, "main.go")
        node("u", SymbolKind.PARAMETER, "helpers.go")
        assertTrue(CallGraphEdge(mainFile.id, main.id, RelationKind.CONTAINS) in graph.edges)
        assertTrue(CallGraphEdge(helpersFile.id, user.id, RelationKind.CONTAINS) in graph.edges)
        assertTrue(CallGraphEdge(user.id, title.id, RelationKind.CONTAINS) in graph.edges)
        assertTrue(CallGraphEdge(helpersFile.id, name.id, RelationKind.CONTAINS) in graph.edges)
        assertTrue(CallGraphEdge(user.id, base.id, RelationKind.INHERITS) in graph.edges)
        assertTrue(CallGraphEdge(entity.id, named.id, RelationKind.INHERITS) in graph.edges)
        assertTrue(CallGraphEdge(main.id, run.id, RelationKind.CALL) in graph.edges)
        assertTrue(CallGraphEdge(main.id, helper.id, RelationKind.CALL) in graph.edges)
        assertTrue(CallGraphEdge(run.id, name.id, RelationKind.CALL) in graph.edges)
        assertTrue(CallGraphEdge(main.id, named.id, RelationKind.REFERENCE) in graph.edges)
        assertTrue(CallGraphEdge(main.id, limit.id, RelationKind.REFERENCE) in graph.edges)
        // Import specs resolve to the package directory; the edge goes to every file in it.
        assertTrue(CallGraphEdge(mainFile.id, utilFile.id, RelationKind.IMPORT) in graph.edges)

        val files = graph.atDetail(GraphDetail.FILE)
        assertTrue(files.graph.nodes.all { it.kind == SymbolKind.FILE })
        val mainNode = files.graph.nodes.single { it.name == "main.go" }
        val helpersNode = files.graph.nodes.single { it.name == "helpers.go" }
        val utilNode = files.graph.nodes.single { it.name == "util.go" }
        assertEquals(1, files.graph.edges.count { it.caller == mainNode.id && it.callee == helpersNode.id })
        assertEquals(1, files.graph.edges.count { it.caller == mainNode.id && it.callee == utilNode.id })
        assertTrue(files.graph.edges.none { it.caller == helpersNode.id && it.callee == mainNode.id })
    }
}
