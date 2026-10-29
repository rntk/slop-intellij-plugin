package dev.insight

import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.callgraph.*
import dev.insight.graph.*

/** Runs the analyzers against real JavaScript and TypeScript PSI; both share one language support class. */
class JavaScriptCallGraphTest : BasePlatformTestCase() {
    fun testJavaScriptFileProducesSchematicWithBuildProgress() {
        val file = configureFile("js")
        val stages = mutableListOf<String>()
        val result = ProjectGraphAnalyzer.build(project, stages::add)
        val root = result.graph.nodes.single { it.kind == SymbolKind.FILE && it.filePath == file.virtualFile.path }
        val calls = dev.insight.enrichment.liftCalls(result.graph).edges
            .filter { it.kind == RelationKind.CALL }.groupBy { it.caller }
        val schematic = SchematicBuilder.build(result, result.graph.hierarchy(), calls, root.id)
        assertNotNull(schematic)
        assertTrue(schematic!!.rows.filterIsInstance<BlockRow>().isNotEmpty())
        assertTrue(stages.any { it.startsWith("Reading declarations:") && it.endsWith("main.js") })
        assertTrue(stages.any { it.startsWith("Resolving references (") && it.endsWith("main.js") })
        assertTrue(stages.last().startsWith("Finalizing graph:"))
    }

    private val library = """
        export function leaf() {}
        export const arrow = () => { leaf() }
        export class Box {
          constructor() {}
          get() { leaf() }
        }
        export class Plain {}
    """.trimIndent()

    private val main = """
        import { leaf, arrow, Box, Plain } from './lib'
        export class Service {
          constructor() { this.init() }
          init() { leaf() }
          run() {
            this.init()
            const box = new Box()
            box.get()
            new Plain()
            [1].forEach(x => arrow())
            setTimeout(function () { leaf() }, 1)
          }
          handler = () => { this.init() }
        }
        export function entry() { new Service().run() }
        const table = { m() { leaf() }, p: () => leaf() }
    """.trimIndent()

    private fun configureFile(extension: String): PsiFile {
        myFixture.addFileToProject("lib.$extension", library)
        return myFixture.configureByText("main.$extension", main)
    }

    private fun label(element: PsiElement): String {
        val owner = PsiTreeUtil.getParentOfType(element, JSClass::class.java)
        return listOfNotNull(owner?.name, (element as PsiNamedElement).name).joinToString(".")
    }

    private fun functions(file: PsiFile) = PsiTreeUtil.findChildrenOfType(file, PsiElement::class.java)
        .filter { CallGraphAnalyzer.isFunction(it) }

    private fun function(file: PsiFile, label: String) = functions(file).single { label(it) == label }

    private fun labels(elements: List<PsiElement>) = elements.map(::label).toSet()

    private fun checkCallees(extension: String) {
        val file = configureFile(extension)
        // this.m(), obj.m() on a typed local, constructors (explicit and implicit), anonymous callbacks and function expressions.
        assertEquals(setOf("Service.init", "Box.constructor", "Box.get", "arrow", "leaf"),
            labels(CallGraphAnalyzer.callees(function(file, "Service.run"), false)))
        assertEquals(setOf("Service.constructor", "Service.run"),
            labels(CallGraphAnalyzer.callees(function(file, "entry"), false)))
        assertEquals(setOf("Service.init"), labels(CallGraphAnalyzer.callees(function(file, "Service.handler"), false)))
        assertEquals(setOf("leaf"), labels(CallGraphAnalyzer.callees(function(file, "Service.init"), false)))
        assertEquals(setOf("leaf"), labels(CallGraphAnalyzer.callees(function(file, "m"), false)))
        assertEquals(setOf("leaf"), labels(CallGraphAnalyzer.callees(function(file, "p"), false)))
    }

    private fun checkCallers(extension: String) {
        val file = configureFile(extension)
        val lib = myFixture.findFileInTempDir("lib.$extension").let { psiManager.findFile(it)!! }
        // The import specifier is not a call site; the callback and function expression belong to run.
        assertEquals(setOf("Service.init", "Service.run", "m", "p", "arrow", "Box.get"),
            labels(CallGraphAnalyzer.callers(function(lib, "leaf"), 100)))
        assertEquals(setOf("Service.run"), labels(CallGraphAnalyzer.callers(function(lib, "arrow"), 100)))
        assertEquals(setOf("Service.run"), labels(CallGraphAnalyzer.callers(function(lib, "Box.get"), 100)))
        assertEquals(setOf("Service.run"), labels(CallGraphAnalyzer.callers(function(lib, "Box.constructor"), 100)))
        assertEquals(setOf("Service.constructor", "Service.run", "Service.handler"),
            labels(CallGraphAnalyzer.callers(function(file, "Service.init"), 100)))
        assertEquals(setOf("entry"), labels(CallGraphAnalyzer.callers(function(file, "Service.run"), 100)))
    }

    private fun checkProjectGraph(extension: String) {
        configureFile(extension)
        val graph = ProjectGraphAnalyzer.build(project).graph
        fun node(name: String, kind: SymbolKind) = graph.nodes.single { it.name.startsWith(name) && it.kind == kind }
        val mainFile = graph.nodes.single { it.kind == SymbolKind.FILE && it.name == "main.$extension" }
        val libFile = graph.nodes.single { it.kind == SymbolKind.FILE && it.name == "lib.$extension" }
        val service = node("Service", SymbolKind.CLASS)
        val box = node("Box", SymbolKind.CLASS)
        val entry = node("entry", SymbolKind.FUNCTION)
        val run = node("run", SymbolKind.FUNCTION)
        val leaf = node("leaf", SymbolKind.FUNCTION)
        val arrow = node("arrow", SymbolKind.FUNCTION) // single node: the binding, not its function expression
        val get = node("get", SymbolKind.FUNCTION)
        val boxConstructor = graph.nodes.single { it.name.startsWith("constructor") && it.filePath == libFile.filePath }
        assertEquals(1, graph.nodes.count { it.name.startsWith("arrow") })
        assertEquals(1, graph.nodes.count { it.name.startsWith("handler") && it.kind == SymbolKind.FUNCTION })
        assertTrue(graph.nodes.any { it.name.startsWith("box") && it.kind == SymbolKind.VARIABLE })
        assertTrue(graph.nodes.any { it.name == "x" && it.kind == SymbolKind.PARAMETER })
        assertTrue(graph.nodes.any { it.name.startsWith("table") && it.kind == SymbolKind.VARIABLE })
        assertTrue(graph.projectGraph)
        assertTrue(CallGraphEdge(mainFile.id, service.id, RelationKind.CONTAINS) in graph.edges)
        assertTrue(CallGraphEdge(service.id, run.id, RelationKind.CONTAINS) in graph.edges)
        assertTrue(CallGraphEdge(libFile.id, arrow.id, RelationKind.CONTAINS) in graph.edges)
        assertTrue(CallGraphEdge(box.id, get.id, RelationKind.CONTAINS) in graph.edges)
        assertTrue(CallGraphEdge(mainFile.id, leaf.id, RelationKind.IMPORT) in graph.edges)
        assertTrue(CallGraphEdge(mainFile.id, box.id, RelationKind.IMPORT) in graph.edges)
        assertTrue(CallGraphEdge(mainFile.id, libFile.id, RelationKind.IMPORT) in graph.edges)
        assertTrue(CallGraphEdge(entry.id, run.id, RelationKind.CALL) in graph.edges)
        assertTrue(CallGraphEdge(run.id, leaf.id, RelationKind.CALL) in graph.edges)
        assertTrue(CallGraphEdge(run.id, arrow.id, RelationKind.CALL) in graph.edges)
        assertTrue(CallGraphEdge(run.id, get.id, RelationKind.CALL) in graph.edges)
        assertTrue(CallGraphEdge(graph.nodes.single { it.kind == SymbolKind.VARIABLE && it.name.startsWith("box") }.id,
            boxConstructor.id, RelationKind.CALL) in graph.edges)
        assertTrue(CallGraphEdge(arrow.id, leaf.id, RelationKind.CALL) in graph.edges)

        val files = graph.hierarchy().let { it.view(it.expansion(GraphDetail.FILE)) }
        assertTrue(files.graph.nodes.all { it.kind == SymbolKind.FILE || it.kind == SymbolKind.DIRECTORY })
        val mainNode = files.graph.nodes.single { it.name == "main.$extension" }
        val libNode = files.graph.nodes.single { it.name == "lib.$extension" }
        assertEquals(1, files.graph.edges.count { it.caller == mainNode.id && it.callee == libNode.id })
        assertEquals(0, files.graph.edges.count { it.caller == libNode.id })
    }

    fun `test JavaScript callees`() = checkCallees("js")
    fun `test TypeScript callees`() = checkCallees("ts")
    fun `test JavaScript callers across files`() = checkCallers("js")
    fun `test TypeScript callers across files`() = checkCallers("ts")
    fun `test JavaScript project graph`() = checkProjectGraph("js")
    fun `test TypeScript project graph`() = checkProjectGraph("ts")

    fun `test JSX and TSX files are supported`() {
        for (extension in listOf("jsx", "tsx", "mjs")) {
            val file = myFixture.configureByText("view.$extension", "function view() { helper() }\nfunction helper() {}")
            assertEquals(setOf("helper"), labels(CallGraphAnalyzer.callees(function(file, "view"), false)))
        }
    }

    fun `test TypeScript classes interfaces and inheritance`() {
        myFixture.addFileToProject("base.ts", """
            export interface I { run(): void; name: string }
            export class A { m() {} }
            export type Alias = A
            export enum Color { Red }
        """.trimIndent())
        myFixture.configureByText("derived.ts", """
            import { A, I } from './base'
            export class B extends A implements I {
              name = 'b'
              constructor(private id: number) { super() }
              run() { this.m(); super.m() }
            }
            function use(i: I) { i.run() }
        """.trimIndent())
        val graph = ProjectGraphAnalyzer.build(project).graph
        fun node(name: String, kind: SymbolKind) = graph.nodes.single { it.name.startsWith(name) && it.kind == kind }
        val a = node("A", SymbolKind.CLASS)
        val i = node("I", SymbolKind.CLASS)
        val b = node("B", SymbolKind.CLASS)
        val derivedFile = graph.nodes.single { it.name == "derived.ts" }
        val baseFile = graph.nodes.single { it.name == "base.ts" }
        node("Color", SymbolKind.CLASS)
        node("Alias", SymbolKind.SYMBOL)
        assertTrue(CallGraphEdge(b.id, a.id, RelationKind.INHERITS) in graph.edges)
        assertTrue(CallGraphEdge(b.id, i.id, RelationKind.INHERITS) in graph.edges)
        assertTrue(CallGraphEdge(derivedFile.id, a.id, RelationKind.IMPORT) in graph.edges)
        assertTrue(graph.nodes.any { it.name.startsWith("name") && it.kind == SymbolKind.PROPERTY && it.filePath == baseFile.filePath })
        assertTrue(graph.nodes.any { it.name.startsWith("id") && it.kind == SymbolKind.PROPERTY })
        assertTrue(graph.nodes.any { it.name.startsWith("i") && it.kind == SymbolKind.PARAMETER })
        // Calls through an interface type resolve to the interface signature; super calls resolve to the base method.
        val run = graph.nodes.filter { it.name.startsWith("run") && it.kind == SymbolKind.FUNCTION }
        val m = node("m", SymbolKind.FUNCTION)
        val bRun = run.single { it.filePath == derivedFile.filePath }
        val use = node("use", SymbolKind.FUNCTION)
        assertTrue(CallGraphEdge(bRun.id, m.id, RelationKind.CALL) in graph.edges)
        assertTrue(CallGraphEdge(use.id, run.single { it.filePath == baseFile.filePath }.id, RelationKind.CALL) in graph.edges)
        val files = graph.hierarchy().let { it.view(it.expansion(GraphDetail.FILE)) }
        val derivedNode = files.graph.nodes.single { it.name == "derived.ts" }
        val baseNode = files.graph.nodes.single { it.name == "base.ts" }
        assertEquals(1, files.graph.edges.count { it.caller == derivedNode.id && it.callee == baseNode.id })
    }

    fun `test require is an import`() {
        myFixture.addFileToProject("lib.js", "exports.f = function () {}\nfunction g() {}\nmodule.exports.g = g")
        myFixture.configureByText("main.js", "const lib = require('./lib')\nfunction entry() { lib.g() }")
        val graph = ProjectGraphAnalyzer.build(project).graph
        val main = graph.nodes.single { it.name == "main.js" }
        val lib = graph.nodes.single { it.name == "lib.js" }
        assertTrue(CallGraphEdge(main.id, lib.id, RelationKind.IMPORT) in graph.edges)
    }
}
